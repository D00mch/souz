package ru.souz.backend.e2e

import io.ktor.client.request.patch
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.scheduler.ScheduledTaskConfig
import ru.souz.backend.scheduler.occurrenceId
import ru.souz.jobs.JobInfo
import ru.souz.jobs.JobRun
import ru.souz.jobs.JobStatus
import ru.souz.llms.restJsonMapper

class BackendScheduledDispatchE2eTest {
    @Test
    fun `replicas enforce one durable quota and duplicates never spend it again`() {
        backendE2eTest(schemaPrefix = "e2e_dispatch_race", scheduledTasks = ScheduledTaskConfig(runsPerUserPerDay = 2)) {
            val owner = UUID.randomUUID().toString()
            scheduledTaskSetup(owner)
            val occurrence = JobRun(UUID.randomUUID(), owner, "Task", restJsonMapper.createObjectNode().put("prompt", "Check mail.").put("atMostOnce", true),
                Instant.parse("2026-10-13T12:00:00Z"), 1)
            backend.runScheduledTask(occurrence)
            backend.awaitExecution(occurrence.occurrenceId("execution"))
            withPeerBackend(llm = llm) { peer ->
                val results = coroutineScope {
                    val pending = mutableListOf<Deferred<IllegalStateException?>>()
                    withTableLocked("agent_executions", "share") {
                        val attempts = listOf(occurrence, occurrence.copy(attempt = 2),
                            occurrence.copy(scheduledAt = occurrence.scheduledAt.plusSeconds(60)),
                            occurrence.copy(scheduledAt = occurrence.scheduledAt.plusSeconds(120)))
                        pending += attempts.mapIndexed { index, run ->
                            async {
                                try {
                                    (if (index % 2 == 0) backend else peer.backend).runScheduledTask(run)
                                    null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: IllegalStateException) {
                                    error
                                }
                            }
                        }
                        awaitLockWait("agent_executions")
                    }
                    pending.awaitAll()
                }
                assertEquals(1, results.count { it != null }, results.toString())
                assertTrue(results.single { it != null }?.message.orEmpty().contains("quota exhausted"))
                assertEquals(2, quota(owner))
                backend.awaitExecution(occurrence.occurrenceId("execution"))
                peer.backend.awaitExecution(occurrence.occurrenceId("execution"))
                peer.backend.runScheduledTask(occurrence.copy(attempt = 3))
                assertEquals(2, quota(owner))
            }
            // A newly composed host shares the same quota after the first peer closes.
            withPeerBackend { peer ->
                assertFailsWith<IllegalStateException> {
                    peer.backend.runScheduledTask(occurrence.copy(jobId = UUID.randomUUID()))
                }
                val stranger = UUID.randomUUID().toString()
                peer.scheduledTaskSetup(stranger)
                peer.backend.runScheduledTask(occurrence.copy(jobId = UUID.randomUUID(), userId = stranger))
                assertEquals(1, quota(stranger))
            }
        }
    }

    @Test
    fun `completed one offs keep their quota and a new UTC day has a new allowance`() {
        backendE2eTest(schemaPrefix = "e2e_dispatch_completed", scheduledTasks = ScheduledTaskConfig(runsPerUserPerDay = 1)) {
            val owner = UUID.randomUUID().toString()
            val chat = scheduledTaskSetup(owner)
            scheduledTaskTool(owner, chat, "create", mapOf("prompt" to "Check mail.", "afterSeconds" to 1))
            backend.startScheduledTaskWorker()
            val completed = eventually("one off dispatched", timeout = 15.seconds) {
                backend.jobService.listJobs(owner).single().takeIf { it.status == JobStatus.SUCCEEDED }
            }
            backend.awaitExecution(completed.run().occurrenceId("execution"))
            val second = scheduledTaskTool(owner, chat, "create", mapOf("prompt" to "Check mail again.", "afterSeconds" to 3600))
            val run = backend.jobService.listJobs(owner).single { it.id.toString() == second["taskId"].asText() }.run()
            assertFailsWith<IllegalStateException> { backend.runScheduledTask(run) }
            assertNull(backend.executionRepository.get(owner, run.occurrenceId("execution")), "Denied insert must roll back.")
            assertEquals(1, quota(owner))
            // Deterministic database clock-window fixture: yesterday's consumption remains durable.
            backend.sql { connection ->
                connection.prepareStatement("update scheduled_task_daily_launches set launch_day = launch_day - 1 where user_id = ?").use {
                    it.setString(1, owner)
                    it.executeUpdate()
                }
            }
            backend.runScheduledTask(run)
            assertEquals(1, quota(owner))
            assertNotNull(backend.executionRepository.get(owner, run.occurrenceId("execution")))
        }
    }

