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
import ru.gigadesk.agent.nodes.NodesErrorHandling
import ru.gigadesk.agent.nodes.NodesClassification
import ru.gigadesk.agent.nodes.NodesLLM
import ru.gigadesk.agent.nodes.NodesMCP
import ru.gigadesk.agent.nodes.NodesSummarization
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
    private val nodesErrorHandling: NodesErrorHandling by di.instance()
    private val nodesSummarization: NodesSummarization by di.instance()
    private val nodesMCP: NodesMCP by di.instance()
    private val settingsProvider: SettingsProvider by di.instance()
    private val sessionService: GraphSessionService by di.instance()

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

    private val graph: Graph<String, String> = buildGraph(name = "Agent") {
        // nodes
        val chatSubgraph: Node<String, GigaResponse.Chat> = nodesLLM.chat("LLM")
        val chatOk: Node<GigaResponse.Chat, GigaResponse.Chat.Ok> = Node("Chat.Ok") { ctx ->
            ctx.map { ctx.input as GigaResponse.Chat.Ok }
        }
        val chatErrorToFinish: Node<GigaResponse.Chat, String> = nodesErrorHandling.chatErrorToFinish()
        val contextEnrich: Node<String, String> = nodesCommon.nodeAppendAdditionalData()
        val nodeClassify: Node<String, String> = nodesClassify.node(GraphSessionService.NODE_NAME_CLASSIFY)
        val nodesMCP: Node<String, String> = nodesMCP.nodeProvideMcpTools("MCP Node")
        val inputToHistory: Node<String, String> = nodesCommon.inputToHistory()
        val toolUse: Node<GigaResponse.Chat.Ok, String> = nodesCommon.toolUse()
        val summary: Node<GigaResponse.Chat.Ok, String> = nodesSummarization.summarize()

        // graph
        nodeInput.edgeTo(inputToHistory)
        inputToHistory.edgeTo(nodeClassify)
        nodeClassify.edgeTo(nodesMCP)
        nodesMCP.edgeTo(contextEnrich)
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

    fun clearContext(): Boolean {
        cancelActiveJob()
        return _ctx.tryEmit(createInitialCtx())
    }

    fun setContext(ctx: AgentContext<String>): Boolean {
        cancelActiveJob()
        return _ctx.tryEmit(ctx)
    }

    fun updateSystemPrompt(prompt: String) {
        val currentModel = settingsProvider.gigaModel
        settingsProvider.setSystemPromptForModel(currentModel, prompt)
        _ctx.tryEmit(currentContext.value.copy(systemPrompt = prompt))
    }

    fun resetSystemPrompt() {
        val currentModel = settingsProvider.gigaModel
        settingsProvider.setSystemPromptForModel(currentModel, null)
        _ctx.tryEmit(currentContext.value.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT))
    }

    fun updateModel(model: GigaModel): String {
        settingsProvider.gigaModel = model
        val newSettings = settings.load().copy(model = model.alias)
        settings.store(newSettings)

        val promptForModel = settingsProvider.getSystemPromptForModel(model) ?: DEFAULT_SYSTEM_PROMPT
        _ctx.tryEmit(currentContext.value.copy(settings = newSettings, systemPrompt = promptForModel))
        return promptForModel
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
                graph.start(ctx) { step, node, from, to ->
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

    private val GigaResponse.Chat.Ok.isToolUse get() = choices.any { it.message.functionCall != null }

    private fun createInitialCtx(): AgentContext<String> {
        val currentModel = settingsProvider.gigaModel
        val prompt = settingsProvider.getSystemPromptForModel(currentModel)
            ?: settingsProvider.systemPrompt
            ?: DEFAULT_SYSTEM_PROMPT
        return AgentContext(
            input = "",
            settings = settings.load(),
            history = emptyList(),
            activeTools = allFunctions,
            systemPrompt = prompt
        )
    }
}


val DEFAULT_SYSTEM_PROMPT = """
## Правила работы:
1. **Приоритет инструментов:** Если задачу можно решить вызовом функции — ВЫЗЫВАЙ ЕЁ. Никогда не пиши название функции текстом и не присылай примеры кода на Python/Bash, если ты не собираешься их исполнять через инструмент.
2. **Рассуждения (Chain of Thought):** Перед действием кратко проанализируй запрос. Сначала подумай, какой инструмент нужен, затем используй его.
3. **Формат ответа:**
   - Если результат получен: кратко сообщи об успехе.
   - Если ошибка: сообщи суть проблемы и предложи решение.
4. **Работа с файлами:** Будь краток. Не выводи содержимое файлов, если тебя об этом прямо не просили.
5. **Возврат текста:**
   - Если нужно вернуть текст - возвращай в формате Markdown.
   - В Markdown не возвращай таблицы - вместо них возвращай форматированные списки.

## Критически важно:
Твоя задача — ДЕЙСТВОВАТЬ, а не болтать. 
""".trimIndent()

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val graph: GraphBasedAgent by di.instance()
    val result = graph.execute("Hey")
    println(result)
}
