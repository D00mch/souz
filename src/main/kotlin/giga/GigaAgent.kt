package com.dumch.giga

import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.config.ConfigStore
import com.dumch.tool.config.ToolInstructionStore
import com.dumch.tool.config.ToolSoundConfig
import com.dumch.tool.config.ToolSoundConfigDiff
import com.dumch.tool.desktop.*
import com.dumch.tool.files.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.collections.map
import java.util.concurrent.atomic.AtomicBoolean

class GigaAgent(
    private val userMessages: Flow<String>,
    private val api: GigaChatAPI,
    private val settings: Settings,
    private val config: ConfigStore = ConfigStore,
) {
    private val l = LoggerFactory.getLogger(GigaAgent::class.java)
    private val tools: Map<String, GigaToolSetup> = settings.functions
    private val functions: List<GigaRequest.Function> = settings.functions.map { it.value.fn }
    private val installedApps = runCatching {
        ToolShowApps.invoke(ToolShowApps.Input(ToolShowApps.AppState.installed))
    }.getOrElse { "[]" }
    private val stopRequested = AtomicBoolean(false)

    fun run(): Flow<String> = channelFlow {
        val conversation = ArrayDeque<GigaRequest.Message>().apply {
            add(systemPrompt)
        }

        userMessages.collect { userText ->
            appendSystemInfo(conversation)
            conversation.add(GigaRequest.Message(GigaMessageRole.user, userText))
            if (settings.stream) {
                streamPipeline(conversation)
            } else {
                singleResponsePipeline(conversation)
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
                "instructions" to instructions
            )
        )
        conversation.add(GigaRequest.Message(GigaMessageRole.user, apps))
    }

    private suspend fun ProducerScope<String>.streamPipeline(conversation: ArrayDeque<GigaRequest.Message>) {
        stopRequested.set(false)
        val responses = chatStream(conversation)
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
            streamPipeline(conversation)
        }
    }

    fun stop() {
        stopRequested.set(true)
    }

    private suspend fun ProducerScope<String>.singleResponsePipeline(conversation: ArrayDeque<GigaRequest.Message>) {
        for (i in 1..10) { // infinite loop protection
            if (!isActive) break
            val response: GigaResponse.Chat = try {
                withContext(Dispatchers.IO) { chat(conversation) }
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
        )
        return api.messageStream(body)
    }

    data class Settings(
        val functions: Map<String, GigaToolSetup>,
        val model: GigaModel = GigaModel.Max,
        val stream: Boolean = false,
    )

    companion object {
        private const val SUMMARIZE_THRESHOLD = 0.95

        private val systemPrompt = GigaRequest.Message(
            role = GigaMessageRole.system,
            content = """
                Ты — помощник человека с ограниченными возможностями. Будь полезным. Говори только по существу. Если какую-то задачу можно решить 
                c помощью имеющихся функций, сделай, а не проси пользователя сделать это. Если сомневаешься, уточни.
            """.trimIndent()
        )

        private val tools: Map<String, GigaToolSetup> by lazy {
            listOf(
                ToolReadFile.toGiga(),
                ToolListFiles.toGiga(),
                ToolNewFile.toGiga(),
                ToolDeleteFile.toGiga(),
                ToolModifyFile.toGiga(),
                ToolWindowsManager.toGiga(),
                ToolSoundConfig(ConfigStore).toGiga(),
                ToolSoundConfigDiff(ConfigStore).toGiga(),
                ToolSafariInfo(ToolRunBashCommand).toGiga(),
                ToolMouseClickMac().toGiga(),
                ToolHotkeyMac().toGiga(),
                ToolMediaControl(ToolRunBashCommand).toGiga(),
                ToolFindTextInFiles.toGiga(),
                ToolDesktopScreenShot().toGiga(),
                ToolInstructionStore(ConfigStore).toGiga(),
                ToolCreateNote(ToolRunBashCommand).toGiga(),
//                ToolShowApps.toGiga(),
//                ToolOpenFolder(ToolRunBashCommand).toGiga(),
                ToolCollectButtons(ToolRunBashCommand).toGiga(),
                ToolOpen(ToolRunBashCommand).toGiga(),
                ToolCreateNewBrowserTab(ToolRunBashCommand).toGiga(),
                ToolMinimizeWindows(ToolRunBashCommand).toGiga(),
            ).associateBy { it.fn.name }
        }

        fun instance(
            userMessages: Flow<String>,
            api: GigaChatAPI,
            model: GigaModel = GigaModel.Max,
            settings: Settings = Settings(tools, model, stream = true)
        ): GigaAgent = GigaAgent(userMessages, api, settings)
    }
}