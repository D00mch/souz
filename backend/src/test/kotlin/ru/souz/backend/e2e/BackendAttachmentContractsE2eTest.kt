package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.attachments.AttachmentStorageConfig
import ru.souz.backend.attachments.toContent
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.FilePresentation
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox

class BackendAttachmentContractsE2eTest {
    @Test
    fun `input files prepare attach and survive persisted sessions without automatic reply reuse`() {
        val root = Files.createTempDirectory(Path.of("build"), "attachment-contracts-").toAbsolutePath()
        var phase = 0
        var fileId = ""
        val llm = E2eLlmApi { request ->
            when (phase++) {
                0 -> {
                    assertEquals(fileId, request.messages.single { it.content == "return this document" }.files.single().file.fileId)
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "PrepareAttachment", "arguments" to mapOf("fileId" to fileId)))
                }
                1 -> {
                    val prepared = restJsonMapper.readTree(request.messages.last().content)
                    val path = prepared["path"].asText()
                    assertEquals("# Original", Files.readString(Path.of(path)))
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply", "arguments" to mapOf("path" to path)))
                }
                else -> reply(request, "Document ready")
            }
        }
        try {
            backendE2eTest("attachment_contracts", attachments = storageConfig(), llm = llm,
                sandboxFactory = { settings -> RuntimeSandboxFactory { scope ->
                    LocalRuntimeSandbox(scope, settings, root, root.resolve("state"), root.resolve(scope.userId))
                } },
            ) {
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user)
                    jsonBody("""{"defaultModel":"${E2E_MODEL.alias}","enabledTools":["PrepareAttachment","AttachFileToReply"]}""")
                }
                val stored = backend.attachmentStorage.save(user, "input", "report.md", "# Original".byteInputStream())
                fileId = stored.id.toString()
                // Internal ingress is exercised here; TG/VK intake is a separate rollout stage.
                val sent = backend.dependencies.executionService.executeChatTurn(
                    user, UUID.fromString(chat), "return this document", attachments = listOf(stored.toContent()),
                )
                backend.awaitExecution(sent.execution.id)
                val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
                assertEquals(listOf("user", "assistant"), messages.map { it["role"].asText() })
                assertEquals(fileId, messages.first()["attachments"][0]["file"]["fileId"].asText())
                val selectedId = messages.last()["attachments"][0]["file"]["fileId"].asText()
                val selected = backend.attachmentStorage.get(user, UUID.fromString(selectedId)).toContent()
                assertContentEquals("# Original".toByteArray(), backend.attachmentStorage.read(user, UUID.fromString(selectedId)))
                val state = backend.agentStateRepository.get(user, UUID.fromString(chat))!!
                assertEquals(2, state.schemaVersion)
                assertEquals(listOf(selected), state.replyAttachments)

                withPeerBackend(E2eLlmApi { request ->
                    assertTrue(request.messages.any { message -> message.files.any { it.file.fileId == fileId } })
                    reply(request, "Continued")
                }) { peer ->
                    // Same execution after reconstruction keeps its explicit selection (option continuation).
                    val resumed = peer.backend.turnRunner.run(
                        AgentConversationKey(user, chat),
                        BackendConversationTurnRequest(prompt = "continue", model = E2E_MODEL.alias,
                            locale = "ru-RU", timeZone = "UTC", executionId = sent.execution.id.toString()),
                        AgentRuntimeEventSink.NONE,
                    ) as BackendConversationTurnOutcome.Completed
                    assertEquals(listOf(selected), resumed.attachments)
                    val next = peer.client.post(BackendHttpRoutes.chatMessages(chat)) {
                        peer.run { trusted(user); jsonBody("""{"content":"next independent turn"}""") }
                    }
                    peer.backend.awaitExecution(UUID.fromString(peer.run { next.jsonBody()["execution"]["id"].asText() }))
                    val later = peer.client.get(BackendHttpRoutes.chatMessages(chat)) { peer.run { trusted(user) } }
                    assertTrue(peer.run { later.jsonBody()["items"].last()["attachments"].isEmpty })
                }

                val meta = ToolInvocationMeta(user, chat, UUID.randomUUID().toString())
                val prepared = backend.attachmentAccess.prepare(meta, fileId)
                Files.writeString(Path.of(prepared.path), "edited working copy")
                assertEquals(prepared.path, backend.attachmentAccess.prepare(meta, fileId).path)
                assertEquals("# Original", Files.readString(Path.of(prepared.path)))
                assertFailsWith<AttachmentException> { backend.attachmentAccess.prepare(meta.copy(userId = "foreign"), fileId) }
                assertFailsWith<AttachmentException> { backend.attachmentAccess.register(meta, root.parent.resolve("outside.txt").toString(), null, FilePresentation.document) }
                for ((files, error) in listOf(
                    listOf(stored.toContent().copy(name = "forged.md")) to "attachment_unavailable",
                    listOf(stored.toContent().copy(presentation = FilePresentation.image)) to "attachment_presentation_mismatch",
                    List(2) { stored.toContent() } to "attachment_duplicate",
                )) {
                    assertEquals(error, assertFailsWith<RuntimeException> {
                        backend.messageRepository.append(user, UUID.fromString(chat), ChatRole.USER, "invalid", attachments = files)
                    }.message)
                }
                assertFailsWith<AttachmentException> {
                    val foreign = backend.attachmentStorage.save("foreign", "file", "secret.txt", "secret".byteInputStream())
                    backend.messageRepository.append(user, UUID.fromString(chat), ChatRole.USER, "foreign", attachments = listOf(foreign.toContent()))
                }
                sql { connection -> connection.prepareStatement("update stored_attachments set expires_at = now() - interval '1 second' where id = ?").use {
                    it.setObject(1, stored.id); it.executeUpdate()
                } }
                assertFailsWith<AttachmentException> {
                    backend.messageRepository.append(user, UUID.fromString(chat), ChatRole.USER, "expired", attachments = listOf(stored.toContent()))
                }
                assertEquals(4, client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"].size())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun storageConfig(): AttachmentStorageConfig {
        val id = UUID.randomUUID()
        return AttachmentStorageConfig(
            "http://${AttachmentMinio.server.host}:${AttachmentMinio.server.firstMappedPort}",
            "contracts-$id", "contracts-$id", "contract-test-secret",
        ).also(AttachmentMinio::provision)
    }
}
