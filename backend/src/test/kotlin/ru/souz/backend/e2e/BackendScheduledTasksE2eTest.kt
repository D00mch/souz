package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.hooks.HookConfig
import ru.souz.backend.hooks.sha256
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver

class BackendScheduledTasksE2eTest {
    @TempDir lateinit var workspace: Path
    private val owner = UUID.randomUUID().toString()
    private val after = mapOf("kind" to "after", "seconds" to 600)

    @Test
    fun `task lifecycle uses ordinary hooks live secret transport and Orion owned metadata`() = runTasks {
        val chat = setup()
        val orion = TestOrion()
        withPublicSocket(chat) { socket ->
            val before = Instant.now()
            val created = task(socket, chat, "create", mapOf("title" to "Gmail watch renewal", "prompt" to "Renew Gmail watch", "schedule" to after), orion)
            assertEquals("succeeded", created["status"].asText(), created.toString())
            val id = created["taskId"].asText()
            val token = orion.secrets.getValue(id)
            val yamlPath = workspace.resolve("hooks/$id/hook.yaml")
            val yaml = Files.readString(yamlPath)
            assertTrue(yaml.contains(sha256(token.toByteArray())))
            for (forbidden in listOf(token, "managedTask", "title:", "purpose:", "schedule:")) assertFalse(yaml.contains(forbidden))
            val runAt = Instant.parse(orion.records.getValue(id)["schedule"]["runAt"].asText())
            assertTrue(runAt.isAfter(before.plusSeconds(599)) && runAt.isBefore(Instant.now().plusSeconds(601)))

            val renamed = task(socket, chat, "update", mapOf("taskId" to id, "title" to "Morning mail"), orion)
            assertEquals("Morning mail", renamed["title"].asText())
            assertEquals(yaml, Files.readString(yamlPath), "Title changes must not rewrite the hook")
            val cron = mapOf("kind" to "cron", "expression" to "0 8 * * 1-5", "timeZone" to "Europe/Moscow")
            val rescheduled = task(socket, chat, "update", mapOf("taskId" to id, "schedule" to cron), orion)
            assertEquals("cron", rescheduled["schedule"]["kind"].asText())
            assertEquals(yaml, Files.readString(yamlPath))
            val editorChat = createPublicChat(owner, "edit-from-another-channel")
            withPublicSocket(editorChat) { editorSocket ->
                val updated = task(editorSocket, editorChat, "update", mapOf("taskId" to id, "prompt" to "Check morning email"), orion)
                assertEquals("succeeded", updated["status"].asText())
            }
            assertEquals("scheduler.get", orion.calls.last())

            val delivered = client.post("/hooks/$id") { header("Authorization", "Bearer $token"); jsonBody("{}") }
            assertEquals(202, delivered.status.value)
            val receiptId = delivered.jsonBody()["receiptId"].asText()
            eventually("scheduled hook completes") {
                client.get("/v1/hooks/receipts/$receiptId") { trusted(owner) }.jsonBody().takeIf { it["status"].asText() == "completed" }
            }
            val got = task(socket, chat, "get", mapOf("taskId" to id), orion)
            val savedPrompt = got["prompt"].asText()
            assertTrue(savedPrompt.startsWith("Check morning email\n\n"))
            assertTrue(savedPrompt.contains("channelType=public_client, channelId=$chat"))
            assertFalse(savedPrompt.contains(editorChat), "Editing must preserve the original destination")
            assertTrue(savedPrompt.contains("SendMessageToChannel"))
            // Reading and then updating a complete prompt does not duplicate its delivery context.
            task(socket, chat, "update", mapOf("taskId" to id, "prompt" to savedPrompt), orion)
            assertEquals(savedPrompt, task(socket, chat, "get", mapOf("taskId" to id), orion)["prompt"].asText())
            assertEquals(receiptId, got["lastExecution"]["receiptId"].asText())
            assertTrue(got["lastExecution"]["result"].asText().contains("Check morning email"))
            assertEquals(1, task(socket, chat, "list", emptyMap(), orion)["tasks"].size())

            // Completed schedules remain available for inspection/explicit cleanup.
            orion.records.getValue(id).put("state", "completed").putNull("nextRunAt")
            assertEquals(0, task(socket, chat, "list", emptyMap(), orion)["tasks"].size())
            assertEquals(1, task(socket, chat, "list", mapOf("includeInactive" to true), orion)["tasks"].size())
            val deleted = task(socket, chat, "delete", mapOf("taskId" to id), orion)
            assertEquals("succeeded", deleted["status"].asText(), deleted.toString())
            assertFalse(Files.exists(yamlPath))
            assertEquals(404, client.post("/hooks/$id") { header("Authorization", "Bearer $token"); jsonBody("{}") }.status.value)
            assertEquals(200, client.get("/v1/hooks/receipts/$receiptId") { trusted(owner) }.status.value)
            assertEquals("scheduler_not_found", task(socket, chat, "delete", mapOf("taskId" to id), orion)["error"]["code"].asText())
            for (url in listOf(BackendHttpRoutes.chatEvents(chat), BackendHttpRoutes.chatMessages(chat))) {
                val stored = client.get(url) { trusted(owner) }.bodyAsText()
                assertFalse(stored.contains(token), "Bearer token leaked into durable chat data")
                assertFalse(stored.contains("scheduler.create"), "Internal RPC leaked into replay/history")
            }
            assertFalse(llm.requests.any { it.toString().contains(token) })
            // At-rest credential check includes internal audit data absent from the public projection.
            sql { connection ->
                connection.prepareStatement("select count(*) from tool_calls where name like 'scheduler.%' or arguments_json::text like ? or result_json::text like ?").use { statement ->
                    statement.setString(1, "%$token%")
                    statement.setString(2, "%$token%")
                    statement.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
                }
            }
        }
    }

