package ru.gigadesk.llms

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
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.GigaChatAPI
import ru.gigadesk.giga.GigaMessageRole
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.TokenLogging
import ru.gigadesk.giga.toFinishReason
import ru.gigadesk.giga.toGiga
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.giga.gigaJsonMapper
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.tool.files.ToolListFiles
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class QwenChatAPI(
    private val settingsProvider: SettingsProvider,
    private val tokenLogging: TokenLogging,
) : GigaChatAPI {
    private val l = LoggerFactory.getLogger(QwenChatAPI::class.java)

    private val apiKey: String
        get() = settingsProvider.qwenChatKey
            ?: System.getenv("QWEN_KEY")
            ?: System.getProperty("QWEN_KEY")
            ?: throw IllegalStateException("QWEN_KEY is not set")

    private val defaultChatModel: String
        get() = System.getenv("QWEN_MODEL")
            ?: System.getProperty("QWEN_MODEL")
            ?: "qwen-flash"

    private val defaultEmbeddingsModel: String
        get() = System.getenv("QWEN_EMBEDDINGS_MODEL")
            ?: System.getProperty("QWEN_EMBEDDINGS_MODEL")
            ?: "text-embedding-v3"

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
        install(SSE) {
            maxReconnectionAttempts = 0
            reconnectionTime = 3.seconds
        }
    }

    override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat = try {
        val body = body.rmFnIds()
        val response = client.post(CHAT_COMPLETIONS_URL) {
            setBody(buildChatRequest(body))
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            parseCompletionsResponse(text, resolveChatModel(body.model)).also { result ->
                if (result is GigaResponse.Chat.Ok) {
                    l.info("Chat response: ")
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
        l.error("Error in Qwen chat", t)
        GigaResponse.Chat.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> = channelFlow {
        val body = body.rmFnIds()
        try {
            val model = resolveChatModel(body.model)
            val streamAccumulator = QwenStreamAccumulator(
                parseFunctionArguments = ::parseFunctionArguments,
                finishReasonResolver = { it.toQwenFinishReason() },
                roleResolver = { it.toGigaRole() },
            )
            client.sse(
                urlString = GENERATION_SSE_URL,
                request = {
                    method = HttpMethod.Post
                    header("X-DashScope-SSE", "enable")
                    setBody(buildGenerationRequest(body))
                },
            ) {
                incoming.collect { event ->
                    val data = event.data ?: return@collect
                    if (data == "[DONE]" || !data.trimStart().startsWith("{")) {
                        return@collect
                    }
                    val chunk = runCatching { parseGenerationChunk(data, model, streamAccumulator) }
                        .getOrElse {
                            l.warn("Failed to parse Qwen stream chunk: $data", it)
                            return@collect
                        }
                    if (chunk.choices.isNotEmpty()) {
                        send(chunk)
                    }
                }
            }
        } catch (e: ClientRequestException) {
            val text = e.response.bodyAsText()
            send(GigaResponse.Chat.Error(e.response.status.value, text))
        } catch (t: Throwable) {
            l.error("Error in Qwen stream chat", t)
            send(GigaResponse.Chat.Error(-1, "Connection error: ${t.message}"))
        }
    }

    override suspend fun embeddings(body: GigaRequest.Embeddings): GigaResponse.Embeddings = try {
        val response = client.post(EMBEDDINGS_URL) {
            setBody(buildEmbeddingsRequest(body))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            GigaResponse.Embeddings.Error(response.status.value, text)
        } else {
            parseEmbeddingsResponse(text)
        }
    } catch (e: ClientRequestException) {
        val text = e.response.bodyAsText()
        GigaResponse.Embeddings.Error(e.response.status.value, text)
    } catch (t: Throwable) {
        l.error("Error in Qwen embeddings", t)
        GigaResponse.Embeddings.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun uploadFile(file: File): GigaResponse.UploadFile {
        throw UnsupportedOperationException("Qwen file upload is not supported")
    }

    override suspend fun downloadFile(fileId: String): String? {
        return null
    }

    override suspend fun balance(): GigaResponse.Balance {
        return GigaResponse.Balance.Error(-1, "Qwen doesn't have billing API")
    }

    private fun buildChatRequest(body: GigaRequest.Chat): Map<String, Any> {
        val tools = buildTools(body.functions)
        return buildMap {
            put("model", resolveChatModel(body.model))
            put("messages", buildMessages(body.messages))
            put("parallel_tool_calls", true)
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

    private fun buildGenerationRequest(body: GigaRequest.Chat): Map<String, Any> {
        val tools = buildTools(body.functions)
        val parameters = HashMap<String, Any>().apply {
            put("result_format", "message")
            put("incremental_output", true)
            put("stream", true)
            body.temperature?.let { put("temperature", it) }
            if (body.maxTokens > 0) {
                put("max_tokens", body.maxTokens)
            }
            if (tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
                put("parallel_tool_calls", true)
            }
        }
        return buildMap {
            put("model", resolveChatModel(body.model))
            put("input", mapOf("messages" to buildMessages(body.messages)))
            put("parameters", parameters)
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

    private fun buildMessages(messages: List<GigaRequest.Message>): List<Map<String, Any>> =
        messages.map { msg ->
            when (msg.role) {
                GigaMessageRole.function -> {
                    val role = if (msg.functionsStateId != null) "tool" else "function"
                    buildMap {
                        put("role", role)
                        put("content", msg.content)
                        msg.name?.let { put("name", it) }
                        msg.functionsStateId?.let { put("tool_call_id", it) }
                    }
                }

                else -> buildMap {
                    put("role", msg.role.name)
                    put("content", msg.content)
                    msg.name?.let { put("name", it) }
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

    private fun parseCompletionsResponse(text: String, model: String): GigaResponse.Chat {
        val node = gigaJsonMapper.readTree(text)
        val choices = parseChoices(node["choices"])
        val usage = parseUsage(node["usage"])
        return GigaResponse.Chat.Ok(
            choices = choices,
            created = node["created"]?.asLong() ?: (System.currentTimeMillis() / 1000),
            model = node["model"]?.asText() ?: model,
            usage = usage,
        )
    }

    private fun parseGenerationChunk(
        text: String,
        model: String,
        streamAccumulator: QwenStreamAccumulator,
    ): GigaResponse.Chat.Ok {
        val node: JsonNode = gigaJsonMapper.readTree(text)
        val choices = streamAccumulator.processChunk(node)
        val usage = parseUsage(node["usage"])
        return GigaResponse.Chat.Ok(
            choices = choices,
            created = System.currentTimeMillis() / 1000,
            model = model,
            usage = usage,
        )
    }

    private fun parseChoices(
        choicesNode: JsonNode?,
        nestedMessageField: String = "message",
    ): List<GigaResponse.Choice> {
        if (choicesNode == null || !choicesNode.isArray) {
            return emptyList()
        }

        val choices = mutableListOf<GigaResponse.Choice>()
        choicesNode.forEachIndexed { idx, choiceNode ->
            val messageNode = choiceNode[nestedMessageField]
            val messageContent = messageNode?.get("content")?.asText().orEmpty()
            val role = messageNode?.get("role")?.asText().toGigaRole()
            val finishReason = choiceNode["finish_reason"]?.asText().toQwenFinishReason()
            val choiceIndex = choiceNode["index"]?.asInt() ?: idx
            val toolCallsNode = messageNode?.get("tool_calls")

            if (toolCallsNode != null && toolCallsNode.isArray && toolCallsNode.size() > 0) {
                toolCallsNode.forEach { toolCallNode ->
                    val functionNode = toolCallNode["function"]
                    val name = functionNode?.get("name")?.asText().orEmpty()
                    val argsText = functionNode?.get("arguments")?.asText() ?: "{}"
                    val args = parseFunctionArguments(argsText)
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

            if (messageContent.isNotEmpty() || toolCallsNode == null || toolCallsNode.size() == 0) {
                choices += GigaResponse.Choice(
                    message = GigaResponse.Message(
                        content = messageContent,
                        role = role,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = choiceIndex,
                    finishReason = finishReason,
                )
            } else if (finishReason != null && choices.isNotEmpty()) {
                val last = choices.last()
                if (last.finishReason == null) {
                    choices[choices.lastIndex] = last.copy(finishReason = finishReason)
                }
            }
        }

        return choices
    }

    private fun parseUsage(node: JsonNode?): GigaResponse.Usage {
        val prompt = node?.get("prompt_tokens")?.asInt() ?: node?.get("input_tokens")?.asInt() ?: 0
        val completion = node?.get("completion_tokens")?.asInt() ?: node?.get("output_tokens")?.asInt() ?: 0
        val total = node?.get("total_tokens")?.asInt() ?: (prompt + completion)
        val cached = node?.get("prompt_tokens_details")?.get("cached_tokens")?.asInt() ?: 0
        return GigaResponse.Usage(prompt, completion, total, cached)
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
                l.warn("Failed to parse Qwen tool arguments: $argsText")
                mapOf("raw" to argsText)
            }
    }

    private fun String?.toQwenFinishReason(): GigaResponse.FinishReason? {
        if (this == null || this.equals("null", ignoreCase = true) || this.isBlank()) {
            return null
        }
        return when (this) {
            "tool_calls" -> GigaResponse.FinishReason.function_call
            "max_tokens" -> GigaResponse.FinishReason.length
            else -> this.toFinishReason()
        }
    }

    private fun String?.toGigaRole(): GigaMessageRole {
        return runCatching { GigaMessageRole.valueOf(this ?: "") }
            .getOrDefault(GigaMessageRole.assistant)
    }

    private fun resolveChatModel(model: String): String {
        if (model.startsWith("GigaChat", ignoreCase = true)) return defaultChatModel
        return model
    }

    private fun resolveEmbeddingsModel(model: String): String {
        if (model.equals("Embeddings", ignoreCase = true)) return defaultEmbeddingsModel
        return model
    }

    companion object {
        private const val CHAT_COMPLETIONS_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"
        private const val EMBEDDINGS_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/embeddings"
        private const val GENERATION_SSE_URL =
            "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/text-generation/generation"
    }

    private class QwenStreamAccumulator(
        private val parseFunctionArguments: (String) -> Map<String, Any>,
        private val finishReasonResolver: (String?) -> GigaResponse.FinishReason?,
        private val roleResolver: (String?) -> GigaMessageRole,
    ) {
        private val choicesState = ConcurrentHashMap<Int, ChoiceState>()

        data class ChoiceState(
            var role: GigaMessageRole = GigaMessageRole.assistant,
            val toolCalls: MutableMap<Int, ToolCallState> = mutableMapOf(),
        )

        data class ToolCallState(
            var id: String? = null,
            var name: String? = null,
            val arguments: StringBuilder = StringBuilder(),
        )

        fun processChunk(chunkNode: JsonNode): List<GigaResponse.Choice> {
            val choicesNode = chunkNode["output"]?.get("choices") ?: return emptyList()
            if (!choicesNode.isArray) return emptyList()

            val resultChoices = mutableListOf<GigaResponse.Choice>()

            choicesNode.forEach { choiceNode ->
                val choiceIndex = choiceNode["index"]?.asInt() ?: 0
                val state = choicesState.getOrPut(choiceIndex) { ChoiceState() }

                val messageNode = choiceNode["message"]
                val finishReason = finishReasonResolver(choiceNode["finish_reason"]?.asText())

                messageNode?.get("role")?.asText()?.let { role ->
                    state.role = roleResolver(role)
                }

                val contentChunk = messageNode?.get("content")?.asText()
                if (!contentChunk.isNullOrEmpty()) {
                    resultChoices += GigaResponse.Choice(
                        message = GigaResponse.Message(
                            content = contentChunk,
                            role = state.role,
                            functionCall = null,
                            functionsStateId = null,
                        ),
                        index = choiceIndex,
                        finishReason = null,
                    )
                }

                val toolCallsNode = messageNode?.get("tool_calls")
                if (toolCallsNode != null && toolCallsNode.isArray) {
                    toolCallsNode.forEach { toolCallNode ->
                        val toolIndex = toolCallNode["index"]?.asInt() ?: 0
                        val toolState = state.toolCalls.getOrPut(toolIndex) { ToolCallState() }

                        toolCallNode["id"]?.asText()?.takeIf { it.isNotBlank() }?.let { toolState.id = it }
                        toolCallNode["function"]?.get("name")?.asText()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { toolState.name = it }
                        toolCallNode["function"]?.get("arguments")?.asText()?.let {
                            toolState.arguments.append(it)
                        }
                    }
                }

                if (finishReason != null) {
                    if (state.toolCalls.isNotEmpty()) {
                        state.toolCalls.entries.sortedBy { it.key }.forEach { (toolIndex, toolState) ->
                            val name = toolState.name ?: return@forEach
                            val argsText = toolState.arguments.toString()
                            resultChoices += GigaResponse.Choice(
                                message = GigaResponse.Message(
                                    content = "",
                                    role = state.role,
                                    functionCall = GigaResponse.FunctionCall(name, parseFunctionArguments(argsText)),
                                    functionsStateId = toolState.id,
                                ),
                                index = toolIndex,
                                finishReason = GigaResponse.FinishReason.function_call,
                            )
                        }
                        state.toolCalls.clear()
                    } else if (finishReason != GigaResponse.FinishReason.function_call) {
                        resultChoices += GigaResponse.Choice(
                            message = GigaResponse.Message(
                                content = "",
                                role = state.role,
                                functionCall = null,
                                functionsStateId = null,
                            ),
                            index = choiceIndex,
                            finishReason = finishReason,
                        )
                    }
                }
            }

            return resultChoices
        }
    }
}

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val filesToolUtil: FilesToolUtil by di.instance()
    val api: QwenChatAPI by di.instance()

    val model = "qwen-plus"
    val request = GigaRequest.Chat(
        model = model,
        stream = true,
        messages = listOf(
            GigaRequest.Message(
                role = GigaMessageRole.system,
                content = """
                    Ты помощник, который при необходимости вызывает функции и отвечает по-русски.
                """.trimIndent()
            ),
            GigaRequest.Message(
                role = GigaMessageRole.user,
                content = "Расскажи сказку",
            ),
        ),
        functions = listOf(
            ToolListFiles(filesToolUtil).toGiga(),
        ).map { it.fn }
    )

    val allTime = measureTime {
        val result = api.messageStream(request)
        val millis = System.currentTimeMillis()
        val first by lazy {
            println("First response in ${System.currentTimeMillis() - millis}")
        }
        result.collect { response ->
            first
            println("Response: $response")
        }
    }
    println("All time: $allTime")
}
