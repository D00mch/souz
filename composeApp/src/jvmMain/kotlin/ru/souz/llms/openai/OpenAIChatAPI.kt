package ru.souz.llms.openai

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
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
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.di.mainDiModule
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.TokenLogging
import ru.souz.llms.restJsonMapper
import ru.souz.llms.toFinishReason
import ru.souz.llms.giga.toGiga
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.files.ToolListFiles
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.get
import kotlin.time.measureTime

class OpenAIChatAPI(
    private val settingsProvider: SettingsProvider,
    private val tokenLogging: TokenLogging,
) : LLMChatAPI {
    private val l = LoggerFactory.getLogger(OpenAIChatAPI::class.java)

    private val apiKey: String
        get() = settingsProvider.openaiKey
            ?: System.getenv("OPENAI_API_KEY")
            ?: System.getProperty("OPENAI_API_KEY")
            ?: throw IllegalStateException("OPENAI_API_KEY is not set")

    private val defaultChatModel: String
        get() = System.getenv("OPENAI_MODEL")
            ?: System.getProperty("OPENAI_MODEL")
            ?: "gpt-5-nano"

    private val defaultEmbeddingsModel: String
        get() = System.getenv("OPENAI_EMBEDDINGS_MODEL")
            ?: System.getProperty("OPENAI_EMBEDDINGS_MODEL")
            ?: "text-embedding-3-small"

    private val client = HttpClient(CIO) {
        defaultRequest {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        install(HttpTimeout) {
            requestTimeoutMillis = settingsProvider.requestTimeoutMillis
        }
        install(ContentNegotiation) {
            jackson {
                disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            }
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    l.debug(message)
                }
            }
            level = LogLevel.INFO
            sanitizeHeader { it.equals(HttpHeaders.Authorization, true) }
        }
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = try {
        val response = client.post(CHAT_COMPLETIONS_URL) {
            setBody(buildChatRequest(body, stream = false))
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            parseCompletionsResponse(text, body.model).also { result ->
                if (result is LLMResponse.Chat.Ok) {
                    l.info("Model: ${body.model}. Response received")
                    tokenLogging.logTokenUsage(result, body)
                }
            }
        } else {
            LLMResponse.Chat.Error(response.status.value, text)
        }
    } catch (e: ClientRequestException) {
        val text = e.response.bodyAsText()
        LLMResponse.Chat.Error(e.response.status.value, text)
    } catch (t: Throwable) {
        l.error("Model: ${body.model}. Error in chat", t)
        LLMResponse.Chat.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = channelFlow {
        try {
            val accumulator = OpenAiStreamAccumulator()

            client.preparePost(CHAT_COMPLETIONS_URL) {
                setBody(buildChatRequest(body, stream = true))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val text = response.bodyAsText()
                    send(LLMResponse.Chat.Error(response.status.value, text))
                    return@execute
                }

                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ")
                        if (data == "[DONE]") break

                        try {
                            val chunkNode: JsonNode = restJsonMapper.readTree(data)
                            val model = chunkNode["model"]?.asText() ?: body.model
                            val created = chunkNode["created"]?.asLong() ?: (System.currentTimeMillis() / 1000)

                            val chunks = accumulator.processChunk(chunkNode)

                            if (chunks.isNotEmpty()) {
                                // Usage is typically null in chunks until the end, or never sent
                                val usage = parseUsage(chunkNode["usage"])
                                val chunks = prepareChoices(chunks)
                                send(LLMResponse.Chat.Ok(chunks, created, model, usage))
                            }
                        } catch (e: Exception) {
                            l.warn("Model: ${body.model}. Failed to parse stream chunk: $data", e)
                        }
                    }
                }
            }
        } catch (e: ClientRequestException) {
            val text = e.response.bodyAsText()
            send(LLMResponse.Chat.Error(e.response.status.value, text))
        } catch (t: Throwable) {
            l.error("Model: ${body.model}. Error in OpenAI stream chat", t)
            send(LLMResponse.Chat.Error(-1, "Connection error: ${t.message}"))
        }
    }

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings = try {
        val response = client.post(EMBEDDINGS_URL) {
            setBody(buildEmbeddingsRequest(body))
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            parseEmbeddingsResponse(text)
        } else {
            LLMResponse.Embeddings.Error(response.status.value, text)
        }
    } catch (e: ClientRequestException) {
        val text = e.response.bodyAsText()
        LLMResponse.Embeddings.Error(e.response.status.value, text)
    } catch (t: Throwable) {
        l.error("Model: ${body.model}. Error in OpenAI embeddings", t)
        LLMResponse.Embeddings.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile {
        throw UnsupportedOperationException("OpenAI file upload is not supported in this implementation")
    }

    override suspend fun downloadFile(fileId: String): String? {
        return null
    }

    override suspend fun balance(): LLMResponse.Balance {
        return LLMResponse.Balance.Error(-1, "Balance check not implemented for OpenAI")
    }

    private fun buildChatRequest(body: LLMRequest.Chat, stream: Boolean): Map<String, Any> {
        val tools = buildTools(body.functions)
        return buildMap {
            val model = resolveChatModel(body.model)
            put("model", model)
            put("messages", buildMessages(body.messages))
            put("stream", stream)

            // thinking models doesn't support temperature
            // body.temperature?.let { put("temperature", it) }
            if (body.maxTokens > 0) {
                put("max_completion_tokens", body.maxTokens)
            }
            if (tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }
    }

    private fun buildEmbeddingsRequest(body: LLMRequest.Embeddings): Map<String, Any> = buildMap {
        put("model", resolveEmbeddingsModel(body.model))
        if (body.input.size == 1) {
            put("input", body.input.first())
        } else {
            put("input", body.input)
        }
        put("encoding_format", "float")
    }

    private fun buildMessages(messages: List<LLMRequest.Message>): List<Map<String, Any?>> {
        val pendingToolCallIdsByName = mutableMapOf<String, ArrayDeque<String>>()
        val remainingToolResultIds = mutableMapOf<String, Int>()
        val remainingToolResultNames = mutableMapOf<String, Int>()
        val result = mutableListOf<MutableMap<String, Any?>>()
        val delayed = mutableListOf<MutableMap<String, Any?>>()
        val pendingToolCallIds = linkedSetOf<String>()
        var currentToolCallAssistant: MutableMap<String, Any?>? = null

        messages.forEach { msg ->
            if (msg.role != LLMMessageRole.function) return@forEach
            when {
                !msg.functionsStateId.isNullOrBlank() -> {
                    val id = msg.functionsStateId ?: return@forEach
                    remainingToolResultIds[id] = (remainingToolResultIds[id] ?: 0) + 1
                }
                !msg.name.isNullOrBlank() -> {
                    val name = msg.name ?: return@forEach
                    remainingToolResultNames[name] = (remainingToolResultNames[name] ?: 0) + 1
                }
            }
        }

        fun emit(message: MutableMap<String, Any?>) {
            if (pendingToolCallIds.isEmpty()) {
                result.add(message)
            } else {
                delayed.add(message)
            }
        }

        fun flushDelayedIfPossible() {
            if (pendingToolCallIds.isNotEmpty() || delayed.isEmpty()) return
            result.addAll(delayed)
            delayed.clear()
        }

        fun parseAssistantToolCall(msg: LLMRequest.Message): Map<String, Any?>? {
            val functionsStateId = msg.functionsStateId ?: return null
            return try {
                val contentJson = restJsonMapper.readTree(msg.content)
                val name = contentJson["name"]?.asText()
                val argumentsNode = contentJson["arguments"]
                if (name != null && argumentsNode != null) {
                    val idBudget = remainingToolResultIds[functionsStateId] ?: 0
                    val nameBudget = remainingToolResultNames[name] ?: 0
                    if (idBudget <= 0 && nameBudget <= 0) {
                        return null
                    }
                    if (idBudget > 0) {
                        remainingToolResultIds[functionsStateId] = idBudget - 1
                    } else {
                        remainingToolResultNames[name] = nameBudget - 1
                    }
                    val arguments = restJsonMapper.writeValueAsString(argumentsNode)
                    pendingToolCallIdsByName.getOrPut(name) { ArrayDeque() }.addLast(functionsStateId)
                    buildMap {
                        put("id", functionsStateId)
                        put("type", "function")
                        put("function", buildMap {
                            put("name", name)
                            put("arguments", arguments)
                        })
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                l.warn(
                    "Failed to parse tool call content for OpenAI: ${msg.content}. " +
                            "Falling back to standard content.",
                    e,
                )
                null
            }
        }

        messages.forEach { msg ->
            when (msg.role) {
                LLMMessageRole.function -> {
                    val toolCallId = msg.functionsStateId ?: msg.name?.let { name ->
                        pendingToolCallIdsByName[name]?.removeFirstOrNull()
                    }
                    if (toolCallId != null) {
                        result.add(
                            mutableMapOf(
                                "role" to "tool",
                                "content" to msg.content,
                                "tool_call_id" to toolCallId,
                            )
                        )
                        val resolvedPendingCall = pendingToolCallIds.remove(toolCallId)
                        if (resolvedPendingCall && pendingToolCallIds.isEmpty()) {
                            currentToolCallAssistant = null
                            flushDelayedIfPossible()
                        }
                    } else {
                        emit(
                            mutableMapOf<String, Any?>(
                                "role" to "function",
                                "content" to msg.content,
                            ).apply {
                                msg.name?.let { put("name", it) }
                            }
                        )
                    }
                }

                LLMMessageRole.assistant -> {
                    val toolCall = parseAssistantToolCall(msg)
                    if (toolCall != null) {
                        val assistantMessage = currentToolCallAssistant?.takeIf { pendingToolCallIds.isNotEmpty() }
                            ?: mutableMapOf<String, Any?>(
                                "role" to "assistant",
                                "content" to null,
                                "tool_calls" to mutableListOf<Map<String, Any?>>(),
                            ).also {
                                result.add(it)
                                currentToolCallAssistant = it
                            }
                        @Suppress("UNCHECKED_CAST")
                        val toolCalls = assistantMessage["tool_calls"] as MutableList<Map<String, Any?>>
                        toolCalls.add(toolCall)
                        pendingToolCallIds.add(toolCall["id"].toString())
                    } else {
                        val normalizedContent = msg.content.toNormalizedAssistantContent()
                        if (normalizedContent == null && msg.name == null) {
                            return@forEach
                        }
                        emit(
                            mutableMapOf<String, Any?>(
                                "role" to msg.role.name,
                                "content" to normalizedContent,
                            ).apply {
                                msg.name?.let { put("name", it) }
                            }
                        )
                    }
                }

                else -> emit(
                    mutableMapOf<String, Any?>(
                        "role" to msg.role.name,
                        "content" to msg.content,
                    ).apply {
                        msg.name?.let { put("name", it) }
                    }
                )
            }
        }

        if (pendingToolCallIds.isNotEmpty()) {
            l.warn(
                "OpenAI payload has unresolved tool calls {}. Delaying {} messages until resolved.",
                pendingToolCallIds,
                delayed.size,
            )
            val normalized = normalizeUnresolvedToolCalls(result, pendingToolCallIds)
            if (delayed.isNotEmpty()) {
                normalized.addAll(delayed)
            }
            return normalized
        }

        flushDelayedIfPossible()
        return result
    }

    private fun buildTools(functions: List<LLMRequest.Function>): List<Map<String, Any>> {
        return functions.map { fn ->
            val properties = fn.parameters.properties.mapValues { (_, prop) ->
                buildMap {
                    put("type", prop.type)
                    prop.description?.let { put("description", it) }
                    prop.enum?.let { put("enum", it) }
                    if (prop.type == "array") {
                        // OpenAI tool schemas require `items` for every array type.
                        // Keep items unconstrained so existing tools can pass arrays of strings, objects, or mixed payloads.
                        put("items", emptyMap<String, Any>())
                    }
                }
            }
            buildMap {
                put("type", "function")
                put(
                    "function",
                    buildMap {
                        put("name", fn.name)
                        put("description", fn.description)
                        put(
                            "parameters",
                            buildMap {
                                put("type", fn.parameters.type)
                                put("properties", properties)
                                if (fn.parameters.required.isNotEmpty()) {
                                    put("required", fn.parameters.required)
                                }
                            }
                        )
                    }
                )
            }
        }
    }

    private fun parseCompletionsResponse(text: String, requestModel: String): LLMResponse.Chat {
        val node = restJsonMapper.readTree(text)
        val choices = prepareChoices(parseChoices(node["choices"], isStream = false))
        val usage = parseUsage(node["usage"])
        return LLMResponse.Chat.Ok(
            choices = choices,
            created = node["created"]?.asLong() ?: (System.currentTimeMillis() / 1000),
            model = node["model"]?.asText() ?: requestModel,
            usage = usage,
        )
    }

    private fun prepareChoices(chunks: List<LLMResponse.Choice>): List<LLMResponse.Choice> = chunks.map { msg ->
        when (msg.message.content) {
            "null" -> msg.copy(message = msg.message.copy(content = ""))
            else -> msg
        }
    }

    private fun parseChoices(
        choicesNode: JsonNode?,
        isStream: Boolean
    ): List<LLMResponse.Choice> {
        if (choicesNode == null || !choicesNode.isArray) {
            return emptyList()
        }

        val choices = mutableListOf<LLMResponse.Choice>()
        choicesNode.forEachIndexed { idx, choiceNode ->
            val messageField = if (isStream) "delta" else "message"
            val messageNode = choiceNode[messageField]

            val messageContent = messageNode?.get("content").toOpenAiMessageContent()
            val role = messageNode?.get("role")?.asText().toOpenAiRole()
            val finishReason = choiceNode["finish_reason"]?.asText().toOpenAiFinishReasonValue()
            val choiceIndex = choiceNode["index"]?.asInt() ?: idx
            val toolCallsNode = messageNode?.get("tool_calls")

            // Non-stream or complete parsing
            if (toolCallsNode != null && toolCallsNode.isArray && toolCallsNode.size() > 0) {
                toolCallsNode.forEach { toolCallNode ->
                    val functionNode = toolCallNode["function"]
                    val name = functionNode?.get("name")?.asText().orEmpty()
                    val argsText = functionNode?.get("arguments")?.asText() ?: ""
                    val args = if (argsText.isNotEmpty()) parseFunctionArguments(argsText) else emptyMap()

                    val functionsStateId = toolCallNode["id"]?.asText()
                    val toolIndex = toolCallNode["index"]?.asInt() ?: choiceIndex

                    choices += LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = "",
                            role = role,
                            functionCall = LLMResponse.FunctionCall(name, args),
                            functionsStateId = functionsStateId,
                        ),
                        index = toolIndex,
                        finishReason = LLMResponse.FinishReason.function_call,
                    )
                }
            }

            if (messageContent.isNotEmpty()) {
                choices += LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = messageContent,
                        role = role,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = choiceIndex,
                    finishReason = if (toolCallsNode != null && toolCallsNode.size() > 0) null else finishReason,
                )
            } else if (toolCallsNode == null || toolCallsNode.size() == 0) {
                choices += LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = "",
                        role = role,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = choiceIndex,
                    finishReason = finishReason,
                )
            }
        }

        return choices
    }

    private fun parseUsage(node: JsonNode?): LLMResponse.Usage {
        val prompt = node?.get("prompt_tokens")?.asInt() ?: 0
        val completion = node?.get("completion_tokens")?.asInt() ?: 0
        val total = node?.get("total_tokens")?.asInt() ?: (prompt + completion)
        return LLMResponse.Usage(prompt, completion, total, 0)
    }

    private fun parseEmbeddingsResponse(text: String): LLMResponse.Embeddings {
        val node = restJsonMapper.readTree(text)
        val data = node["data"]?.mapIndexed { index, item ->
            LLMResponse.Embedding(
                embedding = item["embedding"]?.map { it.asDouble() } ?: emptyList(),
                index = item["index"]?.asInt() ?: index,
                objectType = item["object"]?.asText(),
            )
        } ?: emptyList()

        return LLMResponse.Embeddings.Ok(
            data = data,
            model = node["model"]?.asText() ?: "",
            objectType = node["object"]?.asText() ?: "list",
        )
    }

    private fun parseFunctionArguments(argsText: String): Map<String, Any> {
        if (argsText.isBlank()) return emptyMap()
        return runCatching { restJsonMapper.readValue<Map<String, Any>>(argsText) }
            .getOrElse {
                l.warn("Failed to parse OpenAI tool arguments: $argsText")
                emptyMap()
            }
    }

    private fun normalizeUnresolvedToolCalls(
        messages: List<MutableMap<String, Any?>>,
        unresolvedToolCallIds: Set<String>,
    ): MutableList<MutableMap<String, Any?>> {
        if (unresolvedToolCallIds.isEmpty()) return messages.toMutableList()

        val normalized = mutableListOf<MutableMap<String, Any?>>()
        messages.forEach { message ->
            val role = message["role"] as? String
            @Suppress("UNCHECKED_CAST")
            val toolCalls = message["tool_calls"] as? List<Map<String, Any?>>

            if (role != "assistant" || toolCalls.isNullOrEmpty()) {
                normalized.add(message)
                return@forEach
            }

            val resolvedCalls = mutableListOf<Map<String, Any?>>()
            val unresolvedCalls = mutableListOf<Map<String, Any?>>()
            toolCalls.forEach { toolCall ->
                val id = toolCall["id"]?.toString()
                if (id != null && id in unresolvedToolCallIds) {
                    unresolvedCalls.add(toolCall)
                } else {
                    resolvedCalls.add(toolCall)
                }
            }

            if (resolvedCalls.isNotEmpty()) {
                val resolvedMessage = message.toMutableMap().apply {
                    put("tool_calls", resolvedCalls)
                }
                normalized.add(resolvedMessage)
            }

            if (unresolvedCalls.isNotEmpty()) {
                val fallbackContent = unresolvedCalls.joinToString("\n") { toolCall ->
                    unresolvedToolCallToAssistantContent(toolCall)
                }
                if (fallbackContent.isNotBlank()) {
                    normalized.add(
                        mutableMapOf(
                            "role" to "assistant",
                            "content" to fallbackContent,
                        )
                    )
                }
            }
        }

        return normalized
    }

    private fun unresolvedToolCallToAssistantContent(toolCall: Map<String, Any?>): String {
        val function = toolCall["function"] as? Map<*, *> ?: return ""
        val name = function["name"]?.toString().orEmpty()
        val rawArguments = function["arguments"]?.toString().orEmpty()
        val parsedArguments: Any = runCatching {
            restJsonMapper.readTree(rawArguments)
        }.getOrElse {
            if (rawArguments.isBlank()) emptyMap<String, Any>() else rawArguments
        }
        return runCatching {
            restJsonMapper.writeValueAsString(
                mapOf(
                    "name" to name,
                    "arguments" to parsedArguments,
                )
            )
        }.getOrDefault("")
    }


    private fun resolveChatModel(model: String): String {
        findOpenAiModelAlias(model)?.let { return it }

        val settingsModel = settingsProvider.gigaModel
        if (settingsModel.provider == LlmProvider.OPENAI) {
            return settingsModel.alias
        }

        if (defaultChatModel.startsWith("gpt-", ignoreCase = true)) {
            return defaultChatModel
        }

        return "gpt-5-nano"
    }

    private fun resolveEmbeddingsModel(model: String): String {
        if (model.equals("Embeddings", ignoreCase = true)) return defaultEmbeddingsModel
        return model
    }

    private fun findOpenAiModelAlias(value: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) return null
        if (normalized.startsWith("gpt-", ignoreCase = true)) {
            return normalized
        }
        val model = LLMModel.entries.firstOrNull {
            it.alias.equals(normalized, ignoreCase = true) || it.name.equals(normalized, ignoreCase = true)
        } ?: return null
        if (model.provider == LlmProvider.OPENAI) {
            return model.alias
        }
        return null
    }

    companion object {
        private const val BASE_URL = "https://api.openai.com/v1"
        private const val CHAT_COMPLETIONS_URL = "$BASE_URL/chat/completions"
        private const val EMBEDDINGS_URL = "$BASE_URL/embeddings"
    }
}

