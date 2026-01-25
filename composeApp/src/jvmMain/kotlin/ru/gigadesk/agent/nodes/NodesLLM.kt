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
import ru.gigadesk.agent.engine.buildGraph
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.ceil

/**
 * Nodes with calls to LLM
 */
class NodesLLM(
    private val llmApi: GigaRestChatAPI,
    private val grpcChatApi: GigaGRPCChatApi,
    private val nodesCommon: NodesCommon,
    private val settingsProvider: SettingsProvider,
) {
    private val l = LoggerFactory.getLogger(NodesLLM::class.java)

    val sideEffects: Flow<String> = MutableSharedFlow()
    
    /**
     * Calls LLM's API with the current [AgentContext.history].
     * Converts [AgentContext.history] into [AgentContext.input] as [GigaRequest.Chat] suitable for LLM call
     * Modifies [AgentContext.history] and [AgentContext.input]
     */
    fun chat(name: String = "LLM Chat"): Node<String, GigaResponse.Chat> =
        Node(name) { ctx ->
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
            val history = ArrayList(ctx.history).apply {
                if (response is GigaResponse.Chat.Ok) {
                    addAll(response.choices.mapNotNull { it.toMessage() })
                }
            }
            ctx.map(history = history) { response }
        }

    fun summarize(
        name: String = "Summarize or return",
    ): Node<GigaResponse.Chat.Ok, String> = buildGraph(name) {
        // nodes
        val summarize: Node<GigaResponse.Chat.Ok, GigaResponse.Chat.Ok> = nodeSummarize()
        val summaryToHistory = summaryToHistory<GigaResponse.Chat.Ok>()
        val respToString: Node<GigaResponse.Chat.Ok, String> = nodesCommon.responseToString()

        // graph
        nodeInput.edgeTo { ctx -> if (ctx.historyIsTooBig()) summarize else respToString }
        summarize.edgeTo(summaryToHistory)
        summaryToHistory.edgeTo(respToString)
        respToString.edgeTo(nodeFinish)
    }

    /** Updates [AgentContext.input] based on [AgentContext.history]. */
    private fun nodeSummarize(name: String = "llmSummarize"): Node<GigaResponse.Chat.Ok, GigaResponse.Chat.Ok> =
        Node(name) { ctx ->
            val summaryResponse: GigaResponse.Chat = withContext(Dispatchers.IO) {
                val conversation = ArrayList(ctx.history).apply {
                    add(
                        GigaRequest.Message(
                            role = GigaMessageRole.user,
                            content = SUMMARIZATION_PROMPT,
                        )
                    )
                }
                val request = ctx.toGigaRequest(conversation).copy(functions = emptyList())
                llmApi.message(request)
            }

            when (summaryResponse) {
                is GigaResponse.Chat.Error -> throw GigaException(summaryResponse)
                is GigaResponse.Chat.Ok -> ctx.map { summaryResponse }
            }
        }

    private inline fun <reified T> summaryToHistory(name: String = "summary->history"): Node<GigaResponse.Chat.Ok, T> =
        Node(name) { ctx ->
            val msg: GigaRequest.Message = ctx.input.choices.mapNotNull { it.toMessage() }.last()
            val newHistory = listOf(ctx.systemPrompt.toSystemPromptMessage(), msg)
            ctx.map(history = newHistory)
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

private const val HISTORY_SUMMARIZE_THRESHOLD = 0.8
private const val APPROX_CHARS_PER_TOKEN = 4.0

private fun String.estimateTokenCount(): Int = ceil(length / APPROX_CHARS_PER_TOKEN).toInt()

private fun AgentContext<*>.historyIsTooBig(
    threshold: Double = HISTORY_SUMMARIZE_THRESHOLD,
): Boolean {
    val model = GigaModel.entries.firstOrNull { it.alias == settings.model }
    val contextWindow = model?.maxTokens ?: MAX_TOKENS
    val estimatedTokens = systemPrompt.estimateTokenCount() +
            history.sumOf { it.content.estimateTokenCount() }
    return estimatedTokens >= contextWindow * threshold
}

fun GigaResponse.Choice.toMessage(): GigaRequest.Message? {
    val msg = this.message
    val content: String = when {
        msg.content.isNotBlank() -> msg.content
        msg.functionCall != null -> gigaJsonMapper.writeValueAsString(
            mapOf("name" to msg.functionCall.name, "arguments" to msg.functionCall.arguments)
        )
        else -> return null
    }
    return GigaRequest.Message(
        role = msg.role,
        content = content,
        functionsStateId = msg.functionsStateId
    )
}

private const val SUMMARIZATION_PROMPT = "Можешь вычленить самое важное из истории переписки, чтобы можно было продолжить работу с места, на котором мы остановились. Выдели все важные факты из переписки и перечисли их по факту на новой строке."
