package ru.gigadesk.agent.node

import ru.gigadesk.agent.engine.Node
import ru.gigadesk.giga.GigaChatAPI
import ru.gigadesk.giga.GigaException
import ru.gigadesk.giga.GigaMessageRole
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.gigaJsonMapper
import ru.gigadesk.giga.toSystemPromptMessage
import io.ktor.util.logging.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class NodesLLM(llmApi: GigaChatAPI) {

    private val l = LoggerFactory.getLogger(NodesLLM::class.java)

    val requestToResponse: Node<GigaRequest.Chat, GigaResponse.Chat> = Node("llmCall") { ctx ->
        l.debug { "LLM input is ${ctx.input}" }
        val response = withContext(Dispatchers.IO) {
            llmApi.message(ctx.input)
        }
        l.debug("LLM response is {}", response)
        val history = ArrayList(ctx.history).apply {
            if (response is GigaResponse.Chat.Ok) {
                addAll(response.choices.mapNotNull { it.toMessage() })
            }
        }
        ctx.map(history = history) { response }
    }

    /**
     * Restores the last message, and a system prompt. Other messages are transformed into TLDR
     */
    val summarize: Node<GigaResponse.Chat, GigaResponse.Chat> = Node("llmSummarize") { ctx ->
        val conversation = ArrayList(ctx.history)

        val summaryResponse: GigaResponse.Chat = withContext(Dispatchers.IO) {
            conversation.add(GigaRequest.Message(
                role = GigaMessageRole.user,
                content = "Резюмируй разговор",
            ))
            val request = ctx.toGigaRequest(conversation)
                .copy(functions = emptyList())
            llmApi.message(request)
        }

        val msg: GigaRequest.Message = when (summaryResponse) {
            is GigaResponse.Chat.Error -> {
                l.error("Error on summarization: ${summaryResponse.message}")
                throw GigaException(summaryResponse)
            }
            is GigaResponse.Chat.Ok -> summaryResponse.choices.mapNotNull { it.toMessage() }.last()
        }

        val newHistory = listOf(ctx.systemPrompt.toSystemPromptMessage(), msg)
        ctx.map(history = newHistory) { summaryResponse }
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
}