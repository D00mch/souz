package ru.souz.backend.e2e

import io.ktor.client.request.patch
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.async
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.scheduler.SCHEDULED_TASK_CHAT_TYPE
import ru.souz.backend.scheduler.ScheduledTaskConfig
import ru.souz.backend.scheduler.executionId

class BackendScheduledAdmissionE2eTest {
    @Test
    fun `quota and permission rejections remove empty chats on every attempt and occurrence`() = backendE2eTest(
        "scheduled_rejections", scheduledTasks = ScheduledTaskConfig(runsPerUserPerDay = 1),
    ) {
        val owner = UUID.randomUUID().toString()
        setupTasks(owner)
        val admitted = taskRun(owner, atMostOnce = true)
        backend.runScheduledTask(admitted)
        for (requiresDelivery in listOf(false, true)) {
            if (requiresDelivery) assertEquals(HttpStatusCode.OK, client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(owner)
                jsonBody("""{"enabledTools":["scheduled-task.create","scheduled-task.list","scheduled-task.delete"]}""")
            }.status)
            for (atMostOnce in listOf(false, true)) {
                val task = taskRun(owner, atMostOnce).copy(payload = json.createObjectNode()
                    .put("prompt", "Rejected run").put("requiresDelivery", requiresDelivery).put("atMostOnce", atMostOnce))
                repeat(2) { occurrence ->
                    repeat(3) { attempt ->
                        val run = task.copy(scheduledAt = task.scheduledAt.plusSeconds(occurrence * 60L), attempt = attempt + 1)
                        val failure = assertFailsWith<BackendV1Exception> { backend.runScheduledTask(run) }
                        assertEquals(if (requiresDelivery) "invalid_request" else "scheduled_task_daily_limit", failure.code)
                        assertNull(backend.executionRepository.get(owner, run.executionId()))
                        assertNull(backend.chatRepository.findByRequestId(owner, "scheduled-task:${run.executionId()}"))
                    }
                }
            }
        }
        backend.runScheduledTask(admitted.copy(attempt = 2))
        val execution = assertNotNull(backend.executionRepository.get(owner, admitted.executionId()))
        assertNotNull(backend.chatRepository.get(owner, execution.chatId))
    }

    @Test
    fun `rejection cleanup waits for admission and preserves its committed chat`() = backendE2eTest("scheduled_cleanup_race") {
        val owner = UUID.randomUUID().toString()
        val publicChat = setupTasks(owner)
        val id = UUID.randomUUID()
        val chat = backend.dependencies.chatService.createClient(owner, "scheduled-task:$id", SCHEDULED_TASK_CHAT_TYPE, "Race").chat
        assertFalse(backend.chatRepository.deleteEmptyScheduledChat(UUID.randomUUID().toString(), chat.id))
        assertFalse(backend.chatRepository.deleteEmptyScheduledChat(owner, UUID.fromString(publicChat)))
        withTableLocked("chats", "exclusive") { connection ->
            val cleanup = async { backend.chatRepository.deleteEmptyScheduledChat(owner, chat.id) }
            awaitLockWait("chats")
            connection.prepareStatement("""
                insert into agent_executions(id, user_id, chat_id, status, metadata)
                values (?, ?, ?, 'queued', '{"scheduledTask":"true"}')
            """.trimIndent()).use {
                it.setObject(1, id); it.setString(2, owner); it.setObject(3, chat.id)
                assertEquals(1, it.executeUpdate())
            }
            connection.commit()
            assertFalse(cleanup.await())
        }
        assertNotNull(backend.executionRepository.get(owner, id))
        assertNotNull(backend.chatRepository.get(owner, chat.id))
    }
}
