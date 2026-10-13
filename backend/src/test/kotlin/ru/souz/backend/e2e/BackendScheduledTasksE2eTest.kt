package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.scheduler.ScheduledTaskConfig
import ru.souz.backend.scheduler.scheduledTaskInput
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobInfo
import ru.souz.jobs.JobRun
import ru.souz.jobs.JobSchedule
import ru.souz.jobs.JobStatus
import ru.souz.llms.restJsonMapper

class BackendScheduledTasksE2eTest {
    @Test
    fun `a scheduled reminder runs in a hidden chat and reaches the conversation that created it`() {
        val llm = E2eLlmApi()
        backendE2eTest(schemaPrefix = "e2e_scheduled_delivery", llm = llm) {
            val userId = UUID.randomUUID().toString()
            val chatId = setup(userId)
            val created = tool(userId, chatId, "create", mapOf("prompt" to "Remind the user to drink water.", "afterSeconds" to 1))
            assertEquals("pending", created["status"].asText(), created.toString())
            assertEquals("Remind the user to drink water.", created["title"].asText(), "A missing title defaults to the prompt.")
            val occurrence = backend.jobService.listJobs(userId).single().occurrenceInput()
            assertTrue("channelType=public_client, channelId=$chatId" in occurrence, occurrence)
            llm.requestSkillForPrompt(
                occurrence,
                "SendMessageToChannel",
                mapOf("channelType" to "public_client", "channelId" to chatId, "text" to "Drink water"),
            )
            backend.startScheduledTaskWorker()

            val delivered = eventually("reminder delivered", timeout = 15.seconds) {
                client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                    .firstOrNull { it.path("metadata").path("crossChannel").asText() == "true" }
            }
            assertEquals("Drink water", delivered["content"].asText())
            val chats = client.get(BackendHttpRoutes.CHATS) { trusted(userId) }.jsonBody()["items"]
            assertEquals(listOf(chatId), chats.map { it["id"].asText() }, "Hidden occurrence chats stay out of the chat list.")

            assertEquals(0, tool(userId, chatId, "list", emptyMap())["tasks"].size())
            val history = tool(userId, chatId, "list", mapOf("includeInactive" to true))["tasks"].single()
            assertEquals(created["taskId"], history["taskId"])
            assertEquals("succeeded", history["status"].asText())
        }
    }

    @Test
    fun `owners list and cancel only their own tasks, and cancelled tasks never run`() {
        val llm = E2eLlmApi()
        backendE2eTest(schemaPrefix = "e2e_scheduled_owners", llm = llm) {
            val owner = UUID.randomUUID().toString()
            val stranger = UUID.randomUUID().toString()
            val ownerChat = setup(owner)
            val strangerChat = setup(stranger)

            val daily = tool(owner, ownerChat, "create", mapOf("title" to "Morning mail", "prompt" to "Check mail.", "cron" to "0 9 * * *"))
            val listed = tool(owner, ownerChat, "list", emptyMap())["tasks"].single()
            assertEquals("0 9 * * *", listed["schedule"]["cron"].asText())
            assertEquals("Europe/Moscow", listed["schedule"]["timeZone"].asText(), "cron defaults to the user's time zone")
            val nextRun = OffsetDateTime.parse(listed["nextRunAt"].asText()).atZoneSameInstant(ZoneId.of("Europe/Moscow"))
            assertEquals(LocalTime.of(9, 0), nextRun.toLocalTime(), listed.toString())

            assertEquals(0, tool(stranger, strangerChat, "list", mapOf("includeInactive" to true))["tasks"].size())
            val foreignDelete = tool(stranger, strangerChat, "delete", mapOf("taskId" to daily["taskId"].asText()))
            assertTrue("Task not found" in foreignDelete.toString(), foreignDelete.toString())
            assertEquals(1, tool(owner, ownerChat, "list", emptyMap())["tasks"].size())

            assertEquals("cancelled", tool(owner, ownerChat, "delete", mapOf("taskId" to daily["taskId"].asText()))["status"].asText())
            assertEquals(0, tool(owner, ownerChat, "list", emptyMap())["tasks"].size())
            assertEquals("cancelled", tool(owner, ownerChat, "list", mapOf("includeInactive" to true))["tasks"].single()["status"].asText())

            val cancelled = tool(owner, ownerChat, "create", mapOf("title" to "Cancelled", "prompt" to "cancelled occurrence", "afterSeconds" to 1))
            tool(owner, ownerChat, "delete", mapOf("taskId" to cancelled["taskId"].asText()))
            tool(owner, ownerChat, "create", mapOf("title" to "Control", "prompt" to "control occurrence", "afterSeconds" to 1))
            backend.startScheduledTaskWorker()
            eventually("control occurrence", timeout = 15.seconds) {
                llm.requests.firstOrNull { it.conversationPrompt().startsWith("control occurrence") }
            }
            assertTrue(llm.requests.none { it.conversationPrompt().startsWith("cancelled occurrence") })
        }
    }

