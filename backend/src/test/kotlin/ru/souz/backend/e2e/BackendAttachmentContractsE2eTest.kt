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
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.agent.runtime.BackendConversationRuntimeTurnRunner
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.attachments.AttachmentStorageConfig
import ru.souz.backend.attachments.toContent
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.FilePresentation
import ru.souz.llms.LLMResponse
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
        lateinit var delegate: BackendConversationTurnRunner
        val waitingRunner = object : BackendConversationTurnRunner {
            override suspend fun run(conversationKey: AgentConversationKey, request: BackendConversationTurnRequest,
                eventSink: AgentRuntimeEventSink, initialUsage: LLMResponse.Usage): BackendConversationTurnOutcome {
                // Simulate the external choice request; the real runner persists the actual agent session.
                eventSink.emit(AgentRuntimeEvent.ChoiceRequested(UUID.randomUUID().toString(), "generic_selection",
                    "Replace the document?", listOf(AgentRuntimeEvent.ChoiceRequested.ChoiceOption("replace", "Replace")), "single"))
                return delegate.run(conversationKey, request, eventSink, initialUsage)
            }
        }
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
                2 -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply", "arguments" to mapOf("fileId" to fileId)))
                else -> reply(request, "Document ready")
            }
        }
        try {
            backendE2eTest("attachment_contracts", attachments = storageConfig(), llm = llm,
                featureFlags = BackendFeatureFlags(wsEvents = true, options = true), turnRunnerOverride = waitingRunner,
                sandboxFactory = { settings -> RuntimeSandboxFactory { scope ->
                    LocalRuntimeSandbox(scope, settings, root, root.resolve("state"), root.resolve(scope.userId))
                } },
            ) {
                delegate = BackendConversationRuntimeTurnRunner(backend.conversationRuntimeFactory)
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user)
                    jsonBody("""{"defaultModel":"${E2E_MODEL.alias}","enabledTools":["PrepareAttachment","AttachFileToReply","DetachFileFromReply"]}""")
                }
                val stored = backend.attachmentStorage.save(user, "input", "report.md", "# Original".byteInputStream())
                fileId = stored.id.toString()
                // Internal ingress is exercised here; TG/VK intake is a separate rollout stage.
                val sent = backend.dependencies.executionService.executeChatTurn(
                    user, UUID.fromString(chat), "return this document", attachments = listOf(stored.toContent()),
                )
                backend.awaitExecution(sent.execution.id)
                val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
                assertEquals(listOf("user"), messages.map { it["role"].asText() })
                assertEquals(fileId, messages.first()["attachments"][0]["file"]["fileId"].asText())
                val state = backend.agentStateRepository.get(user, UUID.fromString(chat))!!
                assertEquals(2, state.schemaVersion)
                assertEquals(AgentExecutionStatus.WAITING_OPTION, backend.executionRepository.get(user, sent.execution.id)!!.status)
                val selected = state.replyAttachments.first()
                val retained = stored.toContent()
                assertEquals(listOf(selected, retained), state.replyAttachments)
                assertContentEquals("# Original".toByteArray(), backend.attachmentStorage.read(user, UUID.fromString(selected.file.fileId)))
                val corrected = backend.attachmentStorage.save(user, "corrected", "corrected.md", "# Corrected".byteInputStream()).toContent()
                val optionId = client.get(BackendHttpRoutes.chatEvents(chat)) { trusted(user) }.jsonBody()["items"]
                    .single { it["type"].asText() == "option.requested" }["payload"]["optionId"].asText()
                var resumedPhase = 0

                withPeerBackend(E2eLlmApi { request ->
                    assertTrue(request.messages.any { message -> message.files.any { it.file.fileId == fileId } })
                    when (resumedPhase++) {
                        0 -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "DetachFileFromReply",
                            "arguments" to mapOf("fileId" to selected.file.fileId)))
                        1 -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply",
                            "arguments" to mapOf("fileId" to corrected.file.fileId)))
                        else -> reply(request, "Continued")
                    }
                }) { peer ->
                    val answer = peer.client.post(BackendHttpRoutes.optionAnswer(optionId)) {
                        peer.run { trusted(user); jsonBody("""{"selectedOptionIds":["replace"]}""") }
                    }
                    assertEquals(sent.execution.id.toString(), peer.run { answer.jsonBody()["execution"]["id"].asText() })
                    peer.backend.awaitExecution(sent.execution.id)
                    assertEquals(AgentExecutionStatus.COMPLETED, peer.backend.executionRepository.get(user, sent.execution.id)!!.status)
                    val resumedMessages = peer.client.get(BackendHttpRoutes.chatMessages(chat)) { peer.run { trusted(user) } }
                    assertEquals(listOf(retained.file.fileId, corrected.file.fileId), peer.run {
                        resumedMessages.jsonBody()["items"].last()["attachments"].map { it["file"]["fileId"].asText() }
                    })
                    assertEquals(listOf(retained, corrected), peer.backend.agentStateRepository.get(user, UUID.fromString(chat))!!.replyAttachments)
                    // A missing ID, like a different ID, cannot inherit an execution's selection.
                    val missingId = peer.backend.turnRunner.run(
                        AgentConversationKey(user, chat),
                        BackendConversationTurnRequest(prompt = "continue", model = E2E_MODEL.alias,
                            locale = "ru-RU", timeZone = "UTC"),
                        AgentRuntimeEventSink.NONE,
                    ) as BackendConversationTurnOutcome.Completed
                    assertTrue(missingId.attachments.isEmpty())
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