private fun String.toNormalizedAssistantContent(): String? {
    if (this.isBlank()) return null
    if (this.equals("null", ignoreCase = true)) return null
    return this
}

private fun JsonNode?.toOpenAiMessageContent(): String {
    if (this == null || this.isNull) return ""
    val text = this.asText()
    return if (text.equals("null", ignoreCase = true)) "" else text
}


private fun String?.toOpenAiFinishReasonValue(): LLMResponse.FinishReason? {
    if (this == null || this.equals("null", ignoreCase = true) || this.isBlank()) {
        return null
    }
    return when (this) {
        "tool_calls" -> LLMResponse.FinishReason.function_call
        "stop" -> LLMResponse.FinishReason.stop
        "length" -> LLMResponse.FinishReason.length
        else -> this.toFinishReason()
    }
}

private fun String?.toOpenAiRole(): LLMMessageRole {
    return runCatching { LLMMessageRole.valueOf(this ?: "") }
        .getOrDefault(LLMMessageRole.assistant)
}

// Helper class to buffer tool call arguments in streaming
private class OpenAiStreamAccumulator {
    private val choicesState = ConcurrentHashMap<Int, ChoiceState>()
    private val toolChoiceIndexByPair = mutableMapOf<Pair<Int, Int>, Int>()
    private var nextToolChoiceIndex = -1

