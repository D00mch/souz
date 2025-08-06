package com.dumch.giga

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

    // Чтобы самим не думать об управлении ЖЦ, воспользуемся имеющимся channelFlow
    fun run(): Flow<String> = channelFlow {
        val conversation = ArrayList<GigaRequest.Message>() // TODO: shrink on every N steps

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
        if (conversation.size > 20) return // TODO: decide based on model's max tokens
        val response: GigaResponse.Chat = withContext(Dispatchers.IO) {
            conversation.add(GigaRequest.Message(
                role = GigaMessageRole.system,
                content = "Summarize the conversation so far",
            ))
            chat(conversation)
        }
        val msg: GigaRequest.Message = when(response) {
            is GigaResponse.Chat.Error -> throw CancellationException("Can't summarize the conversation")
            is GigaResponse.Chat.Ok -> response.toRequestMessages().last()
        }
        val lastMsg = conversation.last()
        conversation.clear()
        conversation.add(msg)
        conversation.add(lastMsg)
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
        private val tools: Map<String, GigaToolSetup> = listOf(
            ToolReadFile.toGiga(),
            ToolListFiles.toGiga(),
            ToolNewFile.toGiga(),
            ToolDeleteFile.toGiga(),
            ToolModifyFile.toGiga(),
            ToolFindTextInFiles.toGiga(),
        ).associateBy { it.fn.name }

        fun instance(userMessages: Flow<String>, api: GigaChatAPI): GigaAgent {
            return GigaAgent(userMessages, api, tools)
        }
    }
}