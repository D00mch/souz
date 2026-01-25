package ru.gigadesk.agent.nodes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.agent.engine.Node
import ru.gigadesk.agent.engine.buildGraph
import ru.gigadesk.giga.GigaMessageRole
import ru.gigadesk.giga.GigaException
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.GigaRestChatAPI
import ru.gigadesk.giga.MAX_TOKENS
import ru.gigadesk.giga.toMessage
import ru.gigadesk.giga.toSystemPromptMessage
import kotlin.math.ceil

/**
 * Nodes responsible for summarizing conversation history.
 */
class NodesSummarization(
    private val llmApi: GigaRestChatAPI,
    private val nodesCommon: NodesCommon,
) {
    /**
     * Summarizes the current history when it grows too large.
     * Updates [AgentContext.history] and [AgentContext.input] based on summarization result.
     */
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

private const val SUMMARIZATION_PROMPT = "Можешь вычленить самое важное из истории переписки, чтобы можно было продолжить работу с места, на котором мы остановились. Выдели все важные факты из переписки и перечисли их по факту на новой строке."
