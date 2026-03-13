@file:OptIn(ExperimentalAtomicApi::class)

package ru.souz.agent

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.AgentSettings
import ru.souz.agent.engine.AgentTools
import ru.souz.agent.engine.Graph
import ru.souz.agent.engine.Node
import ru.souz.agent.engine.buildGraph
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesLua
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.session.GraphSessionService
import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaModel
import ru.souz.tool.ToolsFactory
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException

class LuaGraphBasedAgent(
    di: DI,
    private val logObjectMapper: ObjectMapper,
) : Agent {
    private val l = LoggerFactory.getLogger(LuaGraphBasedAgent::class.java)

    private val toolsFactory: ToolsFactory by di.instance()
    private val nodesLua: NodesLua by di.instance()
    private val nodesCommon: NodesCommon by di.instance()
    private val nodesClassify: NodesClassification by di.instance()
    private val nodesMCP: NodesMCP by di.instance()
    private val settingsProvider: SettingsProvider by di.instance()
    private val sessionService: GraphSessionService by di.instance()

    private val settings = AtomicReference(
        AgentSettings(
            model = settingsProvider.gigaModel.alias,
            temperature = settingsProvider.temperature,
            tools = AgentTools(toolsFactory.toolsByCategory),
            contextSize = settingsProvider.contextSize,
        )
    )
    private val allFunctions = settings.load().tools.byName.values.map { it.fn }

    private val _ctx = MutableStateFlow(createInitialCtx())
    override val currentContext: StateFlow<AgentContext<String>> = _ctx

    private val runningJob = AtomicReference<Deferred<*>?>(null)

    override val sideEffects: Flow<String> = nodesLua.sideEffects

    private val graph: Graph<String, String> = buildGraph(name = "LuaAgent") {
        val contextEnrich: Node<String, String> = nodesCommon.nodeAppendAdditionalData()
        val nodeClassify: Node<String, String> = nodesClassify.node(GraphSessionService.NODE_NAME_CLASSIFY)
        val nodeMcp: Node<String, String> = nodesMCP.nodeProvideMcpTools("MCP Node")
        val inputToHistory: Node<String, String> = nodesCommon.inputToHistory()
        val planLua: Node<String, String> = nodesLua.plan()
        val runLua: Node<String, String> = nodesLua.execute()

        nodeInput.edgeTo(inputToHistory)
        inputToHistory.edgeTo(nodeClassify)
        nodeClassify.edgeTo(nodeMcp)
        nodeMcp.edgeTo(contextEnrich)
        contextEnrich.edgeTo(planLua)
        planLua.edgeTo(runLua)
        runLua.edgeTo(nodeFinish)
    }

    override fun clearContext(): Boolean {
        cancelActiveJob()
        return _ctx.tryEmit(createInitialCtx())
    }

    override fun setContext(ctx: AgentContext<String>): Boolean {
        cancelActiveJob()
        return _ctx.tryEmit(ctx)
    }

    override fun updateSystemPrompt(prompt: String) {
        settingsProvider.setSystemPromptForModel(settingsProvider.gigaModel, prompt)
        _ctx.tryEmit(currentContext.value.copy(systemPrompt = prompt))
    }

    override fun resetSystemPrompt() {
        val currentModel = settingsProvider.gigaModel
        settingsProvider.setSystemPromptForModel(currentModel, null)
        _ctx.tryEmit(currentContext.value.copy(systemPrompt = DEFAULT_LUA_SYSTEM_PROMPT))
    }

    override fun setModel(model: GigaModel): String {
        settingsProvider.gigaModel = model
        val newSettings = settings.load().copy(model = model.alias)
        settings.store(newSettings)

        val promptForModel = settingsProvider.getSystemPromptForModel(model) ?: DEFAULT_LUA_SYSTEM_PROMPT
        _ctx.tryEmit(currentContext.value.copy(settings = newSettings, systemPrompt = promptForModel))
        return promptForModel
    }

    override fun setTemperature(temperature: Float) {
        val newSettings = settings.load().copy(temperature = temperature)
        settings.store(newSettings)
        _ctx.tryEmit(currentContext.value.copy(settings = newSettings))
    }

    override fun setContextSize(contextSize: Int) {
        val newSettings = settings.load().copy(contextSize = contextSize)
        settings.store(newSettings)
        _ctx.tryEmit(currentContext.value.copy(settings = newSettings))
    }

    override fun cancelActiveJob() {
        runningJob.load()?.cancel(CancellationException("Cleared by force"))
    }

    override suspend fun execute(input: String): String {
        cancelActiveJob()
        val ctx = currentContext.value.copy(input = input)

        sessionService.startTask(input)

        val newContext = coroutineScope {
            val result: Deferred<AgentContext<String>> = async {
                graph.start(ctx) { step, node, from, to ->
                    val prettyInput = logObjectMapper.writeValueAsString(from.input)
                    l.debug("Step: {}, node: {}, input: {}", step.index, node.name, prettyInput)
                    sessionService.onStep(step, node, from, to)
                }
            }
            runningJob.store(result)
            try {
                result.await()
            } finally {
                runningJob.compareAndSet(result, null)
            }
        }

        try {
            sessionService.finishTask()
        } catch (e: Exception) {
            l.warn("sessionService fall", e)
        }

        _ctx.emit(newContext)
        return newContext.input
    }

    private fun createInitialCtx(): AgentContext<String> {
        val currentModel = settingsProvider.gigaModel
        val prompt = settingsProvider.getSystemPromptForModel(currentModel) ?: DEFAULT_LUA_SYSTEM_PROMPT
        return AgentContext(
            input = "",
            settings = settings.load(),
            history = emptyList(),
            activeTools = allFunctions,
            systemPrompt = prompt,
        )
    }
}

val DEFAULT_LUA_SYSTEM_PROMPT = """
Work as an autonomous desktop assistant that solves tasks by writing Lua code for immediate execution.
Prefer tools (not Lua libraries) when possible.
When you are not sure if the path is correct, try to find the file by tools first.
Keep the final answer concise, and return Markdown when formatting helps.
""".trimIndent()
