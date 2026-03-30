@file:OptIn(ExperimentalAtomicApi::class)

package ru.souz.agent.nodes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.Node
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.toMessage
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Nodes with calls to LLM
 */
class NodesLLM(
    private val llmApi: LLMChatAPI,
    private val settingsProvider: AgentSettingsProvider,
) {
    private val l = LoggerFactory.getLogger(NodesLLM::class.java)

    val sideEffects: Flow<String> = MutableSharedFlow(extraBufferCapacity = 16)
    
    /**
     * Calls LLM's API with the current [AgentContext.history].
     * Converts [AgentContext.history] into [AgentContext.input] as [LLMRequest.Chat] suitable for LLM call
     *
     * Modifies [AgentContext.history] and [AgentContext.input]
     */
    fun chat(name: String = "LLM Chat"): Node<String, LLMResponse.Chat> =
        Node(name) { ctx: AgentContext<String> ->
            l.debug("LLM input is {}", ctx.input)
            val response = withContext(Dispatchers.IO) {
                val req = ctx.toGigaRequest(ctx.history)
                if (settingsProvider.useStreaming) {
                    streamResponse(req.copy(stream = true))
                } else {
                    llmApi.message(req)
                }
            }
            l.debug("LLM response is {}", response)
            val history = ArrayList(ctx.history).apply {
                if (response is LLMResponse.Chat.Ok) {
                    addAll(response.choices.mapNotNull { it.toMessage() })
                }
            }
            ctx.map(history = history) { response }
        }

    private suspend fun streamResponse(request: LLMRequest.Chat): LLMResponse.Chat {
        val streamResponse = AtomicReference<LLMResponse.Chat?>(null)
        val choicesByIndex = ConcurrentHashMap<Int, ChoiceAccumulator>()
        val pending = StringBuilder()
        var increasingChunkSize = 20

        withContext(Dispatchers.IO) {
            llmApi.messageStream(request).takeWhile { response ->
                l.info("Stream response type ${response::class.java}")
                if (response is LLMResponse.Chat.Error) {
                    streamResponse.store(response)
                    false
                } else {
                    true
                }
            }.collect { response ->
                response as LLMResponse.Chat.Ok
                l.debug("choices: {}", response.choices)

                val content = response.choices.firstOrNull()?.message?.content
                if (content?.isNotEmpty() == true) {
                    pending.append(content)
                    if (pending.length >= increasingChunkSize) {
                        val toEmit = pending.toString()
                        l.info("About to emit into sideEffects flow: {}", toEmit)
                        (sideEffects as MutableSharedFlow<String>).tryEmit(toEmit)
                        pending.clear()
                        increasingChunkSize *= 3
                    }
                }

                response.choices.forEach { choice ->
                    val acc = choicesByIndex.getOrPut(choice.index) {
                        ChoiceAccumulator(choice.message.role)
                    }
                    acc.merge(choice)
                }
                val merged = choicesByIndex.entries.map { (index, acc) -> acc.toChoice(index) }

                LLMResponse.Chat.Ok(
                    choices = merged,
                    created = response.created,
                    model = response.model,
                    usage = response.usage,
                ).also(streamResponse::store)
            }

            if (pending.isNotEmpty()) {
                val toEmit = pending.toString()
                l.info("About to emit final chunk into sideEffects flow: {}", toEmit)
                (sideEffects as MutableSharedFlow<String>).tryEmit(toEmit)
            }
        }

        return streamResponse.load() ?: LLMResponse.Chat.Error(-1, "Connection error")
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
            if (msg.content.isNotBlank()) {
                content.append(msg.content)
            }
            if (msg.functionCall != null) {
                functionCall = msg.functionCall
            }
            if (msg.functionsStateId != null) {
                functionsStateId = msg.functionsStateId
            }
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