    @Test
    fun `rejections clean fresh hooks while uncertain mutations preserve truthful partial state`() = runTasks {
        val chat = setup()
        val orion = TestOrion()
        withPublicSocket(chat) { socket ->
            orion.failures["scheduler.create"] = "failed" to "scheduler_invalid_schedule"
            val rejected = task(socket, chat, "create", createArguments(), orion)
            assertEquals("failed", rejected["status"].asText())
            assertEquals("missing", rejected["hookStatus"].asText())
            assertFalse(Files.exists(workspace.resolve("hooks/${rejected["taskId"].asText()}")))

            orion.failures["scheduler.create"] = "timed_out" to "secret-from-Orion-must-not-escape"
            val uncertain = task(socket, chat, "create", createArguments(), orion)
            assertEquals("partial", uncertain["status"].asText())
            assertEquals("unknown", uncertain["scheduleStatus"].asText())
            assertFalse(uncertain.toString().contains("secret-from-Orion"))
            val orphan = uncertain["taskId"].asText()
            val orphanPath = workspace.resolve("hooks/$orphan/hook.yaml")
            val orphanYaml = Files.readString(orphanPath)
            val absent = task(socket, chat, "delete", mapOf("taskId" to orphan), orion)
            assertEquals("not_checked", absent["hookStatus"].asText())
            assertEquals(orphanYaml, Files.readString(orphanPath))

            orion.failures.clear()
            val created = task(socket, chat, "create", createArguments(), orion)
            val id = created["taskId"].asText()
            orion.failures["scheduler.update"] = "timed_out" to "timeout"
            val partialUpdate = task(socket, chat, "update", mapOf("taskId" to id, "prompt" to "Changed instruction", "title" to "Changed title"), orion)
            assertEquals("partial", partialUpdate["status"].asText())
            assertEquals("unknown", partialUpdate["scheduleStatus"].asText())
            assertFalse(partialUpdate.has("schedule"))
            assertTrue(Files.readString(workspace.resolve("hooks/$id/hook.yaml")).contains("Changed instruction"))
            orion.failures["scheduler.delete"] = "timed_out" to "timeout"
            val partialDelete = task(socket, chat, "delete", mapOf("taskId" to id), orion)
            assertEquals("partial", partialDelete["status"].asText())
            assertEquals("disabled", partialDelete["hookStatus"].asText())
            assertEquals(503, client.post("/hooks/$id") { header("Authorization", "Bearer ${orion.secrets.getValue(id)}"); jsonBody("{}") }.status.value)
            assertEquals("hook_disabled", task(socket, chat, "update", mapOf("taskId" to id, "prompt" to "Must not resume"), orion)["error"]["code"].asText())
            orion.failures.clear()
            assertEquals("succeeded", task(socket, chat, "delete", mapOf("taskId" to id), orion)["status"].asText())
            assertTrue(Files.exists(orphanPath), "Unrelated orphan is never implicitly cleaned")
        }
    }

