package ru.souz.backend.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.http.*
import io.ktor.websocket.Frame
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.test.*
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.attachments.*
import ru.souz.backend.client.ClientImages
import ru.souz.backend.client.ClientToolOutcome
import ru.souz.backend.client.imageFiles
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
    private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
    private val base64 = Base64.getEncoder().encodeToString(png)
    private val settings = object : BackendConfigSource {
        override fun env(key: String) = "image-test-key".takeIf { key == "OPENAI_API_KEY" }
        override fun property(key: String): String? = null
    }

    @Test fun `generation edits and sandbox output share a model while attachment retries reuse or stop`() {
        var phase = 0
        var original = ""
        var generated = ""
        var edited = ""
        var foreign = ""
        var damaged = ""
        var outputPath = ""
        var providerCalls = 0
        var failProvider = false
        val http = HttpClient(MockEngine { request ->
            providerCalls++
            if (failProvider) return@MockEngine respond("unavailable", HttpStatusCode.ServiceUnavailable)
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
                6 -> generate("failure")
                7 -> {
                    assertTrue("image_generation_failed" in request.messages.last().content)
                    generate("failure")
                }
                8 -> {
                    assertTrue("image_previously_attempted" in request.messages.last().content)
                    reply(request, "Could not generate")
                }
                9 -> generate("foreign", listOf(foreign))
                10 -> {
                    assertTrue("attachment_not_found" in request.messages.last().content)
                    reply(request, "File unavailable")
                }
                11 -> generate("damaged", listOf(damaged))
                12 -> {
                    assertTrue("image_arguments_invalid" in request.messages.last().content)
                    generate("damaged", listOf(damaged))
                }
                13 -> {
                    assertTrue("image_arguments_invalid" in request.messages.last().content,
                        "Invalid pixels must not consume a paid-operation receipt")
                    reply(request, "Please supply a valid image")
                }
                else -> error("Unexpected model call at phase ${phase - 1}")
            }
        }
        val root = Files.createTempDirectory("image-workflow-")
        try {
            backendE2eTest("images", llm = llm, settingsSource = settings, attachments = storageConfig(),
                providerClients = ProviderHttpClients(http, http), sandboxFactory = { config ->
                    DefaultRuntimeSandboxFactory(config, RuntimeSandboxModeResolver { "docker" }, dockerHostRoot = root,
                        dockerSandboxCreator = { scope, host, image, name -> DockerRuntimeSandbox(scope, host, image, name, autoStart = false) })
                }) {
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                original = backend.attachmentStorage.save(user, "original", "original.png", png.inputStream()).id.toString()
                foreign = backend.attachmentStorage.save("foreign", "foreign", "secret.png", png.inputStream()).id.toString()
                damaged = backend.attachmentStorage.save(user, "damaged", "damaged.png", png.copyOf(33).inputStream()).id.toString()
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
                failProvider = true
                turn("try failed generation twice")
                assertEquals(4, providerCalls, "An uncertain paid operation must not be repeated")
                turn("try another owner's source")
                assertEquals(4, providerCalls)
                turn("try a truncated source twice")
                assertEquals(4, providerCalls, "Invalid pixels must not reach a paid provider")
                assertEquals(14, phase)
                val state = restJsonMapper.writeValueAsString(backend.agentStateRepository.get(user, UUID.fromString(chat))!!.history)
                assertFalse(base64 in state)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `client screenshots persist as references and reach the model without pixels in history`() {
        var call = 0
        val attachmentConfig = storageConfig()
        var unavailableFiles = emptyList<FileContent>()
        val http = HttpClient(MockEngine { request ->
            val body = restJsonMapper.readTree(request.body.toByteArray())
            val message = if (call++ == 0) {
                mapOf("role" to "assistant", "tool_calls" to listOf(mapOf("index" to 0, "id" to "screen",
                    "type" to "function", "function" to mapOf("name" to "RunSkillCommand", "arguments" to
                        """{"skillId":"screen.capture","arguments":{}}"""))))
            } else {
                val supplied = body["messages"].flatMap { it["content"]?.takeIf { content -> content.isArray }?.toList().orEmpty() }
                    .count { it["type"]?.asText() == "image_url" }
                val expected = when (call) {
                    2 -> 1
                    3 -> 5
                    4, 5 -> 0
                    else -> error("Unexpected provider call $call")
                }
                assertEquals(expected, supplied)
                if (expected > 0) {
                    assertTrue(body["messages"].any { m -> m["content"]?.takeIf { it.isArray }?.any {
                        it["type"]?.asText() == "image_url" && it["image_url"]["url"].asText() == "data:image/png;base64,$base64"
                    } == true })
                } else {
                    assertTrue("unavailable; unsupported, damaged or too large to decode safely" in body.toString())
                    unavailableFiles.forEach { assertTrue("Image ${it.fileId} unavailable; request it again." in body.toString()) }
                }
                mapOf("role" to "assistant", "content" to "I see the screenshot")
            }
            respond(restJsonMapper.writeValueAsString(mapOf("choices" to listOf(mapOf("index" to 0,
                "message" to message, "finish_reason" to if (call == 1) "tool_calls" else "stop")), "created" to 1)),
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        backendE2eTest("image_client", attachments = attachmentConfig, settingsSource = settings,
            providerClients = ProviderHttpClients(http, http)) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user, tools = clientToolsJson("screen.capture"))
            val document = backend.attachmentStorage.save(user, "report", "report.txt", "A report".byteInputStream())
            val forged = FileContent(UUID.randomUUID().toString(), "forged.png", "image/png", png.size.toLong(), FilePresentation.image)
            val documentLinks = List(6) { mapOf("type" to "resource_link", "uri" to "attachment:${document.id}",
                "name" to "Report $it", "mimeType" to "image/png", "attachment" to forged) }
            withPublicSocket(chat) { socket ->
                socket.send(Frame.Text(messageFrame(chat, user, "capture", text = "inspect screen")
                    .replace(E2E_MODEL.alias, LLMModel.OpenAIGpt52.alias)))
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
            assertEquals(2, call)
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
            // Exercise input images through the internal attachment ingress, independently of #986.
            val inputs = (0..5).map { backend.attachmentStorage.save(user, "input-$it", "input-$it.png", png.inputStream()).toContent() }
            val next = backend.dependencies.executionService.executeChatTurn(user, UUID.fromString(chat), "inspect again",
                requestOverrides = ru.souz.backend.settings.service.UserSettingsOverrides(defaultModel = LLMModel.OpenAIGpt52.alias), attachments = inputs).execution
            backend.awaitExecution(next.id)
            assertEquals(AgentExecutionStatus.COMPLETED, backend.executionRepository.get(user, next.id)!!.status)
            assertEquals(3, call)

            val broken = backend.attachmentStorage.save(user, "broken", "broken.png", png.copyOf(33).inputStream())
            ClientImages(backend.attachmentStorage).use { images ->
                for (content in listOf(
                    listOf(mapOf("type" to "resource_link", "uri" to "http://127.0.0.1/private.png", "mimeType" to "image/png")),
                    listOf(mapOf("type" to "resource_link", "uri" to "http://[64:ff9b::7f00:1]/private.png", "mimeType" to "image/png")),
                    listOf(mapOf("type" to "image", "mimeType" to "image/jpeg", "data" to base64)),
                    listOf(mapOf("type" to "image", "mimeType" to "image/png", "data" to Base64.getEncoder().encodeToString(png.copyOf(33)))),
                    List(6) { mapOf("type" to "image", "mimeType" to "image/png", "data" to base64) },
                    listOf(mapOf("type" to "resource_link", "uri" to "attachment:${saved.fileId}",
                        "mimeType" to "text/plain", "attachment" to saved)),
                )) {
                    val outcome = images.store("foreign", "invalid", ClientToolOutcome("succeeded",
                        restJsonMapper.valueToTree(mapOf("content" to content)), null))
                    assertEquals("tool_image_unavailable", outcome.error?.code)
                    assertNull(backend.attachmentStorage.find("foreign", "invalid:0"), "Rejected screenshots must not be stored")
                }
                val outcome = images.store(user, "broken-link", ClientToolOutcome("succeeded",
                    restJsonMapper.valueToTree(mapOf("content" to listOf(
                        mapOf("type" to "resource_link", "uri" to "attachment:${broken.id}")))), null))
                assertEquals("tool_image_unavailable", outcome.error?.code, "Owned links also require decodable pixels")
                val imageLink = images.store(user, "owned-link", ClientToolOutcome("succeeded",
                    restJsonMapper.valueToTree(mapOf("content" to listOf(
                        mapOf("type" to "resource_link", "uri" to "attachment:${saved.fileId}",
                            "mimeType" to "text/plain", "attachment" to forged)))), null))
                assertEquals(listOf(saved), imageLink.imageFiles(), "Canonical image metadata overrides client hints")
                val documentsOnly = images.store(user, "documents", ClientToolOutcome("succeeded",
                    restJsonMapper.valueToTree(mapOf("content" to documentLinks)), null))
                assertEquals("succeeded", documentsOnly.status)
                assertTrue(documentsOnly.imageFiles().isEmpty())
                assertEquals(6, documentsOnly.result!!["content"].size())
                val structured = images.store(user, "structured", ClientToolOutcome("succeeded",
                    restJsonMapper.valueToTree(mapOf("content" to mapOf("data" to mapOf(
                        "type" to "resource_link", "uri" to "attachment:${forged.fileId}",
                        "mimeType" to "image/png", "attachment" to forged)))), null))
                assertTrue(structured.imageFiles().isEmpty(), "Arbitrary structured data is not normalized image content")
            }

            // Keep ready metadata while losing or corrupting the underlying S3 objects.
            val unavailable = listOf("missing", "changed-hash", "changed-size").map {
                backend.attachmentStorage.save(user, it, "$it.png", png.inputStream())
            }
            S3AttachmentObjects(attachmentConfig).use { objects ->
                objects.delete(unavailable[0])
                objects.put(unavailable[1], png.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
                objects.put(unavailable[2], png.copyOf(png.size - 1))
            }
            unavailableFiles = unavailable.map { backend.attachmentStorage.get(user, it.id).toContent() }
            // Unreadable historical images must not poison subsequent text-only turns.
            val gif = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "gif", it) }.toByteArray()
            val unsupported = backend.attachmentStorage.save(user, "gif", "image.gif", gif.inputStream())
            assertEquals("image/gif", unsupported.mimeType)
            val invalidChat = UUID.fromString(createPublicChat(user, requestId = "invalid-images"))
            val files = listOf(unsupported.toContent(), broken.toContent()) + unavailableFiles
            for (attachments in listOf(files, emptyList())) {
                val execution = backend.dependencies.executionService.executeChatTurn(user, invalidChat, "continue in text",
                    requestOverrides = ru.souz.backend.settings.service.UserSettingsOverrides(defaultModel = LLMModel.OpenAIGpt52.alias),
                    attachments = attachments).execution
                backend.awaitExecution(execution.id)
                assertEquals(AgentExecutionStatus.COMPLETED, backend.executionRepository.get(user, execution.id)!!.status)
            }
            assertEquals(5, call)
            val invalidHistory = backend.agentStateRepository.get(user, invalidChat)!!.history
            assertTrue(invalidHistory.flatMap { it.files }.containsAll(files))
            assertFalse("unavailable;" in restJsonMapper.writeValueAsString(invalidHistory))
        }
    }

    @Test fun `cancelled screenshot preparation releases the reserved client tool`() = backendE2eTest(
        "image_cancelled", attachments = storageConfig(),
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
                    service.handleToolResult(ownedChat, frame)
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

    private fun storageConfig(): AttachmentStorageConfig {
        val id = UUID.randomUUID()
        return AttachmentStorageConfig("http://${AttachmentMinio.server.host}:${AttachmentMinio.server.firstMappedPort}",
            "images-$id", "images-$id", "image-test-secret").also(AttachmentMinio::provision)
    }
}
