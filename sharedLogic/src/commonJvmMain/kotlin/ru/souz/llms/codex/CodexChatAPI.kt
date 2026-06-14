package ru.souz.llms.codex

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.TokenLogging
import ru.souz.llms.restJsonMapper
import java.io.File

class CodexChatAPI(
    private val settingsProvider: SettingsProvider,
    private val tokenLogging: TokenLogging,
    private val oauthService: CodexOAuthService,
) : LLMChatAPI {

    private val l = LoggerFactory.getLogger(CodexChatAPI::class.java)

    private val client = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = settingsProvider.requestTimeoutMillis }
        install(ContentNegotiation) {
            jackson { disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) }
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) = l.debug(message)
            }
            level = LogLevel.INFO
            sanitizeHeader { it.equals(HttpHeaders.Authorization, true) }
        }
    }

    // Codex API requires stream=true; accumulate all chunks into one response.
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = try {
        messageStream(body).let { flow ->
            var last: LLMResponse.Chat = LLMResponse.Chat.Error(-1, "No response from Codex")
            flow.collect { chunk -> last = chunk }
            last
        }.also { result ->
            if (result is LLMResponse.Chat.Ok) {
                l.info("Codex model: ${body.model}. Response received")
                tokenLogging.logTokenUsage(result, body)
            }
        }
    } catch (t: Throwable) {
        l.error("Codex model: ${body.model}. Error in chat", t)
        LLMResponse.Chat.Error(-1, "Codex error: ${t.message}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = channelFlow {
        try {
            val token = oauthService.refreshTokenIfNeeded()
            val accountId = settingsProvider.codexAccountId.orEmpty()
            val requestBody = buildResponsesRequest(body, stream = true)

            client.preparePost(CODEX_BASE_URL) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                header("Chatgpt-Account-Id", accountId)
                header("originator", ORIGINATOR)
                header("OpenAI-Beta", OPENAI_BETA)
                setBody(requestBody)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    send(LLMResponse.Chat.Error(response.status.value, response.bodyAsText()))
                    return@execute
                }

                val channel = response.bodyAsChannel()
                var pendingEvent: String? = null
                val collectedItems = mutableListOf<JsonNode>()
                var usageNode: JsonNode? = null
                val created = System.currentTimeMillis() / 1000

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    when {
                        line.startsWith("event: ") -> pendingEvent = line.removePrefix("event: ").trim()
                        line.startsWith("data: ") -> {
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") break
                            try {
                                val node = restJsonMapper.readTree(data)
                                // Event type: prefer SSE header, fall back to JSON "type" field
                                val eventType = pendingEvent ?: node["type"]?.asText()
                                l.info("Codex SSE event: $eventType")
                                when (eventType) {
                                    "response.output_item.done" -> {
                                        val item = node["item"] ?: node
                                        collectedItems.add(item)
                                    }
                                    "response.completed" -> {
                                        val resp = node["response"] ?: node
                                        usageNode = resp["usage"]
                                        val result = buildChatOkFromItems(collectedItems, usageNode, body.model, created)
                                        send(result)
                                    }
                                    "response.failed", "response.incomplete" -> {
                                        val err = node["response"]?.get("error")?.asText()
                                            ?: node["error"]?.asText()
                                            ?: "Stream error: $eventType"
                                        send(LLMResponse.Chat.Error(-1, err))
                                    }
                                }
                            } catch (e: Exception) {
                                l.debug("Codex SSE parse error: ${e.message}")
                            }
                            pendingEvent = null
                        }
                    }
                }

                // fallback: if response.completed never fired but items were collected
                if (collectedItems.isNotEmpty() && usageNode == null) {
                    send(buildChatOkFromItems(collectedItems, null, body.model, created))
                }
            }
        } catch (t: Throwable) {
            l.error("Codex stream error", t)
            send(LLMResponse.Chat.Error(-1, "Codex stream error: ${t.message}"))
        }
    }

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        LLMResponse.Embeddings.Error(-1, "Codex provider does not support embeddings")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        throw UnsupportedOperationException("Codex provider does not support file uploads")

    override suspend fun downloadFile(fileId: String): String? = null

    override suspend fun balance(): LLMResponse.Balance =
        LLMResponse.Balance.Error(-1, "Balance not available for Codex")

    // --- Request building ---

    private fun buildResponsesRequest(body: LLMRequest.Chat, stream: Boolean): Map<String, Any?> {
        val instructions = body.messages.firstOrNull { it.role == LLMMessageRole.system }?.content
        val inputItems = body.messages
            .filter { it.role != LLMMessageRole.system }
            .mapNotNull { msg -> mapMessageToInputItem(msg) }

        l.info("Codex input[${inputItems.size}]: ${inputItems.mapIndexed { i, item ->
            val type = item["type"] as? String ?: "?"
            val extra = when (type) {
                "function_call" -> " call_id=${item["call_id"]} name=${item["name"]}"
                "function_call_output" -> " call_id=${item["call_id"]}"
                "message" -> " role=${item["role"]}"
                else -> ""
            }
            "[$i]$type$extra"
        }}")

        val tools: List<Map<String, Any>>? = body.functions.takeIf { it.isNotEmpty() }?.map { fn ->
            mapOf(
                "type" to "function",
                "name" to fn.name,
                "description" to fn.description,
                "parameters" to mapOf(
                    "type" to fn.parameters.type,
                    "properties" to fn.parameters.properties.mapValues { (_, prop) ->
                        buildMap {
                            put("type", prop.type)
                            if (!prop.description.isNullOrBlank()) put("description", prop.description)
                            if (!prop.enum.isNullOrEmpty()) put("enum", prop.enum)
                        }
                    },
                    "required" to fn.parameters.required,
                )
            )
        }

        return buildMap {
            put("model", body.model)
            put("input", inputItems)
            if (!instructions.isNullOrBlank()) put("instructions", instructions)
            put("store", false)
            put("stream", stream)
            if (!tools.isNullOrEmpty()) put("tools", tools)
        }
    }

    private fun mapMessageToInputItem(msg: LLMRequest.Message): Map<String, Any?>? {
        return when (msg.role) {
            LLMMessageRole.user -> mapOf("type" to "message", "role" to "user", "content" to msg.content)
            LLMMessageRole.assistant -> {
                if (msg.functionsStateId != null) {
                    // assistant tool call
                    val parsed = runCatching {
                        restJsonMapper.readValue<Map<String, Any>>(msg.content)
                    }.getOrNull()
                    if (parsed != null) {
                        mapOf(
                            "type" to "function_call",
                            "call_id" to msg.functionsStateId,
                            "name" to (parsed["name"] as? String ?: ""),
                            "arguments" to (
                                parsed["arguments"]?.let { args ->
                                    if (args is String) args
                                    else restJsonMapper.writeValueAsString(args)
                                } ?: "{}"
                            ),
                        )
                    } else {
                        mapOf("type" to "message", "role" to "assistant", "content" to msg.content)
                    }
                } else {
                    mapOf("type" to "message", "role" to "assistant", "content" to msg.content)
                }
            }
            LLMMessageRole.function -> mapOf(
                "type" to "function_call_output",
                "call_id" to (msg.functionsStateId ?: msg.name ?: "unknown"),
                "output" to msg.content,
            )
            LLMMessageRole.system -> null // extracted to instructions
        }
    }

    // --- Response parsing ---

    private fun parseResponsesResponse(text: String, model: String): LLMResponse.Chat {
        return try {
            val node = restJsonMapper.readTree(text)
            val outputArray = node["output"] ?: return LLMResponse.Chat.Error(-1, "No output in response")
            val usageNode = node["usage"]
            val created = node["created_at"]?.asLong() ?: (System.currentTimeMillis() / 1000)
            buildChatOkFromItems(outputArray.toList(), usageNode, model, created)
        } catch (e: Exception) {
            l.error("Codex: failed to parse response: $text", e)
            LLMResponse.Chat.Error(-1, "Parse error: ${e.message}")
        }
    }

    private fun buildChatOkFromItems(
        items: List<JsonNode>,
        usageNode: JsonNode?,
        model: String,
        created: Long,
    ): LLMResponse.Chat.Ok {
        val choices = mutableListOf<LLMResponse.Choice>()

        l.info("Codex: parsing ${items.size} output items: ${items.map { it["type"]?.asText() }}")
        items.forEach { item ->
            val type = item["type"]?.asText()
            when (type) {
                "message" -> {
                    val contentNode = item["content"]
                    val text = when {
                        contentNode == null -> item["text"]?.asText() ?: ""
                        contentNode.isArray -> contentNode.mapNotNull { c ->
                            // Responses API uses "output_text", Chat Completions uses "text"
                            val cType = c["type"]?.asText()
                            if (cType == "text" || cType == "output_text") c["text"]?.asText() else null
                        }.joinToString("")
                        contentNode.isTextual -> contentNode.asText()
                        else -> ""
                    }
                    if (text.isNotBlank()) {
                        choices.add(
                            LLMResponse.Choice(
                                message = LLMResponse.Message(
                                    content = text,
                                    role = LLMMessageRole.assistant,
                                    functionsStateId = null,
                                ),
                                index = choices.size,
                                finishReason = LLMResponse.FinishReason.stop,
                            )
                        )
                    }
                }
                "function_call" -> {
                    val name = item["name"]?.asText() ?: return@forEach
                    val argsRaw = item["arguments"]?.asText() ?: "{}"
                    val callId = item["call_id"]?.asText() ?: item["id"]?.asText()
                    val args = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        restJsonMapper.readValue<Map<String, Any>>(argsRaw)
                    }.getOrDefault(emptyMap())
                    choices.add(
                        LLMResponse.Choice(
                            message = LLMResponse.Message(
                                content = restJsonMapper.writeValueAsString(
                                    mapOf("name" to name, "arguments" to args)
                                ),
                                role = LLMMessageRole.assistant,
                                functionCall = LLMResponse.FunctionCall(name = name, arguments = args),
                                functionsStateId = callId,
                            ),
                            index = choices.size,
                            finishReason = LLMResponse.FinishReason.function_call,
                        )
                    )
                }
            }
        }

        val usage = LLMResponse.Usage(
            promptTokens = usageNode?.get("input_tokens")?.asInt() ?: 0,
            completionTokens = usageNode?.get("output_tokens")?.asInt() ?: 0,
            totalTokens = usageNode?.get("total_tokens")?.asInt() ?: 0,
            precachedTokens = 0,
        )

        return LLMResponse.Chat.Ok(choices = choices, created = created, model = model, usage = usage)
    }

    companion object {
        private const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex/responses"
        private const val ORIGINATOR = "codex_cli_rs"
        private const val OPENAI_BETA = "responses=experimental"
    }
}
