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
import ru.souz.backend.attachments.ToolAttachFileToReply
import ru.souz.backend.attachments.ToolDetachFileFromReply
import ru.souz.backend.attachments.ToolPrepareAttachment
import ru.souz.backend.attachments.toContent
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.storage.postgres.PostgresMessageRepository
import ru.souz.db.SettingsProvider
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation
import ru.souz.llms.LLMResponse
import ru.souz.llms.MAX_MESSAGE_FILES
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver
import ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox

class BackendAttachmentContractsE2eTest {
    @Test
    fun `input files prepare attach and survive persisted sessions without automatic reply reuse`() {
        val root = Files.createTempDirectory(Path.of("build"), "attachment-contracts-").toAbsolutePath()
        var phase = 0
        var fileId = ""
        lateinit var readSandboxFile: (String) -> String
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
                    val input = request.messages.single { it.content.startsWith("return this document") }
                    assertEquals(fileId, input.files.single().fileId)
                    // The provider-visible text carries the ID the model passes to file tools.
                    assertTrue("fileId: $fileId" in input.content)
                    toolCallReply(request, "SpawnSubagent", mapOf(
                        "task" to "Prepare and save fileId: $fileId",
                        "skillIds" to listOf("PrepareAttachment", "AttachFileToReply"),
                    ))
                }
                1 -> toolCallReply(request, "PrepareAttachment", mapOf("fileId" to fileId))
                2 -> {
                    val prepared = restJsonMapper.readTree(request.messages.last().content)
                    val path = prepared["path"].asText()
                    assertEquals("# Original", readSandboxFile(path))
                    toolCallReply(request, "AttachFileToReply", mapOf("path" to path))
                }
                3 -> reply(request, "Child document ready")
                4 -> {
                    val child = request.messages.last()
                    assertEquals("SpawnSubagent", child.name)
                    assertTrue(child.replyFiles.isEmpty())
                    val savedId = child.files.single().fileId
                    assertEquals(savedId, restJsonMapper.readTree(child.content)["files"][0]["fileId"].asText())
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply", "arguments" to mapOf("fileId" to savedId)))
                }
                5 -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply", "arguments" to mapOf("fileId" to fileId)))
                else -> reply(request, "Document ready")
            }
        }
        try {
            backendE2eTest("attachment_contracts", attachments = storageConfig(), llm = llm,
                featureFlags = BackendFeatureFlags(wsEvents = true, options = true), turnRunnerOverride = waitingRunner,
                sandboxFactory = dockerSandboxes(root),
            ) {
                delegate = BackendConversationRuntimeTurnRunner(backend.conversationRuntimeFactory)
                val user = UUID.randomUUID().toString()
                val fs = backend.sandboxFileSystem(user)
                readSandboxFile = { path -> fs.readText(fs.resolvePath(path)) }
                val chat = createPublicChat(user)
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user)
                    jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","enabledTools":["PrepareAttachment","AttachFileToReply","DetachFileFromReply"]}""")
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
                assertEquals(fileId, messages.first()["attachments"][0]["fileId"].asText())
                val state = backend.agentStateRepository.get(user, UUID.fromString(chat))!!
                assertEquals(2, state.schemaVersion)
                assertEquals(AgentExecutionStatus.WAITING_OPTION, backend.executionRepository.get(user, sent.execution.id)!!.status)
                val selected = state.replyAttachments.first()
                val retained = stored.toContent()
                assertEquals(listOf(selected, retained), state.replyAttachments)
                assertContentEquals("# Original".toByteArray(), backend.attachmentStorage.read(user, UUID.fromString(selected.fileId)))
                val corrected = backend.attachmentStorage.save(user, "corrected", "corrected.md", "# Corrected".byteInputStream()).toContent()
                val optionId = client.get(BackendHttpRoutes.chatEvents(chat)) { trusted(user) }.jsonBody()["items"]
                    .single { it["type"].asText() == "option.requested" }["payload"]["optionId"].asText()
                var resumedPhase = 0

                withPeerBackend(E2eLlmApi { request ->
                    assertTrue(request.messages.any { message -> message.files.any { it.fileId == fileId } })
                    when (resumedPhase++) {
                        0 -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "DetachFileFromReply",
                            "arguments" to mapOf("fileId" to selected.fileId)))
                        1 -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply",
                            "arguments" to mapOf("fileId" to corrected.fileId)))
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
                    assertEquals(listOf(retained.fileId, corrected.fileId), peer.run {
                        resumedMessages.jsonBody()["items"].last()["attachments"].map { it["fileId"].asText() }
                    })
                    assertEquals(listOf(retained, corrected), peer.backend.agentStateRepository.get(user, UUID.fromString(chat))!!.replyAttachments)
                    // A missing ID, like a different ID, cannot inherit an execution's selection.
                    val missingId = peer.backend.turnRunner.run(
                        AgentConversationKey(user, chat),
                        BackendConversationTurnRequest(prompt = "continue", model = E2E_LOCAL_MODEL.alias,
                            locale = "ru-RU", timeZone = "UTC"),
                        AgentRuntimeEventSink.NONE,
                    ) as BackendConversationTurnOutcome.Completed
                    assertTrue(missingId.session.replyAttachments.isEmpty())
                    val next = peer.client.post(BackendHttpRoutes.chatMessages(chat)) {
                        peer.run { trusted(user); jsonBody("""{"content":"next independent turn"}""") }
                    }
                    peer.backend.awaitExecution(UUID.fromString(peer.run { next.jsonBody()["execution"]["id"].asText() }))
                    val later = peer.client.get(BackendHttpRoutes.chatMessages(chat)) { peer.run { trusted(user) } }
                    assertTrue(peer.run { later.jsonBody()["items"].last()["attachments"].isEmpty })
                }

                val meta = ToolInvocationMeta(user, chat, UUID.randomUUID().toString())
                val prepared = backend.attachmentAccess.prepare(meta, fileId)
                fs.writeText(fs.resolvePath(prepared.path), "edited working copy")
                assertEquals(prepared.path, backend.attachmentAccess.prepare(meta, fileId).path)
                assertEquals("# Original", readSandboxFile(prepared.path))
                assertFailsWith<AttachmentException> { backend.attachmentAccess.prepare(meta.copy(userId = "foreign"), fileId) }
                for ((path, error) in listOf("/etc/hosts" to "attachment_path_forbidden", "missing.md" to "attachment_path_not_found")) {
                    assertEquals(error, assertFailsWith<AttachmentException> {
                        backend.attachmentAccess.save(meta, path, FilePresentation.document)
                    }.code)
                }
                val attach = ToolAttachFileToReply(backend.attachmentAccess)
                for ((arguments, error) in listOf(
                    mapOf("path" to prepared.path, "fileId" to fileId) to "attachment_source_required",
                    mapOf("fileId" to fileId, "presentation" to "video") to "attachment_presentation_invalid",
                    mapOf("fileId" to fileId, "presentation" to "image") to "attachment_presentation_mismatch",
                )) {
                    val result = attach.invoke(LLMResponse.FunctionCall(ToolAttachFileToReply.NAME, arguments), meta)
                    assertEquals(error, restJsonMapper.readTree(result.content)["error"].asText())
                    assertTrue(result.replyFiles.isEmpty())
                }
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
                // Existing-message finalization replaces or clears attachments; text-only edits preserve them.
                val writer = backend.messageRepository as PostgresMessageRepository
                val last = checkNotNull(writer.latest(user, UUID.fromString(chat)))
                for (replacement in listOf(listOf(corrected), emptyList())) {
                    sql { connection -> writer.updateContent(connection, user, last.chatId, last.id, "updated", replacement) }
                    assertEquals(replacement, writer.updateContent(user, last.chatId, last.id, "text only")!!.attachments)
                    val read = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"].last()
                    assertEquals("text only", read["content"].asText())
                    assertEquals(replacement.map { it.fileId }, read["attachments"].map { it["fileId"].asText() })
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reply limits are enforced at selection and an unavailable file does not discard the answer`() {
        val root = Files.createTempDirectory(Path.of("build"), "attachment-limits-").toAbsolutePath()
        lateinit var files: List<FileContent>
        lateinit var expire: (FileContent) -> Unit
        var phase = 0
        val llm = E2eLlmApi { request ->
            when (val call = phase++) {
                in 0..MAX_MESSAGE_FILES -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to ToolAttachFileToReply.NAME,
                    "arguments" to mapOf("fileId" to files[call].fileId)))
                else -> {
                    val rejected = restJsonMapper.readTree(request.messages.last().content)
                    assertEquals("attachment_reply_limit_exceeded", rejected["error"].asText())
                    assertEquals(MAX_MESSAGE_FILES, rejected["selectedFiles"].asInt())
                    // The selected file expires before the final commit.
                    expire(files.first())
                    reply(request, "Here are your charts")
                }
            }
        }
        try {
            backendE2eTest("attachment_limits", attachments = storageConfig(), llm = llm, sandboxFactory = dockerSandboxes(root)) {
                val user = UUID.randomUUID().toString()
                val chat = UUID.fromString(createPublicChat(user))
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user)
                    jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","enabledTools":["${ToolAttachFileToReply.NAME}"]}""")
                }
                files = (0..MAX_MESSAGE_FILES).map {
                    backend.attachmentStorage.save(user, "chart-$it", "chart-$it.md", "# Chart $it".byteInputStream()).toContent()
                }
                expire = { file -> sql { connection ->
                    connection.prepareStatement("update stored_attachments set expires_at = now() - interval '1 second' where id = ?::uuid").use {
                        it.setString(1, file.fileId); it.executeUpdate()
                    }
                } }
                val sent = backend.dependencies.executionService.executeChatTurn(user, chat, "send all charts")
                backend.awaitExecution(sent.execution.id)

                assertEquals(AgentExecutionStatus.COMPLETED, backend.executionRepository.get(user, sent.execution.id)!!.status)
                val answer = client.get(BackendHttpRoutes.chatMessages(chat.toString())) { trusted(user) }.jsonBody()["items"].last()
                assertEquals("Here are your charts", answer["content"].asText())
                assertEquals(files.subList(1, MAX_MESSAGE_FILES).map { it.fileId }, answer["attachments"].map { it["fileId"].asText() })
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `local sandboxes share one workspace so attachment tools refuse to run there`() {
        val root = Files.createTempDirectory(Path.of("build"), "attachment-local-").toAbsolutePath()
        try {
            backendE2eTest("attachment_local", attachments = storageConfig(), sandboxFactory = { settings ->
                DefaultRuntimeSandboxFactory(settings, RuntimeSandboxModeResolver { "local" }, root, root.resolve("state"), root.resolve("workspace"))
            }) {
                val user = UUID.randomUUID().toString()
                val stored = backend.attachmentStorage.save(user, "input", "secret.md", "# Secret".byteInputStream())
                val meta = ToolInvocationMeta(user, UUID.randomUUID().toString(), UUID.randomUUID().toString())
                for (tool in listOf(ToolPrepareAttachment(backend.attachmentAccess), ToolAttachFileToReply(backend.attachmentAccess),
                    ToolDetachFileFromReply(backend.attachmentAccess))) {
                    val result = tool.invoke(LLMResponse.FunctionCall(tool.fn.name, mapOf("fileId" to stored.id.toString())), meta)
                    assertEquals("attachment_sandbox_unsupported", restJsonMapper.readTree(result.content)["error"].asText())
                    assertTrue(result.replyFiles.isEmpty() && result.detachedReplyFiles.isEmpty())
                }
                assertTrue(Files.notExists(root.resolve("workspace/attachments")))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun dockerSandboxes(root: Path): (SettingsProvider) -> RuntimeSandboxFactory = { settings ->
        DefaultRuntimeSandboxFactory(settings, RuntimeSandboxModeResolver { "docker" }, dockerHostRoot = root,
            dockerSandboxCreator = { scope, hostRoot, image, name -> DockerRuntimeSandbox(scope, hostRoot, image, name, autoStart = false) })
    }

    private fun storageConfig(): AttachmentStorageConfig {
        val id = UUID.randomUUID()
        return AttachmentStorageConfig(
            "http://${AttachmentMinio.server.host}:${AttachmentMinio.server.firstMappedPort}",
            "contracts-$id", "contracts-$id", "contract-test-secret",
        ).also(AttachmentMinio::provision)
    }
}
