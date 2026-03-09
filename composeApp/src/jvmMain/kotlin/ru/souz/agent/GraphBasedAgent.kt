@file:OptIn(ExperimentalAtomicApi::class)

package ru.souz.agent

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
import ru.souz.agent.engine.*
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.session.GraphSessionService
import ru.souz.db.SettingsProvider
import ru.souz.di.mainDiModule
import ru.souz.giga.*
import ru.souz.tool.*
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
            toolsByCategory = toolsFactory.toolsByCategory,
            contextSize = settingsProvider.contextSize,
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
        _ctx.tryEmit(
            currentContext.value.copy(
                systemPrompt = defaultSystemPromptForRegion(settingsProvider.regionProfile)
            )
        )
    }

    fun updateModel(model: GigaModel): String {
        settingsProvider.gigaModel = model
        val newSettings = settings.load().copy(model = model.alias)
        settings.store(newSettings)

        val promptForModel = settingsProvider.getSystemPromptForModel(model)
            ?: defaultSystemPromptForRegion(settingsProvider.regionProfile)
        _ctx.tryEmit(currentContext.value.copy(settings = newSettings, systemPrompt = promptForModel))
        return promptForModel
    }

    fun updateTemperature(temperature: Float) {
        val newSettings = settings.load().copy(temperature = temperature)
        settings.store(newSettings)
        _ctx.tryEmit(currentContext.value.copy(settings = newSettings))
    }

    fun updateContextSize(contextSize: Int) {
        val newSettings = settings.load().copy(contextSize = contextSize)
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

        val newContext = coroutineScope {
            val result: Deferred<AgentContext<String>> = async {
                graph.start(ctx) { step, node, from, to ->
                    val prettyInput = logObjectMapper.writeValueAsString(from.input)
                    l.debug { "Step: ${step.index}, node: ${node.name}, input: $prettyInput" }

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

    private val GigaResponse.Chat.Ok.isToolUse get() = choices.any { it.message.functionCall != null }

    private fun createInitialCtx(): AgentContext<String> {
        val currentModel = settingsProvider.gigaModel
        val defaultPrompt = defaultSystemPromptForRegion(settingsProvider.regionProfile)
        val prompt = settingsProvider.getSystemPromptForModel(currentModel)
            ?: settingsProvider.systemPrompt?.takeUnless(::isDefaultSystemPrompt)
            ?: defaultPrompt
        return AgentContext(
            input = "",
            settings = settings.load(),
            history = emptyList(),
            activeTools = allFunctions,
            systemPrompt = prompt
        )
    }
}


fun defaultSystemPromptForRegion(regionProfile: String): String =
    if (regionProfile.equals("en", ignoreCase = true)) {
        DEFAULT_SYSTEM_PROMPT_EN
    } else {
        DEFAULT_SYSTEM_PROMPT_RU
    }

fun isDefaultSystemPrompt(prompt: String): Boolean =
    prompt == DEFAULT_SYSTEM_PROMPT_RU || prompt == DEFAULT_SYSTEM_PROMPT_EN

val DEFAULT_SYSTEM_PROMPT_RU = """
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

val DEFAULT_SYSTEM_PROMPT_EN = """
## Work Rules:
1. **Tool Priority:** If a task can be solved by calling a function, CALL IT. Never write function names as plain text and never provide Python/Bash code examples unless you are going to execute them via a tool.
2. **Reasoning (Chain of Thought):** Briefly analyze the request before acting. First decide which tool is needed, then use it.
3. **Response Format:**
   - If the result is obtained: briefly report success.
   - If there is an error: explain the issue and suggest a solution.
4. **Working with Files:** Be concise. Do not output file contents unless explicitly asked.
5. **Returning Text:**
   - If text must be returned, use Markdown format.
   - Do not use tables in Markdown; use formatted lists instead.

## Critically Important:
Your task is to ACT, not to chat.
""".trimIndent()

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val graph: GraphBasedAgent by di.instance()
    val result = graph.execute("Hey")
    println(result)
}