    data class ChoiceState(
        var role: LLMMessageRole? = null,
        val content: StringBuilder = StringBuilder(),
        val toolCalls: MutableMap<Int, ToolCallState> = mutableMapOf(),
        var finishReason: LLMResponse.FinishReason? = null
    )

    data class ToolCallState(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder()
    )

    fun processChunk(chunkNode: JsonNode): List<LLMResponse.Choice> {
        val choicesNode = chunkNode["choices"] ?: return emptyList()
        if (!choicesNode.isArray) return emptyList()

        val resultChoices = mutableListOf<LLMResponse.Choice>()

        choicesNode.forEach { choiceNode ->
            val index = choiceNode["index"]?.asInt() ?: 0
            val state = choicesState.getOrPut(index) { ChoiceState() }

            val delta = choiceNode["delta"]
            val finishReasonText = choiceNode["finish_reason"]?.asText()
            val finishReason = finishReasonText.toOpenAiFinishReasonValue()

            // Update Role
            delta?.get("role")?.asText()?.let {
                if (it.isNotBlank()) state.role = LLMMessageRole.valueOf(it)
            }

            // Append Content
            val contentOrNull = delta?.get("content")?.asText()
            if (!contentOrNull.isNullOrEmpty()) {
                // Emit content immediately as stream
                resultChoices.add(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = contentOrNull,
                            role = state.role ?: LLMMessageRole.assistant,
                            functionCall = null,
                            functionsStateId = null
                        ),
                        index = index,
                        finishReason = null
                    )
                )
            }

