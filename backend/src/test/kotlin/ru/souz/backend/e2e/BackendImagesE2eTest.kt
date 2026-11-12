package ru.souz.backend.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.http.*
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import ru.souz.backend.attachments.*
import ru.souz.backend.client.ClientToolOutcome
import ru.souz.backend.client.LiveClientToolDispatcher
import ru.souz.backend.client.ToolResultFrame
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.llms.*
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.runtime.DEFAULT_IMAGE_MODEL
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.ImageSource
import ru.souz.llms.runtime.InvalidImageException
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver
import ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox

class BackendImagesE2eTest {
    private val png = attachmentPng
    private val base64 = Base64.getEncoder().encodeToString(png)
    private val settings = object : BackendConfigSource {
        override fun env(key: String) = "image-test-key".takeIf { key == "OPENAI_API_KEY" }
        override fun property(key: String): String? = null
    }

    @Test
    fun `output quotas reject before payment and roll back receipts for a later retry`() {
        for (ownerLimit in listOf(false, true)) for (byteLimit in listOf(false, true)) {
            val base = AttachmentMinio.config()
            val bound = 2 * png.size
            val config = AttachmentStorageConfig(base.endpoint, base.bucket, base.accessKey, base.secretKey,
                maxFileBytes = bound,
                maxOwnerBytes = if (ownerLimit && byteLimit) 3L * png.size else Long.MAX_VALUE,
                maxTotalBytes = if (!ownerLimit && byteLimit) 3L * png.size else Long.MAX_VALUE,
                maxOwnerFiles = if (ownerLimit && !byteLimit) 1 else 100,
                maxTotalFiles = if (!ownerLimit && !byteLimit) 1 else 100)
            val clock = AttachmentClock(Instant.now())
            backendE2eTest("image_output_quota", attachments = config, clock = clock) {
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user); jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}"}""")
                }
                val execution = backend.dependencies.executionService.executeChatTurn(user, UUID.fromString(chat), "hello").execution
                backend.awaitExecution(execution.id)
                val meta = ToolInvocationMeta(user, chat, execution.id.toString())
                val storage = backend.attachmentStorage
                val service = backend.imageGeneration
                storage.save(if (ownerLimit) user else "another-owner", "full", "full.png", (png + png).inputStream())
                var calls = 0
                val gateway = ImageGenerationGateway {
                    calls++
                    GeneratedImage(ImageSource.read(png), "OPENAI", DEFAULT_IMAGE_MODEL)
                }
                assertEquals("attachment_quota_exceeded", assertFailsWith<AttachmentException> {
                    service.generate(meta, "draw", emptyList(), "route", gateway)
                }.code)
                assertEquals(0, calls)
                clock.now = clock.now.plus(ATTACHMENT_RETENTION)
                assertEquals(1, storage.cleanup(), "Quota failure must leave no pending output row")
                val file = service.generate(meta, "draw", emptyList(), "route", gateway)
                assertEquals(1, calls, "Quota failure must leave no paid receipt")
                assertEquals("image.png", file.name)
                assertContentEquals(png, storage.read(user, UUID.fromString(file.fileId)))
                assertEquals(file, service.generate(meta, "draw", emptyList(), "route", gateway))
                assertEquals(1, calls, "A cached output needs no new storage allowance")
                if (byteLimit) {
                    service.generate(meta, "another", emptyList(), "route", gateway)
                    assertEquals(2, calls, "Completion must shrink maximum reserved bytes to actual output size")
                }
            }
        }
    }

    @Test fun `generation editing and explicit reply selection share the configured image route`() {
        var phase = 0
        var original = ""
        var generated = ""
        var edited = ""
        var outputPath = ""
        var providerCalls = 0
        val http = HttpClient(MockEngine { request ->
            providerCalls++
            if (request.url.encodedPath.endsWith("/edits")) {
                val multipart = request.body.toByteArray().toString(Charsets.ISO_8859_1)
                assertTrue(DEFAULT_IMAGE_MODEL in multipart && "source-1.png" in multipart)
                assertTrue(png.toString(Charsets.ISO_8859_1) in multipart)
            } else assertEquals(DEFAULT_IMAGE_MODEL, restJsonMapper.readTree(request.body.toByteArray())["model"].asText())
            respond("""{"data":[{"b64_json":"$base64"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        val llm = E2eLlmApi { request ->
            fun skill(name: String, arguments: Map<String, Any>) = toolCallReply(request, "RunSkillCommand",
                mapOf("skillId" to name, "arguments" to arguments))
            fun generate(prompt: String, sources: List<String> = emptyList()) =
                skill("GenerateAttachmentImage", mapOf("prompt" to prompt, "fileIds" to sources))
            when (phase++) {
                0 -> generate("draw")
                1 -> {
                    generated = request.messages.last().files.single().fileId
                    assertTrue(request.messages.last().replyFiles.isEmpty())
                    generate("draw")
                }
                2 -> {
                    assertEquals(generated, request.messages.last().files.single().fileId)
                    generate("combine", listOf(original, generated))
                }
                3 -> {
                    edited = request.messages.last().files.single().fileId
                    assertNotEquals(generated, edited)
                    skill("AttachFileToReply", mapOf("fileId" to edited, "presentation" to "image"))
                }
                4 -> skill("GenerateImage", mapOf("prompt" to "sandbox", "outputPath" to outputPath))
                5 -> reply(request, "Image ready")
                else -> error("Unexpected model call at phase ${phase - 1}")
            }
        }
        val root = Files.createTempDirectory("image-workflow-").toRealPath()
        try {
            backendE2eTest("images", llm = llm, settingsSource = settings, attachments = AttachmentMinio.config(),
                providerClients = ProviderHttpClients(http, http, mcp = http), sandboxFactory = { config ->
                    DefaultRuntimeSandboxFactory(config, RuntimeSandboxModeResolver { "docker" }, dockerHostRoot = root,
                        dockerSandboxCreator = { scope, host, image, name -> DockerRuntimeSandbox(scope, host, image, name, autoStart = false) })
                }) {
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                original = backend.attachmentStorage.save(user, "original", "original.png", png.inputStream()).id.toString()
                val fs = backend.sandboxFileSystem(user)
                outputPath = fs.resolvePath("sandbox.png").path
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user); jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","enabledTools":["GenerateAttachmentImage","GenerateImage","AttachFileToReply"]}""")
                }
                suspend fun turn(text: String) {
                    val execution = backend.dependencies.executionService.executeChatTurn(user, UUID.fromString(chat), text).execution
                    backend.awaitExecution(execution.id)
                    assertEquals(AgentExecutionStatus.COMPLETED, backend.executionRepository.get(user, execution.id)!!.status)
                }
                turn("create and edit")
                assertEquals(3, providerCalls)
                assertContentEquals(png, backend.attachmentStorage.read(user, UUID.fromString(edited)))
                val answer = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"].last()
                assertEquals(edited, answer["attachments"].single()["fileId"].asText())
                assertEquals(FilePresentation.image.name, answer["attachments"].single()["presentation"].asText())
                assertContentEquals(png, fs.readBytes(fs.resolvePath(outputPath)))
                assertEquals(6, phase)
                val state = restJsonMapper.writeValueAsString(backend.agentStateRepository.get(user, UUID.fromString(chat))!!.history)
                assertFalse(base64 in state)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `paid receipts survive restart and invalid editing sources never claim an attempt`() {
        val schema = newPostgresSchema("image_receipts")
        val config = AttachmentMinio.config()
        val user = UUID.randomUUID().toString()
        lateinit var meta: ToolInvocationMeta
        lateinit var saved: FileContent
        var calls = 0
        val gateway = ImageGenerationGateway { input ->
            calls++
            check(input.prompt != "uncertain") { "provider disconnected" }
            GeneratedImage(ImageSource.read(png), "OPENAI", DEFAULT_IMAGE_MODEL)
        }
        for (restarted in listOf(false, true)) backendE2eTest("image_receipts", schema = schema, attachments = config) {
            if (!restarted) {
                val chat = createPublicChat(user)
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user); jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}"}""")
                }
                val execution = backend.dependencies.executionService.executeChatTurn(user, UUID.fromString(chat), "hello").execution
                backend.awaitExecution(execution.id)
                meta = ToolInvocationMeta(user, chat, execution.id.toString())
            }
            val service = ImageGeneration(backend.attachmentStorage, backend.executionQuotas)
            val result = service.generate(meta, "draw", emptyList(), "route", gateway)
            if (restarted) assertEquals(saved, result) else saved = result
            if (!restarted) {
                assertFailsWith<IllegalStateException> { service.generate(meta, "uncertain", emptyList(), "route", gateway) }
                val foreign = backend.attachmentStorage.save("foreign", "foreign", "secret.png", png.inputStream())
                val damaged = backend.attachmentStorage.save(user, "damaged", "broken.png", png.copyOf(33).inputStream())
                repeat(2) {
                    assertEquals("attachment_not_found", assertFailsWith<AttachmentException> {
                        service.generate(meta, "edit", listOf(foreign.id.toString()), "route", gateway)
                    }.code)
                    assertFailsWith<InvalidImageException> {
                        service.generate(meta, "edit", listOf(damaged.id.toString()), "route", gateway)
                    }
                }
            }
            assertEquals("image_previously_attempted", assertFailsWith<AttachmentException> {
                service.generate(meta, "uncertain", emptyList(), "route", gateway)
            }.code)
            assertEquals(2, calls)
        }
    }

    @Test
    fun `concurrent paid attempts call the provider once and successful retries reuse storage`() = backendE2eTest(
        "image_concurrent_attempts", attachments = AttachmentMinio.config(maxTotalFiles = 1),
    ) {
        val user = UUID.randomUUID().toString()
        val chat = createPublicChat(user)
        client.patch(BackendHttpRoutes.SETTINGS) {
            trusted(user); jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}"}""")
        }
        val execution = backend.dependencies.executionService.executeChatTurn(user, UUID.fromString(chat), "hello").execution
        backend.awaitExecution(execution.id)
        val meta = ToolInvocationMeta(user, chat, execution.id.toString())
        val service = ImageGeneration(backend.attachmentStorage, backend.executionQuotas)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val gateway = ImageGenerationGateway {
            calls++
            entered.complete(Unit)
            release.await()
            GeneratedImage(ImageSource.read(png), "OPENAI", DEFAULT_IMAGE_MODEL)
        }
        coroutineScope {
            val first = async { service.generate(meta, "draw", emptyList(), "route", gateway) }
            try {
                withTimeout(5.seconds) { entered.await() }
                assertEquals("image_previously_attempted", assertFailsWith<AttachmentException> {
                    service.generate(meta, "draw", emptyList(), "route", gateway)
                }.code)
                assertEquals("attachment_quota_exceeded", assertFailsWith<AttachmentException> {
                    service.generate(meta, "competing output", emptyList(), "route", gateway)
                }.code, "The in-flight paid call must already own the output slot")
                assertEquals(1, calls)
            } finally {
                release.complete(Unit)
            }
            val file = first.await()
            assertEquals(file, service.generate(meta, "draw", emptyList(), "route", gateway))
            assertEquals(1, calls)
        }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `edits share chat image capacity before reads through provider waits and cancellation cleanup`() = backendE2eTest(
        "image_edit_capacity", attachments = AttachmentMinio.config(),
        llmLimits = ru.souz.backend.app.BackendLlmLimits(globalProviderConcurrency = 1),
    ) {
        val user = UUID.randomUUID().toString()
        val chat = createPublicChat(user)
        client.patch(BackendHttpRoutes.SETTINGS) {
            trusted(user); jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}"}""")
        }
        val execution = backend.dependencies.executionService.executeChatTurn(user, UUID.fromString(chat), "hello").execution
        backend.awaitExecution(execution.id)
        val meta = ToolInvocationMeta(user, chat, execution.id.toString())
        val file = backend.attachmentStorage.save(user, "source", "source.png", png.inputStream())
        val quotas = backend.executionQuotas
        val storage = spyk(backend.attachmentStorage)
        var reads = 0
        coEvery { storage.read(any<StoredAttachment>()) } coAnswers {
            reads++
            backend.attachmentStorage.read(firstArg())
        }
        val service = ImageGeneration(storage, quotas)
        runTest {
            val prepared = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val releaseProvider = CompletableDeferred<Unit>()
            val providerEntered = CompletableDeferred<Unit>()
            val provider = async {
                quotas.withProviderPermit(LlmProvider.OPENAI) {
                    providerEntered.complete(Unit)
                    releaseProvider.await()
                }
            }
            providerEntered.await()
            val chatEntered = CompletableDeferred<Unit>()
            val projection = async {
                quotas.withImageProjectionPermit {
                    chatEntered.complete(Unit)
                    awaitCancellation()
                }
            }
            chatEntered.await()
            val gateway = ImageGenerationGateway {
                prepared.complete(Unit)
                try {
                    quotas.withProviderPermit(LlmProvider.OPENAI) { awaitCancellation() }
                } finally {
                    withContext(NonCancellable) {
                        cleanup.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
            val editing = async { service.generate(meta, "edit", listOf(file.id.toString()), "route", gateway) }
            prepared.await()
            val waiting = async {
                service.generate(meta, "next edit", listOf(file.id.toString()), "route", ImageGenerationGateway {
                    GeneratedImage(ImageSource.read(png), "OPENAI", DEFAULT_IMAGE_MODEL)
                })
            }
            val production = async {
                assertFailsWith<AttachmentException> {
                    backend.imageGeneration.generate(meta, "missing", listOf(UUID.randomUUID().toString()), "route", gateway)
                }
            }
            try {
                runCurrent()
                assertEquals(1, reads, "Queued edits must retain only file references")
                assertFalse(waiting.isCompleted)
                assertFalse(production.isCompleted, "Production DI must share chat projection capacity")
                editing.cancel()
                cleanup.await()
                runCurrent()
                assertEquals(1, reads, "Provider cancellation cleanup retains image capacity")
                assertFalse(waiting.isCompleted)
                waiting.cancelAndJoin()
                assertEquals(1, reads, "Cancelling a queued edit does not read sources")
            } finally {
                releaseCleanup.complete(Unit)
                editing.cancelAndJoin()
                projection.cancelAndJoin()
                waiting.cancelAndJoin()
                releaseProvider.complete(Unit)
                provider.await()
            }
            assertEquals("attachment_not_found", production.await().code)
            val output = service.generate(meta, "after cleanup", listOf(file.id.toString()), "route", ImageGenerationGateway {
                GeneratedImage(ImageSource.read(png), "OPENAI", DEFAULT_IMAGE_MODEL)
            })
            assertContentEquals(png, backend.attachmentStorage.read(user, UUID.fromString(output.fileId)))
            assertEquals(2, reads, "Capacity is reusable after cancellation and source failure")
        }
    }

    @Test
    fun `blocked screenshot leaves the same socket free for another result and cancellation`() = backendE2eTest(
        "image_socket_concurrency", attachments = AttachmentMinio.config(),
        llm = E2eLlmApi().apply { requestSkill("screen.capture", emptyMap()) },
    ) {
        val user = UUID.randomUUID().toString()
        val chat = createPublicChat(user, tools = clientToolsJson("screen.capture"))
        val owned = backend.dependencies.publicClientService.requireChat(UUID.fromString(chat), "backend")
        val dispatcher = LiveClientToolDispatcher(backend.clientThreadRegistry, backend.dependencies.eventService)
        withPublicSocket(chat) { socket -> coroutineScope {
            socket.send(Frame.Text(messageFrame(chat, user, "capture")))
            assertEquals("accepted", readJson(socket)["status"].asText())
            readJson(socket)
            val screenshot = readJson(socket)
            withTableLocked("stored_attachments", "share") {
                socket.send(Frame.Text(toolResultFrame(screenshot, "succeeded", mapOf("content" to listOf(
                    mapOf("type" to "image", "mimeType" to "image/png", "data" to base64),
                )))))
                awaitLockWait("stored_attachments")
                val other = async { dispatcher.call(owned, "other", emptyMap(), Duration.ofSeconds(5)) }
                val command = readJson(socket)
                assertEquals("other", command["payload"]["name"].asText())
                socket.send(Frame.Text(toolResultFrame(command, "succeeded", mapOf("answer" to "ready"))))
                withTimeout(5.seconds) {
                    assertEquals(command["payload"]["toolCallId"], readJson(socket)["toolCallId"])
                    assertEquals("succeeded", other.await().status)
                    socket.send(Frame.Text("""{"kind":"thread.cancel","chatId":"$chat","requestId":"cancel","threadId":${screenshot["threadId"]}}"""))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    assertEquals("thread.status", readJson(socket)["type"].asText())
                }
            }
            assertEquals("thread.cancelled", readJson(socket)["type"].asText())
            backend.clientThreadRegistry.awaitRemoved(UUID.fromString(screenshot["threadId"].asText()))
            assertTrue(backend.clientThreadRegistry.isEmpty())
        } }
    }

    @Test
    fun `database deadlines restore preparation capacity and allow disconnect while the lock remains held`() = backendE2eTest(
        "image_socket_limit", attachments = AttachmentMinio.config(),
    ) {
        backend.dataSource.maximumPoolSize = 12
        val user = UUID.randomUUID().toString()
        val chat = createPublicChat(user)
        val peer = createPublicChat(user, requestId = "peer")
        val service = backend.dependencies.publicClientService
        val chatRecord = service.requireChat(UUID.fromString(chat), "backend")
        val peerRecord = service.requireChat(UUID.fromString(peer), "backend")
        val document = backend.attachmentStorage.save(user, "document", "report.txt", "report".byteInputStream())
        val resource = mapOf("type" to "resource_link", "uri" to "attachment:${document.id}")
        val link = mapOf("content" to listOf(resource))
        val dispatcher = LiveClientToolDispatcher(backend.clientThreadRegistry, backend.dependencies.eventService)
        val deadline = Duration.ofSeconds(45)
        withPublicSocket(chat) { first ->
            withPublicSocket(peer) { second ->
                withTimeout(5.seconds) {
                    for ((id, socket) in listOf(chat to first, peer to second)) {
                        socket.send(Frame.Text(historyFrame(id, "ready-$id", "user", "ready")))
                        assertEquals("accepted", readJson(socket)["status"].asText())
                    }
                }
                withTableLocked("stored_attachments", "access exclusive") {
                    val calls = List(8) {
                        val call = async { dispatcher.call(chatRecord, "document", emptyMap(), deadline) }
                        val event = withTimeout(5.seconds) {
                            val incoming = async { readJson(first) }
                            select {
                                incoming.onAwait { it }
                                call.onAwait { fail("Dispatch ended before tool-start publication: ${it.status}") }
                            }
                        }
                        first.send(Frame.Text(toolResultFrame(event, "succeeded", link)))
                        event["payload"]["toolCallId"].asText() to call
                    }
                    awaitLockWait("stored_attachments", count = calls.size)

                    suspend fun peerResult(result: Map<String, Any>): ClientToolOutcome = withTimeout(15.seconds) {
                        val call = async { dispatcher.call(peerRecord, "peer", emptyMap(), deadline) }
                        val event = withTimeout(5.seconds) {
                            val incoming = async { readJson(second) }
                            select {
                                incoming.onAwait { it }
                                call.onAwait { fail("Peer dispatch ended before tool-start publication: ${it.status}") }
                            }
                        }
                        second.send(Frame.Text(toolResultFrame(event, "succeeded", result)))
                        val ack = readJson(second)
                        assertEquals("accepted", ack["status"].asText())
                        assertEquals(event["payload"]["toolCallId"].asText(), ack["toolCallId"].asText())
                        call.await()
                    }

                    assertEquals("client_tool_failed", peerResult(link).error?.code)
                    assertEquals("Tool result content limit exceeded.",
                        peerResult(mapOf("content" to List(1_000) { resource })).error?.message)
                    assertEquals("succeeded", peerResult(mapOf("answer" to "ready")).status)
                    withTimeout(30.seconds) {
                        val acknowledged = List(calls.size) {
                            val ack = readJson(first)
                            assertEquals("accepted", ack["status"].asText())
                            ack["toolCallId"].asText()
                        }.toSet()
                        assertEquals(calls.map { it.first }.toSet(), acknowledged)
                        for ((_, call) in calls) assertEquals("tool_image_unavailable", call.await().error?.code)
                    }
                    // A new preparation is admitted even though the same database lock is still held.
                    assertEquals("tool_image_unavailable", peerResult(link).error?.code)
                    val disconnected = async { dispatcher.call(chatRecord, "disconnect", emptyMap(), deadline) }
                    val event = withTimeout(5.seconds) { readJson(first) }
                    first.send(Frame.Text(toolResultFrame(event, "succeeded", link)))
                    awaitLockWait("stored_attachments")
                    withTimeout(20.seconds) {
                        first.close()
                        assertEquals("failed", disconnected.await().status)
                        eventually("attachment worker exits before the database lock is released", timeout = 15.seconds) {
                            sql { connection -> connection.createStatement().use { statement ->
                                statement.executeQuery("select count(*) from pg_locks where not granted and relation = 'stored_attachments'::regclass")
                                    .use { rows -> rows.next(); rows.getInt(1) == 0 }
                            } }.takeIf { it }?.let { Unit }
                        }
                        eventually("socket teardown joins its result worker while the lock is held") {
                            Unit.takeIf {
                                backend.dependencies.metrics.registry.get("souz.ws.connections.active").gauge().value() == 1.0
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `client image quotas span chats and public owner IDs without blocking owned reuse`() = backendE2eTest(
        "image_client_quotas", attachments = AttachmentMinio.config(maxOwnerFiles = 1, maxTotalFiles = 2),
        llm = E2eLlmApi().apply { requestSkill("screen.capture", emptyMap()) },
    ) {
        suspend fun capture(user: String, requestId: String, content: Map<String, String>): String? {
            val chat = createPublicChat(user, requestId = requestId, tools = clientToolsJson("screen.capture"))
            withPublicSocket(chat) { socket ->
                socket.send(Frame.Text(messageFrame(chat, user, "capture")))
                assertEquals("accepted", readJson(socket)["status"].asText())
                readJson(socket)
                val started = readJson(socket)
                socket.send(Frame.Text(toolResultFrame(started, "succeeded", mapOf("content" to listOf(content)))))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals("thread.completed", readJson(socket)["type"].asText())
            }
            val state = backend.agentStateRepository.get(user, UUID.fromString(chat))!!
            assertFalse(base64 in restJsonMapper.writeValueAsString(state.history))
            val result = state.history.single { it.name == "RunSkillCommand" }
            if (result.files.isEmpty()) assertTrue("tool_image_unavailable" in result.content)
            return result.files.singleOrNull()?.fileId
        }
        val image = mapOf("type" to "image", "mimeType" to "image/png", "data" to base64)
        val owner = UUID.randomUUID().toString()
        val saved = assertNotNull(capture(owner, "first", image))
        assertNull(capture(owner, "same-owner-new-chat", image))
        assertNotNull(capture(UUID.randomUUID().toString(), "second-owner", image))
        assertNull(capture(UUID.randomUUID().toString(), "fresh-owner", image))
        assertEquals(saved, capture(owner, "reuse", mapOf("type" to "resource_link", "uri" to "attachment:$saved")))
    }

    @Test
    fun `client screenshots and document links persist as trusted references`() = backendE2eTest(
        "image_client", attachments = AttachmentMinio.config(),
        llm = E2eLlmApi().apply { requestSkill("screen.capture", emptyMap()) },
    ) {
        val user = UUID.randomUUID().toString()
        val chat = createPublicChat(user, tools = clientToolsJson("screen.capture"))
        val document = backend.attachmentStorage.save(user, "report", "report.txt", "A report".byteInputStream())
        val forged = FileContent(UUID.randomUUID().toString(), "forged.png", "image/png", png.size.toLong(), FilePresentation.image)
        val documentLinks = List(6) { mapOf("type" to "resource_link", "uri" to "attachment:${document.id}",
            "name" to "Report $it", "mimeType" to "image/png", "attachment" to forged) }
        withPublicSocket(chat) { socket ->
            socket.send(Frame.Text(messageFrame(chat, user, "capture", text = "inspect screen")))
            assertEquals("accepted", readJson(socket)["status"].asText())
            readJson(socket)
            val started = readJson(socket)
            assertEquals("screen.capture", started["payload"]["name"].asText())
            val result = toolResultFrame(started, "succeeded", mapOf("content" to documentLinks + listOf(
                mapOf("type" to "resource_link", "uri" to "https://example.invalid/report.txt", "attachment" to forged),
                mapOf("type" to "resource", "resource" to mapOf("uri" to "file:///screen.png", "blob" to base64)),
                mapOf("type" to "resource", "resource" to mapOf("uri" to "screen://capture/0", "mimeType" to "IMAGE/PNG", "blob" to base64)),
                mapOf("type" to "image", "mimeType" to "Image/PnG", "data" to base64))))
            socket.send(Frame.Text(result))
            assertEquals("accepted", readJson(socket)["status"].asText())
            assertEquals("thread.completed", readJson(socket)["type"].asText())
            socket.send(Frame.Text(result))
            assertEquals("tool_call_not_found", readJson(socket)["error"]["code"].asText())
        }
        val state = backend.agentStateRepository.get(user, UUID.fromString(chat))!!
        val saved = state.history.flatMap { it.files }.distinctBy { it.fileId }
        assertEquals(3, saved.size)
        for (image in saved) {
            assertEquals("image/png", image.mimeType)
            assertContentEquals(png, backend.attachmentStorage.read(user, UUID.fromString(image.fileId)))
        }
        val toolContent = state.history.single { it.name == "RunSkillCommand" }.content
        val links = restJsonMapper.readTree(toolContent)["content"]
        assertEquals(10, links.size())
        assertEquals("text/plain", links[0]["mimeType"].asText(), "Owned metadata overrides the image hint")
        assertEquals("Report 0", links[0]["name"].asText())
        assertTrue(links.take(7).none { it.has("attachment") }, "Client metadata cannot create file references")
        assertFalse(base64 in restJsonMapper.writeValueAsString(state.history))
        assertFalse(base64 in restJsonMapper.writeValueAsString(llm.requests.flatMap { it.messages }))
        assertEquals(saved.map { it.fileId }, llm.requests.last().messages.single { it.name == "RunSkillCommand" }.files.map { it.fileId })
    }

    @Test fun `cancelled screenshot preparation releases the reserved client tool`() = backendE2eTest(
        "image_cancelled", attachments = AttachmentMinio.config(),
        llm = E2eLlmApi().apply { requestSkill("screen.capture", emptyMap()) },
    ) {
        val user = UUID.randomUUID().toString()
        val chat = createPublicChat(user, tools = clientToolsJson("screen.capture"))
        val service = backend.dependencies.publicClientService
        val ownedChat = service.requireChat(UUID.fromString(chat), "backend")
        withPublicSocket(chat) { socket ->
            socket.send(Frame.Text(messageFrame(chat, user, "capture")))
            assertEquals("accepted", readJson(socket)["status"].asText())
            readJson(socket)
            val started = readJson(socket)
            assertEquals("screen.capture", started["payload"]["name"].asText())
            val frame = ToolResultFrame(
                "tool.result", chat, started["threadId"].asText(), started["payload"]["toolCallId"].asText(),
                "succeeded", restJsonMapper.valueToTree(mapOf("content" to listOf(
                    mapOf("type" to "image", "mimeType" to "image/png", "data" to base64),
                ))),
            )
            lateinit var receiver: Job
            withTableLocked("stored_attachments", "share") {
                receiver = launch {
                    service.handleToolResult(ownedChat, frame).prepare()
                    fail("Cancelled preparation must not return an ACK")
                }
                awaitLockWait("stored_attachments")
                receiver.cancel()
            }
            assertTrue(receiver.isCancelled)
            withTimeout(5.seconds) {
                assertEquals("thread.completed", readJson(socket)["type"].asText())
                backend.clientThreadRegistry.awaitRemoved(UUID.fromString(frame.threadId))
            }
            val result = llm.requests.last().messages.single { it.name == "RunSkillCommand" }
            assertTrue("client_tool_failed" in result.content)
            assertTrue(result.files.isEmpty())
            assertTrue(backend.clientThreadRegistry.isEmpty())
            socket.send(Frame.Text(restJsonMapper.writeValueAsString(frame)))
            assertEquals("tool_call_not_found", readJson(socket)["error"]["code"].asText())
        }
    }

}