    @Test
    fun `invalid arguments ownership missing records and unavailable Orion cannot alter existing hooks`() = runTasks {
        val chat = setup()
        val otherChat = createPublicChat(UUID.randomUUID().toString(), "other")
        val orion = TestOrion()
        withPublicSocket(chat) { socket ->
            for (invalid in listOf(
                createArguments() + ("ownerUserId" to owner),
                createArguments() + ("prompt" to "x".repeat(16_384)),
                createArguments() + ("schedule" to mapOf("kind" to "after", "seconds" to 1.5)),
                createArguments() + ("schedule" to mapOf("kind" to "cron", "expression" to "0 8 1 * 1", "timeZone" to "UTC")),
                createArguments() + ("schedule" to mapOf("kind" to "once", "runAt" to "2000-01-01T00:00:00Z")),
            )) assertEquals("invalid_arguments", task(socket, chat, "create", invalid, orion)["error"]["code"].asText())
            assertTrue(orion.calls.isEmpty())
            val created = task(socket, chat, "create", createArguments(), orion)
            val id = created["taskId"].asText()
            val path = workspace.resolve("hooks/$id/hook.yaml")
            val original = Files.readString(path)
            for (operation in listOf("get", "update", "delete")) {
                val args = mapOf("taskId" to id) + if (operation == "update") mapOf("prompt" to "Must not change") else emptyMap()
                assertEquals("scheduler_unavailable", task(socket, chat, operation, args + ("channelId" to otherChat), orion)["error"]["code"].asText())
                orion.failures["scheduler.get"] = "failed" to "unavailable"
                assertEquals("not_checked", task(socket, chat, operation, args, orion)["hookStatus"].asText())
                orion.failures.clear()
                val record = orion.records.remove(id)!!
                assertEquals("scheduler_not_found", task(socket, chat, operation, args, orion)["error"]["code"].asText())
                orion.records[id] = record
                assertEquals(original, Files.readString(path))
            }
            // A mismatched response ID is not authority to touch either hook.
            orion.overrideReply = { command, _ -> if (command == "scheduler.get") mapOf("found" to true, "record" to orion.records.getValue(id).deepCopy().put("scheduleId", "task-${UUID.randomUUID()}")) else null }
            assertEquals("scheduler_invalid_response", task(socket, chat, "delete", mapOf("taskId" to id), orion)["error"]["code"].asText())
            assertEquals(original, Files.readString(path))
        }
    }