            // Process Tool Calls
            val toolCallsNode = delta?.get("tool_calls")
            if (toolCallsNode != null && toolCallsNode.isArray) {
                toolCallsNode.forEach { tcNode ->
                    val tcIndex = tcNode["index"]?.asInt() ?: 0
                    val tcState = state.toolCalls.getOrPut(tcIndex) { ToolCallState() }

                    tcNode["id"]?.asText()?.let { tcState.id = it }
                    tcNode["function"]?.get("name")?.asText()?.let { tcState.name = it }
                    tcNode["function"]?.get("arguments")?.asText()?.let { tcState.arguments.append(it) }
                }
            }

            // Handle Finish Reason
            if (finishReason != null) {
                state.finishReason = finishReason
                // If finish reason implies tool calls, emit them now
                if (state.toolCalls.isNotEmpty()) {
                    state.toolCalls.forEach { (toolCallIndex, tcState) ->
                        val argsMap = parseFunctionArguments(tcState.arguments.toString())
                        // Emit the full tool call
                        if (tcState.name != null) {
                            val syntheticChoiceIndex = syntheticToolChoiceIndex(index, toolCallIndex)
                            resultChoices.add(
                                LLMResponse.Choice(
                                    message = LLMResponse.Message(
                                        content = "",
                                        role = state.role ?: LLMMessageRole.assistant,
                                        functionCall = LLMResponse.FunctionCall(
                                            name = tcState.name!!,
                                            arguments = argsMap
                                        ),
                                        functionsStateId = tcState.id
                                    ),
                                    index = syntheticChoiceIndex,
                                    finishReason = finishReason
                                )
                            )
                        }
                    }
                    // Clear tool calls after emitting to avoid duplicate emissions if multiple finish reasons (unlikely)
                    state.toolCalls.clear()
                } else if (finishReason != LLMResponse.FinishReason.function_call) {
                    // Signal end of stream
                    resultChoices.add(
                        LLMResponse.Choice(
                            message = LLMResponse.Message(
                                content = "",
                                role = state.role ?: LLMMessageRole.assistant,
                                functionCall = null,
                                functionsStateId = null
                            ),
                            index = index,
                            finishReason = finishReason
                        )
                    )
                }
            }
        }
        return resultChoices
    }

    private fun parseFunctionArguments(argsText: String): Map<String, Any> {
        if (argsText.isBlank()) return emptyMap()
        return runCatching { restJsonMapper.readValue<Map<String, Any>>(argsText) }
            .getOrElse {
                emptyMap()
            }
    }

    private fun syntheticToolChoiceIndex(choiceIndex: Int, toolCallIndex: Int): Int {
        val key = choiceIndex to toolCallIndex
        return toolChoiceIndexByPair.getOrPut(key) {
            nextToolChoiceIndex--
            nextToolChoiceIndex + 1
        }
    }
}


suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val filesToolUtil: FilesToolUtil by di.instance()
    val api: OpenAIChatAPI by di.instance()

    val model = System.getenv("OPENAI_MODEL")
        ?: System.getProperty("OPENAI_MODEL")
        ?: "gpt-5-nano"

    val request = LLMRequest.Chat(
        model = model,
        stream = true,
        messages = listOf(
            LLMRequest.Message(
                role = LLMMessageRole.system,
                content = "Ты помощник, который при необходимости вызывает функции. Отвечай кратко."
            ),
            LLMRequest.Message(
                role = LLMMessageRole.user,
                content = "Перечисли файлы в текущей папке.",
            ),
        ),
        functions = listOf(
            ToolListFiles(filesToolUtil).toGiga(),
        ).map { it.fn }
    )

    val allTime = measureTime {
        val result = api.messageStream(request)
        val millis = System.currentTimeMillis()
        var firstPrinted = false

        result.collect { response ->
            if (!firstPrinted) {
                println("First response in ${System.currentTimeMillis() - millis} ms")
                firstPrinted = true
            }
            when (response) {
                is LLMResponse.Chat.Ok -> {
                    response.choices.forEach { choice ->
                        val content = choice.message.content
                        if (content.isNotEmpty()) print(content)

                        if (choice.message.functionCall != null) {
                            println("\nFunction call: ${choice.message.functionCall}")
                        }
                    }
                }

                is LLMResponse.Chat.Error -> {
                    println("Error: ${response.message}")
                }
            }
        }
        println("\nDone.")
    }
    println("Total time: $allTime")
}