    @Test
    fun `occurrences run concurrently up to the limit and a stalled run frees its slot at the timeout`() {
        val llm = E2eLlmApi()
        backendE2eTest(
            schemaPrefix = "e2e_scheduled_concurrency",
            llm = llm,
            scheduledTasks = ScheduledTaskConfig(concurrentRuns = 2, runTimeoutMillis = 3_000),
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = setup(userId)
            repeat(3) { index ->
                tool(userId, chatId, "create", mapOf("title" to "Task $index", "prompt" to "Task $index.", "afterSeconds" to 1))
            }
            val jobs = backend.jobService.listJobs(userId)
            val occurrences = jobs.associate { it.id to it.occurrenceInput() }
            occurrences.values.forEach(llm::pausePromptUntilReleased) // Never released: only the timeout ends them.
            fun started() = occurrences.filterValues { prompt -> llm.requests.any { it.conversationPrompt() == prompt } }.keys
            // Hidden chats have no route, so their executions are read directly.
            fun cancelled(): Int = sql { connection ->
                connection.prepareStatement("""
                    select count(*) from agent_executions e join chats c on c.id = e.chat_id
                    where c.client_type = 'scheduled_task' and e.status in ('cancelling', 'cancelled')
                """.trimIndent()).use { statement -> statement.executeQuery().use { it.next(); it.getInt(1) } }
            }
            // One claim sweep must see every occurrence due, or a poll interval would separate them.
            val due = jobs.maxOf { it.scheduledAt }.plusSeconds(1)
            eventually("every occurrence due") { due.takeIf { Instant.now().isAfter(it) } }
            backend.startScheduledTaskWorker()

            val running = eventually("two occurrences running at once", timeout = 10.seconds) { started().takeIf { it.size == 2 } }
            assertEquals(0, cancelled(), "Both occurrences must start before either run times out.")
            val waiting = occurrences.keys.single { it !in running }
            eventually("the third occurrence claimed and waiting for a slot") {
                backend.jobService.listJobs(userId).single { it.id == waiting }.takeIf { it.status == JobStatus.RUNNING }
            }
            assertEquals(running, started())
            eventually("the third occurrence starts after the timeout", timeout = 10.seconds) { waiting.takeIf { it in started() } }
            eventually<Int>("timed-out occurrences cancelled") { cancelled().takeIf { it >= 2 } }
        }
    }

    @Test
    fun `create rejects invalid schedules and schedules beyond the daily run budget shared by active tasks`() {
        backendE2eTest(schemaPrefix = "e2e_scheduled_validation", scheduledTasks = ScheduledTaskConfig(runsPerUserPerDay = 25)) {
            val userId = UUID.randomUUID().toString()
            val chatId = setup(userId)
            val base = mapOf("title" to "Task", "prompt" to "Do it.")
            listOf(
                base to "exactly one of runAt, afterSeconds or cron",
                base + mapOf("afterSeconds" to 60, "cron" to "0 9 * * *") to "exactly one of runAt, afterSeconds or cron",
                base + ("runAt" to "2020-01-01T09:00:00+03:00") to "future RFC 3339",
                base + ("afterSeconds" to 0) to "afterSeconds must be positive",
                base + mapOf("cron" to "0 9 * * *", "timeZone" to "Mars/Base") to "IANA timeZone",
                base + mapOf("cron" to "*/10 * * * * *", "timeZone" to "UTC") to "6 parts",
                base + mapOf("cron" to "* * * * *", "timeZone" to "UTC") to "Daily run budget exceeded: this task needs 1440",
            ).forEach { (arguments, error) ->
                val result = tool(userId, chatId, "create", arguments).toString()
                assertTrue(error in result, "arguments=$arguments result=$result")
            }
            assertEquals(0, tool(userId, chatId, "list", mapOf("includeInactive" to true))["tasks"].size())

            val hourly = base + mapOf("cron" to "0 * * * *", "timeZone" to "UTC")
            val first = tool(userId, chatId, "create", hourly)["taskId"].asText()
            val second = tool(userId, chatId, "create", hourly).toString()
            assertTrue("1 of 25 remain" in second, second)
            tool(userId, chatId, "delete", mapOf("taskId" to first))
            assertTrue(tool(userId, chatId, "create", hourly).has("taskId"), "Cancelling a task returns its runs to the budget.")
        }
    }

