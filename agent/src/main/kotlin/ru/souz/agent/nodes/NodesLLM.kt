package ru.souz.agent.nodes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.AgentStreamChunk
import ru.souz.agent.graph.Node
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.RoutedModel
import ru.souz.agent.spi.TurnClassifier
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.toMessage
import ru.souz.llms.toSystemPromptMessage
import kotlin.time.TimeSource

/**
 * Nodes with calls to LLM
 */
internal class NodesLLM(
    private val llmApi: LLMChatAPI,
    private val settingsProvider: AgentSettingsProvider,
    private val conversationInstructions: suspend () -> String = { "" },
    private val turnClassifier: TurnClassifier? = null,
    /** Rebinds a routed turn's tools to the chosen model: a `SpawnSubagent` child defaults to its parent's model. */
    private val routedTools: ((AgentSettings) -> List<LLMToolSetup>)? = null,
) {
    private val l = LoggerFactory.getLogger(NodesLLM::class.java)

    private val mutableSideEffects = MutableSharedFlow<AgentStreamChunk>(extraBufferCapacity = 16)
    val sideEffects: Flow<AgentStreamChunk> = mutableSideEffects

    /** Classify once, before recall or any conversational LLM call. */
    fun prepareTurn(): Node<String, String> = Node("Turn classification") { ctx ->
        val started = TimeSource.Monotonic.markNow()
        val decision = try {
            turnClassifier?.classify(ctx.classificationRequest())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            l.warn("Turn classification failed category={}", error.javaClass.simpleName)
            null
        }
        val routing = ctx.settings.routing
        val target = routing?.let { if (decision?.complex == false) it.simple else it.complex }
        var prepared = if (target == null) ctx else ctx.on(ctx.settings.routedTo(target))
        val simpleReply = routing != null && decision?.complex == false
        if (simpleReply) {
            // Discard the previous turn's Skill inventory along with executable tools.
            val replaced = if (prepared.history.firstOrNull()?.role == LLMMessageRole.system) 1 else 0
            prepared = prepared.withOnlyTools(emptyList()).map(
                history = listOf(ctx.systemPrompt.toSystemPromptMessage()) + prepared.history.drop(replaced),
                activeExchangeStartIndex = prepared.activeExchangeStartIndex + 1 - replaced,
            )
        }
        if (decision?.expectedFast != true) ctx.runtimeEventSink.emit(AgentRuntimeEvent.InitialWait)
        l.info("Turn classification fast={} recall={} model={} durationMs={}",
            decision?.expectedFast, decision?.recallMemory, prepared.settings.model, started.elapsedNow().inWholeMilliseconds)
        prepared.copy(recallMemory = decision?.recallMemory != false, simpleReply = simpleReply)
    }

    fun chat(name: String = "LLM Chat", streamRevision: Long = 0L): Node<String, LLMResponse.Chat> =
        Node(name, retryable = true) { ctx -> call(ctx, conversationInstructions(), streamRevision) }

    /** Calls the LLM on [ctx]'s settings and tools, compacting completed history when the request exceeds the budget. */
    private suspend fun call(
        ctx: AgentContext<String>,
        instructions: String,
        streamRevision: Long,
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
            )
            request = build(history)
        }
        val response = if (request.exceedsContextBudget(ctx.settings.contextSize)) {
            LLMResponse.Chat.Error(-1, "The conversation, instructions and tools exceed the context budget. " +
                "Shorten the request or increase the configured context size.")
        } else request(ctx, request, streamRevision)
        return ctx.map(
            history = history + (response as? LLMResponse.Chat.Ok)?.choices.orEmpty().mapNotNull { it.toMessage() },
            activeExchangeStartIndex = ctx.activeExchangeStartIndex + history.size - ctx.history.size,
        ) { response }
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

    /** Runs one call on the model already selected for this turn. */
    private suspend fun request(
        ctx: AgentContext<*>,
        request: LLMRequest.Chat,
        streamRevision: Long,
    ): LLMResponse.Chat {
        l.debug("LLM input is {}", ctx.input)
        val response = withContext(Dispatchers.IO) {
            if (settingsProvider.useStreaming) {
                streamResponse(
                    request = request.copy(stream = true),
                    eventSink = ctx.runtimeEventSink,
                    streamRevision = streamRevision,
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
    ): LLMResponse.Chat {
        var lastResponse: LLMResponse.Chat = LLMResponse.Chat.Error(-1, "Connection error")
        val choices = sortedMapOf<Int, ChoiceAccumulator>()
        val pending = StringBuilder()
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
            if (content.isNotEmpty()) publish(content)
            response.choices.forEach { choice ->
                choices.getOrPut(choice.index) { ChoiceAccumulator(choice.message.role) }.merge(choice)
            }
        }
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
