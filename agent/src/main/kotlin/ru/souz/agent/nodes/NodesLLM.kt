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
import ru.souz.llms.giga.GigaChatAPI
import ru.souz.llms.GigaMessageRole
import ru.souz.llms.GigaRequest
import ru.souz.llms.GigaResponse
import ru.souz.llms.toMessage
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Nodes with calls to LLM
 */
class NodesLLM(
    private val llmApi: GigaChatAPI,
    private val settingsProvider: AgentSettingsProvider,
) {
    private val l = LoggerFactory.getLogger(NodesLLM::class.java)

    val sideEffects: Flow<String> = MutableSharedFlow(extraBufferCapacity = 16)
    
    /**
     * Calls LLM's API with the current [AgentContext.history].
     * Converts [AgentContext.history] into [AgentContext.input] as [GigaRequest.Chat] suitable for LLM call
     *
     * Modifies [AgentContext.history] and [AgentContext.input]
     */
    fun chat(name: String = "LLM Chat"): Node<String, GigaResponse.Chat> =
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
                if (response is GigaResponse.Chat.Ok) {
                    addAll(response.choices.mapNotNull { it.toMessage() })
                }
            }
            ctx.map(history = history) { response }
        }

    private suspend fun streamResponse(request: GigaRequest.Chat): GigaResponse.Chat {
        val streamResponse = AtomicReference<GigaResponse.Chat?>(null)
        val choicesByIndex = ConcurrentHashMap<Int, ChoiceAccumulator>()
        val pending = StringBuilder()
        var increasingChunkSize = 20

        withContext(Dispatchers.IO) {
            llmApi.messageStream(request).takeWhile { response ->
                l.info("Stream response type ${response::class.java}")
                if (response is GigaResponse.Chat.Error) {
                    streamResponse.store(response)
                    false
                } else {
                    true
                }
            }.collect { response ->
                response as GigaResponse.Chat.Ok
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

                GigaResponse.Chat.Ok(
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

        return streamResponse.load() ?: GigaResponse.Chat.Error(-1, "Connection error")
    }

    private class ChoiceAccumulator(
        var role: GigaMessageRole,
        val content: StringBuilder = StringBuilder(),
        var functionCall: GigaResponse.FunctionCall? = null,
        var functionsStateId: String? = null,
        var finishReason: GigaResponse.FinishReason? = null,
    ) {
        fun merge(choice: GigaResponse.Choice) {
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

        fun toChoice(index: Int): GigaResponse.Choice = GigaResponse.Choice(
            message = GigaResponse.Message(
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
