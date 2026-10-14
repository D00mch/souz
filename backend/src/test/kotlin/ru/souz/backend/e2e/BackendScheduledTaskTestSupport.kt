package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.assertEquals
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.scheduler.scheduledTaskInput
import ru.souz.jobs.JobInfo
import ru.souz.jobs.JobRun

internal suspend fun BackendE2eScope.scheduledTaskSetup(userId: String): String {
    val chatId = createPublicChat(userId, "create-$userId")
    val settings = client.patch(BackendHttpRoutes.SETTINGS) {
        trusted(userId)
        jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","streamingMessages":false,"timeZone":"Europe/Moscow"}""")
    }
    assertEquals(HttpStatusCode.OK, settings.status)
    return chatId
}

/** Runs one turn whose scripted model calls scheduled-task.[operation] and returns the tool result. */
internal suspend fun BackendE2eScope.scheduledTaskTool(
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
internal fun JobInfo.occurrenceInput(): String =
    scheduledTaskInput(run())

internal fun JobInfo.run(attempt: Int = 1): JobRun = JobRun(id, userId, title, payload, scheduledAt, attempt)

/** Move persisted retry availability forward without changing the stable occurrence timestamp. */
internal fun BackendE2eScope.makeScheduledJobReady(id: UUID) = backend.sql { connection ->
    connection.prepareStatement("update jobs set available_at = clock_timestamp() - interval '1 second' where id = ?").use {
        it.setObject(1, id)
        it.executeUpdate()
    }
}
