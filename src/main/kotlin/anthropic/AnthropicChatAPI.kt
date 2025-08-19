package com.dumch.anthropic

import com.dumch.giga.*
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.desktop.ToolOpen
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

class AnthropicChatAPI(
    private val fallback: GigaRestChatAPI = GigaRestChatAPI.INSTANCE,
) : GigaChatAPI by fallback {

    private val l = LoggerFactory.getLogger(AnthropicChatAPI::class.java)

    private val client: HttpClient = HttpClient(CIO) {
        anthropicDefaults()
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    l.debug(message)
                }
            }
            level = LogLevel.INFO
            sanitizeHeader { it.equals("x-api-key", true) }
        }
        install(SSE) {
            maxReconnectionAttempts = 0
            reconnectionTime = 3.seconds
        }
    }

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> = flow {
        try {
            client.sse(
                urlString = URL,
                request = {
                    method = HttpMethod.Post
                    val request = buildRequest(body).apply { put("stream", true) }
                    l.info("Request: $request")
                    setBody(request)
                }
            ) {
                incoming.collect { event ->
                    val data = event.data ?: return@collect
                    if (data == "[DONE]") return@collect
                    val node = objectMapper.readTree(data)
                    val type = node["type"]?.asText()
                    if (type == "content_block_delta") {
                        val text = node["delta"]?.get("text")?.asText().orEmpty()
                        if (text.isNotEmpty()) {
                            emit(toChunk(text, body.model, node["index"]?.asInt() ?: 0))
                        }
                    }
                    // TODO()
                    // need to support tools use here
                }
            }
        } catch (t: Throwable) {
            l.error("Error in Anthropic chat stream", t)
            emit(GigaResponse.Chat.Error(-1, "Connection error: ${t.message}"))
        }
    }

    private fun buildRequest(body: GigaRequest.Chat): HashMap<String, Any> {
        val systemPrompt = body.messages
            .filter { it.role == GigaMessageRole.system }
            .joinToString("\n") { it.content }
            .takeIf { it.isNotBlank() }

        val messages = body.messages
            .filter { it.role != GigaMessageRole.system }
            .map { msg ->
                mapOf(
                    "role" to when (msg.role) {
                        GigaMessageRole.assistant -> "assistant"
                        else -> "user"
                    },
                    "content" to listOf(
                        mapOf("type" to "text", "text" to msg.content)
                    )
                )
            }

        val tools = body.functions.map { fn ->
            val properties = fn.parameters.properties.mapValues { (_, prop) ->
                mutableMapOf<String, Any>("type" to prop.type).apply {
                    prop.description?.let { put("description", it) }
                    prop.enum?.let { put("enum", it) }
                }
            }
            mapOf(
                "name" to fn.name,
                "description" to fn.description,
                "input_schema" to mapOf(
                    "type" to fn.parameters.type,
                    "properties" to properties,
                    "required" to fn.parameters.required,
                )
            )
        }

        return HashMap<String, Any>().apply {
            put("model", body.model)
            put("max_tokens", body.maxTokens)
            put("messages", messages)
            systemPrompt?.let { put("system", it) }
            body.temperature?.let { put("temperature", it) }
            if (tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", mapOf("type" to "auto"))
            }
        }
    }

    private fun toChunk(text: String, model: String, index: Int): GigaResponse.Chat {
        val choice = GigaResponse.Choice(
            message = GigaResponse.Message(
                content = text,
                role = GigaMessageRole.assistant,
                functionCall = null,
                functionsStateId = null,
            ),
            index = index,
            finishReason = null,
        )
        return GigaResponse.Chat.Ok(
            choices = listOf(choice),
            created = System.currentTimeMillis() / 1000,
            model = model,
            usage = GigaResponse.Usage(0, 0, 0, 0),
        )
    }

    companion object {
        private const val URL = "https://api.anthropic.com/v1/messages"
    }
}

suspend fun main() {
    val api = AnthropicChatAPI(GigaRestChatAPI.INSTANCE)
    val systemPrompt = GigaRequest.Message(
        role = GigaMessageRole.system,
        content = """
                Ты — помощник человека с ограниченными возможностями. Будь полезным. Говори только по существу. Если какую-то задачу можно решить 
                c помощью имеющихся функций, сделай, а не проси пользователя сделать это. Если сомневаешься, уточни.
            """.trimIndent()
    )

    val result = api.messageStream(
        GigaRequest.Chat(
            model = "claude-3-7-sonnet-20250219",
            stream = true,
            messages = listOf(
                systemPrompt,
                GigaRequest.Message(
                    role = GigaMessageRole.user,
                    content = "Открой папку ~/Downloads",
                ),
            ),
            functions = listOf(
                ToolOpen(ToolRunBashCommand).toGiga(),
            ).map { it.fn }
        ))
    result.collect {
        println("Response: $it")
    }
}
