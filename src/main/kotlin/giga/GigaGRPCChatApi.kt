package com.dumch.giga

import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.desktop.ToolOpen
import com.fasterxml.jackson.module.kotlin.readValue
import gigachat.v1.ChatServiceGrpcKt
import gigachat.v1.Gigachatv1
import io.grpc.*
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

/**
 * Simple gRPC client for GigaChat ChatService.
 * @param gigaChatAPI GigaRestChatAPI to support other methods like image upload
 */
class GigaGRPCChatApi(
    private val auth: GigaAuth,
    private val gigaChatAPI: GigaRestChatAPI = GigaRestChatAPI.INSTANCE,
) : GigaChatAPI by gigaChatAPI {
    private val l = LoggerFactory.getLogger(GigaGRPCChatApi::class.java)

    init {
        // Bridge JUL (used by gRPC) to SLF4J so logback handles all logs
        if (!SLF4JBridgeHandler.isInstalled()) {
            SLF4JBridgeHandler.removeHandlersForRootLogger()
            SLF4JBridgeHandler.install()
        }

        val envLevel = System.getenv("GIGA_GRPC_LOG_LEVEL")
            ?: System.getenv("GIGA_LOG_LEVEL")
        val nettyLevel = envLevel
            ?.let {
                val normalized = if (it.equals("NONE", ignoreCase = true)) "OFF" else it
                runCatching { Level.valueOf(normalized) }.getOrNull()
            }
            ?: Level.WARN
        l.info("GIGA_GRPC_LOG_LEVEL: $nettyLevel")
        (LoggerFactory.getLogger("io.grpc") as? Logger)?.level = nettyLevel
        (LoggerFactory.getLogger("io.grpc.netty.shaded") as? Logger)?.level = nettyLevel
        (LoggerFactory.getLogger("io.netty") as? Logger)?.level = nettyLevel
    }

    private val channel: ManagedChannel =
        NettyChannelBuilder.forAddress("gigachat.devices.sberbank.ru", 443)
            .sslContext(loadSslContext())
            .build()

    private val stub: ChatServiceGrpcKt.ChatServiceCoroutineStub =
        ChatServiceGrpcKt.ChatServiceCoroutineStub(channel)

    override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat {
        val request = Gigachatv1.ChatRequest.newBuilder()
            .setModel(body.model)
            .setOptions(
                Gigachatv1.ChatOptions.newBuilder()
                    .apply {
                        addAllFunctions(
                            body.functions.map { it.toGRPC() }
                        )
                        maxTokens = body.maxTokens
                        body.temperature?.let { temperature = it }
                    }
                    .build()
            )
            .addAllMessages(body.messages.map { msg ->
                Gigachatv1.Message.newBuilder()
                    .setRole(msg.role.name)
                    .setContent(msg.content)
                    .apply {
                        msg.functionsStateId?.let { id -> functionsStateId = id }
                        msg.attachments?.let { att -> addAllAttachments(att) }
                    }
                    .build()
            })
            .build()

        val token = loadAccessToken()
        return try {
            stub.chat(request, authHeaders(token)).toGigaResponse()
        } catch (e: Exception) {
            l.error("Error in gRPC chat", e)
            suspend fun retryWithRefresh() =
                stub.chat(request, authHeaders(refreshAccessToken())).toGigaResponse()
            when {
                e is StatusException && e.status.code == Status.Code.UNAUTHENTICATED -> retryWithRefresh()
                e is StatusRuntimeException && e.status.code == Status.Code.UNAUTHENTICATED -> retryWithRefresh()
                else -> throw e
            }
        }
    }

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> {
        val request = Gigachatv1.ChatRequest.newBuilder()
            .setModel(body.model)
            .setOptions(
                Gigachatv1.ChatOptions.newBuilder()
                    .apply {
                        addAllFunctions(
                            body.functions.map { it.toGRPC() }
                        )
                        maxTokens = body.maxTokens
                        body.temperature?.let { temperature = it }
                    }
                    .build()
            )
            .addAllMessages(body.messages.map { msg ->
                Gigachatv1.Message.newBuilder()
                    .setRole(msg.role.name)
                    .setContent(msg.content)
                    .apply {
                        msg.functionsStateId?.let { id -> functionsStateId = id }
                        msg.attachments?.let { att -> addAllAttachments(att) }
                    }
                    .build()
            })
            .build()

        suspend fun stream(token: String): Flow<GigaResponse.Chat> {
            return stub.chatStream(request, authHeaders(token))
                .map { resp -> resp.mapResponse(body.model) }
        }

        return stream(loadAccessToken()).catch { e ->
            l.error("Error in gRPC chat stream", e)
            if ((e is StatusException && e.status.code == Status.Code.UNAUTHENTICATED) ||
                (e is StatusRuntimeException && e.status.code == Status.Code.UNAUTHENTICATED)
            ) {
                emitAll(stream(refreshAccessToken()))
            } else {
                emit(GigaResponse.Chat.Error(-1, "Connection error: ${e.message}"))
            }
        }
    }

    private fun Gigachatv1.ChatResponse.mapResponse(model: String): GigaResponse.Chat {
        val resp = this
        val usage = GigaResponse.Usage(
            promptTokens = resp.usage.promptTokens,
            completionTokens = resp.usage.completionTokens,
            totalTokens = resp.usage.totalTokens,
            precachedTokens = 0,
        )
        val choices = resp.alternativesList.mapNotNull { alt ->
            val msg = alt.message
            if (alt.finishReason.equals("stop", ignoreCase = true)) {
                l.info("finishReason: ${alt.finishReason}")
                return@mapNotNull null
            }
            if (msg.content.isBlank() && !msg.hasFunctionCall()) {
                l.info("Empty message chunk skipped")
                return@mapNotNull null
            }
            GigaResponse.Choice(
                message = GigaResponse.Message(
                    content = msg.content,
                    role = if (msg.role.isBlank()) {
                        GigaMessageRole.assistant
                    } else {
                        GigaMessageRole.valueOf(msg.role)
                    },
                    functionCall = if (msg.hasFunctionCall()) {
                        GigaResponse.FunctionCall(
                            name = msg.functionCall.name,
                            arguments = objectMapper.readValue(msg.functionCall.arguments)
                        )
                    } else {
                        null
                    },
                    functionsStateId = msg.functionsStateId,
                ),
                index = alt.index,
                finishReason = alt.finishReason.toFinishReason()
            )
        }
        return GigaResponse.Chat.Ok(
            choices = choices,
            created = resp.timestamp,
            model = model,
            usage = usage
        )
    }

    private fun GigaRequest.Function.toGRPC(): Gigachatv1.Function? {
        val fn = this
        return Gigachatv1.Function.newBuilder()
            .setName(fn.name)
            .setDescription(fn.description)
            .setParameters(objectMapper.writeValueAsString(fn.parameters))
            .addAllFewShotExamples(fn.fewShotExamples.map { it.toGRPC() })
            .build()
    }

    private fun GigaRequest.FewShotExample.toGRPC(): Gigachatv1.AnyExample {
        val example = this
        return Gigachatv1.AnyExample.newBuilder()
            .setRequest(example.request)
            .setParams(
                Gigachatv1.Params.newBuilder()
                    .addAllPairs(
                        example.params.map { (k, v) ->
                            Gigachatv1.Pair.newBuilder()
                                .setKey(k)
                                .setValue(v.toString())
                                .build()
                        }
                    )
            )
            .build()
    }

    private suspend fun loadAccessToken(): String {
        return System.getProperty("GIGA_ACCESS_TOKEN") ?: refreshAccessToken()
    }

    private suspend fun refreshAccessToken(): String {
        val apiKey = System.getenv("GIGA_KEY") ?: System.getProperty("GIGA_KEY")
            ?: throw IllegalStateException("GIGA_KEY is not set")
        val newToken = auth.requestToken(apiKey, "GIGACHAT_API_PERS")
        System.setProperty("GIGA_ACCESS_TOKEN", newToken)
        return newToken
    }

    private fun authHeaders(token: String): Metadata = Metadata().apply {
        val key = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        put(key, "Bearer $token")
    }

    private fun Gigachatv1.ChatResponse.toGigaResponse(): GigaResponse.Chat {
        val choices = alternativesList.map { alt ->
            val msg = alt.message
            val functionCall = if (msg.hasFunctionCall()) {
                val args: Map<String, Any> = objectMapper.readValue(msg.functionCall.arguments)
                GigaResponse.FunctionCall(
                    name = msg.functionCall.name,
                    arguments = args
                )
            } else null

            GigaResponse.Choice(
                message = GigaResponse.Message(
                    content = msg.content,
                    role = GigaMessageRole.valueOf(msg.role),
                    functionCall = functionCall,
                    functionsStateId = if (msg.hasFunctionsStateId()) msg.functionsStateId else null,
                ),
                index = alt.index,
                finishReason = alt.finishReason.toFinishReason()
            )
                .also { l.info("response: $it") }
        }

        val u = usage
        val usageDto = GigaResponse.Usage(
            promptTokens = u.promptTokens,
            completionTokens = u.completionTokens,
            totalTokens = u.totalTokens,
            precachedTokens = 0,
        )

        return GigaResponse.Chat.Ok(
            choices = choices,
            created = timestamp,
            model = modelInfo.name,
            usage = usageDto,
        )
    }

    companion object {
        val INSTANCE = GigaGRPCChatApi(GigaAuth)
    }

    private fun loadSslContext(): SslContext {
        val certPath = "certs/russiantrustedca.pem"
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(certPath)
                ?: throw IllegalStateException("Certificate not found: $certPath")
        stream.use { ins ->
            return GrpcSslContexts.forClient().trustManager(ins).build()
        }
    }
}

suspend fun main() {
    val api = GigaGRPCChatApi.INSTANCE

    val systemPrompt = GigaRequest.Message(
        role = GigaMessageRole.system,
        content = """
                Ты — помощник человека с ограниченными возможностями. Будь полезным. Говори только по существу. Если какую-то задачу можно решить 
                c помощью имеющихся функций, сделай, а не проси пользователя сделать это. Если сомневаешься, уточни.
            """.trimIndent()
    )

    val result = api.messageStream(
        GigaRequest.Chat(
        model = GigaModel.Pro.alias,
        stream = true,
        messages = listOf(
            systemPrompt,
            GigaRequest.Message(
                role = GigaMessageRole.user,
                content = "Привет, как дела?",
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