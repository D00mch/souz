package ru.gigadesk.llms

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.giga.GigaChatAPI
import ru.gigadesk.giga.GigaMessageRole
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.LlmProvider
import ru.gigadesk.giga.TokenLogging
import ru.gigadesk.giga.gigaJsonMapper
import ru.gigadesk.giga.toFinishReason
import ru.gigadesk.giga.toSystemPromptMessage
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_ANTHROPIC_MODEL = "claude-haiku-4-5-20251001"
private val EPHEMERAL_CACHE_CONTROL = mapOf("type" to "ephemeral")

private data class ToolUseBlock(
    val name: String,
    val id: String?,
    val inputBuilder: StringBuilder = StringBuilder(),
    val initialInput: String = "{}",
)

private data class ParsedToolCall(
    val name: String,
    val arguments: Map<String, Any>,
)

class AnthropicChatAPI(
    private val settingsProvider: SettingsProvider,
    private val tokenLogging: TokenLogging,
) : GigaChatAPI {
    private val l = LoggerFactory.getLogger(AnthropicChatAPI::class.java)

    private val apiKey: String
        get() = settingsProvider.anthropicKey
            ?: System.getenv("ANTHROPIC_API_KEY")
            ?: System.getProperty("ANTHROPIC_API_KEY")
            ?: throw IllegalStateException("ANTHROPIC_API_KEY is not set")

    private val defaultChatModel: String
        get() = System.getenv("ANTHROPIC_MODEL")
            ?: System.getProperty("ANTHROPIC_MODEL")
            ?: DEFAULT_ANTHROPIC_MODEL

    private val client = HttpClient(CIO) {
        anthropicDefaults(
            apiKey = apiKey,
            requestTimeoutMillis = settingsProvider.requestTimeoutMillis,
        )
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

    private val fileTypes = ConcurrentHashMap<String, String>()

    override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat = try {
        val model = resolveChatModel(body.model)
        val response = client.post(MESSAGES_URL) {
            header("anthropic-beta", FILES_API_BETA)
            setBody(buildChatRequest(body, model, stream = false))
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            parseMessageResponse(text, model).also { result ->
                if (result is GigaResponse.Chat.Ok) {
                    tokenLogging.logTokenUsage(result, body.copy(model = model))
                }
            }
        } else {
            GigaResponse.Chat.Error(response.status.value, text)
        }
    } catch (e: ClientRequestException) {
        val text = e.response.bodyAsText()
        GigaResponse.Chat.Error(e.response.status.value, text)
    } catch (t: Throwable) {
        l.error("Model: ${body.model}. Error in Anthropic chat", t)
        GigaResponse.Chat.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> = channelFlow {
        val model = resolveChatModel(body.model)
        val toolBlocks = mutableMapOf<Int, ToolUseBlock>()
        var streamModel = model

        try {
            client.sse(
                urlString = MESSAGES_URL,
                request = {
                    method = HttpMethod.Post
                    header("anthropic-beta", FILES_API_BETA)
                    setBody(buildChatRequest(body, model, stream = true))
                },
            ) {
                incoming.collect { event ->
                    val data = event.data ?: return@collect
                    if (data == "[DONE]" || !data.trimStart().startsWith("{")) {
                        return@collect
                    }

                    val node = runCatching { gigaJsonMapper.readTree(data) }
                        .getOrElse {
                            l.warn("Failed to parse Anthropic stream chunk: $data", it)
                            return@collect
                        }

                    when (node["type"]?.asText()) {
                        "message_start" -> {
                            streamModel = node["message"]?.get("model")?.asText() ?: streamModel
                        }

                        "content_block_start" -> {
                            val index = node["index"]?.asInt() ?: 0
                            val contentBlock = node["content_block"]
                            if (contentBlock?.get("type")?.asText() == "tool_use") {
                                val name = contentBlock["name"]?.asText().orEmpty()
                                val id = contentBlock["id"]?.asText()
                                val input = contentBlock["input"]?.toString() ?: "{}"
                                toolBlocks[index] = ToolUseBlock(
                                    name = name,
                                    id = id,
                                    inputBuilder = StringBuilder(),
                                    initialInput = input,
                                )
                            }
                        }

                        "content_block_delta" -> {
                            val index = node["index"]?.asInt() ?: 0
                            val delta = node["delta"]
                            when (delta?.get("type")?.asText()) {
                                "text_delta" -> {
                                    val textChunk = delta["text"]?.asText().orEmpty()
                                    if (textChunk.isNotEmpty()) {
                                        send(toTextChunk(textChunk, streamModel, index))
                                    }
                                }

                                "input_json_delta" -> {
                                    val partialJson = delta["partial_json"]?.asText()
                                    if (!partialJson.isNullOrEmpty()) {
                                        toolBlocks[index]?.inputBuilder?.append(partialJson)
                                    }
                                }

                                else -> {
                                    val textChunk = delta?.get("text")?.asText().orEmpty()
                                    if (textChunk.isNotEmpty()) {
                                        send(toTextChunk(textChunk, streamModel, index))
                                    }
                                    val partialJson = delta?.get("partial_json")?.asText()
                                    if (!partialJson.isNullOrEmpty()) {
                                        toolBlocks[index]?.inputBuilder?.append(partialJson)
                                    }
                                }
                            }
                        }

                        "content_block_stop" -> {
                            val index = node["index"]?.asInt() ?: 0
                            val block = toolBlocks.remove(index) ?: return@collect
                            val jsonArgs = if (block.inputBuilder.isNotEmpty()) {
                                block.inputBuilder.toString()
                            } else {
                                block.initialInput
                            }
                            val args = parseFunctionArguments(jsonArgs)
                            send(toToolChunk(block.name, args, block.id, streamModel, index))
                        }

                        "message_delta" -> {
                            val finishReason = node["delta"]
                                ?.get("stop_reason")
                                ?.asText()
                                .toAnthropicFinishReason()
                            if (finishReason != null && finishReason != GigaResponse.FinishReason.function_call) {
                                send(toFinishChunk(finishReason, streamModel))
                            }
                        }
                    }
                }
            }
        } catch (e: ClientRequestException) {
            val text = e.response.bodyAsText()
            send(GigaResponse.Chat.Error(e.response.status.value, text))
        } catch (t: Throwable) {
            l.error("Model: ${body.model}. Error in Anthropic stream chat", t)
            send(GigaResponse.Chat.Error(-1, "Connection error: ${t.message}"))
        }
    }

    override suspend fun embeddings(body: GigaRequest.Embeddings): GigaResponse.Embeddings {
        return GigaResponse.Embeddings.Error(-1, "Anthropic embeddings API is not supported")
    }

    override suspend fun uploadFile(file: File): GigaResponse.UploadFile {
        val mime = withContext(Dispatchers.IO) {
            Files.probeContentType(file.toPath())
        } ?: "application/octet-stream"

        val response = client.submitFormWithBinaryData(
            url = FILES_URL,
            formData = formData {
                append(
                    key = "file",
                    value = file.readBytes(),
                    headers = Headers.build {
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${file.name}\"")
                        append(HttpHeaders.ContentType, mime)
                    },
                )
            },
        ) {
            header("anthropic-beta", FILES_API_BETA)
        }

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Anthropic file upload failed: ${response.status.value}. $text")
        }

        val node = gigaJsonMapper.readTree(text)
        val upload = GigaResponse.UploadFile(
            bytes = node["bytes"]?.asLong() ?: 0L,
            createdAt = node["created_at"]?.asLong() ?: (System.currentTimeMillis() / 1000),
            filename = node["filename"]?.asText() ?: file.name,
            id = node["id"]?.asText() ?: "",
            objectType = node["type"]?.asText() ?: node["object"]?.asText() ?: "file",
            purpose = node["purpose"]?.asText() ?: "general",
            accessPolicy = node["access_policy"]?.asText() ?: "",
        )
        fileTypes[upload.id] = mime
        return upload
    }

    override suspend fun downloadFile(fileId: String): String? {
        return null
    }

    override suspend fun balance(): GigaResponse.Balance {
        return GigaResponse.Balance.Error(-1, "Balance check not implemented for Anthropic")
    }

    private fun buildChatRequest(
        body: GigaRequest.Chat,
        model: String,
        stream: Boolean,
    ): MutableMap<String, Any> {
        val messages = buildMessages(body.messages)

        val request = mutableMapOf<String, Any>(
            "model" to model,
            "max_tokens" to body.maxTokens,
            "messages" to messages,
            "stream" to stream,
        )
        buildSystemPrompt(body.messages)?.let { request["system"] = it }
        body.temperature?.let { request["temperature"] = it }

        val tools = buildTools(body.functions)
        if (tools.isNotEmpty()) {
            request["tools"] = tools.withLastToolCacheControl()
            request["tool_choice"] = mapOf("type" to "auto")
        }

        return request
    }

    private fun buildSystemPrompt(messages: List<GigaRequest.Message>): List<Map<String, Any>>? {
        val prompt = messages
            .asSequence()
            .filter { it.role == GigaMessageRole.system }
            .joinToString("\n") { it.content }
            .takeIf { it.isNotBlank() }
            ?: return null

        return listOf(
            mapOf(
                "type" to "text",
                "text" to prompt,
                "cache_control" to EPHEMERAL_CACHE_CONTROL,
            )
        )
    }

    private fun buildMessages(messages: List<GigaRequest.Message>): List<Map<String, Any>> {
        val lastToolCallIds = mutableMapOf<String, String>()

        val builtMessages = messages
            .asSequence()
            .filter { it.role != GigaMessageRole.system }
            .map { msg ->
                when (msg.role) {
                    GigaMessageRole.assistant -> buildAssistantMessage(msg, lastToolCallIds)
                    GigaMessageRole.function -> buildFunctionResultMessage(msg, lastToolCallIds)
                    else -> mapOf(
                        "role" to "user",
                        "content" to buildTextAndAttachments(msg.content, msg.attachments),
                    )
                }
            }
            .toList()

        return addPromptCacheBreakpointToMessages(builtMessages)
    }

    private fun addPromptCacheBreakpointToMessages(messages: List<Map<String, Any>>): List<Map<String, Any>> {
        if (messages.isEmpty()) return messages

        val preferredIndex = (messages.lastIndex - 1).takeIf { it >= 0 }
            ?.downTo(0)
            ?.firstOrNull { messages[it].hasCacheableContent() }

        val fallbackIndex = messages.indices.lastOrNull { messages[it].hasCacheableContent() }
        val messageIndex = preferredIndex ?: fallbackIndex ?: return messages
        val targetMessage = messages[messageIndex]
        val contentBlocks = targetMessage.readContentBlocks() ?: return messages
        val cachedBlocks = contentBlocks.withLastCacheControl()

        return messages.mapIndexed { index, message ->
            if (index == messageIndex) {
                message + ("content" to cachedBlocks)
            } else {
                message
            }
        }
    }

    private fun buildAssistantMessage(
        msg: GigaRequest.Message,
        lastToolCallIds: MutableMap<String, String>,
    ): Map<String, Any> {
        val toolUseId = msg.functionsStateId
        if (toolUseId != null) {
            parseAssistantToolCall(msg.content)?.let { toolCall ->
                lastToolCallIds[toolCall.name] = toolUseId
                return mapOf(
                    "role" to "assistant",
                    "content" to listOf(
                        mapOf(
                            "type" to "tool_use",
                            "id" to toolUseId,
                            "name" to toolCall.name,
                            "input" to toolCall.arguments,
                        ),
                    ),
                )
            }
        }

        return mapOf(
            "role" to "assistant",
            "content" to listOf(
                mapOf(
                    "type" to "text",
                    "text" to msg.content,
                ),
            ),
        )
    }

    private fun buildFunctionResultMessage(
        msg: GigaRequest.Message,
        lastToolCallIds: Map<String, String>,
    ): Map<String, Any> {
        val toolUseId = msg.functionsStateId ?: msg.name?.let { lastToolCallIds[it] }
        if (toolUseId != null) {
            val content: Any = if (msg.attachments.isNullOrEmpty()) {
                msg.content.ifBlank { "{}" }
            } else {
                buildTextAndAttachments(msg.content, msg.attachments)
            }

            return mapOf(
                "role" to "user",
                "content" to listOf(
                    mapOf(
                        "type" to "tool_result",
                        "tool_use_id" to toolUseId,
                        "content" to content,
                    ),
                ),
            )
        }

        return mapOf(
            "role" to "user",
            "content" to buildTextAndAttachments(msg.content, msg.attachments),
        )
    }

    private fun buildTextAndAttachments(
        text: String,
        attachments: List<String>?,
    ): List<Map<String, Any>> {
        val content = mutableListOf<Map<String, Any>>()
        if (text.isNotBlank()) {
            content += mapOf("type" to "text", "text" to text)
        }

        attachments?.forEach { fileId ->
            content += mapOf(
                "type" to attachmentBlockType(fileId),
                "source" to mapOf(
                    "type" to "file",
                    "file_id" to fileId,
                ),
            )
        }

        if (content.isEmpty()) {
            content += mapOf("type" to "text", "text" to "")
        }

        return content
    }

    private fun attachmentBlockType(fileId: String): String {
        val mime = fileTypes[fileId].orEmpty()
        return if (mime.startsWith("image/", ignoreCase = true)) "image" else "document"
    }

    private fun parseAssistantToolCall(content: String): ParsedToolCall? {
        return runCatching {
            val contentNode = gigaJsonMapper.readTree(content)
            val name = contentNode["name"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
            val argsNode = contentNode["arguments"]
            val args = if (argsNode == null || argsNode.isNull) {
                emptyMap()
            } else {
                parseFunctionArguments(argsNode.toString())
            }
            ParsedToolCall(name = name, arguments = args)
        }.getOrNull()
    }

    private fun buildTools(functions: List<GigaRequest.Function>): List<Map<String, Any>> {
        return functions.map { fn ->
            val properties = fn.parameters.properties.mapValues { (_, prop) ->
                buildMap {
                    put("type", prop.type)
                    prop.description?.let { put("description", it) }
                    prop.enum?.let { put("enum", it) }
                }
            }

            val inputSchema = mutableMapOf<String, Any>(
                "type" to fn.parameters.type,
                "properties" to properties,
            )
            if (fn.parameters.required.isNotEmpty()) {
                inputSchema["required"] = fn.parameters.required
            }

            mapOf(
                "name" to fn.name,
                "description" to fn.description,
                "input_schema" to inputSchema,
            )
        }
    }

    private fun parseMessageResponse(text: String, requestModel: String): GigaResponse.Chat {
        val node = gigaJsonMapper.readTree(text)
        val choices = parseChoices(node["content"])
        val finishReason = node["stop_reason"]?.asText().toAnthropicFinishReason()
        if (finishReason != null && choices.isNotEmpty()) {
            val last = choices.last()
            if (last.finishReason == null) {
                choices[choices.lastIndex] = last.copy(finishReason = finishReason)
            }
        }

        if (choices.isEmpty()) {
            choices += GigaResponse.Choice(
                message = GigaResponse.Message(
                    content = "",
                    role = GigaMessageRole.assistant,
                    functionCall = null,
                    functionsStateId = null,
                ),
                index = 0,
                finishReason = finishReason,
            )
        }

        return GigaResponse.Chat.Ok(
            choices = choices,
            created = System.currentTimeMillis() / 1000,
            model = node["model"]?.asText() ?: requestModel,
            usage = parseUsage(node["usage"]),
        )
    }

    private fun parseChoices(contentNode: JsonNode?): MutableList<GigaResponse.Choice> {
        if (contentNode == null || !contentNode.isArray) {
            return mutableListOf()
        }

        val choices = mutableListOf<GigaResponse.Choice>()
        contentNode.forEachIndexed { index, block ->
            when (block["type"]?.asText()) {
                "text" -> {
                    val text = block["text"]?.asText().orEmpty()
                    choices += GigaResponse.Choice(
                        message = GigaResponse.Message(
                            content = text,
                            role = GigaMessageRole.assistant,
                            functionCall = null,
                            functionsStateId = null,
                        ),
                        index = index,
                        finishReason = null,
                    )
                }

                "tool_use" -> {
                    val name = block["name"]?.asText().orEmpty()
                    val functionsStateId = block["id"]?.asText()
                    val args = parseFunctionArguments(block["input"]?.toString() ?: "{}")
                    choices += GigaResponse.Choice(
                        message = GigaResponse.Message(
                            content = "",
                            role = GigaMessageRole.assistant,
                            functionCall = GigaResponse.FunctionCall(name, args),
                            functionsStateId = functionsStateId,
                        ),
                        index = index,
                        finishReason = GigaResponse.FinishReason.function_call,
                    )
                }
            }
        }
        return choices
    }

    private fun parseUsage(node: JsonNode?): GigaResponse.Usage {
        val promptTokens = (node?.get("input_tokens")?.asInt() ?: 0) +
            (node?.get("cache_creation_input_tokens")?.asInt() ?: 0)
        val completionTokens = node?.get("output_tokens")?.asInt() ?: 0
        val precachedTokens = node?.get("cache_read_input_tokens")?.asInt() ?: 0
        return GigaResponse.Usage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            precachedTokens = precachedTokens,
        )
    }

    private fun parseFunctionArguments(argsText: String): Map<String, Any> {
        if (argsText.isBlank()) return emptyMap()
        return runCatching { gigaJsonMapper.readValue<Map<String, Any>>(argsText) }
            .getOrElse {
                l.warn("Failed to parse Anthropic tool arguments: $argsText", it)
                emptyMap()
            }
    }

    private fun resolveChatModel(model: String): String {
        findAnthropicModelAlias(model)?.let { return it }

        val settingsModel = settingsProvider.gigaModel
        if (settingsModel.provider == LlmProvider.ANTHROPIC) {
            return settingsModel.alias
        }

        if (defaultChatModel.startsWith("claude", ignoreCase = true)) {
            return defaultChatModel
        }

        return DEFAULT_ANTHROPIC_MODEL
    }

    private fun findAnthropicModelAlias(value: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) return null
        if (normalized.startsWith("claude", ignoreCase = true)) {
            return normalized
        }
        val model = GigaModel.entries.firstOrNull {
            it.alias.equals(normalized, ignoreCase = true) || it.name.equals(normalized, ignoreCase = true)
        } ?: return null
        if (model.provider == LlmProvider.ANTHROPIC) {
            return model.alias
        }
        return null
    }

    private fun toTextChunk(text: String, model: String, index: Int): GigaResponse.Chat.Ok {
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
    ): GigaResponse.Chat.Ok {
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

    private fun toFinishChunk(
        finishReason: GigaResponse.FinishReason,
        model: String,
    ): GigaResponse.Chat.Ok {
        val choice = GigaResponse.Choice(
            message = GigaResponse.Message(
                content = "",
                role = GigaMessageRole.assistant,
                functionCall = null,
                functionsStateId = null,
            ),
            index = 0,
            finishReason = finishReason,
        )
        return GigaResponse.Chat.Ok(
            choices = listOf(choice),
            created = System.currentTimeMillis() / 1000,
            model = model,
            usage = GigaResponse.Usage(0, 0, 0, 0),
        )
    }

    companion object {
        private const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        private const val FILES_URL = "https://api.anthropic.com/v1/files"
        private const val FILES_API_BETA = "files-api-2025-04-14"
    }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.readContentBlocks(): List<Map<String, Any>>? {
    val rawBlocks = this["content"] as? List<*> ?: return null
    val blocks = rawBlocks.mapNotNull { it as? Map<String, Any> }
    return blocks.takeIf { it.size == rawBlocks.size }
}

private fun Map<String, Any>.hasCacheableContent(): Boolean =
    readContentBlocks()?.any { it.isCacheableBlock() } ?: false

private fun List<Map<String, Any>>.withLastToolCacheControl(): List<Map<String, Any>> {
    if (isEmpty()) return this
    val targetIndex = this.lastIndex
    return mapIndexed { index, tool ->
        if (index == targetIndex) {
            tool + ("cache_control" to EPHEMERAL_CACHE_CONTROL)
        } else {
            tool
        }
    }
}

private fun List<Map<String, Any>>.withLastCacheControl(): List<Map<String, Any>> {
    val index = indexOfLast { it.isCacheableBlock() }
    if (index < 0) return this
    return mapIndexed { i, block ->
        if (i == index) {
            block + ("cache_control" to EPHEMERAL_CACHE_CONTROL)
        } else {
            block
        }
    }
}

private fun Map<String, Any>.isCacheableBlock(): Boolean {
    val type = this["type"] as? String ?: return false
    return when (type) {
        "text" -> (this["text"] as? String)?.isNotBlank() == true
        else -> true
    }
}

private fun String?.toAnthropicFinishReason(): GigaResponse.FinishReason? {
    if (this == null || this.equals("null", ignoreCase = true) || this.isBlank()) {
        return null
    }
    return when (this) {
        "tool_use" -> GigaResponse.FinishReason.function_call
        "max_tokens" -> GigaResponse.FinishReason.length
        "end_turn", "stop_sequence" -> GigaResponse.FinishReason.stop
        else -> this.toFinishReason()
    }
}

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val api: AnthropicChatAPI by di.instance()

    val request = GigaRequest.Chat(
        model = System.getenv("ANTHROPIC_MODEL") ?: GigaModel.AnthropicHaiku45.alias,
        temperature = 0f,
        maxTokens = 128,
        messages = listOf(
            "You are a concise assistant.".toSystemPromptMessage(),
            GigaRequest.Message(
                role = GigaMessageRole.user,
                content = "Reply with exactly: ANTHROPIC_OK",
            ),
        ),
    )

    println(api.message(request))
}