    @Test
    fun `hook execution creates a one off task via another live channel without a public thread`() = runTasks {
        val chat = setup()
        val orion = TestOrion()
        withPublicSocket(chat) { socket ->
            val parent = task(socket, chat, "create", createArguments() + ("prompt" to "Plan sunset"), orion)["taskId"].asText()
            val savedPrompt = task(socket, chat, "get", mapOf("taskId" to parent), orion)["prompt"].asText()
            val payload = ru.souz.backend.hooks.hookInput(savedPrompt, "{}")
            llm.requestSkillForPrompt(payload, "scheduled-task.create", createArguments() + ("title" to "Sunset light"))
            val accepted = client.post("/hooks/$parent") { header("Authorization", "Bearer ${orion.secrets.getValue(parent)}"); jsonBody("{}") }
            assertEquals(202, accepted.status.value)
            val remote = withTimeout(15_000) { readJson(socket) }
            assertEquals("scheduler.create", remote["payload"]["name"].asText())
            answer(socket, remote, orion)
            assertEquals("ack", readJson(socket)["kind"].asText())
            val receiptId = accepted.jsonBody()["receiptId"].asText()
            eventually("hook-origin scheduler tool completes") {
                client.get("/v1/hooks/receipts/$receiptId") { trusted(owner) }.jsonBody().takeIf { it["status"].asText() == "completed" }
            }
            assertEquals(2, orion.records.size)
            val child = orion.records.keys.single { it != parent }
            val childPrompt = task(socket, chat, "get", mapOf("taskId" to child), orion)["prompt"].asText()
            assertTrue(childPrompt.contains("No user-facing source channel is available"))
            assertFalse(childPrompt.contains("channelId=$chat"), "Scheduler RPC channel is not a reply destination")
            assertEquals("succeeded", task(socket, chat, "delete", mapOf("taskId" to parent), orion)["status"].asText())
            assertTrue(child in orion.records)
            assertTrue(Files.exists(workspace.resolve("hooks/$child/hook.yaml")))
        }
    }

    @Test
    fun `list paginates sanitizes responses and missing hooks remain explicitly visible`() = runTasks {
        val chat = setup()
        val orion = TestOrion()
        withPublicSocket(chat) { socket ->
            val first = task(socket, chat, "create", createArguments(), orion)["taskId"].asText()
            val second = task(socket, chat, "create", createArguments(), orion)["taskId"].asText()
            Files.delete(workspace.resolve("hooks/$second/hook.yaml"))
            orion.overrideReply = { command, args ->
                if (command != "scheduler.list") null else if (!args.has("cursor"))
                    mapOf("records" to listOf(orion.records.getValue(first)), "nextCursor" to "page-2")
                else mapOf("records" to orion.records.values.toList(), "nextCursor" to null)
            }
            val listed = task(socket, chat, "list", mapOf("includeInactive" to true), orion)
            assertEquals(2, listed["tasks"].size(), "Page overlap is deduplicated")
            assertEquals("missing", listed["tasks"].single { it["taskId"].asText() == second }["hookStatus"].asText())
            assertEquals(1, task(socket, chat, "list", emptyMap(), orion)["tasks"].size())
            assertEquals("succeeded", task(socket, chat, "delete", mapOf("taskId" to second), orion)["status"].asText())
            orion.overrideReply = { _, _ -> mapOf("records" to emptyList<Any>(), "nextCursor" to "same-cursor") }
            assertEquals("scheduler_invalid_response", task(socket, chat, "list", emptyMap(), orion)["error"]["code"].asText())
            orion.overrideReply = { _, _ -> mapOf("found" to true, "record" to orion.records.getValue(first).deepCopy().put("bearerToken", "do-not-return-this-secret")) }
            val malformed = task(socket, chat, "get", mapOf("taskId" to first), orion)
            assertEquals("scheduler_invalid_response", malformed["error"]["code"].asText())
            assertFalse(malformed.toString().contains("do-not-return-this-secret"))
        }
    }

