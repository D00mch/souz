package com.dumch.anthropic

import com.dumch.giga.*
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.desktop.ToolOpen
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds
import java.io.File
import java.nio.file.Files

const val MODEL = "claude-3-5-haiku-20241022"

private data class ToolUseBlock(
    val name: String,
    val id: String?,
    val inputBuilder: StringBuilder = StringBuilder(),
    val initialInput: String = "{}",
)

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

    // Dump function to get errors like 400
    suspend fun dump(body: GigaRequest.Chat): GigaResponse.Chat {
        val resp = client.post("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            val body = buildRequest(body)
            setBody(body + ("stream" to false)) // force non-streaming
        }
        val text = resp.bodyAsText()
        println("status=${resp.status.value}, body=$text")
        TODO("Not yet implemented")
    }

    private val fileTypes = mutableMapOf<String, String>()

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> = channelFlow {
        val toolBlocks = mutableMapOf<Int, ToolUseBlock>()
        try {
            client.sse(
                urlString = URL,
                request = {
                    method = HttpMethod.Post
                    header("anthropic-beta", "files-api-2025-04-14")
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
                    when (type) {
                        "content_block_delta" -> {
                            val index = node["index"]?.asInt() ?: 0
                            val delta = node["delta"]
                            val text = delta?.get("text")?.asText().orEmpty()
                            if (text.isNotEmpty()) {
                                send(toChunk(text, body.model, index))
                            } else {
                                val partialJson = delta?.get("partial_json")?.asText()
                                if (!partialJson.isNullOrEmpty()) {
                                    toolBlocks[index]?.inputBuilder?.append(partialJson)
                                }
                            }
                        }
                        "content_block_start" -> {
                            val index = node["index"]?.asInt() ?: 0
                            val block = node["content_block"]
                            if (block?.get("type")?.asText() == "tool_use") {
                                val name = block["name"]?.asText().orEmpty()
                                val id = block["id"]?.asText()
                                val input = block["input"]?.toString() ?: "{}"
                                toolBlocks[index] = ToolUseBlock(name, id, StringBuilder(), input)
                            }
                        }
                        "content_block_stop" -> {
                            val index = node["index"]?.asInt() ?: 0
                            val block = toolBlocks.remove(index)
                            if (block != null) {
                                val jsonStr = if (block.inputBuilder.isNotEmpty()) {
                                    block.inputBuilder.toString()
                                } else {
                                    block.initialInput
                                }
                                val args: Map<String, Any> = if (jsonStr.isNotBlank()) {
                                    objectMapper.readValue(jsonStr)
                                } else emptyMap()
                                send(toToolChunk(block.name, args, block.id, body.model, index))
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            l.error("Error in Anthropic chat stream", t)
            send(GigaResponse.Chat.Error(-1, "Connection error: ${t.message}"))
        }
    }

    override suspend fun uploadFile(file: File): GigaResponse.UploadFile {
        val mime = Files.probeContentType(file.toPath()) ?: "application/octet-stream"
        val response = client.submitFormWithBinaryData(
            url = FILES_URL,
            formData = formData {
                append(
                    key = "file",
                    value = file.readBytes(),
                    headers = Headers.build {
                        append(
                            HttpHeaders.ContentDisposition,
                            "form-data; name=\"file\"; filename=\"${file.name}\""
                        )

                        append(HttpHeaders.ContentType, mime)
                    }
                )
            }
        ) {
            header("anthropic-beta", "files-api-2025-04-14")
        }
        val node = objectMapper.readTree(response.bodyAsText())
        val upload = GigaResponse.UploadFile(
            bytes = node["bytes"]?.asLong() ?: 0L,
            createdAt = node["created_at"]?.asLong() ?: System.currentTimeMillis() / 1000,
            filename = node["filename"]?.asText() ?: file.name,
            id = node["id"]?.asText() ?: "",
            objectType = node["type"]?.asText() ?: node["object"]?.asText() ?: "file",
            purpose = node["purpose"]?.asText() ?: "general",
            accessPolicy = node["access_policy"]?.asText() ?: "",
        )
        fileTypes[upload.id] = mime
        return upload
    }

    private fun buildRequest(body: GigaRequest.Chat): HashMap<String, Any> {
        val systemPrompt = body.messages
            .filter { it.role == GigaMessageRole.system }
            .joinToString("\n") { it.content }
            .takeIf { it.isNotBlank() }

        val messages = body.messages
            .filter { it.role != GigaMessageRole.system }
            .map { msg ->
                val content = ArrayList<Map<String, Any>>()
                content += mapOf("type" to "text", "text" to msg.content)
                msg.attachments?.forEach { id ->
                    val mime = fileTypes[id].orEmpty()
                    val blockType = if (mime.startsWith("image/")) "image" else "document"
                    content += mapOf(
                        "type" to blockType,
                        "source" to mapOf(
                            "type" to "file",
                            "file_id" to id,
                        ),
                    )
                }
                mapOf(
                    "role" to when (msg.role) {
                        GigaMessageRole.assistant -> "assistant"
                        else -> "user"
                    },
                    "content" to content
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
            put("model", MODEL)
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

    private fun toToolChunk(
        name: String,
        args: Map<String, Any>,
        functionsStateId: String?,
        model: String,
        index: Int,
    ): GigaResponse.Chat {
        val choice = GigaResponse.Choice(
            message = GigaResponse.Message(
                content = "",
                role = GigaMessageRole.assistant,
                functionCall = GigaResponse.FunctionCall(name, args),
                functionsStateId = functionsStateId,
            ),
            index = index,
            finishReason = GigaResponse.FinishReason.function_call,
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
        private const val FILES_URL = "https://api.anthropic.com/v1/files"
    }
}

suspend fun main() {
    val api = AnthropicChatAPI(GigaRestChatAPI.INSTANCE)
//
//    val result = api.uploadFile(File("/Users/m1/IdeaProjects/kotlin/abledo/patch.patch"))
//    println(result)

    val systemPrompt = GigaRequest.Message(
        role = GigaMessageRole.system,
        content = """
                Ты — помощник человека с ограниченными возможностями. Будь полезным. Говори только по существу. Если какую-то задачу можно решить
                c помощью имеющихся функций, сделай, а не проси пользователя сделать это. Если сомневаешься, уточни.
            """.trimIndent()
    )

    val request = GigaRequest.Chat(
        model = MODEL,
        stream = true,
        messages = listOf(
            systemPrompt,
            GigaRequest.Message(
                role = GigaMessageRole.user,
                content = "Открой папку ~/Downloads",
                attachments = listOf("file_011CSJmf9TkDp4foEAxjvW5M"),
            ),
        ),
        functions = listOf(
            ToolOpen(ToolRunBashCommand).toGiga(),
        ).map { it.fn }
    )

//    api.message(request)
     api.messageStream(request).collect { println("Response: $it") }
}