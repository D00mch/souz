package ru.souz.backend.e2e

import io.ktor.client.request.get
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.scheduler.ScheduledTaskConfig
import ru.souz.backend.scheduler.occurrenceId
import ru.souz.backend.scheduler.scheduledTaskInput
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobRun
import ru.souz.jobs.JobSchedule
import ru.souz.jobs.JobStatus
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

class BackendScheduledTasksE2eTest {
    @Test
    fun `at most once lets competing handlers and recovered attempts reuse one execution even after completion`() {
        backendE2eTest(schemaPrefix = "e2e_scheduled_dedup") {
            val userId = UUID.randomUUID().toString()
            scheduledTaskSetup(userId)
            val job = JobRun(UUID.randomUUID(), userId, "Once", restJsonMapper.createObjectNode().put("prompt", "Check mail.").put("atMostOnce", true),
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
                val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                assertEquals(listOf("user", "assistant"), messages.map { it["role"].asText() })

                val next = job.copy(scheduledAt = job.scheduledAt.plusSeconds(60))
                peer.backend.runScheduledTask(next)
                peer.backend.awaitExecution(next.occurrenceId("execution"))
                val nextExecution = assertNotNull(backend.executionRepository.get(userId, next.occurrenceId("execution")))
                assertTrue(nextExecution.chatId != completed.chatId)
                assertEquals(2, llm.requests.size, "A new occurrence of the same job still runs.")
            }
        }
    }

    @Test
    fun `at least once relaunches a recovered attempt while competing handlers of one attempt launch once`() {
        backendE2eTest(schemaPrefix = "e2e_scheduled_at_least_once") {
            val userId = UUID.randomUUID().toString()
            scheduledTaskSetup(userId)
            // No atMostOnce flag: the default semantics.
            val job = JobRun(UUID.randomUUID(), userId, "Pills", restJsonMapper.createObjectNode().put("prompt", "Take pills."),
                Instant.parse("2026-10-13T12:00:00Z"), attempt = 1)
            val prompt = scheduledTaskInput(job)
            backend.runScheduledTask(job)
            backend.runScheduledTask(job)
            backend.awaitExecution(job.occurrenceId("execution"))
            assertEquals(1, llm.requests.count { it.conversationPrompt() == prompt }, "One attempt launches once.")

            // The worker hands the same occurrence out again after an uncertain outcome.
            val retry = job.copy(attempt = 2)
            backend.runScheduledTask(retry)
            backend.awaitExecution(retry.occurrenceId("execution"))
            assertEquals(2, llm.requests.count { it.conversationPrompt() == prompt }, "A recovered attempt runs again.")
            assertTrue(retry.occurrenceId("chat") != job.occurrenceId("chat"))
        }
    }

    @Test
    fun `at most once leaves a recovered occurrence persisted before launch unexecuted`() {
        backendE2eTest(schemaPrefix = "e2e_scheduled_crash") {
            val userId = UUID.randomUUID().toString()
            scheduledTaskSetup(userId)
            val job = JobRun(UUID.randomUUID(), userId, "Interrupted", restJsonMapper.createObjectNode().put("prompt", "Check mail.").put("atMostOnce", true),
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
            }
        }
    }

    @Test
    fun `a scheduled reminder runs in a hidden chat and reaches the conversation that created it`() {
        val llm = E2eLlmApi()
        backendE2eTest(schemaPrefix = "e2e_scheduled_delivery", llm = llm) {
            val userId = UUID.randomUUID().toString()
            val chatId = scheduledTaskSetup(userId)
            val created = scheduledTaskTool(userId, chatId, "create", mapOf("prompt" to "Remind the user to drink water.", "afterSeconds" to 1))
            assertEquals("pending", created["status"].asText(), created.toString())
            assertEquals("Remind the user to drink water.", created["title"].asText(), "A missing title defaults to the prompt.")
            val occurrence = backend.jobService.listJobs(userId).items.single().occurrenceInput()
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

            assertEquals(0, scheduledTaskTool(userId, chatId, "list")["tasks"].size())
            val history = scheduledTaskTool(userId, chatId, "list", mapOf("includeInactive" to true))["tasks"].single()
            assertEquals(created["taskId"], history["taskId"])
            assertEquals("succeeded", history["status"].asText())
        }
    }

