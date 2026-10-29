package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import kotlinx.coroutines.delay
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
    fun `competing handlers launch once per key and recovery follows the chosen delivery semantics`() {
        for (atMostOnce in listOf(false, true)) {
            backendE2eTest(schemaPrefix = "e2e_scheduled_dedup") {
                val userId = UUID.randomUUID().toString()
                scheduledTaskSetup(userId)
                val payload = restJsonMapper.createObjectNode().put("prompt", "Check mail.")
                // An absent flag exercises the at-least-once default.
                if (atMostOnce) payload.put("atMostOnce", true)
                val job = JobRun(UUID.randomUUID(), userId, "Task", payload,
                    Instant.parse("2026-10-13T12:00:00Z"), attempt = 1)
                val prompt = scheduledTaskInput(job)
                val executionId = job.occurrenceId("execution")
                llm.pausePromptUntilReleased(prompt)
                withPeerBackend(llm = llm) { peer ->
                    // Competing retries share a key only with at-most-once semantics.
                    withTableLocked("agent_executions", "share") {
                        launch { backend.runScheduledTask(job) }
                        launch { peer.backend.runScheduledTask(job.copy(attempt = if (atMostOnce) 2 else 1)) }
                        awaitLockWait("agent_executions")
                    }
                    withTimeout(10.seconds) { llm.awaitPrompt(prompt) }
                    assertEquals(1, llm.requests.size)
                    llm.releasePrompt(prompt)
                    backend.awaitExecution(executionId)
                    peer.backend.awaitExecution(executionId)
                    val completed = assertNotNull(backend.executionRepository.get(userId, executionId))
                    assertEquals(AgentExecutionStatus.COMPLETED, completed.status)
                    val messages = client.get(BackendHttpRoutes.chatMessages(completed.chatId)) { trusted(userId) }.jsonBody()["items"]
                    assertEquals(listOf("user", "assistant"), messages.map { it["role"].asText() })

                    val retry = job.copy(attempt = 3)
                    backend.runScheduledTask(retry)
                    peer.backend.runScheduledTask(retry.copy(title = "Renamed"))
                    backend.awaitExecution(retry.occurrenceId("execution"))
                    peer.backend.awaitExecution(retry.occurrenceId("execution"))
                    assertEquals(completed, backend.executionRepository.get(userId, executionId))
                    assertEquals(atMostOnce, retry.occurrenceId("chat") == completed.chatId)
                    assertEquals(if (atMostOnce) 1 else 2, llm.requests.size)

                    val next = job.copy(scheduledAt = job.scheduledAt.plusSeconds(60))
                    peer.backend.runScheduledTask(next)
                    peer.backend.awaitExecution(next.occurrenceId("execution"))
                    val nextExecution = assertNotNull(backend.executionRepository.get(userId, next.occurrenceId("execution")))
                    assertTrue(nextExecution.chatId != completed.chatId)
                    assertEquals(if (atMostOnce) 2 else 3, llm.requests.size, "A new occurrence still runs.")
                }
            }
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
    fun `an owner at its limit leaves slots to others and due tasks wait in the queue until a timeout frees one`() {
        val started = Channel<String>(Channel.UNLIMITED)
        val cancelled = Channel<Unit>(Channel.UNLIMITED)
        val llm = E2eLlmApi { request ->
            started.send(request.conversationPrompt().substringBefore("\n"))
            try { awaitCancellation() } finally { cancelled.trySend(Unit) }
        }
        backendE2eTest(
            schemaPrefix = "e2e_scheduled_concurrency",
            llm = llm,
            scheduledTasks = ScheduledTaskConfig(concurrentRuns = 3, concurrentRunsPerUser = 2, runTimeoutMillis = 5_000),
        ) {
            val busy = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            scheduledTaskSetup(busy)
            scheduledTaskSetup(other)
            suspend fun create(owner: String, prompt: String) =
                backend.jobService.createJob(owner, CreateJobRequest(prompt, restJsonMapper.createObjectNode().put("prompt", prompt)))
            // A failed start must release its slot before valid occurrences run.
            create("missing-user", "Missing owner")
            // Availability order puts every busy task first.
            listOf("Busy 1", "Busy 2", "Busy 3").forEach { create(busy, it) }
            listOf("Other 1", "Other 2").forEach { create(other, it) }
            backend.startScheduledTaskWorker()
            withTimeout(15.seconds) {
                assertEquals(setOf("Busy 1", "Busy 2", "Other 1"), List(3) { started.receive() }.toSet())
                delay(500) // Give a full worker the chance to claim more.
                val queued = listOf(busy, other).flatMap { backend.jobService.listJobs(it).items }
                    .filter { it.status == JobStatus.PENDING && it.attempts == 0 }
                assertEquals(setOf("Busy 3", "Other 2"), queued.map { it.title }.toSet(), "Waiting tasks stay unclaimed.")
                val metrics = client.get("/metrics").bodyAsText()
                listOf("souz_scheduled_task_slots{state=\"busy\"} 3.0", "souz_scheduled_task_slots{state=\"free\"} 0.0",
                    "souz_scheduled_task_owners_at_limit 1.0", "souz_scheduled_task_dispatch_delay_seconds_count 3")
                    .forEach { assertTrue(metrics.contains(it), it) }
                assertTrue(listOf(busy, other).none { metrics.contains(it) }, "No owner labels.")
                val oldestDue = Regex("(?m)^souz_scheduled_task_oldest_due_seconds (\\S+)$").find(metrics)?.groupValues?.get(1)?.toDouble()
                assertTrue(oldestDue != null && oldestDue > 0.0, "Oldest due wait: $oldestDue")
                assertTrue(cancelled.tryReceive().isFailure, "All three start before any times out.")
                assertEquals(setOf("Busy 3", "Other 2"), List(2) { started.receive() }.toSet())
                repeat(3) { cancelled.receive() } // Each timed-out run is cancelled.
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
