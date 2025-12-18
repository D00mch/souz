package ru.gigadesk.giga

import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.db.StorredData
import ru.gigadesk.db.asString
import ru.gigadesk.tool.*
import ru.gigadesk.tool.browser.ToolSafariInfo
import ru.gigadesk.tool.application.ToolShowApps
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
import ru.gigadesk.agent.nodes.NodesClassification
import ru.gigadesk.keys.SelectedText
import java.util.concurrent.atomic.AtomicBoolean

class GigaAgent(
    private val userMessages: Flow<String>,
    private val api: GigaChatAPI,
    private val ragRepo: DesktopInfoRepository,
    private val settings: Settings,
    private val nodesClassification: NodesClassification,
    private val apiClassifier: UserMessageClassifier = ApiClassifier(api),
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
    private val recreateRequested = AtomicBoolean(false)

    fun run(): Flow<String> = channelFlow {
        val conversation = ArrayDeque<GigaRequest.Message>().apply {
            add(systemPrompt)
        }

        userMessages.collect { userText ->
            if (recreateRequested.get()) {
                recreateRequested.set(false)
                clean(conversation)
            }

            val category = classify(userText, conversation)
            appendActualInformation(userText, conversation)
            val fns = category?.let { functionsByCategory[it] } ?: functions
            conversation.add(GigaRequest.Message(GigaMessageRole.user, userText))
            if (settings.stream) {
                streamPipeline(conversation, fns)
            } else {
                singleResponsePipeline(conversation, fns)
            }
        }
    }

    private suspend fun appendActualInformation(
        userText: String,
        conversation: ArrayDeque<GigaRequest.Message>
    ) {
        val msgEmbeddings: List<StorredData> = ragRepo.search(userText)
        val openedApps = runCatching {
            ToolShowApps.invoke(ToolShowApps.Input(ToolShowApps.AppState.running))
        }.getOrElse { "[]" }
        val safariOpenedTabs = runCatching {
            ToolSafariInfo(ToolRunBashCommand).invoke(ToolSafariInfo.Input(ToolSafariInfo.InfoType.tabs))
        }.getOrElse { "{}" }
        conversation.add(
            GigaRequest.Message(
                role = GigaMessageRole.user,
                content = "Информация о моей системе: ${msgEmbeddings.asString()}" +
                        ", Эти приложения сейчас открыты: $openedApps," +
                        ", Эти вкладки в Safari открыты: $safariOpenedTabs"
            )
        )
    }

    private suspend fun ProducerScope<String>.streamPipeline(
        conversation: ArrayDeque<GigaRequest.Message>,
        fns: List<GigaRequest.Function>,
        cycle: Int = 0,
    ) {
        if (cycle > MAX_TOOLS_CYCLES) return
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
        conversation.squeezeTexts()
        if (results.isEmpty()) {
            return
        } else {
            conversation.addAll(results)
            trySummarize(totalTokens, conversation)
            streamPipeline(conversation, fns, cycle + 1)
        }
    }

    // Consolidates agent messages with text into one message
    private fun ArrayDeque<GigaRequest.Message>.squeezeTexts() {
        if (isEmpty()) return
        val squeezed = ArrayDeque<GigaRequest.Message>(size)
        for (msg in this) {
            val last = squeezed.lastOrNull()
            if (
                last != null &&
                last.role == GigaMessageRole.assistant &&
                msg.role == GigaMessageRole.assistant &&
                last.content.isNotBlank() &&
                msg.content.isNotBlank() &&
                (last.functionsStateId == msg.functionsStateId || msg.functionsStateId == null)
            ) {
                squeezed.removeLast()
                val joined = if (last.content.isEmpty()) msg.content else last.content + "\n" + msg.content
                squeezed.add(last.copy(content = joined))
            } else {
                squeezed.add(msg)
            }
        }
        clear()
        addAll(squeezed)
    }

    fun stop() {
        stopRequested.set(true)
    }

    private suspend fun ProducerScope<String>.singleResponsePipeline(
        conversation: ArrayDeque<GigaRequest.Message>,
        fns: List<GigaRequest.Function>,
    ) {
        for (i in 1..MAX_TOOLS_CYCLES) { // infinite loop protection
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
        try {
            val categoryByLocal = localClassifier.classify(bodyJson)
            val categoryByApi = apiClassifier.classify(bodyJson)
            if (categoryByApi != categoryByLocal) {
                l.info("Categories do not match: Local: $categoryByLocal, API: $categoryByApi")
                return null
            }
            return categoryByLocal
        } catch (e: Exception) {
            l.error("Error in apiClassifier: ${e.message}")
            return null
        }
    }

    private fun buildClassifierBody(
        userText: String,
        conversation: ArrayDeque<GigaRequest.Message>,
    ): GigaRequest.Chat {
        val smallHistory = conversation.takeLast(if (conversation.size > 3) 2 else 0)
            .joinToString("\n") { it.content }
        val messages = ArrayDeque<GigaRequest.Message>().apply {
            add(GigaRequest.Message(GigaMessageRole.system, nodesClassification.buildPrompt(toolsByCategory)))
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
        clean(conversation)
        conversation.add(msg)
    }

    fun requestCleanUp() {
        recreateRequested.set(true)
        stopRequested.set(true)
    }

    private fun clean(conversation: ArrayDeque<GigaRequest.Message>) {
        conversation.clear()
        conversation.add(systemPrompt)
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
        private const val SUMMARIZE_THRESHOLD = 0.8
        private const val MAX_TOOLS_CYCLES = 8

        private val SYSTEM_PROMPT = """
Ты — помощник, управляющий компьютером. Будь полезным. Говори только по существу.
Если получил команду, выполняй, потом говори, что сделал.
Если какую-то задачу можно решить c помощью имеющихся функций, сделай, а не проси пользователя сделать это. 
Если сомневаешься, уточни. 
Если работаешь с файлами, отвечай кратко, не нужно рассказывать все, только по делу.
""".trimIndent()

        private val systemPrompt = GigaRequest.Message(
            role = GigaMessageRole.system,
            content = SYSTEM_PROMPT
        )

        fun instance(
            userMessages: Flow<String>,
            api: GigaChatAPI,
            desktopRepo: DesktopInfoRepository,
            nodesClassification: NodesClassification,
            selectedText: SelectedText,
            model: GigaModel = GigaModel.Max,
            settings: Settings = Settings(ToolsFactory(desktopRepo, selectedText).toolsByCategory, model, stream = false)
        ): GigaAgent = GigaAgent(userMessages, api, desktopRepo, settings, nodesClassification)
    }
}