    @Test
    fun `owners list and cancel only their own tasks, and cancelled tasks never run`() {
        backendE2eTest(schemaPrefix = "e2e_scheduled_owners") {
            val owner = UUID.randomUUID().toString()
            val stranger = UUID.randomUUID().toString()
            val ownerChat = scheduledTaskSetup(owner)
            val strangerChat = scheduledTaskSetup(stranger)

            val daily = scheduledTaskTool(owner, ownerChat, "create", mapOf("title" to "Morning mail", "prompt" to "Check mail.", "cron" to "0 9 * * *"))
            val listed = scheduledTaskTool(owner, ownerChat, "list")["tasks"].single()
            assertEquals("0 9 * * *", listed["schedule"]["cron"].asText())
            assertEquals("Europe/Moscow", listed["schedule"]["timeZone"].asText(), "cron defaults to the user's time zone")
            val nextRun = OffsetDateTime.parse(listed["nextRunAt"].asText()).atZoneSameInstant(ZoneId.of("Europe/Moscow"))
            assertEquals(LocalTime.of(9, 0), nextRun.toLocalTime(), listed.toString())

            assertEquals(0, scheduledTaskTool(stranger, strangerChat, "list", mapOf("includeInactive" to true))["tasks"].size())
            val foreignDelete = scheduledTaskTool(stranger, strangerChat, "delete", mapOf("taskId" to daily["taskId"].asText()))
            assertTrue("Task not found" in foreignDelete.toString(), foreignDelete.toString())
            assertEquals(1, scheduledTaskTool(owner, ownerChat, "list")["tasks"].size())

            assertEquals("cancelled", scheduledTaskTool(owner, ownerChat, "delete", mapOf("taskId" to daily["taskId"].asText()))["status"].asText())
            assertEquals(0, scheduledTaskTool(owner, ownerChat, "list")["tasks"].size())
            assertEquals("cancelled", scheduledTaskTool(owner, ownerChat, "list", mapOf("includeInactive" to true))["tasks"].single()["status"].asText())

            val cancelled = scheduledTaskTool(owner, ownerChat, "create", mapOf("title" to "Cancelled", "prompt" to "cancelled occurrence", "afterSeconds" to 1))
            scheduledTaskTool(owner, ownerChat, "delete", mapOf("taskId" to cancelled["taskId"].asText()))
            scheduledTaskTool(owner, ownerChat, "create", mapOf("prompt" to "control occurrence", "runAt" to Instant.now().plusSeconds(1).toString()))
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
            scheduledTaskSetup(userId)
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
                    backend.jobService.listJobs(userId).items.single { it.title == "Task 2" }.takeIf { it.status == JobStatus.RUNNING }
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
            val chatId = scheduledTaskSetup(userId)
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
                val result = scheduledTaskTool(userId, chatId, "create", arguments).toString()
                assertTrue(error in result, "arguments=$arguments result=$result")
            }
            assertEquals(0, scheduledTaskTool(userId, chatId, "list", mapOf("includeInactive" to true))["tasks"].size())

            val hourly = base + mapOf("cron" to "0 * * * *", "timeZone" to "UTC")
            val first = scheduledTaskTool(userId, chatId, "create", hourly)["taskId"].asText()
            val second = scheduledTaskTool(userId, chatId, "create", hourly).toString()
            assertTrue("1 of 25 remain" in second, second)
            scheduledTaskTool(userId, chatId, "delete", mapOf("taskId" to first))
            assertTrue(scheduledTaskTool(userId, chatId, "create", hourly).has("taskId"), "Cancelling a task returns its runs to the budget.")
        }
    }

    @Test
    fun `scheduled executions never wait for an option answer even when options are enabled`() {
        val scripted = ScriptedOptionTurnRunner()
        val runner = object : BackendConversationTurnRunner {
            override suspend fun run(
                conversationKey: AgentConversationKey,
                request: BackendConversationTurnRequest,
                eventSink: AgentRuntimeEventSink,
                initialUsage: LLMResponse.Usage,
            ): BackendConversationTurnOutcome {
                val waiting = scripted.run(conversationKey, request, eventSink, initialUsage)
                // Like the production runner, the turn waits only when the sink accepted the choice.
                return if ((eventSink as BackendAgentRuntimeEventSink).hasRequestedOption) waiting
                else BackendConversationTurnOutcome.Completed("Done", waiting.usage, waiting.session)
            }
        }
        backendE2eTest(
            schemaPrefix = "e2e_scheduled_options",
            featureFlags = BackendFeatureFlags(wsEvents = true, options = true),
            turnRunnerOverride = runner,
        ) {
            val owner = UUID.randomUUID().toString()
            scheduledTaskSetup(owner)
            val run = backend.jobService.createJob(owner, CreateJobRequest("Ask", restJsonMapper.createObjectNode().put("prompt", "Ask me."))).run()
            backend.runScheduledTask(run)
            backend.awaitExecution(run.occurrenceId("execution"))
            val execution = assertNotNull(backend.executionRepository.get(owner, run.occurrenceId("execution")))
            assertEquals(AgentExecutionStatus.COMPLETED, execution.status)
            val events = client.get(BackendHttpRoutes.chatEvents(execution.chatId)) { trusted(owner) }.jsonBody()["items"]
            assertTrue(events.none { it["type"].asText() == "option.requested" }, events.toString())
        }
    }

    @Test
    fun `list pages through tasks and the budget counts active tasks beyond the first page`() {
        backendE2eTest(schemaPrefix = "e2e_scheduled_pages", scheduledTasks = ScheduledTaskConfig(runsPerUserPerDay = 101)) {
            val userId = UUID.randomUUID().toString()
            val chatId = scheduledTaskSetup(userId)
            val later = JobSchedule.Once(Instant.now().plusSeconds(3600))
            // The budget reads 100 active tasks per page, so the 101st sits on its second page.
            val active = List(101) {
                backend.jobService.createJob(userId, CreateJobRequest("Task $it", restJsonMapper.createObjectNode().put("prompt", "Do it."), later))
            }
            val rejected = scheduledTaskTool(userId, chatId, "create", mapOf("prompt" to "One more.", "afterSeconds" to 3600)).toString()
            assertTrue("0 of 101 remain" in rejected, rejected)

            // The newest task is cancelled, so the default listing must start below it.
            backend.jobService.cancelJob(userId, active.last().id)
            val expected = backend.jobService.listJobs(userId, limit = 4, includeInactive = false).items.map { it.id.toString() }
            assertTrue(active.last().id.toString() !in expected)
            // Short pages keep the tool result under the agent's 8 KB knowledge offload threshold.
            val first = scheduledTaskTool(userId, chatId, "list", mapOf("limit" to 2))
            val cursor = assertNotNull(first["nextCursor"], first.toString().take(1000)).asText()
            val second = scheduledTaskTool(userId, chatId, "list", mapOf("limit" to 2, "cursor" to cursor))
            assertEquals(expected, (first["tasks"] + second["tasks"]).map { it["taskId"].asText() })
        }
    }

}
