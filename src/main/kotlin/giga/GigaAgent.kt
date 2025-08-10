package com.dumch.giga

import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.desktop.ToolCollectButtons
import com.dumch.tool.desktop.ToolCreateNote
import com.dumch.tool.desktop.ToolDesktopScreenShot
import com.dumch.tool.desktop.ToolMouseClickMac
import com.dumch.tool.desktop.ToolOpenApp
import com.dumch.tool.desktop.ToolOpenFile
import com.dumch.tool.desktop.ToolOpenFolder
import com.dumch.tool.desktop.ToolOpenPhoto
import com.dumch.tool.desktop.ToolCreateNewBrowserTab
import com.dumch.tool.desktop.ToolMinimizeWindows
import com.dumch.tool.files.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.slf4j.LoggerFactory

class GigaAgent(
    private val userMessages: Flow<String>,
    private val api: GigaChatAPI,
    private val tools: Map<String, GigaToolSetup>,
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
                    l.error("Error: ${e.message}", e)
                    send("Не смогли достучаться до сервера. Будем пробовать снова?")
                    break
                }
                when (response) {
                    is GigaResponse.Chat.Error -> {
                        l.error("Error: ${response.message}")
                        send("Возникли сложности, объясните еще раз")
                        break
                    }

                    is GigaResponse.Chat.Ok -> {
                        conversation.addAll(response.toRequestMessages())
                        try {
                            trySummarize(response, conversation)
                        } catch (e: Throwable) {
                            send("Error: ${e.message}. Continue? (y/n)")
                            break
                        }

                        val toolAwaits = ArrayList<Deferred<GigaRequest.Message>>()
                        for (ch in response.choices) {
                            val msg = ch.message
                            when {
                                msg.content.isNotBlank() -> {
                                    send(msg.content)
                                }

                                msg.functionCall != null && msg.functionsStateId != null -> {
                                    val deferred = async(Dispatchers.IO) { executeTool(msg.functionCall) }
                                    toolAwaits.add(deferred)
                                }
                            }
                        }
                        if (toolAwaits.isEmpty()) break
                        try {
                            conversation.addAll(toolAwaits.awaitAll())
                        } catch (t: Throwable) {
                            send("Error: ${t.message}. Continue? (y/n)")
                            break
                        }
                    }
                }
            }
        }
    }

    private suspend fun trySummarize(response: GigaResponse.Chat.Ok, conversation: ArrayDeque<GigaRequest.Message>) {
        val modelContextWindow = GigaModel.entries.first { response.model.startsWith(it.alias) }.maxTokens
        val smallConversation = response.usage.totalTokens < modelContextWindow * SUMMARIZE_THRESHOLD
        if (smallConversation) return

        val summaryResponse: GigaResponse.Chat = withContext(Dispatchers.IO) {
            conversation.add(GigaRequest.Message(
                role = GigaMessageRole.user,
                content = "Summarize the conversation so far",
            ))
            chat(conversation)
        }
        val msg: GigaRequest.Message = when(summaryResponse) {
            is GigaResponse.Chat.Error -> {
                l.error("Error on summarization: ${summaryResponse.message}")
                return trySummarizaWithLess(conversation, response)
            }
            is GigaResponse.Chat.Ok -> summaryResponse.toRequestMessages().last()
        }
        l.info("Summarizing the conversation... $msg")
        val lastMsg = conversation.last()
        conversation.clear()
        conversation.add(systemPrompt)
        conversation.add(msg)
        conversation.add(lastMsg)
    }

    private suspend fun trySummarizaWithLess(
        conversation: ArrayDeque<GigaRequest.Message>,
        response: GigaResponse.Chat.Ok
    ) {
        conversation.removeFirst() // Remove system prompt
        conversation.removeFirst() // Remove first user message
        conversation.removeLast() // Summarization request message
        trySummarize(response, conversation)
    }

    private fun GigaResponse.Chat.Ok.toRequestMessages(): Collection<GigaRequest.Message> {
        return choices.map { ch ->
            val msg = ch.message
            val content: String = when {
                msg.content.isNotBlank() -> msg.content

                msg.functionCall != null -> gigaJsonMapper.writeValueAsString(
                    mapOf("name" to msg.functionCall.name, "arguments" to msg.functionCall.arguments)
                )

                else -> throw IllegalStateException("Can't get content from ${ch}")
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

    private suspend fun chat(conversation: ArrayDeque<GigaRequest.Message>): GigaResponse.Chat {
        val body = GigaRequest.Chat(
            messages = conversation,
            functions = functions,
        )
        return api.message(body)
    }

    companion object {
        private const val SUMMARIZE_THRESHOLD = 0.7

        private val systemPrompt = GigaRequest.Message(
            role = GigaMessageRole.system,
            content = """
                Ты — помощник человека с ограниченными возможностями. Будь полезным. Говори только по существу. Если какую-то задачу можно решить 
                c помощью имеющихся функций, сделай, а не проси пользователя сделать это. Если сомневаешься, уточни.
                Если юзер спрашивает, какие кнопки на экране, воводи только их названия, юзеру не интересны координаты.
                Если тебя просят открыть какой-то сайт, а браузер(например, Safari) уже запущен - открой новую вкладку в том же окне через toolCreateNewBrowserTab. 
                Если тебя просят проанализировать или описать то, что находится на экране, используй тул DesktopScreenShot.
                Если тебя просят нажать на какую-то кнопку, используй тул получения кнопок, а после нажимай. 
                Если тебя просят свернуть данное окно - передавай current в качестве параметра в туле MinimizeWindows.
                Если тебя просят открыть приложение c наименованием на русском языке, при необходимости переводи его на английский, чтобы устройство поняло о какой программе речь, например Заметки - Notes.
                Если тебя просят нажать куда-либо на экране, используй инструмент получения экрана определяй координаты и нажимай на подходящую область используя тул MouseClick.
            """.trimIndent()
        )

        private val tools: Map<String, GigaToolSetup> = listOf(
            ToolReadFile.toGiga(),
            ToolListFiles.toGiga(),
            ToolNewFile.toGiga(),
            ToolDeleteFile.toGiga(),
            ToolModifyFile.toGiga(),
            ToolMouseClickMac().toGiga(),
            ToolFindTextInFiles.toGiga(),
            ToolDesktopScreenShot().toGiga(),
            ToolCreateNote(ToolRunBashCommand).toGiga(),
            ToolOpenPhoto(ToolRunBashCommand).toGiga(),
            ToolOpenFolder(ToolRunBashCommand).toGiga(),
            ToolCollectButtons(ToolRunBashCommand).toGiga(),
            ToolOpenFile(ToolRunBashCommand).toGiga(),
            ToolCreateNewBrowserTab(ToolRunBashCommand).toGiga(),
            ToolMinimizeWindows(ToolRunBashCommand).toGiga(),
            ToolOpenApp(ToolRunBashCommand).toGiga(),
        ).associateBy { it.fn.name }

        fun instance(userMessages: Flow<String>, api: GigaChatAPI): GigaAgent {
            return GigaAgent(userMessages, api, tools)
        }
    }
}