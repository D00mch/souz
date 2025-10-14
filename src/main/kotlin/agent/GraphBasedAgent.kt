package com.dumch.agent

import com.dumch.agent.engine.*
import com.dumch.agent.node.NodesCommon
import com.dumch.agent.node.NodesLLM
import com.dumch.db.DesktopInfoRepository
import com.dumch.db.VectorDB
import com.dumch.giga.*
import com.dumch.tool.ToolsFactory
import io.ktor.util.logging.debug
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil

class GraphBasedAgent(
    private val model: String,
    private val llmApi: GigaChatAPI,
    private val desktopInfoRepository: DesktopInfoRepository,
) {
    private val l = LoggerFactory.getLogger(GraphBasedAgent::class.java)
    private val llmNodes = NodesLLM(llmApi)

    // Make sure summarization only happens after all tool requests from LLM are answered
    private val summarization: Node<GigaResponse.Chat, String> by graph(name = "Go to user") {
        input.edgeTo { ctx -> if (ctx.historyIsTooBig()) llmNodes.summarize else NodesCommon.respToString }
        llmNodes.summarize.edgeTo(NodesCommon.respToString)
        NodesCommon.respToString.edgeTo(nodeFinish)
    }

    private val settings = AgentSettings(
        model = model,
        temperature = 0.7f,
        toolsByCategory = ToolsFactory(desktopInfoRepository).toolsByCategory
    )
    private val initialCtx = AgentContext(
        input = "",
        settings = settings,
        history = emptyList(),
        activeTools = settings.tools.values.map { it.fn },
        systemPrompt = SYSTEM_PROMPT
    )

    private val _ctx: MutableStateFlow<AgentContext<String>> = MutableStateFlow(initialCtx)
    val currentContext: StateFlow<AgentContext<String>> = _ctx

    private val runningJob = AtomicReference<Deferred<*>>()

    fun clearContext(): Boolean {
        cancelActiveJob()
        return _ctx.tryEmit(initialCtx)
    }

    fun cancelActiveJob() {
        runningJob.get()?.cancel(CancellationException("Cleared by force"))
    }

    /** Execute one job at a time */
    suspend fun execute(input: String): String {
        cancelActiveJob()
        val ctx = currentContext.value.copy(input = input)
        val result: Deferred<AgentContext<String>> = coroutineScope {
            async {
                buildGraph().start(ctx) { step, node, ctx ->
                    l.debug { "Step: ${step.index}, node: ${node.name}, input: ${ctx.input}" }
                }
            }
        }
        runningJob.set(result)
        val newContext = result.await()
        _ctx.emit(newContext)
        return newContext.input
    }

    private fun buildGraph(): Graph<String, String> = buildGraph(name = "Agent") {
        input.edgeTo(NodesCommon.stringToReq)
        NodesCommon.stringToReq.edgeTo(llmNodes.requestToResponse)
        llmNodes.requestToResponse.edgeTo { ctx ->
            when (val output = ctx.input) {
                is GigaResponse.Chat.Error -> summarization
                is GigaResponse.Chat.Ok -> if (isToolUse(output)) NodesCommon.nodeToolUse else summarization
            }
        }
        NodesCommon.nodeToolUse.edgeTo(llmNodes.requestToResponse)
        summarization.edgeTo(nodeFinish)
    }

    private fun isToolUse(input: GigaResponse.Chat.Ok): Boolean = input.choices.any { it.message.functionCall != null }
}

private const val HISTORY_SUMMARIZE_THRESHOLD = 0.8
private const val APPROX_CHARS_PER_TOKEN = 4.0

private fun AgentContext<GigaResponse.Chat>.historyIsTooBig(
    threshold: Double = HISTORY_SUMMARIZE_THRESHOLD,
): Boolean {
    val model = GigaModel.entries.firstOrNull { it.alias == settings.model }
    val contextWindow = model?.maxTokens ?: MAX_TOKENS
    val estimatedTokens = systemPrompt.estimateTokenCount() +
            history.sumOf { it.content.estimateTokenCount() }
    return estimatedTokens >= contextWindow * threshold
}

private fun String.estimateTokenCount(): Int = ceil(length / APPROX_CHARS_PER_TOKEN).toInt()

val SYSTEM_PROMPT = """
Ты — помощник, управляющий компьютером. Будь полезным. Говори только по существу.
Если получил команду, выполняй, потом говори, что сделал.
Если какую-то задачу можно решить c помощью имеющихся функций, сделай, а не проси пользователя сделать это. 
Если сомневаешься, уточни. 
Если работаешь с файлами, отвечай кратко, не нужно рассказывать все, только по делу.
""".trimIndent()

suspend fun main() {
    val api = GigaRestChatAPI(GigaAuth)
    val desktopRepo = DesktopInfoRepository(GigaRestChatAPI(GigaAuth), VectorDB)
    val graph = GraphBasedAgent(GigaModel.Pro.alias, api, desktopRepo)
    val result = graph.execute("Hey")
    println(result)
}

/*
TODO:
1. Stream
2. RAG
3. Classification
 */