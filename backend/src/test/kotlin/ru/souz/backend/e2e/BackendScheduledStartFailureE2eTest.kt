package ru.souz.backend.e2e

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.scheduler.occurrenceId
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobInfo
import ru.souz.jobs.JobSchedule
import ru.souz.jobs.JobStatus
import ru.souz.llms.restJsonMapper

class BackendScheduledStartFailureE2eTest {
    @Test
    fun `start retries recover before persistence but never relaunch a persisted failed execution`() {
        for (beforePersistence in listOf(true, false)) {
            backendE2eTest(schemaPrefix = "e2e_scheduled_start") {
                val owner = UUID.randomUUID().toString()
                scheduledTaskSetup(owner)
                val job = backend.jobService.createJob(owner, CreateJobRequest(
                    "Retry", restJsonMapper.createObjectNode().put("prompt", "Check mail."),
                    JobSchedule.Cron("* * * * *", "UTC"), continueAfterFailure = true,
                ))
                val run = job.run()
                val table = if (beforePersistence) "chats" else "messages"
                val condition = if (beforePersistence) "new.client_type = 'scheduled_task'"
                    else "new.chat_id = '${run.occurrenceId("chat")}'::uuid"
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
                eventually<JobInfo>("first start failure", timeout = 15.seconds) {
                    backend.jobService.listJobs(owner).single().takeIf { it.status == JobStatus.PENDING && it.attempts == 1 }
                }
                backend.sql { it.createStatement().use { statement -> statement.execute("drop trigger reject_scheduled_start on $table") } }
                // Safe pre-insert failures recover. A failed committed execution consumes its
                // reservation and must remain failed through the worker's remaining retries.
                repeat(if (beforePersistence) 1 else 2) { index ->
                    makeScheduledJobReady(job.id)
                    eventually<JobInfo>("retried start", timeout = 15.seconds) {
                        backend.jobService.listJobs(owner).single().takeIf {
                            it.status == JobStatus.PENDING &&
                                (it.scheduledAt > job.scheduledAt || it.attempts == index + 2)
                        }
                    }
                }
                val next = backend.jobService.listJobs(owner).single()
                assertTrue(next.scheduledAt > job.scheduledAt)
                assertEquals(0, next.attempts)
                backend.awaitExecution(run.occurrenceId("execution"))
                val execution = assertNotNull(backend.executionRepository.get(owner, run.occurrenceId("execution")))
                if (beforePersistence) {
                    assertEquals(AgentExecutionStatus.COMPLETED, execution.status)
                    assertTrue(backend.jobService.listFailedOccurrences(owner, job.id).isEmpty())
                    assertEquals(1, llm.requests.size)
                } else {
                    assertEquals(AgentExecutionStatus.FAILED, execution.status)
                    assertEquals(job.scheduledAt, backend.jobService.listFailedOccurrences(owner, job.id).single().scheduledAt)
                    assertTrue(llm.requests.isEmpty())
                }
            }
        }
    }
}
