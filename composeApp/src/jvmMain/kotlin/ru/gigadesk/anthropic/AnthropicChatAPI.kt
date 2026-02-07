package ru.gigadesk.anthropic

import ru.gigadesk.giga.*
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.application.ToolOpen
import ru.gigadesk.tool.files.FilesToolUtil
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.di.mainDiModule
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
    private val fallback: GigaChatAPI,
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

    override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat = try {
        val response = client.post(URL) {
            header("anthropic-beta", "files-api-2025-04-14")
            val request = buildRequest(body)
            l.info("Request: $request")
            setBody(request)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            GigaResponse.Chat.Error(response.status.value, text)
        } else {
            val node = objectMapper.readTree(text)
            val model = node["model"]?.asText() ?: body.model

            val choices = mutableListOf<GigaResponse.Choice>()
            val content = node["content"] ?: objectMapper.createArrayNode()
            content.forEachIndexed { index, blockNode ->
                when (blockNode["type"]?.asText()) {
                    "text" -> {
                        val txt = blockNode["text"]?.asText().orEmpty()
                        choices += GigaResponse.Choice(
                            message = GigaResponse.Message(
                                content = txt,
                                role = GigaMessageRole.assistant,
                                functionCall = null,
                                functionsStateId = null,
                            ),
                            index = index,
                            finishReason = null,
                        )
                    }
                    "tool_use" -> {
                        val name = blockNode["name"]?.asText().orEmpty()
                        val id = blockNode["id"]?.asText()
                        val inputStr = blockNode["input"]?.toString() ?: "{}"
                        val args: Map<String, Any> = if (inputStr.isNotBlank()) {
                            objectMapper.readValue(inputStr)
                        } else emptyMap()
                        choices += GigaResponse.Choice(
                            message = GigaResponse.Message(
                                content = "",
                                role = GigaMessageRole.assistant,
                                functionCall = GigaResponse.FunctionCall(name, args),
                                functionsStateId = id,
                            ),
                            index = index,
                            finishReason = GigaResponse.FinishReason.function_call,
                        )
                    }
                }
            }

            val usageNode = node["usage"]
            val usage = GigaResponse.Usage(
                promptTokens = usageNode?.get("input_tokens")?.asInt() ?: 0,
                completionTokens = usageNode?.get("output_tokens")?.asInt() ?: 0,
                totalTokens = (usageNode?.get("input_tokens")?.asInt() ?: 0) +
                        (usageNode?.get("output_tokens")?.asInt() ?: 0),
                precachedTokens = 0,
            )

            val finish = when (node["stop_reason"]?.asText()) {
                "max_tokens" -> GigaResponse.FinishReason.length
                "tool_use" -> GigaResponse.FinishReason.function_call
                "end_turn", "stop_sequence" -> GigaResponse.FinishReason.stop
                else -> null
            }
            if (finish != null && choices.isNotEmpty()) {
                val last = choices.last()
                if (last.finishReason == null) {
                    choices[choices.lastIndex] = last.copy(finishReason = finish)
                }
            }

            GigaResponse.Chat.Ok(
                choices = choices,
                created = System.currentTimeMillis() / 1000,
                model = model,
                usage = usage,
            )
        }
    } catch (e: ClientRequestException) { // 4xx
        val resp = e.response.bodyAsText()
        l.error("Anthropic 400 body: $resp")
        GigaResponse.Chat.Error(e.response.status.value, resp)
    } catch (t: Throwable) {
        l.error("Error in Anthropic chat", t)
        GigaResponse.Chat.Error(-1, "Connection error: ${t.message}")
    }

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

    override suspend fun balance(): GigaResponse.Balance = fallback.balance()

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
                    val blockType = if (mime.startsWith("composeApp/src/jvmMain/kotlin/ru.gigadesk/image/")) "composeApp/src/jvmMain/kotlin/ru.gigadesk/imageseApp/src/jvmMain/kotlin/ru.gigadesk/image" else "document"
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
    val di = DI.invoke { import(mainDiModule) }
    val fallback: GigaChatAPI by di.instance()
    val filesToolUtil: FilesToolUtil by di.instance()
    val api = AnthropicChatAPI(fallback)

//    val result = api.uploadFile(File("/Users/m1/Pictures/портрет.jpeg"))
//    println(result)
//    if (true) return

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
                attachments = listOf("011CSK2qjkHumd9ZdXnTPCoR"),
            ),
        ),
        functions = listOf(
            ToolOpen(ToolRunBashCommand, filesToolUtil).toGiga(),
        ).map { it.fn }
    )

    val result = api.message(request)
    println("Result: $result")
//     api.messageStream(request).collect { println("Response: $it") }
}
