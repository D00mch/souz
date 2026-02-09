package ru.gigadesk.giga

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.fasterxml.jackson.module.kotlin.readValue
import gigachat.v1.ChatServiceGrpcKt
import gigachat.v1.Gigachatv1
import io.grpc.*
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.tool.files.ToolListFiles
import ru.gigadesk.tool.files.FilesToolUtil
import java.util.*
import kotlin.time.measureTime

/**
 * Simple gRPC client for GigaChat ChatService.
 * @param gigaChatAPI GigaRestChatAPI to support other methods like image upload
 */
class GigaGRPCChatApi(
    private val auth: GigaAuth,
    private val gigaChatAPI: GigaRestChatAPI,
) : GigaChatAPI by gigaChatAPI {
    private val l by lazy { LoggerFactory.getLogger(GigaGRPCChatApi::class.java) }

    private val maxInboundMessageSizeBytes by lazy {
        val envValue = System.getenv("GIGA_GRPC_MAX_INBOUND_MB")
            ?: System.getProperty("GIGA_GRPC_MAX_INBOUND_MB")
        val sizeMb = envValue?.toIntOrNull() ?: DEFAULT_MAX_INBOUND_MESSAGE_MB
        val bytes = sizeMb * 1024 * 1024
        l.info("gRPC max inbound message size: ${sizeMb}MB ($bytes bytes)")
        bytes
    }

    private val keepAliveTimeMs by lazy {
        val value = System.getenv("GIGA_GRPC_KEEPALIVE_TIME_MS")
            ?: System.getProperty("GIGA_GRPC_KEEPALIVE_TIME_MS")
        val keepAliveMs = value?.toLongOrNull() ?: DEFAULT_KEEPALIVE_TIME_MS
        l.info("gRPC keepAlive time: ${keepAliveMs}ms")
        keepAliveMs
    }

    private val keepAliveTimeoutMs by lazy {
        val value = System.getenv("GIGA_GRPC_KEEPALIVE_TIMEOUT_MS")
            ?: System.getProperty("GIGA_GRPC_KEEPALIVE_TIMEOUT_MS")
        val timeoutMs = value?.toLongOrNull() ?: DEFAULT_KEEPALIVE_TIMEOUT_MS
        l.info("gRPC keepAlive timeout: ${timeoutMs}ms")
        timeoutMs
    }

    private val keepAliveWithoutCalls by lazy {
        val value = System.getenv("GIGA_GRPC_KEEPALIVE_WITHOUT_CALLS")
            ?: System.getProperty("GIGA_GRPC_KEEPALIVE_WITHOUT_CALLS")
        val withoutCalls = value?.toBooleanStrictOrNull() ?: DEFAULT_KEEPALIVE_WITHOUT_CALLS
        l.info("gRPC keepAlive without calls: $withoutCalls")
        withoutCalls
    }

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

    private val channel: ManagedChannel by lazy {
        NettyChannelBuilder.forAddress("gigachat.devices.sberbank.ru", 443)
            .sslContext(loadSslContext())
            .maxInboundMessageSize(maxInboundMessageSizeBytes)
            .keepAliveTime(keepAliveTimeMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .keepAliveTimeout(keepAliveTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .keepAliveWithoutCalls(keepAliveWithoutCalls)
            .build()
    }

    private val stub: ChatServiceGrpcKt.ChatServiceCoroutineStub by lazy {
        ChatServiceGrpcKt.ChatServiceCoroutineStub(channel)
    }

    override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat {
        val request = request(body)
        val token = loadAccessToken()
        return try {
            stub.chat(request, headers(token)).toGigaResponse()
        } catch (e: Exception) {
            l.error("Error in gRPC chat", e)
            suspend fun retryWithRefresh() =
                stub.chat(request, headers(refreshAccessToken())).toGigaResponse()
            when (e) {
                is StatusException if e.status.code == Status.Code.UNAUTHENTICATED -> retryWithRefresh()
                is StatusRuntimeException if e.status.code == Status.Code.UNAUTHENTICATED -> retryWithRefresh()
                else -> throw e
            }
        }
    }

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> {
        suspend fun stream(token: String): Flow<GigaResponse.Chat> {
            return stub.chatStream(request(body), headers(token))
                .map { resp -> resp.mapResponse(body.model) }
        }

        return stream(loadAccessToken()).catch { e ->
            l.error("Error in gRPC chat stream", e)
            if ((e is StatusException && e.status.code == Status.Code.UNAUTHENTICATED) ||
                (e is StatusRuntimeException && e.status.code == Status.Code.UNAUTHENTICATED)
            ) {
                emitAll(stream(refreshAccessToken()))
            } else if (
                (e is StatusException && e.status.code == Status.Code.RESOURCE_EXHAUSTED) ||
                (e is StatusRuntimeException && e.status.code == Status.Code.RESOURCE_EXHAUSTED) ||
                e.message?.contains("status code 413") == true
            ) {
                emit(GigaResponse.Chat.Error(413, "Resource exhausted: ${e.message}"))
            } else {
                emit(GigaResponse.Chat.Error(-1, "Connection error: ${e.message}"))
            }
        }
    }

    private fun GigaGRPCChatApi.request(body: GigaRequest.Chat): Gigachatv1.ChatRequest =
        Gigachatv1.ChatRequest.newBuilder()
            .setModel(body.model)
            .setOptions(
                Gigachatv1.ChatOptions.newBuilder()
                    .apply {
                        addAllFunctions(
                            body.functions.map { it.toGRPC() }
                        )
                        maxTokens = body.maxTokens
                        body.temperature?.let { temperature = it }
                        body.updateInterval?.let { updateInterval = it.toFloat() }
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
                            arguments = gigaJsonMapper.readValue(msg.functionCall.arguments)
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
            .setParameters(gigaJsonMapper.writeValueAsString(fn.parameters))
            .addAllFewShotExamples(fn.fewShotExamples?.map { it.toGRPC() } ?: emptyList())
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

    private val uuid by lazy { UUID.randomUUID().toString() }

    private fun headers(token: String): Metadata = Metadata().apply {
        val authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        put(authKey, "Bearer $token")
        val sessionKey = Metadata.Key.of("x-session-id", Metadata.ASCII_STRING_MARSHALLER)
        put(sessionKey, uuid)
    }

    private fun Gigachatv1.ChatResponse.toGigaResponse(): GigaResponse.Chat {
        val choices = alternativesList.map { alt ->
            val msg = alt.message
            val functionCall = if (msg.hasFunctionCall()) {
                val args: Map<String, Any> = gigaJsonMapper.readValue(msg.functionCall.arguments)
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

    private fun loadSslContext(): SslContext {
        val certPath = "certs/russiantrustedca.pem"
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(certPath)
            ?: throw IllegalStateException("Certificate not found: $certPath")
        stream.use { ins ->
            return GrpcSslContexts.forClient().trustManager(ins).build()
        }
    }
}

private const val DEFAULT_MAX_INBOUND_MESSAGE_MB = 32
private const val DEFAULT_KEEPALIVE_TIME_MS = Long.MAX_VALUE
private const val DEFAULT_KEEPALIVE_TIMEOUT_MS = 20_000L
private const val DEFAULT_KEEPALIVE_WITHOUT_CALLS = false

suspend fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val api: GigaGRPCChatApi by di.instance()
    val filesToolUtil: FilesToolUtil by di.instance()
//  val api: GigaRestChatAPI by di.instance()

    val systemPrompt = GigaRequest.Message(
        role = GigaMessageRole.system,
        content = """
            Ты отличный сказочник
            """.trimIndent()
    )
    val request = GigaRequest.Chat(
        model = GigaModel.Pro.alias,
        stream = true,
        messages = listOf(
            systemPrompt,
            GigaRequest.Message(
                role = GigaMessageRole.user,
                content = "Напиши сказку о Царе Салтане в 15 предложений",
            ),
        ),
        functions = listOf(
            ToolListFiles(filesToolUtil).toGiga(),
        ).map { it.fn }
    )

    @Suppress("USELESS_IS_CHECK", "CAST_NEVER_SUCCEEDS")
    val allTime = measureTime {
        if (api is GigaGRPCChatApi) {
            val result = api.messageStream(request)

            val millis = System.currentTimeMillis()
            val first by lazy {
                println("First response in ${System.currentTimeMillis() - millis}")
            }
            result.collect {
                first
                println("Response: $it")
            }
        } else {
            api as GigaRestChatAPI
            val response = api.message(request.copy(stream = false))
            println(response)
        }
    }
    println("All time: $allTime")
}
