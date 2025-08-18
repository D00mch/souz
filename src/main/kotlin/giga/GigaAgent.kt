package com.dumch.giga

import com.dumch.db.DesktopInfoRepository
import com.dumch.tool.UserMessageClassifier
import com.dumch.tool.LocalRegexClassifier
import com.dumch.tool.ToolCategory
import com.dumch.tool.ToolsFactory
import com.dumch.tool.desktop.ToolShowApps
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
    private val ragRepo: DesktopInfoRepository,
    private val settings: Settings,
    private val apiClassifier: UserMessageClassifier = ApiGigaClassifier(api),
    private val localClassifier: UserMessageClassifier = LocalRegexClassifier,
) {
    private val l = LoggerFactory.getLogger(GigaAgent::class.java)
    private val logObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>> = settings.toolsByCategory
    private val functionsByCategory: Map<ToolCategory, List<GigaRequest.Function>> =
        toolsByCategory.mapValues { entry -> entry.value.values.map { setup -> setup.fn } }

    private val tools: Map<String, GigaToolSetup> =
        toolsByCategory.values.flatMap { it.entries }.associate { it.key to it.value }
    private val functions: List<GigaRequest.Function> = tools.values.map { it.fn }
    private val stopRequested = AtomicBoolean(false)

    fun run(): Flow<String> = channelFlow {
        val conversation = ArrayDeque<GigaRequest.Message>().apply {
            add(systemPrompt)
        }

        userMessages.collect { userText ->
            val category = classify(userText, conversation)
            appendRelatedTextsFromDB(userText, conversation)
            appendCurrentDesktopInfo(conversation)
            val fns = category?.let { functionsByCategory[it] } ?: functions
            conversation.add(GigaRequest.Message(GigaMessageRole.user, userText))
            if (settings.stream) {
                streamPipeline(conversation, fns)
            } else {
                singleResponsePipeline(conversation, fns)
            }
        }
    }

    private suspend fun appendRelatedTextsFromDB(
        userText: String,
        conversation: ArrayDeque<GigaRequest.Message>
    ) {
        val msgEmbeddings = ragRepo.search(userText)
        conversation.add(
            GigaRequest.Message(
                role = GigaMessageRole.user,
                content = msgEmbeddings.joinToString("; ", prefix = "$DESKTOP_DETAILS: ")
            )
        )
    }

    private fun appendCurrentDesktopInfo(
        conversation: ArrayDeque<GigaRequest.Message>
    ) {
        val openedApps = runCatching {
            ToolShowApps.invoke(ToolShowApps.Input(ToolShowApps.AppState.running))
        }.getOrElse { "[]" }
        val safariOpenedTabs = runCatching {
            ToolSafariInfo(ToolRunBashCommand).invoke(ToolSafariInfo.Input(ToolSafariInfo.InfoType.tabs))
        }.getOrElse { "{}" }
        val apps = objectMapper.writeValueAsString(
            mapOf(
                "opened apps" to openedApps,
                "opened safari tabs to position number" to safariOpenedTabs,
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
        l.debug("Classifying user message: $userText, \nbody: \n${logObjectMapper.writeValueAsString(body)}")
        return try {
            apiClassifier.classify(bodyJson)
        } catch (e: Exception) {
            l.error("Error in apiClassifier: ${e.message}")
            localClassifier.classify(bodyJson)
        }
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
        val msg: GigaRequest.Message = when (summaryResponse) {
            is GigaResponse.Chat.Error -> {
                l.error("Error on summarization: ${summaryResponse.message}")
                return conversation.clear()
            }
            is GigaResponse.Chat.Ok -> summaryResponse.toRequestMessages().last()
        }
        l.info("Summarizing the conversation... $msg")
        conversation.clear()
        conversation.add(systemPrompt)
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
Ты — алгоритм классификации. Выбери категорию запроса.
Категории:
- coder: если слышишь "кодер", или когда нужно объяснить, изменить или написать код, провести рефакторинг;
- browser: веб-страницы, вкладки, или браузерные горячие клавиши, или когда надо получить общую информацию о новостях или погоде;
- desktop: манипуляции с рабочем столом, окнами и экранами, открытие и использование приложений, работа с заметками, открытие папко;
- io: когда понадобится получить скриншот экрана или прочесть, что на экране;            
- config: изменение или сохранение настроек, вроде скорости речи, запоминание инструкций;            
- dataAnalytics: когда надо создать график или найти корреляцию между двумя переменными.
Примеры:
"напиши функцию" -> coder,
"открой вкладку" -> browser,
"перемести окно" -> desktop,
"расскажи, что на экране" -> io, 
"прочти весь текст с экрана" -> io,
"уменьши громкость" -> config,
"построй график дохода" -> dataAnalytics

Ответ с только одним словом: coder, browser, desktop, io, config, or dataAnalytics.
""".trimIndent()

        private val SYSTEM_PROMPT = """
Ты — помощник человека с ограниченными возможностями. Будь полезным. Говори только по существу. Если какую-то задачу можно решить 
c помощью имеющихся функций, сделай, а не проси пользователя сделать это. Если сомневаешься, уточни.
Не зацикливайся на задаче, если ее нельзя решить за 5 шагов. Экономь мои токены!
Если работаешь с файлами, отвечай кратко, не нужно рассказывать все, только по делу.
""".trimIndent()

        private const val DESKTOP_DETAILS = "Вот информация о системе, которая может быть полезна"

        private val systemPrompt = GigaRequest.Message(
            role = GigaMessageRole.system,
            content = SYSTEM_PROMPT
        )

        fun instance(
            userMessages: Flow<String>,
            api: GigaChatAPI,
            desktopRepo: DesktopInfoRepository,
            model: GigaModel = GigaModel.Max,
            settings: Settings = Settings(ToolsFactory(desktopRepo).toolsByCategory, model, stream = true)
        ): GigaAgent = GigaAgent(userMessages, api, desktopRepo, settings)
    }
}