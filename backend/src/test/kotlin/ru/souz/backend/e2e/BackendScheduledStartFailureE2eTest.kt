package ru.souz.backend.e2e

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.scheduler.SCHEDULED_TASK_CHAT_TYPE
import ru.souz.backend.scheduler.executionId
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobRun
import ru.souz.jobs.JobSchedule
import ru.souz.jobs.JobStatus

class BackendScheduledStartFailureE2eTest {
    @Test
    fun `start retries recover before persistence but at most once never relaunches a persisted failed execution`() {
        for (beforePersistence in listOf(true, false)) {
            backendE2eTest("scheduled_start") {
                val owner = UUID.randomUUID().toString()
                setupTasks(owner)
                val job = backend.jobService.createJob(owner, CreateJobRequest(
                    "Retry", json.createObjectNode().put("prompt", "Check mail.").put("atMostOnce", true),
                    JobSchedule.Cron("* * * * *", "UTC"), continueAfterFailure = true,
                ))
                val run = JobRun(job.id, owner, job.title, job.payload, job.scheduledAt, 1)
                val table = if (beforePersistence) "chats" else "messages"
                val condition = if (beforePersistence) "new.client_type = 'scheduled_task'"
                    else "exists (select 1 from chats where id = new.chat_id and client_type = 'scheduled_task')"
                backend.sql { connection ->
                    connection.createStatement().use {
                        it.execute("""
                            create function reject_scheduled_start() returns trigger language plpgsql as ${'$'}${'$'}
                            begin
                              if $condition then raise exception 'temporary start failure'; end if;
                              return new;
                            end;
                            ${'$'}${'$'}
                        """.trimIndent())
                        it.execute("create trigger reject_scheduled_start before insert on $table for each row execute function reject_scheduled_start()")
                    }
                }
                makeScheduledJobReady(job.id)
                backend.startScheduledTaskWorker()
                eventually("first start failure", timeout = 15.seconds) {
                    backend.jobService.listJobs(owner).items.single().takeIf { it.status == JobStatus.PENDING && it.attempts == 1 }
                }
                backend.sql { it.createStatement().use { statement -> statement.execute("drop trigger reject_scheduled_start on $table") } }
                // Pre-insert failures recover; a committed failed execution must fail every retry.
                repeat(if (beforePersistence) 1 else 2) { index ->
                    makeScheduledJobReady(job.id)
                    val retried = eventually("retried start", timeout = 15.seconds) {
                        backend.jobService.listJobs(owner).items.single().takeIf {
                            it.status == JobStatus.PENDING && (it.scheduledAt > job.scheduledAt || it.attempts == index + 2)
                        }
                    }
                    if (!beforePersistence) assertTrue("temporary start failure" in assertNotNull(retried.lastError))
                }
                val next = backend.jobService.listJobs(owner).items.single()
                assertTrue(next.scheduledAt > job.scheduledAt)
                assertEquals(0, next.attempts)
                val execution = assertNotNull(backend.executionRepository.get(owner, run.executionId()))
                if (beforePersistence) {
                    assertEquals(AgentExecutionStatus.COMPLETED, execution.status)
                    assertTrue(backend.jobService.listFailedOccurrences(owner, setOf(job.id)).isEmpty())
                    assertEquals(1, llm.requests.size)
                } else {
                    assertEquals(AgentExecutionStatus.FAILED, execution.status)
                    val failure = backend.jobService.listFailedOccurrences(owner, setOf(job.id)).single()
                    assertEquals(job.scheduledAt, failure.scheduledAt)
                    assertEquals(3, failure.attempts)
                    assertEquals(execution.errorMessage, failure.error)
                    assertTrue(llm.requests.isEmpty())
                }
            }
        }
    }

    @Test
    fun `failed execution committed after the initial lookup also fails the duplicate dispatch`() = backendE2eTest("scheduled_failed_race") {
        val owner = UUID.randomUUID().toString()
        setupTasks(owner)
        val job = backend.jobService.createJob(owner, CreateJobRequest(
            "Race", json.createObjectNode().put("prompt", "Check mail.").put("atMostOnce", true),
        ))
        val run = JobRun(job.id, owner, job.title, job.payload, job.scheduledAt, 1)
        val chat = backend.dependencies.chatService.createClient(owner, "scheduled-task:${run.executionId()}", SCHEDULED_TASK_CHAT_TYPE, job.title).chat
        withTableLocked("chats", "exclusive") { connection ->
            val dispatch = async { assertFailsWith<IllegalStateException> { backend.runScheduledTask(run) } }
            awaitLockWait("chats") // Admission passed its initial lookup and is waiting for the chat lock.
            assertNull(backend.executionRepository.get(owner, run.executionId()))
            connection.prepareStatement("""
                insert into agent_executions(id, user_id, chat_id, status, error_message, finished_at, metadata)
                values (?, ?, ?, 'failed', 'persisted start failure', clock_timestamp(), '{"scheduledTask":"true"}')
            """.trimIndent()).use {
                it.setObject(1, run.executionId()); it.setString(2, owner); it.setObject(3, chat.id)
                assertEquals(1, it.executeUpdate())
            }
            connection.commit()
            assertEquals("persisted start failure", dispatch.await().message)
        }
        assertTrue(llm.requests.isEmpty())
    }
}

internal fun BackendE2eScope.makeScheduledJobReady(jobId: UUID) {
    backend.sql { connection ->
        connection.prepareStatement("update jobs set available_at = clock_timestamp() - interval '1 second' where id = ?").use {
            it.setObject(1, jobId)
            assertEquals(1, it.executeUpdate())
        }
    }
}
