package ru.souz.backend.scheduler

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import kotlinx.coroutines.CancellationException
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.client.LiveClientToolDispatcher

/** Only these explicit rejections prove that create did not save a schedule. */
internal val schedulerRejections = setOf("scheduler_invalid_schedule", "scheduler_invalid_arguments",
    "scheduler_context_missing", "scheduler_storage_unavailable", "scheduler_not_configured")
private val schedulerErrors = schedulerRejections + setOf("scheduler_not_found", "scheduler_conflict", "scheduler_invalid_state", "scheduler_busy")
internal class SchedulerFailure(val code: String, val uncertain: Boolean) : RuntimeException(code)

internal class OrionSchedulerClient(private val dispatcher: LiveClientToolDispatcher) {
    suspend fun get(chat: Chat, id: String): ScheduleView? = request(chat, "get", mapOf("scheduleId" to id)) { node ->
        taskCheck(node.path("found").isBoolean)
        if (node["found"].booleanValue()) decodeSchedule(node["record"], id) else null
    }

    suspend fun create(chat: Chat, id: String, title: String, schedule: TaskSchedule, secret: String): ScheduleView =
        request(chat, "create", mapOf("scheduleId" to id, "title" to title, "schedule" to schedule,
            "target" to mapOf("hookId" to id, "bearerToken" to secret))) { node ->
            taskCheck(node.path("created").isBoolean)
            decodeSchedule(node["record"], id)
        }

    suspend fun update(chat: Chat, id: String, patch: Map<String, Any>): ScheduleView =
        request(chat, "update", patch + ("scheduleId" to id)) { decodeSchedule(it["record"], id) }

    suspend fun delete(chat: Chat, id: String) = request(chat, "delete", mapOf("scheduleId" to id)) {
        taskCheck(it.path("scheduleId").textValue() == id && it.path("removed").isBoolean)
    }

    suspend fun list(chat: Chat): List<ScheduleView> {
        val records = linkedMapOf<String, ScheduleView>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(100) {
            val page = request(chat, "list", buildMap {
                put("limit", 200)
                cursor?.let { put("cursor", it) }
            }) { node ->
                taskCheck(node.path("records").isArray && node["records"].size() <= 200)
                taskCheck(node.has("nextCursor") && (node["nextCursor"].isNull || node["nextCursor"].isTextual))
                node["records"].map { decodeSchedule(it) } to node["nextCursor"].textValue()
            }
            page.first.forEach { records[it.scheduleId] = it }
            cursor = page.second ?: return records.values.sortedBy { it.scheduleId }
            if (cursor.length !in 1..4096 || !cursors.add(cursor)) throw SchedulerFailure("scheduler_invalid_response", false)
        }
        throw SchedulerFailure("scheduler_list_limit", false)
    }

    private suspend fun <T> request(chat: Chat, command: String, args: Map<String, Any>, decode: (JsonNode) -> T): T {
        val mutation = command in setOf("create", "update", "delete")
        try {
            val outcome = dispatcher.call(chat, "scheduler.$command", args, Duration.ofSeconds(30))
            if (outcome.status != "succeeded") {
                val rejected = outcome.status == "failed" && outcome.error?.code in schedulerErrors
                throw SchedulerFailure(if (rejected) outcome.error!!.code else if (mutation) "scheduler_outcome_unknown" else "scheduler_unavailable", mutation && !rejected)
            }
            return try {
                decode(requireNotNull(outcome.result))
            } catch (_: Exception) {
                throw SchedulerFailure("scheduler_invalid_response", mutation)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SchedulerFailure) {
            throw failure
        } catch (_: Exception) {
            throw SchedulerFailure(if (mutation) "scheduler_outcome_unknown" else "scheduler_unavailable", mutation)
        }
    }
}
