package ru.abledo.agent

import ru.abledo.agent.engine.*
import ru.abledo.agent.node.NodesCommon
import ru.abledo.agent.node.NodesLLM
import ru.abledo.db.DesktopInfoRepository
import ru.abledo.db.VectorDB
import ru.abledo.db.asString
import ru.abledo.giga.*
import ru.abledo.tool.ToolCategory
import ru.abledo.tool.ToolsFactory
import ru.abledo.tool.UserMessageClassifier
import ru.abledo.tool.LocalRegexClassifier
import ru.abledo.tool.ToolRunBashCommand
import ru.abledo.tool.desktop.ToolShowApps
import io.ktor.util.logging.debug
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.kodein.di.DI
import org.kodein.di.instance
import ru.abledo.di.mainDiModule
import ru.abledo.tool.browser.detectDefaultBrowser
import ru.abledo.tool.browser.prettyName
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil

class GraphBasedAgent(
    private val model: String,
    private val llmApi: GigaChatAPI,
    private val desktopInfoRepository: DesktopInfoRepository,
) {
    private val l = LoggerFactory.getLogger(GraphBasedAgent::class.java)
    private val nodesLLM = NodesLLM(llmApi)
    private val logObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val apiClassifier: UserMessageClassifier = ApiClassifier(llmApi)
    private val localClassifier: UserMessageClassifier = LocalRegexClassifier

    // Make sure summarization only happens after all tool requests from LLM are answered
    private val nodeSummarize: Node<GigaResponse.Chat, String> by graph(name = "Go to user") {
        nodeInput.edgeTo { ctx -> if (ctx.historyIsTooBig()) nodesLLM.summarize else NodesCommon.respToString }
        nodesLLM.summarize.edgeTo(NodesCommon.respToString)
        NodesCommon.respToString.edgeTo(nodeFinish)
    }

    private val nodeClassify: Node<String, String> = Node("classify") { ctx: AgentContext<String> ->
        val category = classify(ctx.input, ctx.history)
        val functions = category?.let { functionsByCategory[it] } ?: allFunctions
        ctx.map(activeTools = functions) { it }
    }

    private val nodeAppendAdditionalData: Node<String, String> = Node("appendActualInformation") { ctx ->
        val additionalMessage: GigaRequest.Message? = appendActualInformation(ctx.input)
        if (additionalMessage == null) {
            ctx
        } else {
            val history: List<GigaRequest.Message> = ctx.history
                .filterNot { msg -> msg.role == GigaMessageRole.user && msg.content.startsWith(INFO_PREFIX) }
                .plus(additionalMessage)
            ctx.map(history = history)
        }
    }

    private val settings = AgentSettings(
        model = model,
        temperature = 0.7f,
        toolsByCategory = ToolsFactory(desktopInfoRepository).toolsByCategory
    )
    private val functionsByCategory: Map<ToolCategory, List<GigaRequest.Function>> =
        settings.toolsByCategory.mapValues { entry -> entry.value.values.map { setup -> setup.fn } }
    private val allFunctions: List<GigaRequest.Function> = settings.tools.values.map { it.fn }
    private val initialCtx = AgentContext(
        input = "",
        settings = settings,
        history = emptyList(),
        activeTools = allFunctions,
        systemPrompt = SYSTEM_PROMPT
    )

    private val _ctx: MutableStateFlow<AgentContext<String>> = MutableStateFlow(initialCtx)
    val currentContext: StateFlow<AgentContext<String>> = _ctx

    private val runningJob = AtomicReference<Deferred<*>>()

    fun clearContext(): Boolean {
        cancelActiveJob()
        return _ctx.tryEmit(initialCtx)
    }

    fun setContext(ctx: AgentContext<String>): Boolean {
        cancelActiveJob()
        return _ctx.tryEmit(ctx)
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

    private suspend fun classify(
        userText: String,
        history: List<GigaRequest.Message>,
    ): ToolCategory? {
        val body = buildClassifierBody(userText, history)
        val bodyJson = gigaJsonMapper.writeValueAsString(body)
        l.debug("Classifying user message: {}, \nbody: \n{}", userText, logObjectMapper.writeValueAsString(body))
        return try {
            val categoryByLocal = localClassifier.classify(bodyJson)
            val categoryByApi = apiClassifier.classify(bodyJson)
            if (categoryByApi != categoryByLocal) {
                l.info("Categories do not match: Local: {}, API: {}", categoryByLocal, categoryByApi)
                null
            } else {
                categoryByLocal
            }
        } catch (e: Exception) {
            l.error("Error in apiClassifier: {}", e.message)
            null
        }
    }

    private fun buildClassifierBody(
        userText: String,
        history: List<GigaRequest.Message>,
    ): GigaRequest.Chat {
        val smallHistory = history
            .takeLast(if (history.size > 3) 2 else 0)
            .joinToString("\n") { it.content }
        val messages = listOf(
            GigaRequest.Message(GigaMessageRole.system, CLASSIFIER_PROMPT),
            GigaRequest.Message(GigaMessageRole.user, "History:\n$smallHistory\n"),
            GigaRequest.Message(GigaMessageRole.user, "New message:\n$userText"),
        )
        return GigaRequest.Chat(
            model = settings.model,
            messages = messages,
            functions = emptyList(),
        )
    }

    private suspend fun appendActualInformation(userText: String): GigaRequest.Message? {
        if (userText.isBlank()) return null

        val msgRelatedDataInTheStore: String = try {
            desktopInfoRepository.search(userText).asString()
        } catch (e: Exception) {
            l.error("Error while searching desktop info: {}", e.message)
            ""
        }

        val openedApps = try {
            val msg = ToolShowApps.invoke(ToolShowApps.Input(ToolShowApps.AppState.running))
            "Эти приложения сейчас открыты: $msg"
        } catch (e: Exception) {
            l.error("Error while fetching opened apps: {}", e.message)
            ""
        }

        val browserName = try {
            val defaultBrowser = ToolRunBashCommand.detectDefaultBrowser()
            "Дефолтный браузер — ${defaultBrowser.prettyName}"
        } catch (e: Exception) {
            l.error("Error while fetching opened tabs: {}", e.message)
            ""
        }

        return GigaRequest.Message(
            role = GigaMessageRole.user,
            content = listOf(INFO_PREFIX, msgRelatedDataInTheStore, openedApps, browserName)
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

private val CLASSIFIER_PROMPT = """
Ты — алгоритм классификации. Выбери категорию запроса.
Категории:
- coder: если слышишь "кодер", или когда нужно объяснить, изменить или написать код, провести рефакторинг;
- browser: веб-страницы, вкладки, или браузерные горячие клавиши, или когда надо получить общую информацию о новостях или погоде;
- desktop: манипуляции с рабочем столом, окнами и экранами, открытие и использование приложений, работа с заметками, открытие папок и файлов;
- config: изменение или сохранение настроек, вроде скорости речи, запоминание и исполнение инструкций;
- dataAnalytics: когда надо создать график или найти корреляцию между двумя переменными;
Примеры:
добавь вызов логов в данную функцию -> coder
что делается выделенный код-> coder
открой сайт сбербанка -> browser
найди в закладках обзор фондового рынка -> browser
расскажи кратко о чем рассказано на текущей странице -> browser
напиши Анюте сообщение: это тест приложения
открой фото тёти фроси -> desktop
открой папку отчеты -> desktop
открой приложение Intellij IDEA -> desktop
перемести окно на передний план -> desktop
запомни инструкцию при слове тишина уменьшай громкость -> config
построй график дохода по клиенту -> dataAnalytics

Ответ с только одним словом: coder, browser, desktop, config, or dataAnalytics.
""".trimIndent()

val SYSTEM_PROMPT = """
Ты — помощник, управляющий компьютером. Будь полезным. Говори только по существу.
Если получил команду, выполняй, потом говори, что сделал.
Если какую-то задачу можно решить c помощью имеющихся функций, сделай, а не проси пользователя сделать это.
Если сомневаешься, уточни.
Если работаешь с файлами, отвечай кратко, не нужно рассказывать все, только по делу.
""".trimIndent()

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val api: GigaRestChatAPI by di.instance()
    val desktopRepo = DesktopInfoRepository(api, VectorDB)
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