    @Test
    fun `workspace limit preserves existing hooks and enabled tool settings govern scheduling`() = runTasks {
        val chat = setup()
        val orion = TestOrion()
        withPublicSocket(chat) { socket ->
            val first = task(socket, chat, "create", createArguments(), orion)["taskId"].asText()
            repeat(99) { Files.createDirectory(workspace.resolve("hooks/empty-$it")) }
            val calls = orion.calls.size
            val limited = task(socket, chat, "create", createArguments(), orion)
            assertEquals("failed", limited["status"].asText(), limited.toString())
            assertEquals("hook_limit_exceeded", limited["error"]["code"].asText())
            assertEquals(calls, orion.calls.size)
            assertEquals("enabled", task(socket, chat, "get", mapOf("taskId" to first), orion)["hookStatus"].asText())
            assertEquals(200, client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(owner); jsonBody("""{"enabledTools":["scheduled-task.get"]}""")
            }.status.value)
            assertEquals("succeeded", task(socket, chat, "get", mapOf("taskId" to first), orion)["status"].asText())
            val before = orion.calls.size
            task(socket, chat, "delete", mapOf("taskId" to first), orion)
            assertEquals(before, orion.calls.size)
            assertTrue(Files.exists(workspace.resolve("hooks/$first/hook.yaml")))
        }
    }

    @Test
    fun `shared workspace ownership and manual edits prevent destructive follow up writes`() {
        val other = UUID.randomUUID().toString()
        val foreignPath = workspace.resolve("hooks/foreign/hook.yaml")
        Files.createDirectories(foreignPath.parent)
        val invalidForeign = "ownerUserId: ${UUID.randomUUID()}\nhookId: foreign\nunknownField: invalid\n"
        Files.writeString(foreignPath, invalidForeign)
        runTasks(setOf(owner, other)) {
            val chat = setup()
            val orion = TestOrion()
            withPublicSocket(chat) { socket ->
                val id = task(socket, chat, "create", createArguments(), orion)["taskId"].asText()
                assertTrue(id in orion.records, "Another owner's invalid definition must not block scheduling")
                val path = workspace.resolve("hooks/$id/hook.yaml")
                val original = Files.readString(path)
                val foreign = original.replace(owner, other)
                Files.writeString(path, foreign)
                for (operation in listOf("update", "delete")) {
                    val args = mapOf("taskId" to id) + if (operation == "update") mapOf("prompt" to "Must not alter another owner") else emptyMap()
                    val result = task(socket, chat, operation, args, orion)
                    assertEquals("hook_configuration_changed", result["error"]["code"].asText())
                    assertEquals(foreign, Files.readString(path))
                    assertTrue(id in orion.records)
                }
                Files.writeString(path, original)
                // An operator changes the file while scheduler.delete is in flight.
                orion.overrideReply = { command, _ ->
                    if (command == "scheduler.delete") Files.writeString(path, Files.readString(path).replace("Remind the owner", "Operator changed this"))
                    null
                }
                val result = task(socket, chat, "delete", mapOf("taskId" to id), orion)
                assertEquals("partial", result["status"].asText())
                assertEquals("missing", result["scheduleStatus"].asText())
                assertEquals("hook_configuration_changed", result["error"]["code"].asText())
                assertTrue(Files.readString(path).contains("Operator changed this"))
                assertFalse(id in orion.records)
                assertEquals(invalidForeign, Files.readString(foreignPath))
            }
        }
    }

    private fun createArguments(): Map<String, Any> = mapOf("title" to "Reminder", "prompt" to "Remind the owner", "schedule" to after)
    private fun runTasks(owners: Set<String> = setOf(owner), block: suspend BackendE2eScope.() -> Unit) = backendE2eTest("e2e_schedules", hookConfig = HookConfig(owners), sandboxFactory = {
        DefaultRuntimeSandboxFactory(it, RuntimeSandboxModeResolver { "local" }, workspace, workspace.resolve("state"), workspace)
    }, block = block)
    private suspend fun BackendE2eScope.setup(): String {
        assertEquals(200, client.patch(BackendHttpRoutes.SETTINGS) {
            trusted(owner); jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","streamingMessages":false}""")
        }.status.value)
        return createPublicChat(owner)
    }

    private suspend fun BackendE2eScope.task(socket: DefaultClientWebSocketSession, chat: String, operation: String, args: Map<String, Any>, orion: TestOrion): JsonNode = withTimeout(20_000) {
        val prompt = UUID.randomUUID().toString()
        llm.requestSkillForPrompt(prompt, "scheduled-task.$operation", args)
        socket.send(Frame.Text(messageFrame(chat, owner, prompt, text = prompt)))
        var done = false
        var submitAcknowledged = false
        while (!done) {
            val frame = readJson(socket)
            when (frame.path("type").asText()) {
                "tool.call.started" -> {
                    assertTrue(submitAcknowledged, "Scheduler RPC must follow the causal input ACK")
                    answer(socket, frame, orion)
                }
                "thread.completed" -> done = true
                "thread.failed" -> error(frame.toString())
            }
            if (frame.path("kind").asText() == "ack") {
                assertEquals("accepted", frame["status"].asText(), frame.toString())
                if (frame.path("requestId").asText() == prompt) submitAcknowledged = true
            }
        }
        val request = llm.requests.last { it.conversationPrompt() == prompt }
        json.readTree(request.messages.last { it.name == "RunSkillCommand" }.content)
    }

    private suspend fun BackendE2eScope.answer(socket: DefaultClientWebSocketSession, frame: JsonNode, orion: TestOrion) {
        assertTrue(frame["seq"].isNull)
        val payload = frame["payload"]
        val command = payload["name"].asText()
        val args = payload["arguments"]
        assertFalse(args.has("channelId") || args.has("userId"))
        orion.calls += command
        val failure = orion.failures[command]
        val response = mutableMapOf<String, Any>("kind" to "tool.result", "chatId" to frame["chatId"].asText(), "threadId" to frame["threadId"].asText(), "toolCallId" to payload["toolCallId"].asText(), "status" to (failure?.first ?: "succeeded"))
        if (failure != null) response["error"] = mapOf("code" to failure.second, "message" to failure.second)
        else response["result"] = orion.overrideReply?.invoke(command, args) ?: when (command) {
            "scheduler.create" -> {
                val id = args["scheduleId"].asText()
                assertEquals(id, args["target"]["hookId"].asText())
                orion.secrets[id] = args["target"]["bearerToken"].asText()
                val record = json.valueToTree<ObjectNode>(mapOf("scheduleId" to id, "title" to args["title"].asText(), "schedule" to args["schedule"], "state" to "active", "nextRunAt" to args["schedule"]["runAt"]?.asText(), "lastDelivery" to null, "createdAt" to Instant.now().toString(), "updatedAt" to Instant.now().toString()))
                orion.records[id] = record
                mapOf("record" to record, "created" to true)
            }
            "scheduler.get" -> orion.records[args["scheduleId"].asText()]?.let { mapOf("found" to true, "record" to it) } ?: mapOf("found" to false)
            "scheduler.list" -> mapOf("records" to orion.records.values.toList(), "nextCursor" to null)
            "scheduler.update" -> {
                val record = orion.records.getValue(args["scheduleId"].asText())
                for (field in listOf("title", "schedule")) args[field]?.let { record.set<JsonNode>(field, it) }
                mapOf("record" to record)
            }
            "scheduler.delete" -> mapOf("scheduleId" to args["scheduleId"].asText(), "removed" to (orion.records.remove(args["scheduleId"].asText()) != null))
            else -> error("Unexpected command $command")
        }
        socket.send(Frame.Text(json.writeValueAsString(response)))
    }

    private class TestOrion {
        val records = linkedMapOf<String, ObjectNode>()
        val secrets = mutableMapOf<String, String>()
        val calls = mutableListOf<String>()
        val failures = mutableMapOf<String, Pair<String, String>>()
        var overrideReply: ((String, JsonNode) -> Any?)? = null
    }
}
