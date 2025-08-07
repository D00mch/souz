package com.dumch.giga

import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.desktop.ToolOpenApp
import com.dumch.tool.desktop.ToolOpenBrowser
import com.dumch.tool.files.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

class GigaAgent(
    private val userMessages: Flow<String>,
    private val api: GigaChatAPI,
    private val tools: Map<String, GigaToolSetup>,
) {
    private val functions: List<GigaRequest.Function> = tools.map { it.value.fn }

    fun run(): Flow<String> = channelFlow {
        val conversation = ArrayList<GigaRequest.Message>()

        userMessages.collect { userText ->
            trySummarize(conversation)
            conversation.add(GigaRequest.Message(GigaMessageRole.user, userText))
            for (i in 1..10) { // infinite loop protection
                if (!isActive) break
                val response: GigaResponse.Chat = withContext(Dispatchers.IO) {
                    chat(conversation)
                }
                when (response) {
                    is GigaResponse.Chat.Error -> {
                        send(response.message)
                        close()
                        return@collect
                    }

                    is GigaResponse.Chat.Ok -> response
                }
                conversation.addAll(response.toRequestMessages())

                val toolAwaits = ArrayList<Deferred<GigaRequest.Message>>()
                for (ch in response.choices) {
                    val msg = ch.message
                    when {
                        msg.content.isNotBlank() && msg.functionsStateId == null -> send(msg.content)

                        msg.functionCall != null && msg.functionsStateId != null -> {
                            val deferred = async(Dispatchers.IO) { executeTool(msg.functionCall) }
                            toolAwaits.add(deferred)
                        }
                    }
                }
                if (toolAwaits.isEmpty()) break
                conversation.addAll(toolAwaits.awaitAll())
            }
        }
    }

    private suspend fun trySummarize(conversation: ArrayList<GigaRequest.Message>) {
        val inputTokens = api.countTokens(conversation)
        if (inputTokens > MAX_TOKENS * THRESHOLD_PCT) return

        val messages = ArrayList(conversation)
        messages.add(
            GigaRequest.Message(
                role = GigaMessageRole.system,
                content = "Summarize the conversation so far",
            )
        )
        val response: GigaResponse.Chat = withContext(Dispatchers.IO) {
            chat(messages)
        }
        val summary: GigaRequest.Message = when (response) {
            is GigaResponse.Chat.Error -> throw CancellationException("Can't summarize the conversation")
            is GigaResponse.Chat.Ok -> response.toRequestMessages().last()
        }
        val lastMsg = conversation.lastOrNull()
        conversation.clear()
        conversation.add(summary)
        if (lastMsg != null) conversation.add(lastMsg)
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

    private fun executeTool(functionCall: GigaResponse.FunctionCall): GigaRequest.Message {
        val fn = tools[functionCall.name] ?: return GigaRequest.Message(
            GigaMessageRole.function, """{"result":"no such function ${functionCall.name}"}"""
        )
        println("Executing tool: ${fn.fn.name}, arguments: ${functionCall.arguments}")
        return fn.invoke(functionCall)
    }

    private suspend fun chat(conversation: ArrayList<GigaRequest.Message>): GigaResponse.Chat {
        val body = GigaRequest.Chat(
            messages = conversation,
            functions = functions,
        )
        return api.message(body)
    }

    companion object {
        private const val MAX_TOKENS = 10_120L
        private const val THRESHOLD_PCT = 0.8

        private val tools: Map<String, GigaToolSetup> = listOf(
            ToolReadFile.toGiga(),
            ToolListFiles.toGiga(),
            ToolNewFile.toGiga(),
            ToolDeleteFile.toGiga(),
            ToolModifyFile.toGiga(),
            ToolFindTextInFiles.toGiga(),
            ToolOpenBrowser(ToolRunBashCommand).toGiga(),
            ToolOpenApp(ToolRunBashCommand).toGiga(),
        ).associateBy { it.fn.name }

        fun instance(userMessages: Flow<String>, api: GigaChatAPI): GigaAgent {
            return GigaAgent(userMessages, api, tools)
        }
    }
}