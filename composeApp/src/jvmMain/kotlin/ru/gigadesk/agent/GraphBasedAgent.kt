@file:OptIn(ExperimentalAtomicApi::class)

package ru.gigadesk.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.util.logging.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.agent.engine.*
import ru.gigadesk.agent.nodes.NodesCommon
import ru.gigadesk.agent.nodes.NodesLLM
import ru.gigadesk.agent.nodes.NodesClassification
import ru.gigadesk.agent.session.GraphSessionService
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.giga.*
import ru.gigadesk.tool.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException

class GraphBasedAgent(
    di: DI,
    private val logObjectMapper: ObjectMapper,
) {
    private val l = LoggerFactory.getLogger(GraphBasedAgent::class.java)

    private val toolsFactory: ToolsFactory by di.instance()
    private val nodesLLM: NodesLLM by di.instance()
    private val nodesCommon: NodesCommon by di.instance()
    private val nodesClassify: NodesClassification by di.instance()
    private val settingsProvider: SettingsProvider by di.instance()
    private val sessionService: GraphSessionService by di.instance()

    // Make sure summarization only happens after all tool requests from LLM are answered
    private val settings = AtomicReference(
        AgentSettings(
            model = settingsProvider.gigaModel.alias,
            temperature = settingsProvider.temperature,
            toolsByCategory = toolsFactory.toolsByCategory
        )
    )
    private val allFunctions: List<GigaRequest.Function> = settings.load().tools.values.map { it.fn }

    private val _ctx: MutableStateFlow<AgentContext<String>> = MutableStateFlow(createInitialCtx())
    val currentContext: StateFlow<AgentContext<String>> = _ctx

    private val runningJob = AtomicReference<Deferred<*>?>(null)

    val sideEffects: Flow<String> = nodesLLM.sideEffects

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

    fun updateModel(model: GigaModel) {
        settingsProvider.gigaModel = model
        val newSettings = settings.load().copy(model = model.alias)
        settings.store(newSettings)
        _ctx.tryEmit(currentContext.value.copy(settings = newSettings))
    }

    fun updateTemperature(temperature: Float) {
        val newSettings = settings.load().copy(temperature = temperature)
        settings.store(newSettings)
        _ctx.tryEmit(currentContext.value.copy(settings = newSettings))
    }

    fun cancelActiveJob() {
        runningJob.load()?.cancel(CancellationException("Cleared by force"))
    }

    /** Execute one job at a time */
    suspend fun execute(input: String): String {
        cancelActiveJob()
        val ctx = currentContext.value.copy(input = input)

        sessionService.startTask(input)

        val result: Deferred<AgentContext<String>> = coroutineScope {
            async {
                buildGraph().start(ctx) { step, node, from, to ->
                    val prettyInput = logObjectMapper.writeValueAsString(from.input)
                    l.debug { "Step: ${step.index}, node: ${node.name}, input: $prettyInput" }

                    sessionService.onStep(step, node, from, to)
                }
            }
        }
        runningJob.store(result)
        val newContext = result.await()

        try {
            sessionService.finishTask()
        } catch (e: Exception) {
            l.warn("sessionService fall", e)
        }

        _ctx.emit(newContext)
        return newContext.input
    }

    private fun buildGraph(): Graph<String, String> = buildGraph(name = "Agent") {
        // nodes
        val chatSubgraph: Node<String, GigaResponse.Chat> = nodesLLM.chat("LLM")
        val chatOk: Node<GigaResponse.Chat, GigaResponse.Chat.Ok> = Node("Chat.Ok only") { ctx ->
            ctx.map { ctx.input as GigaResponse.Chat.Ok }
        }
        val chatErrorToFinish: Node<GigaResponse.Chat, String> = Node("Chat.Error -> Finish") { ctx ->
            val error = ctx.input as GigaResponse.Chat.Error
            ctx.map { error.message }
        }
        val contextEnrich: Node<String, String> = nodesCommon.nodeAppendAdditionalData()
        val nodeClassify: Node<String, String> = nodesClassify.node()
        val inputToHistory: Node<String, String> = nodesCommon.inputToHistory()
        val toolUse: Node<GigaResponse.Chat.Ok, String> = nodesCommon.toolUse()
        val summary: Node<GigaResponse.Chat.Ok, String> = nodesLLM.summarize()

        // graph
        nodeInput.edgeTo(inputToHistory)
        inputToHistory.edgeTo(nodeClassify)
        nodeClassify.edgeTo(contextEnrich)
        contextEnrich.edgeTo(chatSubgraph)
        chatSubgraph.edgeTo { ctx ->
            when (ctx.input) {
                is GigaResponse.Chat.Error -> chatErrorToFinish
                is GigaResponse.Chat.Ok -> chatOk
            }
        }
        chatOk.edgeTo { ctx -> if (ctx.input.isToolUse) toolUse else summary }
        toolUse.edgeTo(chatSubgraph)
        summary.edgeTo(nodeFinish)
        chatErrorToFinish.edgeTo(nodeFinish)
    }

    private val GigaResponse.Chat.Ok.isToolUse get() = choices.any { it.message.functionCall != null }

    private fun createInitialCtx(): AgentContext<String> = AgentContext(
        input = "",
        settings = settings.load(),
        history = emptyList(),
        activeTools = allFunctions,
        systemPrompt = settingsProvider.systemPrompt ?: DEFAULT_SYSTEM_PROMPT
    )
}


val DEFAULT_SYSTEM_PROMPT = """
Ты — помощник, управляющий компьютером. Будь полезным. Говори только по существу.
Если получил команду, выполняй, потом говори, что сделал.
Если какую-то задачу можно решить c помощью имеющихся функций, сделай, а не проси пользователя сделать это.
Если сомневаешься, уточни.
Если работаешь с файлами, отвечай кратко, не нужно рассказывать все, только по делу.
Ответ должен быть в формате Markdown. Только не таблицы - вместо них присылай просто списки.
""".trimIndent()

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val graph: GraphBasedAgent by di.instance()
    val result = graph.execute("Hey")
    println(result)
}
