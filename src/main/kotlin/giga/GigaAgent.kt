package com.dumch.giga

import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.desktop.ToolCollectButtons
import com.dumch.tool.desktop.ToolCreateNote
import com.dumch.tool.desktop.ToolDesktopScreenShot
import com.dumch.tool.desktop.ToolMouseClickMac
import com.dumch.tool.desktop.ToolOpenApp
import com.dumch.tool.desktop.ToolOpenFile
import com.dumch.tool.desktop.ToolOpenFolder
import com.dumch.tool.desktop.ToolCreateNewBrowserTab
import com.dumch.tool.desktop.ToolHotkeyMac
import com.dumch.tool.desktop.ToolMediaControl
import com.dumch.tool.desktop.ToolMinimizeWindows
import com.dumch.tool.desktop.ToolSafariInfo
import com.dumch.tool.desktop.ToolWindowsManager
import com.dumch.tool.files.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.slf4j.LoggerFactory

class GigaAgent(
    private val userMessages: Flow<String>,
    private val api: GigaChatAPI,
    private val settings: Settings,
) {
    private val l = LoggerFactory.getLogger(GigaAgent::class.java)
    private val functions: List<GigaRequest.Function> = tools.map { it.value.fn }

    fun run(): Flow<String> = channelFlow {
        val conversation = ArrayDeque<GigaRequest.Message>().apply {
            add(systemPrompt)
        }

        userMessages.collect { userText ->
            conversation.add(GigaRequest.Message(GigaMessageRole.user, userText))
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
                            trySummarize(response, conversation)
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

    private suspend fun trySummarize(response: GigaResponse.Chat.Ok, conversation: ArrayDeque<GigaRequest.Message>) {
        val modelContextWindow = GigaModel.entries.first { response.model.startsWith(it.alias) }.maxTokens
        val smallConversation = response.usage.totalTokens < modelContextWindow * SUMMARIZE_THRESHOLD
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
        return choices.map { ch ->
            val msg = ch.message
            val content: String = when {
                msg.content.isNotBlank() -> msg.content

                msg.functionCall != null -> gigaJsonMapper.writeValueAsString(
                    mapOf("name" to msg.functionCall.name, "arguments" to msg.functionCall.arguments)
                )

                else -> throw IllegalStateException("Can't get content from $ch")
            }
            GigaRequest.Message(
                role = ch.message.role,
                content = content,
                functionsStateId = msg.functionsStateId
            )
        }
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
            model = settings.model,
            messages = conversation,
            functions = fns,
        )
        return api.message(body)
    }

    data class Settings(
        val functions: Map<String, GigaToolSetup>,
        val model: String = GigaModel.Pro.alias,
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

        private val tools: Map<String, GigaToolSetup> = listOf(
            ToolReadFile.toGiga(),
            ToolListFiles.toGiga(),
            ToolNewFile.toGiga(),
            ToolDeleteFile.toGiga(),
            ToolModifyFile.toGiga(),
            ToolWindowsManager.toGiga(),
            ToolSafariInfo(ToolRunBashCommand).toGiga(),
            ToolMouseClickMac().toGiga(),
            ToolHotkeyMac().toGiga(),
            ToolMediaControl(ToolRunBashCommand).toGiga(),
            ToolFindTextInFiles.toGiga(),
            ToolDesktopScreenShot().toGiga(),
            ToolCreateNote(ToolRunBashCommand).toGiga(),
            ToolOpenFolder(ToolRunBashCommand).toGiga(),
            ToolCollectButtons(ToolRunBashCommand).toGiga(),
            ToolOpenFile(ToolRunBashCommand).toGiga(),
            ToolCreateNewBrowserTab(ToolRunBashCommand).toGiga(),
            ToolMinimizeWindows(ToolRunBashCommand).toGiga(),
            ToolOpenApp(ToolRunBashCommand).toGiga(),
        ).associateBy { it.fn.name }

        fun instance(userMessages: Flow<String>, api: GigaChatAPI): GigaAgent {
            val settings = Settings(
                tools,
                GigaModel.Max.alias,
                stream = false,
            )
            return GigaAgent(userMessages, api, settings)
        }
    }
}