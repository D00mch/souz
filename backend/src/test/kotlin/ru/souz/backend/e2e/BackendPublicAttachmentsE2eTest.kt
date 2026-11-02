package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.websocket.Frame
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver
import ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.attachments.ATTACHMENT_RETENTION
import ru.souz.backend.attachments.AttachmentClock
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.attachments.S3AttachmentObjects
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.restJsonMapper

class BackendPublicAttachmentsE2eTest {
    @Test
    fun `attachment submission requires configured storage`() = backendE2eTest("ws_files_disabled") {
        val user = UUID.randomUUID().toString()
        val chat = createPublicChat(user)
        withPublicSocket(chat) { session ->
            session.send(Frame.Text(attachmentFrame(chat, user, "file", listOf(UUID.randomUUID().toString()))))
            val rejected = readJson(session)
            assertEquals("rejected", rejected["status"].asText())
            assertEquals("feature_disabled", rejected["error"]["code"].asText())
        }
        assertTrue(llm.requests.isEmpty())
    }

    @Test
    fun `files-only submit imports S3 files and persists a minimal reply and replay with retry after expiry`() {
        val config = AttachmentMinio.config()
        val root = Files.createTempDirectory(Path.of("build"), "ws-attachments-").toAbsolutePath()
        val clock = AttachmentClock(Instant.now())
        var fileId = ""
        val replyReady = CompletableDeferred<Unit>()
        val finishReply = CompletableDeferred<Unit>()
        try {
            backendE2eTest("ws_file_roundtrip", attachments = config, clock = clock, sandboxFactory = { settings ->
                DefaultRuntimeSandboxFactory(settings, RuntimeSandboxModeResolver { "docker" }, dockerHostRoot = root,
                    dockerSandboxCreator = { scope, hostRoot, image, name -> DockerRuntimeSandbox(scope, hostRoot, image, name, autoStart = false) })
            }, llm = E2eLlmApi { request ->
                if (request.messages.last().role == LLMMessageRole.function) {
                    replyReady.complete(Unit)
                    finishReply.await()
                    reply(request, "")
                }
                else {
                    fileId = request.messages.last { it.role == LLMMessageRole.user }.files.single().fileId
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "AttachFileToReply",
                        "arguments" to mapOf("fileId" to fileId)))
                }
            }) {
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                val upload = AttachmentMinio.upload(config, user, "report.md", "# Shared file".toByteArray(), objectKey = "external/произвольный ключ.md")
                fileId = upload.id.toString()
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user); jsonBody("""{"enabledTools":["AttachFileToReply"]}""")
                }
                val frame = attachmentFrame(chat, user, "file-only", listOf(upload.objectKey))
                lateinit var terminal: JsonNode
                withMultiChatSocket { session ->
                    session.send(Frame.Text(frame))
                    val ack = readJson(session)
                    assertEquals("accepted", ack["status"].asText(), ack.toString())
                    assertEquals("thread.status", readJson(session)["type"].asText())
                    replyReady.await()
                    withTableLocked("agent_events", "share") {
                        finishReply.complete(Unit)
                        awaitLockWait("agent_events")
                        // commitTurn finished; the event sink is blocked writing message.created.
                        val execution = backend.executionRepository.get(user, UUID.fromString(ack["thread"]["id"].asText()))!!
                        assertEquals("completed", execution.status.value)
                        sql { connection -> connection.createStatement().use { it.executeUpdate("delete from stored_attachments") } }
                    }
                    terminal = readJson(session)
                    assertEquals("thread.completed", terminal["type"].asText(), terminal.toString())
                    assertEquals("", terminal["payload"]["response"].asText())
                    val file = terminal["payload"]["attachments"].single()
                    assertEquals(setOf("objectKey", "name", "presentation"), file.fieldNames().asSequence().toSet())
                    assertEquals(upload.objectKey, file["objectKey"].asText())
                    assertEquals("report.md", file["name"].asText())
                    assertEquals("document", file["presentation"].asText())
                    assertTrue(fileId != upload.id.toString())
                    // A stored receipt must not depend on a still-available S3 object or live metadata.
                    S3AttachmentObjects(config).use { it.delete(upload) }
                    clock.now = clock.now.plus(ATTACHMENT_RETENTION).plusSeconds(1)
                    session.send(Frame.Text(frame))
                    val retry = readJson(session)
                    assertEquals("accepted", retry["status"].asText())
                    assertTrue(retry["duplicate"].asBoolean())
                    assertEquals(ack["thread"], retry["thread"])
                    assertEquals("thread.status", readJson(session)["type"].asText())
                    session.send(Frame.Text(attachmentFrame(chat, user, "file-only", listOf(upload.objectKey), "changed")))
                    assertEquals("idempotency_conflict", readJson(session)["error"]["code"].asText())
                }
                sql { connection -> connection.createStatement().use { it.executeUpdate("delete from stored_attachments") } }
                withPublicSocket(chat) { session -> assertEquals(terminal, readJson(session)) }
                val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
                assertEquals(listOf("", ""), messages.map { it["content"].asText() })
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `active threads accept attachment follow ups with and without text and keep file order`() {
        val config = AttachmentMinio.config()
        backendE2eTest("ws_file_followup", attachments = config, llm = E2eLlmApi().apply { pauseUntilReleased() }) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val keys = List(2) { AttachmentMinio.upload(config, user, "report.md", "file $it".toByteArray()).objectKey }
            withPublicSocket(chat) { session ->
                session.send(Frame.Text(messageFrame(chat, user, "first", text = "wait for files")))
                val ack = readJson(session)
                readJson(session)
                llm.awaitPrompt("wait for files")
                val textRetry = restJsonMapper.readTree(messageFrame(chat, user, "first", text = "wait for files")) as ObjectNode
                (textRetry["payload"] as ObjectNode).putArray("attachments")
                session.send(Frame.Text(textRetry.toString()))
                assertTrue(readJson(session)["duplicate"].asBoolean())
                readJson(session)
                val follow = attachmentFrame(chat, user, "second", keys)
                session.send(Frame.Text(follow))
                val second = readJson(session)
                assertEquals(ack["thread"]["id"], second["thread"]["id"])
                assertFalse(second["thread"]["created"].asBoolean())
                readJson(session)
                session.send(Frame.Text(follow))
                assertTrue(readJson(session)["duplicate"].asBoolean())
                readJson(session)
                session.send(Frame.Text(attachmentFrame(chat, user, "third", keys.reversed(), "review both",
                    ack["thread"]["id"].asText())))
                assertEquals("accepted", readJson(session)["status"].asText())
                readJson(session)
                llm.release()
                assertEquals("thread.completed", readJson(session)["type"].asText())
            }
            val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
                .filter { it["role"].asText() == "user" }
            assertEquals(listOf("wait for files", "", "review both"), messages.map { it["content"].asText() })
            val inputs = llm.requests.last().messages.filter { it.role == LLMMessageRole.user && it.files.size == 2 }
            assertEquals(2, inputs.size)
            assertEquals(inputs[0].files.reversed(), inputs[1].files)
        }
    }

    @Test
    fun `follow up rejects files that expire during import without committing input or device changes`() {
        val config = AttachmentMinio.config()
        backendE2eTest("ws_expiring_followup", attachments = config, llm = E2eLlmApi().apply { pauseUntilReleased() }) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val keys = List(2) { AttachmentMinio.upload(config, user, "report.md", "file $it".toByteArray()).objectKey }
            sql { connection -> connection.createStatement().use { statement ->
                statement.execute("""
                    create function expire_first_import() returns trigger language plpgsql as ${'$'}${'$'}
                    begin
                        if new.object_key = '${keys[1]}' then
                            update stored_attachments set expires_at = now() - interval '1 second'
                            where object_key = '${keys[0]}';
                        end if;
                        return new;
                    end ${'$'}${'$'};
                    create trigger expire_first_import after insert on stored_attachments
                    for each row execute function expire_first_import();
                """.trimIndent())
            } }
            withPublicSocket(chat) { session ->
                session.send(Frame.Text(messageFrame(chat, user, "initial", text = "wait", deviceId = "original")))
                val accepted = readJson(session)
                readJson(session)
                llm.awaitPrompt("wait")
                val thread = UUID.fromString(accepted["thread"]["id"].asText())
                session.send(Frame.Text(attachmentFrame(chat, user, "expired", keys, "do not accept")))
                val rejected = readJson(session)
                assertEquals("rejected", rejected["status"].asText())
                assertTrue(rejected["error"]["message"].asText().contains("attachment_unavailable"))
                assertEquals("original", backend.clientThreadRegistry.currentDevice(thread)?.deviceId)
                val stored = backend.executionRepository.get(user, thread)!!
                assertEquals("original", json.readTree(stored.latestDeviceContextJson)["deviceId"].asText())
                sql { connection -> connection.prepareStatement(
                    "select count(*) from client_requests where chat_id = ? and request_id = 'expired'"
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(chat))
                    statement.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
                } }
                llm.release()
                assertEquals("thread.completed", readJson(session)["type"].asText())
            }
            val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
            assertEquals(listOf("wait"), messages.filter { it["role"].asText() == "user" }.map { it["content"].asText() })
            assertTrue(llm.requests.flatMap { it.messages }.none { it.files.isNotEmpty() || it.content == "do not accept" })
        }
    }

    @Test
    fun `forwarded attachments produce durable message events without starting a target execution`() {
        val config = AttachmentMinio.config()
        backendE2eTest("ws_forward_file", attachments = config) {
            val user = UUID.randomUUID().toString()
            val source = createPublicChat(user)
            val target = createPublicChat(user, requestId = "create-target")
            val file = backend.attachmentStorage.save(user, "forward", "report.md", "# Forward".byteInputStream())
            llm.requestSkillForPrompt("forward", "SendMessageToChannel", mapOf(
                "channelType" to "public_client", "channelId" to target, "fileIds" to listOf(file.id.toString()),
            ))
            lateinit var forwarded: JsonNode
            withPublicSocket(target) { destination ->
                withPublicSocket(source) { session ->
                    session.send(Frame.Text(messageFrame(source, user, "forward", text = "forward")))
                    assertEquals("accepted", readJson(session)["status"].asText())
                    readJson(session)
                    assertEquals("thread.completed", readJson(session)["type"].asText())
                }
                forwarded = readJson(destination)
                assertEquals("message.created", forwarded["type"].asText())
                assertTrue(forwarded["threadId"].isNull)
                assertEquals("", forwarded["payload"]["content"].asText())
                assertEquals(file.objectKey, forwarded["payload"]["attachments"][0]["objectKey"].asText())
            }
            withPublicSocket(target) { assertEquals(forwarded, readJson(it)) }
            val messages = client.get(BackendHttpRoutes.chatMessages(target)) { trusted(user) }.jsonBody()["items"]
            assertEquals(file.id.toString(), messages.single()["attachments"][0]["fileId"].asText())
        }
    }

    @Test
    fun `invalid attachment frames are rejected before execution and do not relax history text validation`() {
        val config = AttachmentMinio.config()
        backendE2eTest("ws_file_validation", attachments = config) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val file = AttachmentMinio.upload(config, user, "report.md", "text".toByteArray())
            val foreignOwner = UUID.randomUUID().toString()
            val foreign = AttachmentMinio.upload(config, foreignOwner, "report.md", "private".toByteArray())
            val foreignChat = createPublicChat(foreignOwner)
            withPublicSocket(foreignChat) { socket ->
                socket.send(Frame.Text(attachmentFrame(foreignChat, foreignOwner, "foreign", listOf(foreign.objectKey))))
                assertEquals("accepted", readJson(socket)["status"].asText())
                readJson(socket)
                assertEquals("thread.completed", readJson(socket)["type"].asText())
            }
            val valid = restJsonMapper.readTree(attachmentFrame(chat, user, "invalid", listOf(file.objectKey))) as ObjectNode
            val mutations: List<(ObjectNode) -> Unit> = listOf(
                { it.remove("attachments") },
                { it.putArray("attachments") },
                { it.putNull("content") },
                { it.set<JsonNode>("content", restJsonMapper.readTree("""{"type":"text","source":"text","text":" "}""")) },
                { it.putNull("attachments") },
                { it.putArray("attachments").addNull() },
                { (it["attachments"][0] as ObjectNode).put("objectKey", "") },
                { (it["attachments"][0] as ObjectNode).put("objectKey", "я".repeat(513)) },
                { (it["attachments"][0] as ObjectNode).put("name", " ") },
                { (it["attachments"][0] as ObjectNode).put("presentation", "video") },
                { (it["attachments"][0] as ObjectNode).put("presentation", 0) },
                { (it["attachments"][0] as ObjectNode).putNull("presentation") },
                { (it["attachments"][0] as ObjectNode).put("presentation", "image") },
                { (it["attachments"][0] as ObjectNode).put("mimeType", "text/plain") },
                { (it["attachments"][0] as ObjectNode).put("objectKey", "missing/" + UUID.randomUUID()) },
                { (it["attachments"][0] as ObjectNode).put("objectKey", foreign.objectKey) },
                { payload -> val item = payload["attachments"][0]; payload.putArray("attachments").add(item).add(item) },
                { payload -> payload.putArray("attachments").also { array -> repeat(11) {
                    array.addObject().put("objectKey", "missing/" + UUID.randomUUID()).put("name", "report.md")
                } } },
            )
            withPublicSocket(chat) { session ->
                for ((index, mutate) in mutations.withIndex()) {
                    val frame = valid.deepCopy().put("requestId", "invalid-$index")
                    mutate(frame["payload"] as ObjectNode)
                    session.send(Frame.Text(frame.toString()))
                    val rejection = readJson(session)
                    assertEquals("rejected", rejection["status"].asText(), "case $index: $rejection")
                    assertEquals("invalid_request", rejection["error"]["code"].asText(), "case $index: $rejection")
                }
                session.send(Frame.Text(historyFrame(chat, "empty-history", "user", "")))
                assertEquals("rejected", readJson(session)["status"].asText())
                session.send(Frame.Text(attachmentFrame(chat, user, "valid-after-errors", listOf(file.objectKey), "with caption")))
                assertEquals("accepted", readJson(session)["status"].asText())
                readJson(session)
                assertEquals("thread.completed", readJson(session)["type"].asText())
            }
            val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
            assertEquals(1, messages.count { it["role"].asText() == "user" })
        }
    }
}

private fun attachmentFrame(chat: String, user: String, request: String, keys: List<String>, text: String? = null,
    thread: String? = null): String {
    val frame = restJsonMapper.readTree(messageFrame(chat, user, request, thread)) as ObjectNode
    val payload = frame["payload"] as ObjectNode
    if (text == null) payload.remove("content") else (payload["content"] as ObjectNode).put("text", text)
    payload.putArray("attachments").also { files -> keys.forEach { files.addObject().put("objectKey", it).put("name", "report.md") } }
    return frame.toString()
}
