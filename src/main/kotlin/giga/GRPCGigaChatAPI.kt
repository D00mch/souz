package com.dumch.giga

import gigachat.v1.ChatServiceGrpcKt
import gigachat.v1.Gigachatv1
import io.grpc.ManagedChannel
import io.grpc.Metadata
import org.slf4j.LoggerFactory
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import java.io.File

/**
 * Simple gRPC client for GigaChat ChatService.
 */
class GRPCGigaChatAPI(private val auth: GigaAuth) {
    private val l = LoggerFactory.getLogger(GRPCGigaChatAPI::class.java)

    private val channel: ManagedChannel =
        NettyChannelBuilder.forAddress("gigachat.devices.sberbank.ru", 443)
            .sslContext(loadSslContext())
            .build()

    private val stub: ChatServiceGrpcKt.ChatServiceCoroutineStub =
        ChatServiceGrpcKt.ChatServiceCoroutineStub(channel)

    suspend fun message(body: GigaRequest.Chat): Gigachatv1.ChatResponse {
        val token = loadAccessToken()
        val headers = Metadata().apply {
            val key = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
            put(key, "Bearer $token")
        }

        val request = Gigachatv1.ChatRequest.newBuilder()
            .setModel(body.model)
            .setOptions(
                Gigachatv1.ChatOptions.newBuilder()
                    .addAllFunctions(
                        body.functions.map { it.toGRPC() }
                    )
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

        return stub.chat(request, headers)
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
        val apiKey = System.getenv("GIGA_KEY")
        val newToken = auth.requestToken(apiKey, "GIGACHAT_API_PERS")
        System.setProperty("GIGA_ACCESS_TOKEN", newToken)
        return newToken
    }

    companion object {
        val INSTANCE = GRPCGigaChatAPI(GigaAuth)
    }

    private fun loadSslContext(): SslContext {
        val certPath = System.getenv("GRPC_DEFAULT_SSL_ROOTS_FILE_PATH")
            ?: "certs/russiantrustedca.pem"
        val stream = if (File(certPath).exists()) {
            File(certPath).inputStream()
        } else {
            Thread.currentThread().contextClassLoader.getResourceAsStream(certPath)
                ?: throw IllegalStateException("Certificate not found: $certPath")
        }
        stream.use { ins ->
            return GrpcSslContexts.forClient().trustManager(ins).build()
        }
    }
}

suspend fun main() {
    println(System.getenv("GRPC_DEFAULT_SSL_ROOTS_FILE_PATH"))
    val api = GRPCGigaChatAPI.INSTANCE
    val result = api.message(GigaRequest.Chat(
        model = "GigaChat-Pro",
        messages = listOf(
            GigaRequest.Message(
                role = GigaMessageRole.user,
                content = "Привет, как дела?",
            ),
            GigaRequest.Message(
                role = GigaMessageRole.assistant,
                content = "Привет! Я говорю по русски, но я могу общаться на английском и на французском.",
            ),
            GigaRequest.Message(
                role = GigaMessageRole.user,
                content = "Я хочу поговорить на английском.",
            )
        )
    ))
    println(result)
}