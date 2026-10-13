package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.scheduler.ScheduledTaskConfig
import ru.souz.backend.scheduler.executionId
import ru.souz.backend.scheduler.scheduledTaskInput
import ru.souz.jobs.JobRun
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobSchedule
import ru.souz.jobs.JobStatus
import ru.souz.llms.LLMResponse

class BackendScheduledTasksE2eTest {
    @Test
    fun `tools validate schedules and isolate owners while cancellation retains history`() = backendE2eTest("scheduled_tools") {
        val owner = UUID.randomUUID().toString()
        val chat = setupTasks(owner)
        val other = UUID.randomUUID().toString()
        val otherChat = setupTasks(other)
        for (arguments in listOf(
            emptyMap(), mapOf("afterSeconds" to 0), mapOf("afterSeconds" to Long.MAX_VALUE),
            mapOf("runAt" to "2020-01-01T00:00:00Z"), mapOf("cron" to "* * * * * *"),
            mapOf("cron" to "0 9 * * *", "timeZone" to "Mars/Base"),
            mapOf("afterSeconds" to 1, "cron" to "0 9 * * *"),
        )) assertTrue(!taskTool(owner, chat, "create", arguments + ("prompt" to "Do it.")).has("taskId"))
        assertTrue(backend.jobService.listJobs(owner).isEmpty())

        val daily = taskTool(owner, chat, "create", mapOf("prompt" to "Read mail.", "cron" to "0 9 * * *"))
        assertEquals("Europe/Moscow", daily["schedule"]["timeZone"].asText())
        assertEquals(9, OffsetDateTime.parse(daily["nextRunAt"].asText()).hour)
        assertEquals(0, taskTool(other, otherChat, "list")["tasks"].size())
        assertTrue("Task not found" in taskTool(other, otherChat, "delete", mapOf("taskId" to daily["taskId"].asText())).toString())
        assertEquals("cancelled", taskTool(owner, chat, "delete", mapOf("taskId" to daily["taskId"].asText()))["status"].asText())
        assertEquals(0, taskTool(owner, chat, "list")["tasks"].size())
        assertEquals("cancelled", taskTool(owner, chat, "list", mapOf("includeInactive" to true))["tasks"].single()["status"].asText())
    }

