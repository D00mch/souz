package ru.souz.llms.tunnel

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
import ru.souz.llms.giga.GigaChatAPI
import ru.souz.llms.GigaMessageRole
import ru.souz.llms.GigaRequest
import ru.souz.llms.GigaResponse
import ru.souz.llms.TokenLogging
import ru.souz.llms.giga.gigaJsonMapper
import ru.souz.llms.toFinishReason
import ru.souz.llms.giga.toGiga
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.files.ToolListFiles
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.measureTime

class AiTunnelChatAPI(
    private val settingsProvider: SettingsProvider,
    private val tokenLogging: TokenLogging,
) : GigaChatAPI {
    private val l = LoggerFactory.getLogger(AiTunnelChatAPI::class.java)

    private val apiKey: String
        get() = settingsProvider.aiTunnelKey
            ?: System.getenv("AITUNNEL_KEY")
            ?: System.getProperty("AITUNNEL_KEY")
            ?: throw IllegalStateException("AITUNNEL_KEY is not set")

    private val defaultChatModel: String
        get() = System.getenv("AITUNNEL_MODEL")
            ?: System.getProperty("AITUNNEL_MODEL")
            ?: "gpt-4o-mini"

    private val defaultEmbeddingsModel: String
        get() = System.getenv("AITUNNEL_EMBEDDINGS_MODEL")
            ?: System.getProperty("AITUNNEL_EMBEDDINGS_MODEL")
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

    override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat = try {
        val response = client.post(CHAT_COMPLETIONS_URL) {
            setBody(buildChatRequest(body, stream = false))
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            parseCompletionsResponse(text, body.model).also { result ->
                if (result is GigaResponse.Chat.Ok) {
                    l.info("Model: ${body.model}. Response received")
                    tokenLogging.logTokenUsage(result, body)
                }
            }
        } else {
            GigaResponse.Chat.Error(response.status.value, text)
        }
    } catch (e: ClientRequestException) {
        val text = e.response.bodyAsText()
        GigaResponse.Chat.Error(e.response.status.value, text)
    } catch (t: Throwable) {
        l.error("Model: ${body.model}. Error in chat", t)
        GigaResponse.Chat.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> = channelFlow {
        try {
            val accumulator = StreamAccumulator()

            client.preparePost(CHAT_COMPLETIONS_URL) {
                setBody(buildChatRequest(body, stream = true))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val text = response.bodyAsText()
                    send(GigaResponse.Chat.Error(response.status.value, text))
                    return@execute
                }

                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ")
                        if (data == "[DONE]") break

                        try {
                            val chunkNode: JsonNode = gigaJsonMapper.readTree(data)
                            val model = chunkNode["model"]?.asText() ?: body.model
                            val created = chunkNode["created"]?.asLong() ?: (System.currentTimeMillis() / 1000)

                            val chunks = accumulator.processChunk(chunkNode)

                            if (chunks.isNotEmpty()) {
                                // Usage is typically null in chunks until the end, or never sent
                                val usage = parseUsage(chunkNode["usage"])
                                val chunks = prepareChoices(chunks)
                                send(GigaResponse.Chat.Ok(chunks, created, model, usage))
                            }
                        } catch (e: Exception) {
                            l.warn("Model: ${body.model}. Failed to parse stream chunk: $data", e)
                        }
                    }
                }
            }
        } catch (e: ClientRequestException) {
            val text = e.response.bodyAsText()
            send(GigaResponse.Chat.Error(e.response.status.value, text))
        } catch (t: Throwable) {
            l.error("Model: ${body.model}. Error in AiTunnel stream chat", t)
            send(GigaResponse.Chat.Error(-1, "Connection error: ${t.message}"))
        }
    }

    override suspend fun embeddings(body: GigaRequest.Embeddings): GigaResponse.Embeddings = try {
        val response = client.post(EMBEDDINGS_URL) {
            setBody(buildEmbeddingsRequest(body))
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            parseEmbeddingsResponse(text)
        } else {
            GigaResponse.Embeddings.Error(response.status.value, text)
        }
    } catch (e: ClientRequestException) {
        val text = e.response.bodyAsText()
        GigaResponse.Embeddings.Error(e.response.status.value, text)
    } catch (t: Throwable) {
        l.error("Model: ${body.model}. Error in AiTunnel embeddings", t)
        GigaResponse.Embeddings.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun uploadFile(file: File): GigaResponse.UploadFile {
        throw UnsupportedOperationException("AiTunnel file upload is not supported in this implementation")
    }

    override suspend fun downloadFile(fileId: String): String? {
        return null
    }

    override suspend fun balance(): GigaResponse.Balance {
        return GigaResponse.Balance.Error(-1, "Balance check not implemented for AiTunnel")
    }

    private fun buildChatRequest(body: GigaRequest.Chat, stream: Boolean): Map<String, Any> {
        val tools = buildTools(body.functions)
        return buildMap {
            put("model", resolveChatModel(body.model))
            put("messages", buildMessages(body.messages))
            put("stream", stream)

            body.temperature?.let { put("temperature", it) }
            if (body.maxTokens > 0) {
                put("max_tokens", body.maxTokens)
            }
            if (tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }
    }

    private fun buildEmbeddingsRequest(body: GigaRequest.Embeddings): Map<String, Any> = buildMap {
        put("model", resolveEmbeddingsModel(body.model))
        if (body.input.size == 1) {
            put("input", body.input.first())
        } else {
            put("input", body.input)
        }
    }

    private fun buildMessages(messages: List<GigaRequest.Message>): List<Map<String, Any?>> {
        val lastToolCallIds = mutableMapOf<String, String>()

        return messages.map { msg ->
            when (msg.role) {
                GigaMessageRole.function -> {
                    // Try to resolve tool_call_id from history matching if not present
                    val toolCallId = msg.functionsStateId ?: msg.name?.let { lastToolCallIds[it] }

                    if (toolCallId != null) {
                        // OpenAI expects role 'tool' for tool results
                        buildMap {
                            put("role", "tool")
                            put("content", msg.content)
                            put("tool_call_id", toolCallId)
                        }
                    } else {
                        // Fallback to deprecated function role if no ID found
                        buildMap {
                            put("role", "function")
                            put("content", msg.content)
                            msg.name?.let { put("name", it) }
                        }
                    }
                }

                GigaMessageRole.assistant -> {
                    // Check if this is a tool call (GigaChat format: has functionsStateId + content JSON)
                    val functionsStateId = msg.functionsStateId
                    if (functionsStateId != null) {
                        try {
                            val contentJson = gigaJsonMapper.readTree(msg.content)
                            val name = contentJson["name"]?.asText()
                            val argumentsNode = contentJson["arguments"]

                            if (name != null && argumentsNode != null) {
                                val arguments = gigaJsonMapper.writeValueAsString(argumentsNode)
                                lastToolCallIds[name] = functionsStateId

                                return@map buildMap {
                                    put("role", "assistant")
                                    put("content", null)
                                    put("tool_calls", listOf(buildMap {
                                        put("id", functionsStateId)
                                        put("type", "function")
                                        put("function", buildMap {
                                            put("name", name)
                                            put("arguments", arguments)
                                        })
                                    }))
                                }
                            }
                        } catch (e: Exception) {
                            l.warn(
                                "Failed to parse tool call content for AiTunnel: " +
                                        "${msg.content}. Falling back to standard content.", e
                            )
                        }
                    }

                    // Regular assistant message
                    buildMap {
                        put("role", msg.role.name)
                        put("content", msg.content.ifBlank { null }) // OpenAI prefers null over empty string
                        msg.name?.let { put("name", it) }
                    }
                }

                else -> buildMap {
                    put("role", msg.role.name)
                    put("content", msg.content)
                    msg.name?.let { put("name", it) }
                }
            }
        }
    }

    private fun buildTools(functions: List<GigaRequest.Function>): List<Map<String, Any>> {
        return functions.map { fn ->
            val properties = fn.parameters.properties.mapValues { (_, prop) ->
                buildMap {
                    put("type", prop.type)
                    prop.description?.let { put("description", it) }
                    prop.enum?.let { put("enum", it) }
                    if (prop.type == "array") {
                        // AiTunnel routes OpenAI-compatible models that reject array schemas without items.
                        // Keep items unconstrained to preserve existing tools with mixed array payloads.
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

    private fun parseCompletionsResponse(text: String, requestModel: String): GigaResponse.Chat {
        val node = gigaJsonMapper.readTree(text)
        val choices = parseChoices(node["choices"], isStream = false)
        val usage = parseUsage(node["usage"])
        return GigaResponse.Chat.Ok(
            choices = choices,
            created = node["created"]?.asLong() ?: (System.currentTimeMillis() / 1000),
            model = node["model"]?.asText() ?: requestModel,
            usage = usage,
        )
    }

    private fun prepareChoices(chunks: List<GigaResponse.Choice>): List<GigaResponse.Choice> = chunks.map { msg ->
        when (msg.message.content) {
            "null" -> msg.copy(message = msg.message.copy(content = ""))
            else -> msg
        }
    }

    private fun parseChoices(
        choicesNode: JsonNode?,
        isStream: Boolean
    ): List<GigaResponse.Choice> {
        if (choicesNode == null || !choicesNode.isArray) {
            return emptyList()
        }

        val choices = mutableListOf<GigaResponse.Choice>()
        choicesNode.forEachIndexed { idx, choiceNode ->
            val messageField = if (isStream) "delta" else "message"
            val messageNode = choiceNode[messageField]

            val messageContent = messageNode?.get("content")?.asText().orEmpty()
            val role = messageNode?.get("role")?.asText().toGigaRole()
            val finishReason = choiceNode["finish_reason"]?.asText().toOpenAiFinishReason()
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

                    choices += GigaResponse.Choice(
                        message = GigaResponse.Message(
                            content = "",
                            role = role,
                            functionCall = GigaResponse.FunctionCall(name, args),
                            functionsStateId = functionsStateId,
                        ),
                        index = toolIndex,
                        finishReason = GigaResponse.FinishReason.function_call,
                    )
                }
            }

            if (messageContent.isNotEmpty()) {
                choices += GigaResponse.Choice(
                    message = GigaResponse.Message(
                        content = messageContent,
                        role = role,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = choiceIndex,
                    finishReason = if (toolCallsNode != null && toolCallsNode.size() > 0) null else finishReason,
                )
            } else if (toolCallsNode == null || toolCallsNode.size() == 0) {
                choices += GigaResponse.Choice(
                    message = GigaResponse.Message(
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

    private fun parseUsage(node: JsonNode?): GigaResponse.Usage {
        val prompt = node?.get("prompt_tokens")?.asInt() ?: 0
        val completion = node?.get("completion_tokens")?.asInt() ?: 0
        val total = node?.get("total_tokens")?.asInt() ?: (prompt + completion)
        return GigaResponse.Usage(prompt, completion, total, 0)
    }

    private fun parseEmbeddingsResponse(text: String): GigaResponse.Embeddings {
        val node = gigaJsonMapper.readTree(text)
        val data = node["data"]?.mapIndexed { index, item ->
            GigaResponse.Embedding(
                embedding = item["embedding"]?.map { it.asDouble() } ?: emptyList(),
                index = item["index"]?.asInt() ?: index,
                objectType = item["object"]?.asText(),
            )
        } ?: emptyList()

        return GigaResponse.Embeddings.Ok(
            data = data,
            model = node["model"]?.asText() ?: "",
            objectType = node["object"]?.asText() ?: "list",
        )
    }

    private fun parseFunctionArguments(argsText: String): Map<String, Any> {
        if (argsText.isBlank()) return emptyMap()
        return runCatching { gigaJsonMapper.readValue<Map<String, Any>>(argsText) }
            .getOrElse {
                l.warn("Failed to parse AiTunnel tool arguments: $argsText")
                emptyMap()
            }
    }


    private fun resolveChatModel(model: String): String {
        if (model.equals("ai-tunnel", ignoreCase = true) || model.startsWith("GigaChat", ignoreCase = true)) {
            return defaultChatModel
        }
        return model
    }

    private fun resolveEmbeddingsModel(model: String): String {
        if (model.equals("Embeddings", ignoreCase = true)) return defaultEmbeddingsModel
        return model
    }

    companion object {
        private const val BASE_URL = "https://api.aitunnel.ru/v1"
        private const val CHAT_COMPLETIONS_URL = "$BASE_URL/chat/completions"
        private const val EMBEDDINGS_URL = "$BASE_URL/embeddings"
    }
}


private fun String?.toOpenAiFinishReason(): GigaResponse.FinishReason? {
    if (this == null || this.equals("null", ignoreCase = true) || this.isBlank()) {
        return null
    }
    return when (this) {
        "tool_calls" -> GigaResponse.FinishReason.function_call
        "stop" -> GigaResponse.FinishReason.stop
        "length" -> GigaResponse.FinishReason.length
        else -> this.toFinishReason()
    }
}

private fun String?.toGigaRole(): GigaMessageRole {
    return runCatching { GigaMessageRole.valueOf(this ?: "") }
        .getOrDefault(GigaMessageRole.assistant)
}

// Helper class to buffer tool call arguments in streaming
private class StreamAccumulator {
    private val choicesState = ConcurrentHashMap<Int, ChoiceState>()

    data class ChoiceState(
        var role: GigaMessageRole? = null,
        val content: StringBuilder = StringBuilder(),
        val toolCalls: MutableMap<Int, ToolCallState> = mutableMapOf(),
        var finishReason: GigaResponse.FinishReason? = null
    )

    data class ToolCallState(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder()
    )

    fun processChunk(chunkNode: JsonNode): List<GigaResponse.Choice> {
        val choicesNode = chunkNode["choices"] ?: return emptyList()
        if (!choicesNode.isArray) return emptyList()

        val resultChoices = mutableListOf<GigaResponse.Choice>()

        choicesNode.forEach { choiceNode ->
            val index = choiceNode["index"]?.asInt() ?: 0
            val state = choicesState.getOrPut(index) { ChoiceState() }

            val delta = choiceNode["delta"]
            val finishReasonText = choiceNode["finish_reason"]?.asText()
            val finishReason = finishReasonText.toOpenAiFinishReason()

            // Update Role
            delta?.get("role")?.asText()?.let {
                if (it.isNotBlank()) state.role = GigaMessageRole.valueOf(it)
            }

            // Append Content
            val contentOrNull = delta?.get("content")?.asText()
            if (!contentOrNull.isNullOrEmpty()) {
                // Emit content immediately as stream
                resultChoices.add(
                    GigaResponse.Choice(
                        message = GigaResponse.Message(
                            content = contentOrNull,
                            role = state.role ?: GigaMessageRole.assistant,
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
                    state.toolCalls.forEach { (_, tcState) ->
                        val argsMap = parseFunctionArguments(tcState.arguments.toString())
                        // Emit the full tool call
                        if (tcState.name != null) {
                            resultChoices.add(
                                GigaResponse.Choice(
                                    message = GigaResponse.Message(
                                        content = "",
                                        role = state.role ?: GigaMessageRole.assistant,
                                        functionCall = GigaResponse.FunctionCall(
                                            name = tcState.name!!,
                                            arguments = argsMap
                                        ),
                                        functionsStateId = tcState.id
                                    ),
                                    index = index,
                                    finishReason = finishReason
                                )
                            )
                        }
                    }
                    // Clear tool calls after emitting to avoid duplicate emissions if multiple finish reasons (unlikely)
                    state.toolCalls.clear()
                } else if (finishReason != GigaResponse.FinishReason.function_call) {
                    // Signal end of stream
                    resultChoices.add(
                        GigaResponse.Choice(
                            message = GigaResponse.Message(
                                content = "",
                                role = state.role ?: GigaMessageRole.assistant,
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
        return runCatching { gigaJsonMapper.readValue<Map<String, Any>>(argsText) }
            .getOrElse {
                emptyMap()
            }
    }
}


suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val filesToolUtil: FilesToolUtil by di.instance()
    val api: AiTunnelChatAPI by di.instance()

    val model = System.getenv("AITUNNEL_MODEL")
        ?: System.getProperty("AITUNNEL_MODEL")
        ?: "gpt-4o-mini"

    val request = GigaRequest.Chat(
        model = model,
        stream = true,
        messages = listOf(
            GigaRequest.Message(
                role = GigaMessageRole.system,
                content = "Ты помощник, который при необходимости вызывает функции. Отвечай кратко."
            ),
            GigaRequest.Message(
                role = GigaMessageRole.user,
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
                is GigaResponse.Chat.Ok -> {
                    response.choices.forEach { choice ->
                        val content = choice.message.content
                        if (content.isNotEmpty()) print(content)

                        if (choice.message.functionCall != null) {
                            println("\nFunction call: ${choice.message.functionCall}")
                        }
                    }
                }

                is GigaResponse.Chat.Error -> {
                    println("Error: ${response.message}")
                }
            }
        }
        println("\nDone.")
    }
    println("Total time: $allTime")
}
