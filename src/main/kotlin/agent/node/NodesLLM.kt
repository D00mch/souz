package com.dumch.agent.node

import com.dumch.agent.Node
import com.dumch.giga.GigaChatAPI
import com.dumch.giga.GigaRequest
import com.dumch.giga.GigaResponse
import com.dumch.giga.gigaJsonMapper
import io.ktor.util.logging.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class NodesLLM(llmApi: GigaChatAPI) {

    private val l = LoggerFactory.getLogger(NodesLLM::class.java)

    val nodeCallMainLLM: Node<GigaRequest.Chat, GigaResponse.Chat> = Node("llmCall") { ctx ->
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