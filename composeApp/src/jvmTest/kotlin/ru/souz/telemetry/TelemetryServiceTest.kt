package ru.souz.telemetry

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import ru.souz.db.ConfigStore
import ru.souz.giga.GigaResponse
import ru.souz.tool.ToolCategory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelemetryServiceTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `flush registers installation signs telemetry batch and clears outbox`() = runTest {
        resetTelemetryConfigStore()
        val registerBody = AtomicReference<String?>(null)
        val batchBody = AtomicReference<String?>(null)
        val publicKeyRef = AtomicReference<String?>(null)
        val registerCalls = AtomicInteger(0)
        val batchCalls = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/installations/register") { exchange ->
            registerCalls.incrementAndGet()
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            registerBody.set(body)
            val parsed = objectMapper.readTree(body)
            val publicKey = parsed.path("publicKey").asText()
            publicKeyRef.set(publicKey)
            val signaturePayload = listOf(
                "POST",
                "/v1/installations/register",
                exchange.requestHeaders.getFirst("X-Telemetry-Timestamp"),
                exchange.requestHeaders.getFirst("X-Telemetry-Nonce"),
                sha256Base64(body),
            ).joinToString("\n")
            assertTrue(
                verifySignature(
                    encodedPublicKey = publicKey,
                    payload = signaturePayload,
                    signature = exchange.requestHeaders.getFirst("X-Telemetry-Signature"),
                )
            )
            val responseBody = """{"installationId":"installation-1"}"""
            exchange.sendResponseHeaders(201, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }
        server.createContext("/v1/metrics/batch") { exchange ->
            batchCalls.incrementAndGet()
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            batchBody.set(body)
            val installationId = exchange.requestHeaders.getFirst("X-Telemetry-Installation-Id")
            assertEquals("installation-1", installationId)
            val publicKey = assertNotNull(publicKeyRef.get())
            val signaturePayload = listOf(
                "POST",
                "/v1/metrics/batch",
                installationId,
                exchange.requestHeaders.getFirst("X-Telemetry-Timestamp"),
                exchange.requestHeaders.getFirst("X-Telemetry-Nonce"),
                sha256Base64(body),
            ).joinToString("\n")
            assertTrue(
                verifySignature(
                    encodedPublicKey = publicKey,
                    payload = signaturePayload,
                    signature = exchange.requestHeaders.getFirst("X-Telemetry-Signature"),
                )
            )
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()

        try {
            val repo = TelemetryOutboxRepository(
                databasePath = Files.createTempDirectory("telemetry-service-test").resolve("telemetry.db"),
                objectMapper = objectMapper,
            )
            val service = TelemetryService(
                outboxRepository = repo,
                runtimeConfig = TelemetryRuntimeConfig(baseUrl = "http://127.0.0.1:${server.address.port}"),
            )

            val conversationId = service.startConversation(TelemetryConversationStartReason.CHAT_UI)
            val requestContext = service.beginRequest(
                conversationId = conversationId,
                source = TelemetryRequestSource.CHAT_UI,
                model = "gpt-test",
                provider = "OPENAI",
                inputLengthChars = 24,
                attachedFilesCount = 1,
            )
            service.recordToolExecution(
                functionName = "tool.open_file",
                functionArguments = mapOf("path" to "/tmp/demo.txt"),
                toolCategory = ToolCategory.FILES,
                durationMs = 17,
                success = true,
                errorMessage = null,
            )
            service.finishRequest(
                context = requestContext,
                status = TelemetryRequestStatus.SUCCESS,
                responseLengthChars = 128,
                errorMessage = null,
                requestTokenUsage = GigaResponse.Usage(10, 20, 30, 0),
                sessionTokenUsage = GigaResponse.Usage(10, 20, 30, 0),
            )
            service.finishConversation(conversationId, TelemetryConversationEndReason.NEW_CONVERSATION)

            val result = service.flushNow()

            assertTrue(result.success)
            assertEquals(4, result.acceptedEvents)
            assertEquals(0, repo.pendingCount())
            assertEquals(1, registerCalls.get())
            assertEquals(1, batchCalls.get())

            val registration = objectMapper.readTree(assertNotNull(registerBody.get()))
            assertTrue(registration.path("userId").asText().isNotBlank())
            assertTrue(registration.path("deviceId").asText().isNotBlank())
            assertEquals("Ed25519", registration.path("keyAlgorithm").asText())

            val batch = objectMapper.readTree(assertNotNull(batchBody.get()))
            val eventTypes = batch.path("events").map { it.path("type").asText() }
            assertEquals(
                listOf(
                    TelemetryEventType.CONVERSATION_STARTED.wireName,
                    TelemetryEventType.TOOL_EXECUTED.wireName,
                    TelemetryEventType.REQUEST_FINISHED.wireName,
                    TelemetryEventType.CONVERSATION_FINISHED.wireName,
                ),
                eventTypes,
            )
            assertEquals("installation-1", batch.path("client").path("installationId").asText())

            val conversationFinished = batch.path("events").last()
            assertEquals(1, conversationFinished.path("payload").path("requestCount").asInt())
            assertEquals(1, conversationFinished.path("payload").path("toolCallCount").asInt())
            assertEquals(30, conversationFinished.path("payload").path("tokenUsage").path("totalTokens").asInt())

            service.close()
        } finally {
            resetTelemetryConfigStore()
            server.stop(0)
        }
    }

    private fun verifySignature(
        encodedPublicKey: String,
        payload: String,
        signature: String?,
    ): Boolean {
        if (signature.isNullOrBlank()) return false
        val keyFactory = KeyFactory.getInstance("Ed25519")
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(base64Decode(encodedPublicKey)))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(payload.toByteArray(StandardCharsets.UTF_8))
        return verifier.verify(base64Decode(signature))
    }

    private fun resetTelemetryConfigStore() {
        listOf(
            TelemetryStorageKeys.USER_ID,
            TelemetryStorageKeys.DEVICE_ID,
            TelemetryStorageKeys.INSTALLATION_ID,
            TelemetryStorageKeys.PRIVATE_KEY,
            TelemetryStorageKeys.PUBLIC_KEY,
        ).forEach(ConfigStore::rm)
    }
}
