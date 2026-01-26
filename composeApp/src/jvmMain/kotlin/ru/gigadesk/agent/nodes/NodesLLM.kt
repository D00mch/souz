@file:OptIn(ExperimentalAtomicApi::class)

package ru.gigadesk.agent.nodes

import io.ktor.util.logging.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.agent.engine.Node
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Nodes with calls to LLM
 */
class NodesLLM(
    private val llmApi: GigaRestChatAPI,
    private val grpcChatApi: GigaGRPCChatApi,
    private val settingsProvider: SettingsProvider,
) {
    private val l = LoggerFactory.getLogger(NodesLLM::class.java)

    val sideEffects: Flow<String> = MutableSharedFlow()
    
    /**
     * Calls LLM's API with the current [AgentContext.history].
     * Converts [AgentContext.history] into [AgentContext.input] as [GigaRequest.Chat] suitable for LLM call
     *
     * Modifies [AgentContext.history] and [AgentContext.input]
     */
    fun chat(name: String = "LLM Chat"): Node<String, GigaResponse.Chat> =
        Node(name) { ctx: AgentContext<String> ->
            l.debug { "LLM input is ${ctx.input}" }
            val response = withContext(Dispatchers.IO) {
                val req = ctx.toGigaRequest(ctx.history)
                if (settingsProvider.useGrpc) {
                    streamResponse(req)
                } else {
                    llmApi.message(req)
                }
            }
            l.debug("LLM response is {}", response)

            if (response is GigaResponse.Chat.Error && response.status == 413) {
                val resetContent = "Слишком много информации. Я запутался - давай начнем заново и по чуть-чуть"
                val resetMessage = GigaResponse.Message(
                    content = resetContent,
                    role = GigaMessageRole.assistant,
                    functionsStateId = null
                )
                val fixedResponse = GigaResponse.Chat.Ok(
                    choices = listOf(
                        GigaResponse.Choice(
                            message = resetMessage,
                            index = 0,
                            finishReason = GigaResponse.FinishReason.stop
                        )
                    ),
                    created = System.currentTimeMillis() / 1000,
                    model = "system-reset",
                    usage = GigaResponse.Usage(0, 0, 0, 0)
                )
                return@Node ctx.map(history = emptyList()) { fixedResponse }
            }

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

        withContext(Dispatchers.IO) {
            grpcChatApi.messageStream(request).takeWhile { response ->
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
                    l.info("About to emit into sideEffects flow: {}", content)
                    (sideEffects as MutableSharedFlow).emit(content)
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
                ).also { response ->
                    llmApi.logTokenUsage(response, request)
                    streamResponse.store(response)
                }
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
