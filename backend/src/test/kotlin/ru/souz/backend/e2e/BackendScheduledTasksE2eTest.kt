package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.scheduler.ScheduledTaskConfig
import ru.souz.backend.scheduler.occurrenceId
import ru.souz.backend.scheduler.scheduledTaskInput
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobInfo
import ru.souz.jobs.JobRun
import ru.souz.jobs.JobStatus
import ru.souz.llms.restJsonMapper

class BackendScheduledTasksE2eTest {
    @Test
    fun `competing handlers and recovered attempts reuse one execution even after completion`() {
        backendE2eTest(schemaPrefix = "e2e_scheduled_dedup") {
            val userId = UUID.randomUUID().toString()
            setup(userId)
            val job = JobRun(UUID.randomUUID(), userId, "Once", restJsonMapper.createObjectNode().put("prompt", "Check mail."),
                Instant.parse("2026-10-13T12:00:00Z"), attempt = 1)
            val prompt = scheduledTaskInput(job)
            val chatId = job.occurrenceId("chat").toString()
            val executionId = job.occurrenceId("execution")
            llm.pausePromptUntilReleased(prompt)
            withPeerBackend(llm = llm) { peer ->
                // Both hosts contend while execution insertion is blocked in PostgreSQL.
                withTableLocked("agent_executions", "share") {
                    launch { backend.runScheduledTask(job) }
                    launch { peer.backend.runScheduledTask(job.copy(attempt = 2)) }
                    awaitLockWait("agent_executions")
                }
                withTimeout(10.seconds) { llm.awaitPrompt(prompt) }
                assertEquals(1, llm.requests.size)
                llm.releasePrompt(prompt)
                backend.awaitExecution(executionId)
                peer.backend.awaitExecution(executionId)
                val completed = assertNotNull(backend.executionRepository.get(userId, executionId))
                assertEquals(AgentExecutionStatus.COMPLETED, completed.status)

                backend.runScheduledTask(job.copy(attempt = 3))
                peer.backend.runScheduledTask(job.copy(attempt = 4, title = "Renamed"))
                assertEquals(completed, backend.executionRepository.get(userId, executionId))
                assertEquals(1, llm.requests.size)
                assertEquals(1, dailyLaunches(userId), "Competing and recovered dispatches reserve only one launch.")
                val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                assertEquals(listOf("user", "assistant"), messages.map { it["role"].asText() })

                val next = job.copy(scheduledAt = job.scheduledAt.plusSeconds(60))
                peer.backend.runScheduledTask(next)
                peer.backend.awaitExecution(next.occurrenceId("execution"))
                val nextExecution = assertNotNull(backend.executionRepository.get(userId, next.occurrenceId("execution")))
                assertTrue(nextExecution.chatId != completed.chatId)
                assertEquals(2, llm.requests.size, "A new occurrence of the same job still runs.")
                assertEquals(2, dailyLaunches(userId))
            }
        }
    }

    @Test
    fun `a recovered occurrence persisted before launch remains unexecuted`() {
        backendE2eTest(schemaPrefix = "e2e_scheduled_crash") {
            val userId = UUID.randomUUID().toString()
            setup(userId)
            val job = JobRun(UUID.randomUUID(), userId, "Interrupted", restJsonMapper.createObjectNode().put("prompt", "Check mail."),
                Instant.parse("2026-10-13T12:00:00Z"), attempt = 1)
            val executionId = job.occurrenceId("execution")
            withTableLocked("messages", "share") {
                val delivery = launch { backend.runScheduledTask(job) }
                awaitLockWait("messages")
                assertEquals(AgentExecutionStatus.QUEUED, backend.executionRepository.get(userId, executionId)?.status)
                delivery.cancel() // The execution is committed, but the agent has not launched.
            }
            val persisted = assertNotNull(backend.executionRepository.get(userId, executionId))
            withPeerBackend { peer ->
                peer.backend.runScheduledTask(job.copy(attempt = 2))
                assertEquals(persisted, peer.backend.executionRepository.get(userId, executionId))
                assertTrue(llm.requests.isEmpty() && peer.llm.requests.isEmpty())
                assertEquals(1, dailyLaunches(userId), "Persistence reserves quota even if launch is lost.")
            }
        }
    }

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

