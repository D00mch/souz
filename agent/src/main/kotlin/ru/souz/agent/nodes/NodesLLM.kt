package ru.souz.agent.nodes

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
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.toMessage

/**
 * Nodes with calls to LLM
 */
internal class NodesLLM(
    private val llmApi: LLMChatAPI,
    private val settingsProvider: AgentSettingsProvider,
    private val conversationInstructions: suspend () -> String = { "" },
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
            val prepared = prepareRequest(ctx)
            val response = prepared.input?.let { request(ctx, it, streamRevision) }
                ?: LLMResponse.Chat.Error(-1, "The conversation, instructions and tools exceed the context budget. " +
                    "Shorten the request or increase the configured context size.")
            val history = prepared.history + (response as? LLMResponse.Chat.Ok)?.choices.orEmpty().mapNotNull { it.toMessage() }
            prepared.map(history = history) { response }
        }

    private suspend fun prepareRequest(ctx: AgentContext<*>): AgentContext<LLMRequest.Chat?> {
        // Reuse one personality snapshot if compaction requires rebuilding this call.
        val instructions = conversationInstructions()
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
        return ctx.map(
            history = history,
            activeExchangeStartIndex = ctx.activeExchangeStartIndex + history.size - ctx.history.size,
        ) {
            request.takeUnless { it.exceedsContextBudget(ctx.settings.contextSize) }
        }
    }

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

        llmApi.messageStream(request).takeWhile {
            lastResponse = it
            it is LLMResponse.Chat.Ok
        }.collect { response ->
            response as LLMResponse.Chat.Ok
            val content = response.choices.firstOrNull()?.message?.content.orEmpty()
            if (content.isNotEmpty()) {
                eventSink.emit(AgentRuntimeEvent.LlmMessageDelta(content))
                pending.append(content)
                if (pending.length >= chunkSize) {
                    mutableSideEffects.tryEmit(AgentStreamChunk(pending.toString(), streamRevision))
                    pending.clear()
                    chunkSize *= 3
                }
            }
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
