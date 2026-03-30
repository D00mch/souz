package ru.souz.telemetry

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProvider
import ru.souz.llms.GigaResponse
import ru.souz.service.telemetry.TelemetryConversationEndReason
import ru.souz.service.telemetry.TelemetryConversationStartReason
import ru.souz.service.telemetry.TelemetryCryptoService
import ru.souz.service.telemetry.TelemetryEventType
import ru.souz.service.telemetry.TelemetryOutboxRepository
import ru.souz.service.telemetry.TelemetryRequestSource
import ru.souz.service.telemetry.TelemetryRequestStatus
import ru.souz.service.telemetry.TelemetryRuntimeConfig
import ru.souz.service.telemetry.TelemetryService
import ru.souz.service.telemetry.TelemetryStorageKeys
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
    private val cryptoService = TelemetryCryptoService()
    private val settingsProvider = mockk<SettingsProvider>(relaxed = true).apply {
        every { regionProfile } returns "ru"
        every { regionProfile = any() } just runs
    }

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
                cryptoService.sha256Base64(body),
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
                cryptoService.sha256Base64(body),
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
                cryptoService = cryptoService,
                settingsProvider = settingsProvider,
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
            withContext(service.requestContextElement(requestContext)) {
                service.recordToolExecution(
                    functionName = "tool.open_file",
                    functionArguments = mapOf("path" to "/tmp/demo.txt"),
                    toolCategory = ToolCategory.FILES,
                    durationMs = 17,
                    success = true,
                    errorMessage = null,
                )
            }
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

    @Test
    fun `overlapping requests keep tool counts isolated and sanitize errors`() = runTest {
        resetTelemetryConfigStore()

        try {
            val repo = TelemetryOutboxRepository(
                databasePath = Files.createTempDirectory("telemetry-overlap-test").resolve("telemetry.db"),
                objectMapper = objectMapper,
            )
            val service = TelemetryService(
                outboxRepository = repo,
                cryptoService = cryptoService,
                settingsProvider = settingsProvider,
                runtimeConfig = TelemetryRuntimeConfig(baseUrl = ""),
            )

            val conversationId = service.startConversation(TelemetryConversationStartReason.CHAT_UI)
            val requestA = service.beginRequest(
                conversationId = conversationId,
                source = TelemetryRequestSource.CHAT_UI,
                model = "gpt-a",
                provider = "OPENAI",
                inputLengthChars = 10,
                attachedFilesCount = 0,
            )
            val requestB = service.beginRequest(
                conversationId = conversationId,
                source = TelemetryRequestSource.CHAT_UI,
                model = "gpt-b",
                provider = "OPENAI",
                inputLengthChars = 20,
                attachedFilesCount = 0,
            )

            withContext(service.requestContextElement(requestA)) {
                service.recordToolExecution(
                    functionName = "tool.read_file",
                    functionArguments = mapOf("path" to "/Users/duxx/secret.txt"),
                    toolCategory = ToolCategory.FILES,
                    durationMs = 10,
                    success = false,
                    errorMessage = "java.io.FileNotFoundException: /Users/duxx/secret.txt",
                )
            }
            withContext(service.requestContextElement(requestB)) {
                service.recordToolExecution(
                    functionName = "tool.list_files",
                    functionArguments = mapOf("path" to "/tmp"),
                    toolCategory = ToolCategory.FILES,
                    durationMs = 5,
                    success = true,
                    errorMessage = null,
                )
            }

            service.finishRequest(
                context = requestB,
                status = TelemetryRequestStatus.SUCCESS,
                responseLengthChars = 42,
                errorMessage = null,
                requestTokenUsage = GigaResponse.Usage(1, 2, 3, 0),
                sessionTokenUsage = GigaResponse.Usage(7, 8, 15, 0),
            )
            service.finishRequest(
                context = requestA,
                status = TelemetryRequestStatus.ERROR,
                responseLengthChars = null,
                errorMessage = "java.io.FileNotFoundException: /Users/duxx/secret.txt",
                requestTokenUsage = GigaResponse.Usage(4, 5, 9, 0),
                sessionTokenUsage = GigaResponse.Usage(7, 8, 15, 0),
            )
            service.finishConversation(conversationId, TelemetryConversationEndReason.CLEAR_CONTEXT)

            val events = repo.loadReadyBatch(limit = 10, nowMs = Long.MAX_VALUE).map { it.event }
            val requestEventsById = events
                .filter { it.type == TelemetryEventType.REQUEST_FINISHED.wireName }
                .associateBy { it.requestId }

            val requestAPayload = assertNotNull(requestEventsById[requestA.requestId]).payload
            val requestBPayload = assertNotNull(requestEventsById[requestB.requestId]).payload
            assertEquals(1, requestAPayload["toolCallCount"])
            assertEquals(1, requestBPayload["toolCallCount"])
            assertEquals("java.io.FileNotFoundException", requestAPayload["errorMessage"])

            val toolErrorPayload = events
                .first { it.type == TelemetryEventType.TOOL_EXECUTED.wireName && it.requestId == requestA.requestId }
                .payload
            assertEquals("java.io.FileNotFoundException", toolErrorPayload["errorMessage"])

            val conversationFinished = events.last { it.type == TelemetryEventType.CONVERSATION_FINISHED.wireName }
            assertEquals(2, conversationFinished.payload["requestCount"])
            assertEquals(2, conversationFinished.payload["toolCallCount"])
            assertEquals(
                mapOf("promptTokens" to 5, "completionTokens" to 7, "totalTokens" to 12, "precachedTokens" to 0),
                conversationFinished.payload["tokenUsage"],
            )

            service.close()
        } finally {
            resetTelemetryConfigStore()
        }
    }

    private fun verifySignature(
        encodedPublicKey: String,
        payload: String,
        signature: String?,
    ): Boolean {
        if (signature.isNullOrBlank()) return false
        val keyFactory = KeyFactory.getInstance("Ed25519")
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(cryptoService.decodeBase64(encodedPublicKey)))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(payload.toByteArray(StandardCharsets.UTF_8))
        return verifier.verify(cryptoService.decodeBase64(signature))
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
