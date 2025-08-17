package com.dumch.giga

import com.dumch.tool.GigaClassifier
import com.dumch.tool.LocalRegexClassifier
import com.dumch.tool.ToolCategory
import com.dumch.tool.ToolsFactory
import com.dumch.tool.config.ConfigStore
import com.dumch.tool.config.ToolInstructionStore
import com.dumch.tool.desktop.ToolShowApps
import com.dumch.tool.files.ToolListFiles
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.browser.ToolSafariInfo
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

class GigaAgent(
    private val userMessages: Flow<String>,
    private val api: GigaChatAPI,
    private val settings: Settings,
    private val config: ConfigStore = ConfigStore,
    private val apiClassifier: GigaClassifier = ApiGigaClassifier(api),
    private val localClassifier: GigaClassifier = LocalRegexClassifier(),
) {
    private val l = LoggerFactory.getLogger(GigaAgent::class.java)
    private val logObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>> = settings.toolsByCategory
    private val functionsByCategory: Map<ToolCategory, List<GigaRequest.Function>> =
        toolsByCategory.mapValues { entry -> entry.value.values.map { setup -> setup.fn } }

    private val tools: Map<String, GigaToolSetup> =
        toolsByCategory.values.flatMap { it.entries }.associate { it.key to it.value }
    private val functions: List<GigaRequest.Function> = tools.values.map { it.fn }
    private val installedApps = runCatching {
        ToolShowApps.invoke(ToolShowApps.Input(ToolShowApps.AppState.installed))
    }.getOrElse { "[]" }
    private val safariCurrentTabUrl = runCatching {
        ToolSafariInfo(ToolRunBashCommand).invoke(
            ToolSafariInfo.Input(ToolSafariInfo.InfoType.currentTabUrl)
        )
    }.getOrElse { "" }
    private val stopRequested = AtomicBoolean(false)

    fun run(): Flow<String> = channelFlow {
        val conversation = ArrayDeque<GigaRequest.Message>().apply {
            add(systemPrompt)
            appendSystemInfo(this)
        }

        userMessages.collect { userText ->
            val category = classify(userText, conversation)
            val fns = category?.let { functionsByCategory[it] } ?: functions
            conversation.add(GigaRequest.Message(GigaMessageRole.user, userText))
            if (settings.stream) {
                streamPipeline(conversation, fns)
            } else {
                singleResponsePipeline(conversation, fns)
            }
        }
    }

    private fun appendSystemInfo(
        conversation: ArrayDeque<GigaRequest.Message>
    ) {
        val openedApps = runCatching { ToolShowApps.invoke(ToolShowApps.Input(ToolShowApps.AppState.running)) }
            .getOrElse { "[]" }
        val dirs = runCatching {ToolListFiles.invoke(ToolListFiles.Input(System.getenv("HOME"), 3))}
            .getOrElse { "[]" }
        val instructions = runCatching {
            val currentInstructions =
                config.get<ArrayList<ToolInstructionStore.Input>>(ToolInstructionStore.INSTUCTIONS_KEY, ArrayList())
            currentInstructions.map { (name: String, instr: String) ->
                "Когда я говорю: `$name`, выполняй инструкцию: $instr"
            }
        }
        val apps = objectMapper.writeValueAsString(
            mapOf(
                "installed" to installedApps,
                "opened" to openedApps,
                "dirs" to dirs,
                "instructions" to instructions,
                "currentOpenedPageUrl" to safariCurrentTabUrl,
            )
        )
        conversation.add(GigaRequest.Message(GigaMessageRole.user, apps))
    }

    private suspend fun ProducerScope<String>.streamPipeline(
        conversation: ArrayDeque<GigaRequest.Message>,
        fns: List<GigaRequest.Function>,
    ) {
        stopRequested.set(false)
        val responses = chatStream(conversation, fns)
        val results = ArrayList<GigaRequest.Message>()
        var totalTokens = 0
        responses.takeWhile { response ->
            l.info("response: $response")
            !stopRequested.get() && when (response) {
                is GigaResponse.Chat.Error -> {
                    l.error("Error in chunk: response: $response")
                    send("Ошибочка вышла, извините")
                    false
                }
                is GigaResponse.Chat.Ok -> true
            }
        }.collect { response ->
            if (stopRequested.get()) return@collect
            (response as GigaResponse.Chat.Ok).choices.forEach { choice ->
                if (stopRequested.get()) return@forEach
                choice.toMessage()?.let { msg ->
                    conversation.add(msg)
                    handleGigaChoice(choice)?.let { results.add(it) }
                }
            }
            totalTokens += response.usage.totalTokens
        }

        if (stopRequested.get() || results.isEmpty()) {
            return
        } else {
            conversation.addAll(results)
            trySummarize(totalTokens, conversation)
            streamPipeline(conversation, fns)
        }
    }

    fun stop() {
        stopRequested.set(true)
    }

    private suspend fun ProducerScope<String>.singleResponsePipeline(
        conversation: ArrayDeque<GigaRequest.Message>,
        fns: List<GigaRequest.Function>,
    ) {
        for (i in 1..10) { // infinite loop protection
            if (!isActive) break
            val response: GigaResponse.Chat = try {
                withContext(Dispatchers.IO) { chat(conversation, fns) }
            } catch (e: Throwable) {
                l.error("Error: loop $i: ${e.message}", e)
                send("Не смогли достучаться до сервера. Будем пробовать снова?")
                break
            }
            when (response) {
                is GigaResponse.Chat.Error -> {
                    l.error("Error: loop $i: ${response.message}")
                    send("Возникли сложности, объясните еще раз")
                    break
                }

                is GigaResponse.Chat.Ok -> {
                    try {
                        trySummarize(response.usage.totalTokens, conversation)
                        conversation.addAll(response.toRequestMessages())
                    } catch (e: Throwable) {
                        send("Error: loop $i: ${e.message}. Продолжаем работу?")
                        break
                    }

                    val fnCallMessages = response.choices.mapNotNull { handleGigaChoice(it) }

                    // if no functions invoked, we can proceed to the next user's message
                    if (fnCallMessages.isEmpty()) break
                    conversation.addAll(fnCallMessages)
                }
            }
        }
    }

    /** @return true if function was invoked */
    private suspend fun ProducerScope<String>.handleGigaChoice(ch: GigaResponse.Choice): GigaRequest.Message? {
        val msg = ch.message
        when {
            msg.functionCall != null && msg.functionsStateId != null -> return executeTool(msg.functionCall)
            msg.content.isNotBlank() -> send(msg.content)
        }
        return null
    }

    private suspend fun classify(userText: String, conversation: ArrayDeque<GigaRequest.Message>): ToolCategory? {
        val body = buildClassifierBody(userText, conversation)
        val bodyJson = gigaJsonMapper.writeValueAsString(body)
        l.info("Classifying user message: $userText, \nbody: \n${logObjectMapper.writeValueAsString(body)}")
        return apiClassifier.classify(bodyJson) ?: localClassifier.classify(bodyJson)
    }

    private fun buildClassifierBody(
        userText: String,
        conversation: ArrayDeque<GigaRequest.Message>,
    ): GigaRequest.Chat {
        val smallHistory = conversation.takeLast(if (conversation.size > 3) 2 else 0)
            .joinToString("\n") { it.content }
        val messages = ArrayDeque<GigaRequest.Message>().apply {
            add(GigaRequest.Message(GigaMessageRole.system, CLASSIFIER_PROMPT))
            add(GigaRequest.Message(GigaMessageRole.user, "History:\n$smallHistory\n"))
            add(GigaRequest.Message(GigaMessageRole.user, "New message:\n$userText"))
        }
        return GigaRequest.Chat(
            model = settings.model.alias,
            messages = messages,
            functions = emptyList(),
        )
    }

    private suspend fun trySummarize(totalTokens: Int, conversation: ArrayDeque<GigaRequest.Message>) {
        val modelContextWindow = settings.model.maxTokens
        val smallConversation = totalTokens < modelContextWindow * SUMMARIZE_THRESHOLD
        if (smallConversation) return

        l.info("About to summarize the conversation...")
        val summaryResponse: GigaResponse.Chat = withContext(Dispatchers.IO) {
            conversation.add(GigaRequest.Message(
                role = GigaMessageRole.user,
                content = "Резюмируй разговор",
            ))
            chat(conversation, fns = emptyList())
        }
        val msg: GigaRequest.Message = when(summaryResponse) {
            is GigaResponse.Chat.Error -> {
                l.error("Error on summarization: ${summaryResponse.message}")
                return conversation.clear()
            }
            is GigaResponse.Chat.Ok -> summaryResponse.toRequestMessages().last()
        }
        l.info("Summarizing the conversation... $msg")
        conversation.clear()
        conversation.add(systemPrompt)
        appendSystemInfo(conversation)
        conversation.add(msg)
    }

    private fun GigaResponse.Chat.Ok.toRequestMessages(): Collection<GigaRequest.Message> {
        return choices.mapNotNull { it.toMessage() }
    }

    private fun GigaResponse.Choice.toMessage(): GigaRequest.Message? {
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

    private suspend fun executeTool(functionCall: GigaResponse.FunctionCall): GigaRequest.Message {
        val fn = tools[functionCall.name] ?: return GigaRequest.Message(
            GigaMessageRole.function, """{"result":"no such function ${functionCall.name}"}"""
        )
        l.info("Executing tool: ${fn.fn.name}, arguments: ${functionCall.arguments}")
        return fn.invoke(functionCall)
    }

    private suspend fun chat(
        conversation: ArrayDeque<GigaRequest.Message>,
        fns: List<GigaRequest.Function> = functions,
    ): GigaResponse.Chat {
        val body = GigaRequest.Chat(
            model = settings.model.alias,
            messages = conversation,
            functions = fns,
            temperature = settings.temperature,
        )
        return api.message(body)
    }

    private suspend fun chatStream(
        conversation: ArrayDeque<GigaRequest.Message>,
        fns: List<GigaRequest.Function> = functions,
    ): Flow<GigaResponse.Chat> {
        val body = GigaRequest.Chat(
            model = settings.model.alias,
            messages = conversation,
            functions = fns,
            temperature = settings.temperature,
        )
        l.debug("Chat request:\n{}", logObjectMapper.writeValueAsString(body))
        return api.messageStream(body)
    }

    data class Settings(
        val toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>>,
        val model: GigaModel = GigaModel.Max,
        val stream: Boolean = false,
        val temperature: Float? = null,
    )

    companion object {
        private const val SUMMARIZE_THRESHOLD = 0.95

        private val CLASSIFIER_PROMPT = """
You are a classification algorithm. Pick one category for the user's request.
Categories:
- coder: file operations or searching text, when we need to update README.md in the project or find something in code;
- browser: web pages, tabs, or browser hotkeys, or when we need to get general info like weather or news;
- desktop: windows, apps, mouse or general hotkeys;
- io: when we want to get screenshot, or download/upload a document;
- config: changing or storing settings, like sound speed or instructions.
- dataAnalytics: when we want to analyze data, like plotting a graph or finding correlations.
Examples: "создай файл" -> coder, "открой вкладку" -> browser,
"перемести окно" -> desktop, "сделай скриншот" -> io, "уменьши громкость" -> config, "построй график дохода" -> dataAnalytics
Respond with exactly one word: coder, browser, desktop, io, config, or dataAnalytics
""".trimIndent()

        private val SYSTEM_PROMPT = """
Ты — помощник человека с ограниченными возможностями. Будь полезным. Говори только по существу. Если какую-то задачу можно решить 
c помощью имеющихся функций, сделай, а не проси пользователя сделать это. Если сомневаешься, уточни.
Не зацикливайся на задаче, если ее нельзя решить за 5 шагов. Экономь мои токены!
Если работаешь с файлами, отвечай кратко, не нужно рассказывать все, только по делу.
""".trimIndent()

        private val systemPrompt = GigaRequest.Message(
            role = GigaMessageRole.system,
            content = SYSTEM_PROMPT
        )

        fun instance(
            userMessages: Flow<String>,
            api: GigaChatAPI,
            model: GigaModel = GigaModel.Max,
            settings: Settings = Settings(ToolsFactory.toolsByCategory, model, stream = true)
        ): GigaAgent = GigaAgent(userMessages, api, settings)
    }
}