    @Test
    fun `cron bursts outside the planning window still stop at dispatch quota`() {
        backendE2eTest(schemaPrefix = "e2e_dispatch_burst", scheduledTasks = ScheduledTaskConfig(runsPerUserPerDay = 2),
            clock = Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC)) {
            val owner = UUID.randomUUID().toString()
            val chat = scheduledTaskSetup(owner)
            val created = scheduledTaskTool(owner, chat, "create", mapOf("prompt" to "Check mail.", "cron" to "* * 25 * *"))
            assertTrue(created.has("taskId"), created.toString())
            val first = backend.jobService.listJobs(owner).single().run()
            repeat(2) { minute ->
                val run = first.copy(scheduledAt = first.scheduledAt.plusSeconds(minute * 60L))
                backend.runScheduledTask(run)
                backend.awaitExecution(run.occurrenceId("execution"))
            }
            assertFailsWith<IllegalStateException> { backend.runScheduledTask(first.copy(scheduledAt = first.scheduledAt.plusSeconds(120))) }
            assertEquals(2, quota(owner))
        }
    }

    @Test
    fun `reminders reject unavailable delivery at creation and record revocation at dispatch without disabling cron`() {
        backendE2eTest(schemaPrefix = "e2e_dispatch_permissions") {
            val owner = UUID.randomUUID().toString()
            val chat = scheduledTaskSetup(owner)
            val created = scheduledTaskTool(owner, chat, "create", mapOf("prompt" to "Remind me.", "cron" to "0 9 * * *"))
            val id = UUID.fromString(created["taskId"].asText())
            val original = backend.jobService.listJobs(owner).single().run()
            val patched = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(owner)
                jsonBody("""{"enabledTools":["scheduled-task.create","scheduled-task.list","scheduled-task.delete"]}""")
            }
            assertEquals(HttpStatusCode.OK, patched.status)
            val rejected = scheduledTaskTool(owner, chat, "create", mapOf("prompt" to "Remind me again.", "afterSeconds" to 1))
            assertTrue("requires enabled SendMessageToChannel" in rejected.toString(), rejected.toString())
            val action = scheduledTaskTool(owner, chat, "create", mapOf("prompt" to "Check mail.", "afterSeconds" to 3600, "requiresDelivery" to false))
            assertTrue(action.has("taskId"), action.toString())
            backend.startScheduledTaskWorker()
            repeat(3) { index ->
                // Move only retry availability to avoid waiting 30 seconds between attempts.
                makeScheduledJobReady(id)
                eventually<JobInfo>("permission failure attempt ${index + 1}", timeout = 15.seconds) {
                    backend.jobService.listJobs(owner).single { it.id == id }.takeIf {
                        it.status == JobStatus.PENDING && if (index < 2) it.attempts == index + 1 else it.scheduledAt > original.scheduledAt
                    }
                }
            }
            assertNull(backend.executionRepository.get(owner, original.occurrenceId("execution")))
            assertEquals(0, quota(owner))
            val listed = scheduledTaskTool(owner, chat, "list")["tasks"].single { it["taskId"].asText() == id.toString() }
            assertEquals("pending", listed["status"].asText())
            assertTrue("delivery is unavailable" in listed["failedOccurrences"].single()["error"].asText())
            val actionRun = backend.jobService.listJobs(owner).single { it.id.toString() == action["taskId"].asText() }.run()
            backend.runScheduledTask(actionRun)
            backend.awaitExecution(actionRun.occurrenceId("execution"))
            assertEquals(1, quota(owner), "Action-only tasks can run with delivery disabled.")
        }
    }

    private fun BackendE2eScope.quota(owner: String): Int = backend.sql { connection ->
        connection.prepareStatement("select launches from scheduled_task_daily_launches where user_id = ? and launch_day = (clock_timestamp() at time zone 'UTC')::date").use {
            it.setString(1, owner)
            it.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
    }
}