    @Test
    fun `a recurring task survives an occurrence that cannot start while a one-off task retries`() {
        // A single slot: a slot leaked by a failed start would block the second occurrence.
        backendE2eTest(schemaPrefix = "e2e_scheduled_failures", scheduledTasks = ScheduledTaskConfig(concurrentRuns = 1)) {
            // Hidden chats need an existing user, so every occurrence of this owner fails to start.
            val missingUser = UUID.randomUUID().toString()
            fun payload(recurring: Boolean) = restJsonMapper.createObjectNode().put("prompt", "Do it.").put("recurring", recurring)
            val recurring = backend.jobService.createJob(
                missingUser, CreateJobRequest("Daily", payload(true), JobSchedule.Cron("0 9 * * *", "UTC")),
            )
            val oneOff = backend.jobService.createJob(missingUser, CreateJobRequest("Once", payload(false)))
            sql { connection ->
                connection.prepareStatement("update jobs set available_at = now() where id = ?").use {
                    it.setObject(1, recurring.id)
                    it.executeUpdate()
                }
            }
            backend.startScheduledTaskWorker()

            val jobs = eventually("both occurrences processed", timeout = 15.seconds) {
                backend.jobService.listJobs(missingUser).associateBy { it.id }
                    .takeIf { jobs -> jobs.values.all { it.lastFinishedAt != null } }
            }
            with(jobs.getValue(recurring.id)) {
                assertEquals(JobStatus.PENDING, status, "The schedule continues to its next occurrence.")
                assertEquals(0, attempts)
                assertEquals(null, lastError)
            }
            with(jobs.getValue(oneOff.id)) {
                assertEquals(JobStatus.PENDING, status)
                assertEquals(1, attempts)
                assertTrue(lastError != null, "A one-off task keeps its retry.")
            }
        }
    }

    private suspend fun BackendE2eScope.setup(userId: String): String {
        val chatId = createPublicChat(userId, "create-$userId")
        val settings = client.patch(BackendHttpRoutes.SETTINGS) {
            trusted(userId)
            jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","streamingMessages":false,"timeZone":"Europe/Moscow"}""")
        }
        assertEquals(HttpStatusCode.OK, settings.status)
        return chatId
    }

    /** Runs one turn whose scripted model calls scheduled-task.[operation] and returns the tool result. */
    private suspend fun BackendE2eScope.tool(
        userId: String,
        chatId: String,
        operation: String,
        arguments: Map<String, Any>,
    ): JsonNode {
        val prompt = "scheduled-task.$operation ${UUID.randomUUID()}"
        llm.requestSkillForPrompt(prompt, "scheduled-task.$operation", arguments)
        val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
            trusted(userId)
            jsonBody("""{"content":"$prompt"}""")
        }
        assertEquals(HttpStatusCode.OK, sent.status)
        backend.awaitExecution(UUID.fromString(sent.jsonBody()["execution"]["id"].asText()))
        val request = llm.requests.last { it.conversationPrompt() == prompt }
        return json.readTree(request.messages.last { it.name == "RunSkillCommand" }.content)
    }

    /** The hidden-chat input of this job's current occurrence, as the runner builds it. */
    private fun JobInfo.occurrenceInput(): String =
        scheduledTaskInput(JobRun(id, userId, title, payload, scheduledAt, attempt = 1))
}
