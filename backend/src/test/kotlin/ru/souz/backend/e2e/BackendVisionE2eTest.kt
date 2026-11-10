package ru.souz.backend.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.patch
import io.ktor.http.*
import io.ktor.websocket.Frame
import java.util.Base64
import java.util.UUID
import kotlin.test.*
import ru.souz.backend.attachments.*
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.llm.withNamedLlmConfig
import ru.souz.llms.*
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults

class BackendVisionE2eTest {
    @Test
    fun `client and user images plus ViewImage use independent vision while chat remains text only`() = withNamedLlmConfig(
        json = """{
          "openaiConnections":{"vision":{"baseUrl":"https://vision.test/v1","apiKeyEnv":"VISION_KEY"}},
          "models":{"vision-model":{"connection":"vision","modelId":"upstream-vision","parameters":{"reasoning":{"effort":"low"}}}}
        }""",
        environment = mapOf("SOUZ_ATTACHMENTS_VISION_MODEL" to "vision-model", "VISION_KEY" to "independent-vision-key"),
    ) { source ->
        val png = attachmentPng
        val encoded = Base64.getEncoder().encodeToString(png)
        var visionCalls = 0
        val http = HttpClient(MockEngine { request ->
            assertEquals("https://vision.test/v1/chat/completions", request.url.toString())
            assertEquals("Bearer independent-vision-key", request.headers[HttpHeaders.Authorization])
            val body = restJsonMapper.readTree(request.body.toByteArray())
            assertEquals("upstream-vision", body["model"].asText())
            assertEquals("low", body["reasoning"]["effort"].asText())
            val content = body["messages"].single()["content"]
            val images = content.filter { it["type"].asText() == "image_url" }
            assertEquals(if (++visionCalls == 2) 2 else 1, images.size)
            images.forEach { assertEquals("data:image/png;base64,$encoded", it["image_url"]["url"].asText()) }
            val question = content.first()["text"].asText()
            if (visionCalls == 2) assertEquals("compare local images", question)
            else {
                assertTrue("inspect screen" in question)
                assertTrue("fileId=" in question)
            }
            val answer = if (visionCalls == 2) "FILE_ANALYSIS" else "SCREEN_ANALYSIS"
            respond("""{"choices":[{"index":0,"message":{"role":"assistant","content":"$answer"},"finish_reason":"stop"}],"created":1,
              "usage":{"prompt_tokens":20,"completion_tokens":5,"total_tokens":25}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        var phase = 0
        var imagePath = ""
        val llm = E2eLlmApi { request ->
            assertEquals(E2E_MODEL.alias, request.model)
            assertTrue(request.messages.all { it.attachments.isNullOrEmpty() }, "Text model must not receive pixels")
            when (phase++) {
                0 -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "screen.capture", "arguments" to emptyMap<String, Any>()))
                1 -> {
                    assertTrue("SCREEN_ANALYSIS" in request.messages.last().content)
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "ViewImage", "arguments" to
                        mapOf("imagePaths" to listOf(imagePath, imagePath), "question" to "compare local images")))
                }
                2 -> {
                    assertTrue("FILE_ANALYSIS" in request.messages.last().content)
                    reply(request, "Screen inspected")
                }
                3 -> {
                    assertTrue("SCREEN_ANALYSIS" in request.messages.last().content)
                    reply(request, "Upload inspected")
                }
                else -> error("Unexpected main model call")
            }
        }
        backendE2eTest("separate_vision", llm = llm, settingsSource = source, attachments = AttachmentMinio.config(),
            providerClients = ProviderHttpClients(http, http, mcp = http)) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user, tools = clientToolsJson("screen.capture"))
            val fs = backend.sandboxFileSystem(user)
            val file = fs.resolvePath("vision-source.png")
            fs.writeBytes(file, png)
            imagePath = file.path
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(user); jsonBody("""{"enabledTools":["ViewImage"]}""")
            }
            withPublicSocket(chat) { socket ->
                socket.send(Frame.Text(messageFrame(chat, user, "capture", text = "inspect screen")))
                assertEquals("accepted", readJson(socket)["status"].asText())
                readJson(socket)
                val started = readJson(socket)
                assertEquals("screen.capture", started["payload"]["name"].asText())
                socket.send(Frame.Text(toolResultFrame(started, "succeeded", mapOf("content" to listOf(
                    mapOf("type" to "image", "mimeType" to "image/png", "data" to encoded))))))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals("thread.completed", readJson(socket)["type"].asText())
            }
            val state = backend.agentStateRepository.get(user, UUID.fromString(chat))!!
            val image = state.history.flatMap { it.files }.distinctBy { it.fileId }.single()
            assertContentEquals(png, backend.attachmentStorage.read(user, UUID.fromString(image.fileId)))
            val savedHistory = restJsonMapper.writeValueAsString(state.history)
            assertFalse(encoded in savedHistory)
            assertFalse("SCREEN_ANALYSIS" in savedHistory, "Request projection must not enter stored history")
            assertTrue("FILE_ANALYSIS" in savedHistory, "Explicit ViewImage result remains ordinary tool history")
            val uploadChat = createPublicChat(user, requestId = "upload-chat")
            val turn = backend.dependencies.executionService.executeChatTurn(user, UUID.fromString(uploadChat),
                "inspect screen upload", attachments = listOf(image)).execution
            backend.awaitExecution(turn.id)
            assertEquals(AgentExecutionStatus.COMPLETED, backend.executionRepository.get(user, turn.id)!!.status)
            assertEquals(3, visionCalls)
            assertEquals(4, phase)
        }
    }
}