    @Test
    fun `worker delivers reminders to their source chat while action-only and cancelled tasks stay silent`() = backendE2eTest("scheduled_delivery") {
        val owner = UUID.randomUUID().toString()
        val chat = setupTasks(owner)
        val reminder = taskTool(owner, chat, "create", mapOf("prompt" to "Remind me to drink water.", "afterSeconds" to 1))
        taskTool(owner, chat, "create", mapOf("prompt" to "Quiet action.", "afterSeconds" to 1, "requiresDelivery" to false))
        val cancelled = taskTool(owner, chat, "create", mapOf("prompt" to "Cancelled reminder.", "afterSeconds" to 1))
        taskTool(owner, chat, "delete", mapOf("taskId" to cancelled["taskId"].asText()))
        val runs = backend.jobService.listJobs(owner).map { JobRun(it.id, owner, it.title, it.payload, it.scheduledAt, 1) }
        val run = runs.single { it.jobId.toString() == reminder["taskId"].asText() }
        val prompt = scheduledTaskInput(run)
        assertTrue("channelType=public_client, channelId=$chat" in prompt)
        llm.requestSkillForPrompt(prompt, "SendMessageToChannel", mapOf("channelType" to "public_client", "channelId" to chat, "text" to "Drink water"))
        backend.startScheduledTaskWorker()
        eventually("dispatched", timeout = 15.seconds) {
            backend.jobService.listJobs(owner).takeIf { jobs -> jobs.none { it.status == JobStatus.PENDING || it.status == JobStatus.RUNNING } }
        }
        runs.filter { it.jobId.toString() != cancelled["taskId"].asText() }.forEach { backend.awaitExecution(it.executionId()) }
        val delivered = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(owner) }.jsonBody()["items"]
            .filter { it.path("metadata").path("crossChannel").asText() == "true" }
        assertEquals(listOf("Drink water"), delivered.map { it["content"].asText() })
        assertTrue(llm.requests.none { it.conversationPrompt().startsWith("Cancelled reminder.") })
        val visible = client.get(BackendHttpRoutes.CHATS) { trusted(owner) }.jsonBody()["items"]
        assertEquals(listOf(chat), visible.map { it["id"].asText() })
    }

    @Test
    fun `competing hosts deduplicate each launch and retries respect the selected semantics`() = backendE2eTest("scheduled_identity") {
        val owner = UUID.randomUUID().toString()
        setupTasks(owner)
        withPeerBackend(llm = llm) { peer ->
            for (atMostOnce in listOf(true, false)) {
                val run = taskRun(owner, atMostOnce)
                coroutineScope {
                    listOf(backend, peer.backend).map { host -> async { host.runScheduledTask(run) } }.awaitAll()
                }
                backend.awaitExecution(run.executionId())
                peer.backend.awaitExecution(run.executionId())
                val retry = run.copy(attempt = 2)
                peer.backend.runScheduledTask(retry)
                peer.backend.awaitExecution(retry.executionId())
                assertEquals(if (atMostOnce) 1 else 2, llm.requests.count { it.conversationPrompt() == scheduledTaskInput(run) })
                assertEquals(AgentExecutionStatus.COMPLETED, backend.executionRepository.get(owner, retry.executionId())?.status)
            }
        }
    }

    @Test
    fun `at most once does not relaunch an admission interrupted before agent startup`() = backendE2eTest("scheduled_crash") {
        val owner = UUID.randomUUID().toString()
        setupTasks(owner)
        val run = taskRun(owner, atMostOnce = true)
        withTableLocked("messages", "share") {
            val dispatch = launch { backend.runScheduledTask(run) }
            awaitLockWait("messages")
            assertEquals(AgentExecutionStatus.QUEUED, backend.executionRepository.get(owner, run.executionId())?.status)
            dispatch.cancel()
        }
        withPeerBackend(llm = llm) { peer -> peer.backend.runScheduledTask(run.copy(attempt = 2)) }
        assertTrue(llm.requests.isEmpty())
    }

    @Test
    fun `scheduled turns cannot create interactive options in a hidden chat`() {
        val runner = object : BackendConversationTurnRunner {
            override suspend fun run(conversationKey: AgentConversationKey, request: BackendConversationTurnRequest, eventSink: AgentRuntimeEventSink,
                initialUsage: LLMResponse.Usage): BackendConversationTurnOutcome {
                val choice = ScriptedOptionTurnRunner().run(conversationKey, request, eventSink, initialUsage)
                return BackendConversationTurnOutcome.Completed("Done", choice.usage, choice.session)
            }
        }
        backendE2eTest("scheduled_options", featureFlags = BackendFeatureFlags(wsEvents = true, options = true), turnRunnerOverride = runner) {
            val owner = UUID.randomUUID().toString()
            setupTasks(owner)
            val run = taskRun(owner)
            backend.runScheduledTask(run)
            backend.awaitExecution(run.executionId())
            val execution = assertNotNull(backend.executionRepository.get(owner, run.executionId()))
            assertEquals(AgentExecutionStatus.COMPLETED, execution.status)
            val events = client.get(BackendHttpRoutes.chatEvents(execution.chatId)) { trusted(owner) }.jsonBody()["items"]
            assertTrue(events.none { it["type"].asText() == "option.requested" })
        }
    }

    @Test
    fun `daily admissions are atomic across hosts and recover quota on the next UTC day`() = backendE2eTest(
        "scheduled_quota", scheduledTasks = ScheduledTaskConfig(runsPerUserPerDay = 1),
    ) {
        val owner = UUID.randomUUID().toString()
        setupTasks(owner)
        val runs = List(2) { taskRun(owner) }
        withPeerBackend(llm = llm) { peer ->
            val outcomes = coroutineScope {
                runs.mapIndexed { index, run -> async {
                    try {
                        (if (index == 0) backend else peer.backend).runScheduledTask(run)
                        null
                    } catch (error: BackendV1Exception) { error.code }
                } }.awaitAll()
            }
            assertEquals(1, outcomes.count { it == null })
            assertEquals(1, outcomes.count { it == "scheduled_task_daily_limit" })
            val admitted = runs.single { backend.executionRepository.get(owner, it.executionId()) != null }
            backend.awaitExecution(admitted.executionId())
            peer.backend.awaitExecution(admitted.executionId())
            peer.backend.runScheduledTask(admitted) // A duplicate does not need another reservation.
            val next = admitted.copy(attempt = 2)
            assertFailsWith<BackendV1Exception> { backend.runScheduledTask(next) }
            backend.sql { c -> c.prepareStatement("update agent_executions set started_at = started_at - interval '1 day' where id = ?").use {
                it.setObject(1, admitted.executionId()); assertEquals(1, it.executeUpdate())
            } }
            peer.backend.runScheduledTask(next)
            peer.backend.awaitExecution(next.executionId())
            assertEquals(2, llm.requests.size)
        }
    }

    @Test
    fun `delivery is checked at creation and dispatch while actions need no messaging permission`() = backendE2eTest("scheduled_permissions") {
        val owner = UUID.randomUUID().toString()
        val chat = setupTasks(owner)
        taskTool(owner, chat, "create", mapOf("prompt" to "Send a reminder.", "afterSeconds" to 60))
        val job = backend.jobService.listJobs(owner).single()
        val response = client.patch(BackendHttpRoutes.SETTINGS) {
            trusted(owner)
            jsonBody("""{"enabledTools":["scheduled-task.create","scheduled-task.list","scheduled-task.delete"]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("require enabled" in taskTool(owner, chat, "create", mapOf("prompt" to "Another reminder.", "afterSeconds" to 60)).toString())
        assertFailsWith<BackendV1Exception> { backend.runScheduledTask(JobRun(job.id, owner, job.title, job.payload, job.scheduledAt, 1)) }
        val action = taskTool(owner, chat, "create", mapOf("prompt" to "Quiet action.", "afterSeconds" to 60, "requiresDelivery" to false))
        assertNotNull(action["taskId"])
        val run = taskRun(owner)
        backend.runScheduledTask(run)
        backend.awaitExecution(run.executionId())
        assertEquals(AgentExecutionStatus.COMPLETED, backend.executionRepository.get(owner, run.executionId())?.status)
    }

    @Test
    fun `worker leaves excess jobs unclaimed through agent and cancellation persistence cleanup`() {
        val started = Channel<Unit>(Channel.UNLIMITED)
        val cleaning = Channel<Unit>(Channel.UNLIMITED)
        val finishCleanup = Channel<Unit>()
        val llm = E2eLlmApi {
            started.send(Unit)
            try { awaitCancellation() } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    cleaning.send(Unit)
                    finishCleanup.receiveCatching()
                }
            }
        }
        backendE2eTest("scheduled_timeout", llm = llm, scheduledTasks = ScheduledTaskConfig(concurrentRuns = 2, runTimeoutMillis = 500)) {
            val owner = UUID.randomUUID().toString()
            setupTasks(owner)
            val jobs = List(3) {
                backend.jobService.createJob(owner, CreateJobRequest(
                    "Queued action", taskRun(owner).payload, JobSchedule.Once(Instant.now().plusSeconds(60)),
                ))
            }
            jobs.forEach { makeScheduledJobReady(it.id) }
            val runs = jobs.map { JobRun(it.id, owner, it.title, it.payload, it.scheduledAt, 1) }
            suspend fun assertUnclaimed() {
                val waiting = backend.jobService.listJobs(owner).single { it.id == jobs.last().id }
                assertEquals(JobStatus.PENDING, waiting.status)
                assertEquals(0, waiting.attempts)
                assertNull(waiting.lastError)
                assertNull(backend.executionRepository.get(owner, runs.last().executionId()))
                assertTrue(started.tryReceive().isFailure)
            }
            val worker = backend.startScheduledTaskWorker()
            try {
                withTimeout(10.seconds) {
                    repeat(2) { started.receive(); cleaning.receive() }
                    assertUnclaimed()
                    withTableLocked("agent_executions", "share") {
                        repeat(2) { finishCleanup.send(Unit) }
                        awaitLockWait("agent_executions")
                        // Agent cleanup is done; the launcher's cancellation write is still blocked.
                        assertUnclaimed()
                        worker.cancel()
                        assertSame(worker, backend.startScheduledTaskWorker())
                        assertFalse(worker.isCompleted)
                    }
                    worker.join()
                    backend.startScheduledTaskWorker()
                    started.receive()
                    cleaning.receive()
                }
            } finally { finishCleanup.close() }
            runs.forEach {
                backend.awaitExecution(it.executionId())
                assertEquals(AgentExecutionStatus.CANCELLED, backend.executionRepository.get(owner, it.executionId())?.status)
            }
        }
    }
}

internal suspend fun BackendE2eScope.setupTasks(userId: String): String {
    val chat = createPublicChat(userId, "create-$userId")
    assertEquals(HttpStatusCode.OK, client.patch(BackendHttpRoutes.SETTINGS) {
        trusted(userId)
        jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","streamingMessages":false,"timeZone":"Europe/Moscow"}""")
    }.status)
    return chat
}

private suspend fun BackendE2eScope.taskTool(userId: String, chat: String, operation: String, arguments: Map<String, Any> = emptyMap()): JsonNode {
    val prompt = "scheduled-task.$operation ${UUID.randomUUID()}"
    llm.requestSkillForPrompt(prompt, "scheduled-task.$operation", arguments)
    val sent = client.post(BackendHttpRoutes.chatMessages(chat)) { trusted(userId); jsonBody("""{"content":"$prompt"}""") }
    assertEquals(HttpStatusCode.OK, sent.status)
    backend.awaitExecution(UUID.fromString(sent.jsonBody()["execution"]["id"].asText()))
    val request = llm.requests.last { it.conversationPrompt() == prompt }
    return json.readTree(request.messages.last { it.name == "RunSkillCommand" }.content)
}

private fun BackendE2eScope.taskRun(owner: String, atMostOnce: Boolean = false) = JobRun(
    UUID.randomUUID(), owner, "Scheduled action", json.createObjectNode().put("prompt", UUID.randomUUID().toString())
        .put("requiresDelivery", false).put("atMostOnce", atMostOnce), Instant.now(), 1,
)
