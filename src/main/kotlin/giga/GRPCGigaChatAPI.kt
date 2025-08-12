package com.dumch.giga

import gigachat.v1.ChatRequest
import gigachat.v1.ChatResponse
import gigachat.v1.ChatServiceGrpcKt
import gigachat.v1.Message
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import org.slf4j.LoggerFactory

/**
 * Simple gRPC client for GigaChat ChatService.
 */
class GRPCGigaChatAPI(private val auth: GigaAuth) {
    private val l = LoggerFactory.getLogger(GRPCGigaChatAPI::class.java)

    private val channel: ManagedChannel =
        ManagedChannelBuilder.forAddress("gigachat.devices.sberbank.ru", 443)
            .useTransportSecurity()
            .build()

    private val stub: ChatServiceGrpcKt.ChatServiceCoroutineStub =
        ChatServiceGrpcKt.ChatServiceCoroutineStub(channel)

    suspend fun message(body: GigaRequest.Chat): ChatResponse {
        val token = loadAccessToken()
        val headers = Metadata().apply {
            val key = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
            put(key, "Bearer $token")
        }
        val headerStub = MetadataUtils.attachHeaders(stub, headers)

        val request = ChatRequest.newBuilder()
            .setModel(body.model)
            .addAllMessages(body.messages.map {
                Message.newBuilder()
                    .setRole(it.role.name)
                    .setContent(it.content)
                    .apply {
                        it.functionsStateId?.let { id -> functionsStateId = id }
                        it.attachments?.let { att -> addAllAttachments(att) }
                    }
                    .build()
            })
            .build()

        return headerStub.chat(request)
    }

    private suspend fun loadAccessToken(): String {
        return System.getProperty("GIGA_ACCESS_TOKEN") ?: refreshAccessToken()
    }

    private suspend fun refreshAccessToken(): String {
        val apiKey = System.getenv("GIGA_KEY")
        val newToken = auth.requestToken(apiKey, "GIGACHAT_API_CORP")
        System.setProperty("GIGA_ACCESS_TOKEN", newToken)
        return newToken
    }

    companion object {
        val INSTANCE = GRPCGigaChatAPI(GigaAuth)
    }
}