            assertEquals(0, tool(userId, chatId, "list")["tasks"].size())
            val history = tool(userId, chatId, "list", mapOf("includeInactive" to true))["tasks"].single()
            assertEquals(created["taskId"], history["taskId"])
            assertEquals("succeeded", history["status"].asText())
        }
    }

    @Test
    fun `owners list and cancel only their own tasks, and cancelled tasks never run`() {
        backendE2eTest(schemaPrefix = "e2e_scheduled_owners") {
            val owner = UUID.randomUUID().toString()
            val stranger = UUID.randomUUID().toString()
            val ownerChat = setup(owner)
            val strangerChat = setup(stranger)

            val daily = tool(owner, ownerChat, "create", mapOf("title" to "Morning mail", "prompt" to "Check mail.", "cron" to "0 9 * * *"))
            val listed = tool(owner, ownerChat, "list")["tasks"].single()
            assertEquals("0 9 * * *", listed["schedule"]["cron"].asText())
            assertEquals("Europe/Moscow", listed["schedule"]["timeZone"].asText(), "cron defaults to the user's time zone")
            val nextRun = OffsetDateTime.parse(listed["nextRunAt"].asText()).atZoneSameInstant(ZoneId.of("Europe/Moscow"))
            assertEquals(LocalTime.of(9, 0), nextRun.toLocalTime(), listed.toString())

            assertEquals(0, tool(stranger, strangerChat, "list", mapOf("includeInactive" to true))["tasks"].size())
            val foreignDelete = tool(stranger, strangerChat, "delete", mapOf("taskId" to daily["taskId"].asText()))
            assertTrue("Task not found" in foreignDelete.toString(), foreignDelete.toString())
            assertEquals(1, tool(owner, ownerChat, "list")["tasks"].size())

            assertEquals("cancelled", tool(owner, ownerChat, "delete", mapOf("taskId" to daily["taskId"].asText()))["status"].asText())
            assertEquals(0, tool(owner, ownerChat, "list")["tasks"].size())
            assertEquals("cancelled", tool(owner, ownerChat, "list", mapOf("includeInactive" to true))["tasks"].single()["status"].asText())

            val cancelled = tool(owner, ownerChat, "create", mapOf("title" to "Cancelled", "prompt" to "cancelled occurrence", "afterSeconds" to 1))
            tool(owner, ownerChat, "delete", mapOf("taskId" to cancelled["taskId"].asText()))
            tool(owner, ownerChat, "create", mapOf("prompt" to "control occurrence", "runAt" to Instant.now().plusSeconds(1).toString()))
            backend.startScheduledTaskWorker()
            eventually("control occurrence", timeout = 15.seconds) {
                llm.requests.firstOrNull { it.conversationPrompt().startsWith("control occurrence") }
            }
            assertTrue(llm.requests.none { it.conversationPrompt().startsWith("cancelled occurrence") })
        }
    }

    @Test
    fun `occurrences run concurrently up to the limit and a stalled run frees its slot at the timeout`() {
        val started = Channel<Unit>(Channel.UNLIMITED)
        val cancelled = Channel<Unit>(Channel.UNLIMITED)
        val llm = E2eLlmApi {
            started.send(Unit)
            try { awaitCancellation() } finally { cancelled.trySend(Unit) }
        }
        backendE2eTest(
            schemaPrefix = "e2e_scheduled_concurrency",
            llm = llm,
            scheduledTasks = ScheduledTaskConfig(concurrentRuns = 2, runTimeoutMillis = 3_000),
        ) {
            val userId = UUID.randomUUID().toString()
            setup(userId)
            val payload = restJsonMapper.createObjectNode().put("prompt", "Run the task.")
            // A failed start must release its slot before valid occurrences run.
            backend.jobService.createJob("missing-user", CreateJobRequest("Missing owner", payload))
            repeat(3) { index ->
                backend.jobService.createJob(userId, CreateJobRequest("Task $index", payload))
            }
            backend.startScheduledTaskWorker()
            withTimeout(10.seconds) {
                repeat(2) { started.receive() }
                eventually("third occurrence waiting for a slot") {
                    backend.jobService.listJobs(userId).single { it.title == "Task 2" }.takeIf { it.status == JobStatus.RUNNING }
                }
                assertTrue(started.tryReceive().isFailure, "Only two occurrences may start.")
                assertTrue(cancelled.tryReceive().isFailure, "Both occurrences start before either times out.")
                cancelled.receive()
                started.receive() // Timeout frees a slot for the third occurrence.
            }
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
    fun `completed one-offs spend durable quota while retries are free and a new UTC day has a new allowance`() {
        backendE2eTest(schemaPrefix = "e2e_scheduled_daily", scheduledTasks = ScheduledTaskConfig(runsPerUserPerDay = 2)) {
            val userId = UUID.randomUUID().toString()
            val chatId = setup(userId)
            backend.startScheduledTaskWorker()
            val completed = (1..2).map { index ->
                val result = tool(userId, chatId, "create", mapOf("prompt" to "Task $index", "afterSeconds" to 1))
                val id = UUID.fromString(result["taskId"].asText())
                val job = eventually("completed one-off", timeout = 15.seconds) {
                    backend.jobService.listJobs(userId).single { it.id == id }.takeIf { it.status == JobStatus.SUCCEEDED }
                }
                backend.awaitExecution(job.run().occurrenceId("execution"))
                job
            }
            // Completed jobs have left the planning budget; the dispatch quota still applies.
            val thirdId = tool(userId, chatId, "create", mapOf("prompt" to "Third", "afterSeconds" to 3600))["taskId"].asText()
            val third = backend.jobService.listJobs(userId).single { it.id.toString() == thirdId }.run()
            withPeerBackend(llm = llm) { peer ->
                completed.forEach { peer.backend.runScheduledTask(it.run().copy(attempt = 2)) }
                assertFailsWith<IllegalStateException> { peer.backend.runScheduledTask(third) }
                assertNull(peer.backend.executionRepository.get(userId, third.occurrenceId("execution")))
                assertEquals(2, dailyLaunches(userId))

                val otherUser = UUID.randomUUID().toString()
                setup(otherUser)
                peer.backend.runScheduledTask(third.copy(userId = otherUser, jobId = UUID.randomUUID()))
                assertEquals(1, dailyLaunches(otherUser))

                // Deterministically move the persisted usage into the prior UTC day.
                sql { connection -> connection.prepareStatement(
                    "update scheduled_task_daily_launches set day = day - 1 where user_id = ?"
                ).use { it.setString(1, userId); it.executeUpdate() } }
                completed.forEach { peer.backend.runScheduledTask(it.run().copy(attempt = 3)) }
                assertEquals(0, dailyLaunches(userId), "Duplicates remain free across day boundaries.")
                peer.backend.runScheduledTask(third.copy(attempt = 2))
                peer.backend.awaitExecution(third.occurrenceId("execution"))
                assertEquals(1, dailyLaunches(userId))
            }
        }
    }

    @Test
    fun `cron bursts beyond the planning week cannot race past the dispatch quota across replicas`() {
        backendE2eTest(
            schemaPrefix = "e2e_scheduled_burst", scheduledTasks = ScheduledTaskConfig(runsPerUserPerDay = 2),
            clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC),
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = setup(userId)
            val created = tool(userId, chatId, "create", mapOf("prompt" to "Monthly burst", "cron" to "* * 1 * *", "timeZone" to "UTC"))
            assertTrue(created.has("taskId"), created.toString())
            val job = backend.jobService.listJobs(userId).single().run()
            val occurrences = (0..3).map { job.copy(scheduledAt = Instant.parse("2026-11-01T00:00:00Z").plusSeconds(it * 60L)) }
            withPeerBackend(llm = llm) { peer ->
                val dispatched = mutableListOf<JobRun>()
                withTableLocked("scheduled_task_daily_launches", "share") {
                    occurrences.forEachIndexed { index, occurrence -> launch {
                        try {
                            (if (index % 2 == 0) backend else peer.backend).runScheduledTask(occurrence)
                            dispatched.add(occurrence)
                        } catch (error: IllegalStateException) {
                            assertTrue("quota exceeded" in error.message.orEmpty())
                        }
                    } }
                    awaitLockWait("scheduled_task_daily_launches")
                }
                assertEquals(2, dispatched.size)
                dispatched.forEach {
                    backend.awaitExecution(it.occurrenceId("execution"))
                    peer.backend.awaitExecution(it.occurrenceId("execution"))
                    peer.backend.runScheduledTask(it.copy(attempt = 2))
                }
                assertEquals(2, dailyLaunches(userId))
                assertEquals(2, llm.requests.count { it.conversationPrompt().startsWith("Monthly burst\n") })
                assertEquals(2, occurrences.count { backend.executionRepository.get(userId, it.occurrenceId("execution")) != null })
            }
        }
    }

    @Test
    fun `reminders require delivery permission at creation and revoked permission records an exhausted occurrence`() {
        backendE2eTest(schemaPrefix = "e2e_scheduled_permissions") {
            val userId = UUID.randomUUID().toString()
            val chatId = setup(userId)
            val reminder = tool(userId, chatId, "create", mapOf("prompt" to "Reminder", "cron" to "0 9 * * *"))
            val id = UUID.fromString(reminder["taskId"].asText())
            val original = backend.jobService.listJobs(userId).single().run()
            val settings = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"enabledTools":["scheduled-task.create","scheduled-task.list","scheduled-task.delete"]}""")
            }
            assertEquals(HttpStatusCode.OK, settings.status)
            val rejected = tool(userId, chatId, "create", mapOf("prompt" to "Another reminder", "afterSeconds" to 60))
            assertTrue("require enabled SendMessageToChannel" in rejected.toString(), rejected.toString())
            val action = tool(userId, chatId, "create", mapOf("prompt" to "Action only", "afterSeconds" to 3600, "requiresMessage" to false))
            assertTrue(action.has("taskId"), action.toString())
            backend.startScheduledTaskWorker()
            repeat(3) { attempt ->
                makeDue(id)
                eventually<JobInfo>("permission failure attempt ${attempt + 1}", timeout = 15.seconds) {
                    backend.jobService.listJobs(userId).single { it.id == id }.takeIf {
                        it.status == JobStatus.PENDING && if (attempt == 2) it.lastFailure != null else it.attempts == attempt + 1
                    }
                }
            }
            val history = tool(userId, chatId, "list")["tasks"].single { it["taskId"].asText() == id.toString() }
            assertEquals("pending", history["status"].asText())
            assertEquals(original.scheduledAt.toString(), history["lastFailure"]["scheduledAt"].asText())
            assertTrue("SendMessageToChannel" in history["lastFailure"]["error"].asText())
            assertNull(backend.executionRepository.get(userId, original.occurrenceId("execution")))
            assertEquals(0, dailyLaunches(userId))
            assertTrue(llm.requests.none { it.conversationPrompt() == scheduledTaskInput(original) })
            val actionJob = backend.jobService.listJobs(userId).single { it.id.toString() == action["taskId"].asText() }.run()
            backend.runScheduledTask(actionJob)
            backend.awaitExecution(actionJob.occurrenceId("execution"))
            assertEquals(AgentExecutionStatus.COMPLETED, backend.executionRepository.get(userId, actionJob.occurrenceId("execution"))?.status)
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"enabledTools":["scheduled-task.create","scheduled-task.list","scheduled-task.delete","SendMessageToChannel"]}""")
            }
            val next = backend.jobService.listJobs(userId).single { it.id == id }.run()
            assertTrue(next.scheduledAt > original.scheduledAt)
            backend.runScheduledTask(next)
            backend.awaitExecution(next.occurrenceId("execution"))
            assertNotNull(backend.executionRepository.get(userId, next.occurrenceId("execution")))
        }
    }

    @Test
    fun `a persisted start failure is never relaunched or reported as successful on recovery`() {
        backendE2eTest(schemaPrefix = "e2e_scheduled_start_failure") {
            val userId = UUID.randomUUID().toString()
            setup(userId)
            val run = JobRun(UUID.randomUUID(), userId, "Start fails", restJsonMapper.createObjectNode().put("prompt", "Check mail."),
                Instant.parse("2026-10-13T12:00:00Z"), attempt = 1)
            sql { connection -> connection.createStatement().use { statement ->
                statement.execute("""
                    create function reject_scheduled_input() returns trigger language plpgsql as ${'$'}${'$'}
                    begin raise exception 'input persistence unavailable'; end;
                    ${'$'}${'$'}
                """.trimIndent())
                statement.execute("create trigger reject_scheduled_input before insert on messages for each row execute function reject_scheduled_input()")
            } }
            assertFailsWith<BackendV1Exception> { backend.runScheduledTask(run) }
            val failed = assertNotNull(backend.executionRepository.get(userId, run.occurrenceId("execution")))
            assertEquals(AgentExecutionStatus.FAILED, failed.status)
            sql { connection -> connection.createStatement().use { it.execute("drop trigger reject_scheduled_input on messages") } }
            withPeerBackend { peer ->
                assertFailsWith<IllegalStateException> { peer.backend.runScheduledTask(run.copy(attempt = 2)) }
                assertEquals(failed, peer.backend.executionRepository.get(userId, failed.id))
                assertEquals(1, dailyLaunches(userId))
                assertTrue(llm.requests.isEmpty() && peer.llm.requests.isEmpty())
                val next = run.copy(scheduledAt = run.scheduledAt.plusSeconds(60))
                peer.backend.runScheduledTask(next)
                peer.backend.awaitExecution(next.occurrenceId("execution"))
                assertEquals(AgentExecutionStatus.COMPLETED, peer.backend.executionRepository.get(userId, next.occurrenceId("execution"))?.status)
            }
        }
    }

    private fun BackendE2eScope.dailyLaunches(userId: String): Int = sql { connection ->
        connection.prepareStatement("select launches from scheduled_task_daily_launches where user_id = ? and day = (clock_timestamp() at time zone 'UTC')::date")
            .use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
            }
    }

    private fun BackendE2eScope.makeDue(id: UUID) = sql { connection ->
        connection.prepareStatement("update jobs set available_at = clock_timestamp() - interval '1 second' where id = ?")
            .use { it.setObject(1, id); it.executeUpdate() }
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
        arguments: Map<String, Any> = emptyMap(),
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
        scheduledTaskInput(run())

    private fun JobInfo.run(): JobRun = JobRun(id, userId, title, payload, scheduledAt, attempt = 1)
}
