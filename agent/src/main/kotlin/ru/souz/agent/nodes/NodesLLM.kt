package ru.souz.agent.nodes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.AgentStreamChunk
import ru.souz.agent.graph.Node
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.ModelRouting
import ru.souz.agent.spi.RoutedModel
import ru.souz.agent.spi.TaskComplexityClassifier
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.toMessage
import kotlin.time.TimeSource

/**
 * Nodes with calls to LLM
 */
internal class NodesLLM(
    private val llmApi: LLMChatAPI,
    private val settingsProvider: AgentSettingsProvider,
    private val conversationInstructions: suspend () -> String = { "" },
    private val complexityClassifier: TaskComplexityClassifier? = null,
    /** Rebinds a routed turn's tools to the chosen model: a `SpawnSubagent` child defaults to its parent's model. */
    private val routedTools: ((AgentSettings) -> List<LLMToolSetup>)? = null,
    private val attachmentHistoryLimit: Int = ru.souz.agent.DEFAULT_ATTACHMENT_HISTORY_LIMIT,
) {
    private val l = LoggerFactory.getLogger(NodesLLM::class.java)

    private val mutableSideEffects = MutableSharedFlow<AgentStreamChunk>(extraBufferCapacity = 16)
    val sideEffects: Flow<AgentStreamChunk> = mutableSideEffects

    /**
     * Calls LLM's API with the current [AgentContext.history].
     * Converts [AgentContext.history] into [AgentContext.input] as [LLMRequest.Chat] suitable for LLM call
     *
     * Modifies [AgentContext.history] and [AgentContext.input]
     */
    fun chat(
        name: String = "LLM Chat",
        streamRevision: Long = 0L,
    ): Node<String, LLMResponse.Chat> =
        Node(name, retryable = true) { ctx: AgentContext<String> ->
            // Reuse one personality snapshot if compaction requires rebuilding this call.
            val instructions = conversationInstructions()
            val routing = ctx.settings.routing
            if (routing != null && complexityClassifier != null) {
                route(ctx, routing, complexityClassifier, instructions, streamRevision)
            } else {
                call(ctx, instructions, streamRevision)
            }
        }

    /** Calls the LLM on [ctx]'s settings and tools, compacting completed history when the request exceeds the budget. */
    private suspend fun call(
        ctx: AgentContext<String>,
        instructions: String,
        streamRevision: Long,
        release: Deferred<Unit>? = null,
    ): AgentContext<LLMResponse.Chat> {
        fun build(history: List<LLMRequest.Message>) =
            ctx.toChatRequest(history, maxOutputTokens = CHAT_MAX_OUTPUT_TOKENS)
                .withConversationInstructions(instructions)

        var history = ctx.history
        var request = build(history)
        if (request.exceedsContextBudget(ctx.settings.contextSize)) {
            history = llmApi.compactHistory(
                ctx.toChatRequest(history, maxOutputTokens = SUMMARIZATION_MAX_OUTPUT_TOKENS),
                ctx.activeExchangeStartIndex,
                attachmentHistoryLimit,
            )
            request = build(history)
        }
        val response = if (request.exceedsContextBudget(ctx.settings.contextSize)) {
            LLMResponse.Chat.Error(-1, "The conversation, instructions and tools exceed the context budget. " +
                "Shorten the request or increase the configured context size.")
        } else request(ctx, request, streamRevision, release)
        return ctx.map(
            history = history + (response as? LLMResponse.Chat.Ok)?.choices.orEmpty().mapNotNull { it.toMessage() },
            activeExchangeStartIndex = ctx.activeExchangeStartIndex + history.size - ctx.history.size,
        ) { response }
    }

    /**
     * Starts the simple model at once and holds its streamed output until [classifier] decides. A complex turn
     * cancels it and calls the complex model; a classifier failure uses the turn's own model. The chosen
     * context, with settings without routing and tools bound to them, serves every later call of the turn.
     */
    private suspend fun route(
        ctx: AgentContext<String>,
        routing: ModelRouting,
        classifier: TaskComplexityClassifier,
        instructions: String,
        streamRevision: Long,
    ): AgentContext<LLMResponse.Chat> {
        val normal = ctx.settings.copy(routing = null)
        val simple = ctx.on(normal.routedTo(routing.simple))
        val complex = ctx.on(normal.routedTo(routing.complex))
        // A failed speculative call matters only when the simple model is chosen.
        return supervisorScope {
            val decided = CompletableDeferred<Unit>()
            val speculative = async { call(simple, instructions, streamRevision, decided) }
            val started = TimeSource.Monotonic.markNow()
            val (verdict, target) = try {
                if (classifier.isComplex(ctx.classificationRequest())) "complex" to complex else "simple" to simple
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                l.warn("Model routing classifier failed: {}", error.message)
                "unknown" to ctx.on(normal)
            }
            l.info("Model routing class={} model={} durationMs={}", verdict, target.settings.model, started.elapsedNow().inWholeMilliseconds)
            if (target === simple) {
                decided.complete(Unit)
                speculative.await()
            } else {
                speculative.cancelAndJoin()
                call(target, instructions, streamRevision)
            }
        }
    }

    private fun AgentSettings.routedTo(target: RoutedModel): AgentSettings =
        copy(model = target.model, provider = target.provider, routing = null)

    private fun AgentContext<String>.on(settings: AgentSettings): AgentContext<String> =
        routedTools?.let { copy(settings = settings).withOnlyTools(it(settings)) } ?: copy(settings = settings)

    private fun AgentContext<String>.classificationRequest(): LLMRequest.Chat =
        toChatRequest(
            history.filterNot { it.isInjectedContextMessage() || it.isInjectedMemoryContextMessage() },
            maxOutputTokens = CLASSIFICATION_MAX_OUTPUT_TOKENS,
        ).copy(functions = emptyList())

    /** Runs one LLM call; a streamed call holds its deltas until [release] completes. */
    private suspend fun request(
        ctx: AgentContext<*>,
        request: LLMRequest.Chat,
        streamRevision: Long,
        release: Deferred<Unit>? = null,
    ): LLMResponse.Chat {
        l.debug("LLM input is {}", ctx.input)
        val response = withContext(Dispatchers.IO) {
            if (settingsProvider.useStreaming) {
                streamResponse(
                    request = request.copy(stream = true),
                    eventSink = ctx.runtimeEventSink,
                    streamRevision = streamRevision,
                    release = release,
                )
            } else {
                llmApi.message(request)
            }
        }
        l.debug("LLM response is {}", response)
        return response
    }

    private suspend fun streamResponse(
        request: LLMRequest.Chat,
        eventSink: AgentRuntimeEventSink,
        streamRevision: Long,
        release: Deferred<Unit>?,
    ): LLMResponse.Chat {
        var lastResponse: LLMResponse.Chat = LLMResponse.Chat.Error(-1, "Connection error")
        val choices = sortedMapOf<Int, ChoiceAccumulator>()
        val pending = StringBuilder()
        val held = mutableListOf<String>()
        var chunkSize = 20

        suspend fun publish(content: String) {
            eventSink.emit(AgentRuntimeEvent.LlmMessageDelta(content))
            pending.append(content)
            if (pending.length >= chunkSize) {
                mutableSideEffects.tryEmit(AgentStreamChunk(pending.toString(), streamRevision))
                pending.clear()
                chunkSize *= 3
            }
        }

        llmApi.messageStream(request).takeWhile {
            lastResponse = it
            it is LLMResponse.Chat.Ok
        }.collect { response ->
            response as LLMResponse.Chat.Ok
            val content = response.choices.firstOrNull()?.message?.content.orEmpty()
            if (content.isNotEmpty()) {
                held += content
                if (release?.isCompleted != false) {
                    held.forEach { publish(it) }
                    held.clear()
                }
            }
            response.choices.forEach { choice ->
                choices.getOrPut(choice.index) { ChoiceAccumulator(choice.message.role) }.merge(choice)
            }
        }
        release?.await()
        held.forEach { publish(it) }
        if (pending.isNotEmpty()) {
            mutableSideEffects.tryEmit(AgentStreamChunk(pending.toString(), streamRevision))
        }
        return when (val response = lastResponse) {
            is LLMResponse.Chat.Ok -> response.copy(choices = choices.map { (index, acc) -> acc.toChoice(index) })
            else -> response
        }
    }

    private class ChoiceAccumulator(
        var role: LLMMessageRole,
        val content: StringBuilder = StringBuilder(),
        var functionCall: LLMResponse.FunctionCall? = null,
        var functionsStateId: String? = null,
        var finishReason: LLMResponse.FinishReason? = null,
    ) {
        fun merge(choice: LLMResponse.Choice) {
            val msg = choice.message
            content.append(msg.content)
            functionCall = msg.functionCall ?: functionCall
            functionsStateId = msg.functionsStateId ?: functionsStateId
            finishReason = choice.finishReason ?: finishReason
            role = msg.role
        }

        fun toChoice(index: Int): LLMResponse.Choice = LLMResponse.Choice(
            message = LLMResponse.Message(
                content = content.toString(),
                role = role,
                functionCall = functionCall,
                functionsStateId = functionsStateId,
            ),
            index = index,
            finishReason = finishReason,
        )
    }
}
