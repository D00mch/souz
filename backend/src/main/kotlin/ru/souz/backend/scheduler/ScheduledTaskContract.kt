package ru.souz.backend.scheduler

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal class TaskFailure(val code: String) : RuntimeException(code)
internal fun taskCheck(condition: Boolean, code: String = "invalid_arguments") {
    if (!condition) throw TaskFailure(code)
}

internal val taskJson = jacksonObjectMapper()
    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class TaskSchedule(val kind: String, val runAt: String? = null, val expression: String? = null, val timeZone: String? = null)
internal data class ScheduleView(
    val scheduleId: String,
    val title: String,
    val schedule: TaskSchedule,
    val state: String,
    val nextRunAt: String?,
    val lastDelivery: ScheduleDelivery?,
    val createdAt: String,
    val updatedAt: String,
)
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ScheduleDelivery(
    val occurrenceId: String,
    val scheduledAt: String,
    val status: String,
    val receiptId: String? = null,
    val errorCode: String? = null,
    val httpStatus: Int? = null,
)

internal fun validTaskId(value: String): Boolean = value.startsWith("task-") &&
    runCatching { UUID.fromString(value.removePrefix("task-")).toString() == value.removePrefix("task-") }.getOrDefault(false)

internal fun scheduleInput(node: JsonNode, now: Instant, allowAfter: Boolean, requireFuture: Boolean = true): TaskSchedule {
    taskCheck(node.isObject)
    fun fields(vararg names: String) = taskCheck(node.fieldNames().asSequence().toSet() == names.toSet())
    return when (node.path("kind").textValue()) {
        "after" -> {
            fields("kind", "seconds")
            val seconds = node.path("seconds")
            taskCheck(allowAfter && seconds.isIntegralNumber && seconds.canConvertToLong() && seconds.longValue() > 0)
            TaskSchedule("once", runAt = now.plusSeconds(seconds.longValue()).toString())
        }
        "once" -> {
            fields("kind", "runAt")
            val time = Instant.parse(node.path("runAt").textValue())
            taskCheck(!requireFuture || time.isAfter(now))
            TaskSchedule("once", runAt = time.toString())
        }
        "cron" -> {
            fields("kind", "expression", "timeZone")
            val expression = node.path("expression").textValue().orEmpty().trim().replace(Regex("\\s+"), " ")
            val parts = expression.split(' ')
            // Orion validates field ranges and next occurrence with its cron parser.
            taskCheck(expression.length <= 200 && parts.size == 5 && parts.all { it.matches(Regex("[0-9*,/\\-]+")) })
            taskCheck(parts[2] == "*" || parts[4] == "*")
            val zone = node.path("timeZone").textValue().orEmpty()
            taskCheck(zone in ZoneId.getAvailableZoneIds())
            TaskSchedule("cron", expression = expression, timeZone = zone)
        }
        else -> throw TaskFailure("invalid_arguments")
    }
}

internal fun decodeSchedule(node: JsonNode, expectedId: String? = null): ScheduleView {
    val record = taskJson.treeToValue(node, ScheduleView::class.java)
    taskCheck(validTaskId(record.scheduleId) && (expectedId == null || record.scheduleId == expectedId), "scheduler_invalid_response")
    taskCheck(record.title.isNotBlank() && record.title.length <= 200, "scheduler_invalid_response")
    taskCheck(record.state in setOf("active", "dispatching", "completed", "missed", "delivery_failed", "delivery_unknown"), "scheduler_invalid_response")
    scheduleInput(taskJson.valueToTree(record.schedule), Instant.EPOCH, allowAfter = false, requireFuture = false)
    Instant.parse(record.createdAt)
    Instant.parse(record.updatedAt)
    record.nextRunAt?.let(Instant::parse)
    record.lastDelivery?.let {
        UUID.fromString(it.occurrenceId)
        Instant.parse(it.scheduledAt)
        taskCheck(it.status in setOf("sending", "accepted", "failed", "unknown"), "scheduler_invalid_response")
        it.receiptId?.let(UUID::fromString)
        taskCheck(it.receiptId == null || it.status == "accepted", "scheduler_invalid_response")
        taskCheck(it.httpStatus == null || it.httpStatus in 100..599, "scheduler_invalid_response")
    }
    return record.copy(lastDelivery = record.lastDelivery?.let { delivery ->
        delivery.copy(errorCode = delivery.errorCode?.let { if (it in deliveryErrors) it else "delivery_error" })
    })
}

private val deliveryErrors = setOf("hook_not_found", "hook_disabled", "invalid_hook_token", "hook_admission_limit",
    "hook_configuration_changed", "hook_owner_unavailable", "http_error", "network_error", "timeout", "delivery_failed", "delivery_unknown")
