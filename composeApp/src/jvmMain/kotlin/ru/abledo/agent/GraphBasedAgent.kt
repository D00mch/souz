package ru.abledo.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.util.logging.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.abledo.agent.engine.*
import ru.abledo.agent.node.NodesCommon
import ru.abledo.agent.node.NodesLLM
import ru.abledo.agent.nodes.NodesClassification
import ru.abledo.db.DesktopInfoRepository
import ru.abledo.db.SettingsProvider
import ru.abledo.db.asString
import ru.abledo.di.mainDiModule
import ru.abledo.giga.*
import ru.abledo.tool.*
import ru.abledo.tool.browser.detectDefaultBrowser
import ru.abledo.tool.browser.prettyName
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil

class GraphBasedAgent(
    di: DI,
    private val model: GigaModel,
    private val logObjectMapper: ObjectMapper,
) {
    private val l = LoggerFactory.getLogger(GraphBasedAgent::class.java)

    private val toolsFactory: ToolsFactory  by di.instance()
    private val nodesLLM: NodesLLM  by di.instance()
    private val nodesClassify: NodesClassification by di.instance()
    private val nodeClassify = nodesClassify.node
    private val desktopInfoRepository: DesktopInfoRepository  by di.instance()
    private val settingsProvider: SettingsProvider by di.instance()

    // Make sure summarization only happens after all tool requests from LLM are answered
    private val nodeSummarize: Node<GigaResponse.Chat, String> by graph(name = "Go to user") {
        nodeInput.edgeTo { ctx -> if (ctx.historyIsTooBig()) nodesLLM.summarize else NodesCommon.respToString }
        nodesLLM.summarize.edgeTo(NodesCommon.respToString)
        NodesCommon.respToString.edgeTo(nodeFinish)
    }

    /**
     * Makes sure we have additional information (AD) in the history, 2 cases possible:
     * - Swap the previous AD with the current one;
     * - Append AD before the previous message (so agent is not focused on the AD).
     */
    private val nodeAppendAdditionalData: Node<String, String> = Node("appendActualInformation") { ctx ->
        val additionalMessage: GigaRequest.Message? = appendActualInformation(ctx.input)
        if (additionalMessage == null) {
            ctx
        } else {

            val newHistory = ArrayList<GigaRequest.Message>()
            ctx.history.forEach { msg ->
                val isAdditionalData = msg.role == GigaMessageRole.user && msg.content.startsWith(INFO_PREFIX)
                if (!isAdditionalData) newHistory.add(msg)
            }

            if (ctx.history.size > 1) {
                newHistory.apply { add(size - 1, additionalMessage) }
            }

            ctx.map(history = newHistory)
        }
    }

    private val settings = AgentSettings(
        model = model.alias,
        temperature = 0.7f,
        toolsByCategory = toolsFactory.toolsByCategory
    )
    private val allFunctions: List<GigaRequest.Function> = settings.tools.values.map { it.fn }

    private val _ctx: MutableStateFlow<AgentContext<String>> = MutableStateFlow(createInitialCtx())
    val currentContext: StateFlow<AgentContext<String>> = _ctx

    private val runningJob = AtomicReference<Deferred<*>>()

    fun clearContext(): Boolean {
        cancelActiveJob()
        return _ctx.tryEmit(createInitialCtx())
    }

    fun setContext(ctx: AgentContext<String>): Boolean {
        cancelActiveJob()
        return _ctx.tryEmit(ctx)
    }

    fun updateSystemPrompt(prompt: String) {
        settingsProvider.systemPrompt = prompt
        _ctx.tryEmit(currentContext.value.copy(systemPrompt = prompt))
    }

    fun resetSystemPrompt() {
        settingsProvider.systemPrompt = null
        _ctx.tryEmit(currentContext.value.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT))
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
                    val prettyInput = logObjectMapper.writeValueAsString(ctx.input)
                    l.debug { "Step: ${step.index}, node: ${node.name}, input: $prettyInput" }
                }
            }
        }
        runningJob.set(result)
        val newContext = result.await()
        _ctx.emit(newContext)
        return newContext.input
    }

    private fun buildGraph(): Graph<String, String> = buildGraph(name = "Agent") {
        nodeInput.edgeTo(nodeClassify)
        nodeClassify.edgeTo(nodeAppendAdditionalData)
        nodeAppendAdditionalData.edgeTo(NodesCommon.stringToReq)
        NodesCommon.stringToReq.edgeTo(nodesLLM.requestToResponse)
        nodesLLM.requestToResponse.edgeTo { ctx ->
            when (val output = ctx.input) {
                is GigaResponse.Chat.Error -> nodeSummarize
                is GigaResponse.Chat.Ok -> if (isToolUse(output)) NodesCommon.toolUse else nodeSummarize
            }
        }
        NodesCommon.toolUse.edgeTo(nodesLLM.requestToResponse)
        nodeSummarize.edgeTo(nodeFinish)
    }

    private fun isToolUse(input: GigaResponse.Chat.Ok): Boolean = input.choices.any { it.message.functionCall != null }

    private fun createInitialCtx(): AgentContext<String> = AgentContext(
        input = "",
        settings = settings,
        history = emptyList(),
        activeTools = allFunctions,
        systemPrompt = settingsProvider.systemPrompt ?: DEFAULT_SYSTEM_PROMPT
    )

    private suspend fun appendActualInformation(userText: String): GigaRequest.Message? {
        if (userText.isBlank()) return null

        val msgRelatedDataInTheStore: String = try {
            desktopInfoRepository.search(userText).asString()
        } catch (e: Exception) {
            l.error("Error while searching desktop info: {}", e.message)
            ""
        }

        val browserName = try {
            val defaultBrowser = ToolRunBashCommand.detectDefaultBrowser()
            "Дефолтный браузер — ${defaultBrowser.prettyName}"
        } catch (e: Exception) {
            l.error("Error while fetching opened tabs: {}", e.message)
            ""
        }

        val currentDateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val dateInfo = "Текущие дата и время: $currentDateTime"

        return GigaRequest.Message(
            role = GigaMessageRole.user,
            content = listOf(INFO_PREFIX, msgRelatedDataInTheStore, browserName, dateInfo)
                .filter { it.isNotBlank() }
                .joinToString(separator = ";\n")
        )
    }
}

private const val INFO_PREFIX = "Информация о моей системе\n\n"

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

val DEFAULT_SYSTEM_PROMPT = """
Ты — помощник, управляющий компьютером. Будь полезным. Говори только по существу.
Если получил команду, выполняй, потом говори, что сделал.
Если какую-то задачу можно решить c помощью имеющихся функций, сделай, а не проси пользователя сделать это.
Если сомневаешься, уточни.
Если работаешь с файлами, отвечай кратко, не нужно рассказывать все, только по делу.
""".trimIndent()

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val graph: GraphBasedAgent by di.instance()
    val result = graph.execute("Hey")
    println(result)
}