package ru.gigadesk.llms

import com.fasterxml.jackson.databind.DeserializationFeature
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
import ru.gigadesk.giga.objectMapper
import ru.gigadesk.giga.toFinishReason
import ru.gigadesk.giga.toGiga
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.tool.files.ToolListFiles
import java.io.File
import kotlin.time.Duration.Companion.seconds
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
        get() = settingsProvider.aiTunnelModelName?.takeIf { it.isNotBlank() }
            ?: System.getenv("AITUNNEL_MODEL")
            ?: System.getProperty("AITUNNEL_MODEL")
            ?: "gpt-4o-mini" // Дешевый и быстрый дефолт для тестов

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
        install(SSE) {
            maxReconnectionAttempts = 0
            reconnectionTime = 3.seconds
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
                    l.info("AiTunnel response received")
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
        l.error("Error in AiTunnel chat", t)
        GigaResponse.Chat.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> = channelFlow {
        try {
            client.sse(
                urlString = CHAT_COMPLETIONS_URL,
                request = {
                    method = HttpMethod.Post
                    setBody(buildChatRequest(body, stream = true))
                },
            ) {
                incoming.collect { event ->
                    val data = event.data ?: return@collect
                    if (data == "[DONE]") {
                        return@collect
                    }
                    if (!data.trimStart().startsWith("{")) {
                        return@collect
                    }
                    send(parseStreamChunk(data, body.model))
                }
            }
        } catch (e: ClientRequestException) {
            val text = e.response.bodyAsText()
            send(GigaResponse.Chat.Error(e.response.status.value, text))
        } catch (t: Throwable) {
            l.error("Error in AiTunnel stream chat", t)
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
        l.error("Error in AiTunnel embeddings", t)
        GigaResponse.Embeddings.Error(-1, "Connection error: ${t.message}")
    }

    override suspend fun uploadFile(file: File): GigaResponse.UploadFile {
        throw UnsupportedOperationException("AiTunnel file upload is not supported in this implementation")
    }

    override suspend fun downloadFile(fileId: String): String? {
        return null
    }

    override suspend fun balance(): GigaResponse.Balance {
        // AI Tunnel обычно работает как прокси к OpenAI, стандартного эндпоинта баланса может не быть,
        // либо он специфичен. Пока возвращаем ошибку, как в примере.
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
            // OpenAI поддерживает parallel_tool_calls по умолчанию в новых моделях
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
                    // OpenAI ожидает роль 'tool' для результатов вызова функций
                    val role = if (msg.functionsStateId != null) "tool" else "function"
                    buildMap {
                        put("role", role)
                        put("content", msg.content)
                        // Для tool_response обязателен tool_call_id
                        msg.functionsStateId?.let { put("tool_call_id", it) }
                        // Для старых function_call нужен name, для tool_call - нет
                        if (msg.functionsStateId == null && msg.name != null) {
                            put("name", msg.name)
                        }
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

    private fun parseCompletionsResponse(text: String, requestModel: String): GigaResponse.Chat {
        val node = objectMapper.readTree(text)
        // Для обычного ответа OpenAI данные лежат в поле "message"
        val choices = parseChoices(node["choices"], isStream = false)
        val usage = parseUsage(node["usage"])
        return GigaResponse.Chat.Ok(
            choices = choices,
            created = node["created"]?.asLong() ?: (System.currentTimeMillis() / 1000),
            model = node["model"]?.asText() ?: requestModel,
            usage = usage,
        )
    }

    private fun parseStreamChunk(text: String, requestModel: String): GigaResponse.Chat {
        val node = objectMapper.readTree(text)
        // В стриме OpenAI данные лежат в поле "delta"
        val choices = parseChoices(node["choices"], isStream = true)
        // Usage может приходить в последнем чанке (если опция stream_options: {include_usage: true}), но обычно null
        val usage = parseUsage(node["usage"])
        return GigaResponse.Chat.Ok(
            choices = choices,
            created = node["created"]?.asLong() ?: (System.currentTimeMillis() / 1000),
            model = node["model"]?.asText() ?: requestModel,
            usage = usage,
        )
    }

    private fun parseChoices(
        choicesNode: com.fasterxml.jackson.databind.JsonNode?,
        isStream: Boolean
    ): List<GigaResponse.Choice> {
        if (choicesNode == null || !choicesNode.isArray) {
            return emptyList()
        }

        val choices = mutableListOf<GigaResponse.Choice>()
        choicesNode.forEachIndexed { idx, choiceNode ->
            // В стриминге поле называется delta, в обычном ответе message
            val messageField = if (isStream) "delta" else "message"
            val messageNode = choiceNode[messageField]

            val messageContent = messageNode?.get("content")?.asText().orEmpty()
            val role = messageNode?.get("role")?.asText().toGigaRole()
            val finishReason = choiceNode["finish_reason"]?.asText().toOpenAiFinishReason()
            val choiceIndex = choiceNode["index"]?.asInt() ?: idx
            val toolCallsNode = messageNode?.get("tool_calls")

            // Обработка tool_calls (функций)
            if (toolCallsNode != null && toolCallsNode.isArray && toolCallsNode.size() > 0) {
                toolCallsNode.forEach { toolCallNode ->
                    val functionNode = toolCallNode["function"]
                    val name = functionNode?.get("name")?.asText().orEmpty()
                    // В стриме аргументы могут приходить частями, но мы просто передаем строку
                    val argsText = functionNode?.get("arguments")?.asText() ?: ""

                    // Парсим аргументы только если это не стрим или если строка похожа на полный JSON,
                    // но чаще всего для стрима мы просто копим строку в контроллере выше.
                    // Здесь для совместимости вернем map, если удастся распарсить.
                    val args = if (!isStream && argsText.isNotEmpty()) parseFunctionArguments(argsText) else emptyMap()

                    val functionsStateId = toolCallNode["id"]?.asText()
                    val toolIndex = toolCallNode["index"]?.asInt() ?: choiceIndex // В tool_calls тоже есть индекс

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

            // Обычный контент или если нет tool_calls
            if ((messageContent.isNotEmpty()) || (toolCallsNode == null || toolCallsNode.size() == 0)) {
                // В стриме роль может прийти только в первом пакете, далее null.
                // GigaMessageRole.assistant - безопасный дефолт, но лучше сохранять стейт выше.
                // В данной архитектуре мы просто возвращаем то, что пришло.
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
            }
        }

        return choices
    }

    private fun parseUsage(node: com.fasterxml.jackson.databind.JsonNode?): GigaResponse.Usage {
        val prompt = node?.get("prompt_tokens")?.asInt() ?: 0
        val completion = node?.get("completion_tokens")?.asInt() ?: 0
        val total = node?.get("total_tokens")?.asInt() ?: (prompt + completion)
        return GigaResponse.Usage(prompt, completion, total, 0)
    }

    private fun parseEmbeddingsResponse(text: String): GigaResponse.Embeddings {
        val node = objectMapper.readTree(text)
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
        return runCatching { objectMapper.readValue<Map<String, Any>>(argsText) }
            .getOrElse {
                l.warn("Failed to parse AiTunnel tool arguments: $argsText")
                mapOf("raw" to argsText)
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

    private fun resolveChatModel(model: String): String {
        // If model is the placeholder alias or GigaChat, use the user-configured model name
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

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val filesToolUtil: FilesToolUtil by di.instance()

    // Не забудь добавить SettingsProvider в DI или замокать его тут для теста
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
                content = """
                    Ты помощник, который при необходимости вызывает функции. Отвечай кратко.
                """.trimIndent()
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
            // Для удобства вывода в консоль
            when (response) {
                is GigaResponse.Chat.Ok -> {
                    val content = response.choices.firstOrNull()?.message?.content
                    if (!content.isNullOrEmpty()) {
                        print(content)
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