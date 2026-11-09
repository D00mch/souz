package ru.souz.backend.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.http.*
import io.ktor.websocket.Frame
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.souz.backend.client.LiveClientToolDispatcher
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.ImageSource
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.test.*
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.attachments.*
import ru.souz.backend.client.ClientToolOutcome
import ru.souz.backend.client.ToolResultFrame
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.*
import ru.souz.llms.runtime.DEFAULT_IMAGE_MODEL
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults
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
                providerClients = ProviderHttpClients(http, http), sandboxFactory = { config ->
                    DefaultRuntimeSandboxFactory(config, RuntimeSandboxModeResolver { "docker" }, dockerHostRoot = root,
                        dockerSandboxCreator = { scope, host, image, name -> DockerRuntimeSandbox(scope, host, image, name, autoStart = false) })
                }) {
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                original = backend.attachmentStorage.save(user, "original", "original.png", png.inputStream()).id.toString()
                val fs = backend.sandboxFileSystem(user)
                outputPath = fs.resolvePath("sandbox.png").path
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user); jsonBody("""{"defaultModel":"${E2E_MODEL.alias}","enabledTools":["GenerateAttachmentImage","GenerateImage","AttachFileToReply"]}""")
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
                val execution = backend.dependencies.executionService.executeChatTurn(user, UUID.fromString(chat), "hello").execution
                backend.awaitExecution(execution.id)
                meta = ToolInvocationMeta(user, chat, execution.id.toString())
            }
            val service = ImageGeneration(backend.attachmentStorage, backend.dataSource)
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
                    assertFailsWith<ru.souz.llms.runtime.InvalidImageException> {
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
                val other = async { dispatcher.call(owned, "other", emptyMap(), java.time.Duration.ofSeconds(5)) }
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
    fun `attachment preparation times out and keeps capacity while database work is blocked`() = backendE2eTest(
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
        val deadline = java.time.Duration.ofSeconds(45)
        withPublicSocket(chat) { first ->
            withPublicSocket(peer) { second ->
                withTableLocked("stored_attachments", "access exclusive") {
                    val calls = List(8) {
                        val call = async { dispatcher.call(chatRecord, "document", emptyMap(), deadline) }
                        val event = readJson(first)
                        first.send(Frame.Text(toolResultFrame(event, "succeeded", link)))
                        event["payload"]["toolCallId"].asText() to call
                    }
                    awaitLockWait("stored_attachments", count = calls.size)

                    suspend fun peerResult(result: Map<String, Any>): ClientToolOutcome = withTimeout(5.seconds) {
                        val call = async { dispatcher.call(peerRecord, "peer", emptyMap(), deadline) }
                        val event = readJson(second)
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
                        for ((_, call) in calls) assertEquals("client_tool_failed", call.await().error?.code)
                    }
                    assertEquals("client_tool_failed", peerResult(link).error?.code)
                }
            }
        }
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
                mapOf("type" to "image", "mimeType" to "image/png", "data" to base64))))
            socket.send(Frame.Text(result))
            assertEquals("accepted", readJson(socket)["status"].asText())
            assertEquals("thread.completed", readJson(socket)["type"].asText())
            socket.send(Frame.Text(result))
            assertEquals("tool_call_not_found", readJson(socket)["error"]["code"].asText())
        }
        val state = backend.agentStateRepository.get(user, UUID.fromString(chat))!!
        val saved = state.history.flatMap { it.files }.distinctBy { it.fileId }.single()
        assertContentEquals(png, backend.attachmentStorage.read(user, UUID.fromString(saved.fileId)))
        val toolContent = state.history.single { it.name == "RunSkillCommand" }.content
        val links = restJsonMapper.readTree(toolContent)["content"]
        assertEquals(8, links.size())
        assertEquals("text/plain", links[0]["mimeType"].asText(), "Owned metadata overrides the image hint")
        assertEquals("Report 0", links[0]["name"].asText())
        assertTrue(links.take(7).none { it.has("attachment") }, "Client metadata cannot create file references")
        assertFalse(base64 in restJsonMapper.writeValueAsString(state.history))
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
