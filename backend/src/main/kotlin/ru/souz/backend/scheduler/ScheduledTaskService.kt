package ru.souz.backend.scheduler

import com.fasterxml.jackson.databind.JsonNode
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.client.LiveClientToolDispatcher
import ru.souz.backend.hooks.HookAuth
import ru.souz.backend.hooks.HookDefinition
import ru.souz.backend.hooks.HookService
import ru.souz.backend.hooks.HookStore
import ru.souz.backend.hooks.LoadedHook
import ru.souz.backend.hooks.sha256
import ru.souz.llms.ToolInvocationMeta

internal class ScheduledTaskService(
    private val files: ScheduledHookFiles,
    private val hooks: HookService,
    private val receipts: HookStore,
    private val dispatcher: LiveClientToolDispatcher,
    private val scheduler: OrionSchedulerClient,
    private val clock: Clock,
    private val channels: ChannelProviderRegistry,
) {
    private val random = SecureRandom()
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun invoke(operation: String, arguments: Map<String, Any>, meta: ToolInvocationMeta): Map<String, Any?> {
        val result = OperationResult()
        try {
            val input = parseInput(operation, arguments)
            result.id = input.id
            val channel = dispatcher.schedulerChannel(meta.userId, input.channelId, meta.conversationId.uuidOrNull())
                ?: throw TaskFailure("scheduler_unavailable")
            when (operation) {
                "get" -> {
                    result.record = requireRecord(channel, input.id!!, result)
                    val hook = files.read(meta.userId)[input.id]
                    result.hookStatus = hook.status()
                    result.extra["prompt"] = hook?.definition?.prompt
                    val receipt = receipts.latestByHookIds(meta.userId, setOf(input.id))[input.id]
                    result.extra["lastExecution"] = receipt?.let { hooks.status(meta.userId, it.id) }
                }
                "list" -> return list(meta.userId, channel, input.includeInactive)
                else -> files.mutate(meta.userId) { workspace ->
                    when (operation) {
                        "create" -> {
                            val source = channels.listAll(meta.userId).singleOrNull { it.channelId == meta.conversationId }
                            create(workspace, channel, meta.userId, input.copy(prompt = scheduledTaskPrompt(input.prompt!!, source)), result)
                        }
                        "update" -> update(workspace, channel, input, result)
                        "delete" -> delete(workspace, channel, input.id!!, result)
                    }
                }
            }
            return result.finish()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SchedulerFailure) {
            if (failure.code == "scheduler_not_found") { result.record = null; result.scheduleStatus = "missing" }
            if (failure.uncertain) { result.changed = true; result.record = null; result.scheduleStatus = "unknown" }
            return result.finish(failure.code)
        } catch (failure: TaskFailure) {
            return result.finish(failure.code)
        } catch (error: Exception) {
            log.warn("Scheduled task {} failed ({})", operation, error.javaClass.simpleName)
            result.hookStatus = "unavailable"
            return result.finish("hook_operation_failed")
        }
    }

    private suspend fun requireRecord(channel: Chat, id: String, result: OperationResult): ScheduleView {
        val record = scheduler.get(channel, id)
        result.scheduleStatus = record?.state ?: "missing"
        return record ?: throw TaskFailure("scheduler_not_found")
    }

    private suspend fun create(workspace: ScheduledHookFiles.Workspace, channel: Chat, owner: String, input: TaskInput, result: OperationResult) {
        val id = "task-${UUID.randomUUID()}"
        result.id = id
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val definition = HookDefinition(1, id, owner, auth = HookAuth("bearer", sha256(secret.toByteArray())), prompt = input.prompt!!)
        // A write/reload failure can leave a file; never report certain rollback in that case.
        result.hookStatus = "missing"
        val created = workspace.create(definition) {
            result.changed = true
            result.hookStatus = "unavailable"
        }
        result.hookStatus = "enabled"
        try {
            result.record = scheduler.create(channel, id, input.title!!, input.schedule!!, secret)
        } catch (failure: SchedulerFailure) {
            if (!failure.uncertain && failure.code in schedulerRejections) {
                result.scheduleStatus = "missing"
                result.hookStatus = "unavailable"
                workspace.remove(created)
                result.hookStatus = "missing"
                result.changed = false
            }
            throw failure
        }
    }

    private suspend fun update(workspace: ScheduledHookFiles.Workspace, channel: Chat, input: TaskInput, result: OperationResult) {
        val id = input.id!!
        result.record = requireRecord(channel, id, result)
        val hook = workspace.find(id)
        result.hookStatus = hook.status()
        taskCheck(hook != null, "hook_not_found")
        taskCheck(hook!!.definition.enabled, "hook_disabled")
        if (input.prompt != null) {
            val prompt = updatedScheduledTaskPrompt(input.prompt, hook.definition.prompt)
            result.changed = true
            result.hookStatus = "unavailable"
            workspace.replace(hook, hook.definition.copy(prompt = prompt))
            result.hookStatus = "enabled"
        }
        val patch = buildMap<String, Any> {
            input.title?.let { put("title", it) }
            input.schedule?.let { put("schedule", it) }
        }
        if (patch.isNotEmpty()) result.record = scheduler.update(channel, id, patch)
    }

    private suspend fun delete(workspace: ScheduledHookFiles.Workspace, channel: Chat, id: String, result: OperationResult) {
        result.record = requireRecord(channel, id, result)
        var hook = workspace.find(id)
        result.hookStatus = hook.status()
        if (hook != null && hook.definition.enabled) {
            result.changed = true
            result.hookStatus = "unavailable"
            hook = workspace.replace(hook, hook.definition.copy(enabled = false))
            result.hookStatus = "disabled"
        }
        scheduler.delete(channel, id)
        result.changed = true
        result.record = null
        result.scheduleStatus = "missing"
        if (hook != null) {
            result.hookStatus = "unavailable"
            workspace.remove(hook)
        }
        result.hookStatus = "missing"
    }

    private suspend fun list(owner: String, channel: Chat, includeInactive: Boolean): Map<String, Any?> {
        val records = scheduler.list(channel)
        val loaded = try { files.read(owner) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        val latest = receipts.latestByHookIds(owner, records.map { it.scheduleId }.toSet())
        val rows = records.map { record ->
            val hookStatus = if (loaded == null) "unavailable" else loaded[record.scheduleId].status()
            OperationResult(id = record.scheduleId, record = record, hookStatus = hookStatus).apply {
                extra["lastExecution"] = latest[record.scheduleId]?.let { mapOf(
                    "receiptId" to it.id, "receiptStatus" to it.status, "errorCode" to it.errorCode,
                    "llmCalls" to it.llmCalls, "totalTokens" to it.totalTokens,
                ) }
            }.finish()
        }.filter { includeInactive || (it["hookStatus"] == "enabled" && it["scheduleStatus"] in setOf("active", "dispatching")) }
        return mapOf("status" to if (loaded == null) "partial" else "succeeded", "tasks" to rows,
            "error" to if (loaded == null) safeError("hook_workspace_unavailable") else null)
    }

    private fun parseInput(operation: String, arguments: Map<String, Any>): TaskInput = try {
        val node = taskJson.valueToTree<JsonNode>(arguments)
        val allowed = when (operation) {
            "create" -> setOf("title", "prompt", "schedule", "channelId")
            "update" -> setOf("taskId", "title", "prompt", "schedule", "channelId")
            "list" -> setOf("includeInactive", "channelId")
            else -> setOf("taskId", "channelId")
        }
        taskCheck(node.fieldNames().asSequence().all { it in allowed } && node.none { it.isNull })
        fun text(name: String): String? = node[name]?.let { taskCheck(it.isTextual); it.textValue() }
        val id = text("taskId")
        taskCheck(operation in setOf("create", "list") || (id != null && validTaskId(id)))
        val title = text("title")?.also { taskCheck(it.isNotBlank() && it.length <= 200) }
        val prompt = text("prompt")?.also { taskCheck(it.isNotBlank() && it.length <= 16_384) }
        val schedule = node["schedule"]?.let { scheduleInput(it, clock.instant(), allowAfter = operation == "create") }
        taskCheck(operation != "create" || (title != null && prompt != null && schedule != null))
        taskCheck(operation != "update" || title != null || prompt != null || schedule != null)
        taskCheck(!node.has("includeInactive") || node["includeInactive"].isBoolean)
        TaskInput(id, title, prompt, schedule, text("channelId")?.let(UUID::fromString), node.path("includeInactive").asBoolean(false))
    } catch (_: Exception) { throw TaskFailure("invalid_arguments") }
}

private data class TaskInput(val id: String?, val title: String?, val prompt: String?, val schedule: TaskSchedule?, val channelId: UUID?, val includeInactive: Boolean)
private fun String?.uuidOrNull(): UUID? = this?.let { runCatching { UUID.fromString(it) }.getOrNull() }
private fun LoadedHook?.status(): String = when { this == null -> "missing"; definition.enabled -> "enabled"; else -> "disabled" }
private fun safeError(code: String): Map<String, String> = mapOf("code" to code, "message" to code.replace('_', ' '))
private class OperationResult(
    var id: String? = null,
    var record: ScheduleView? = null,
    var hookStatus: String = "not_checked",
    var scheduleStatus: String = "unavailable",
    var changed: Boolean = false,
    val extra: MutableMap<String, Any?> = linkedMapOf(),
) {
    fun finish(error: String? = null): Map<String, Any?> = buildMap {
        put("status", if (error == null) "succeeded" else if (changed) "partial" else "failed")
        id?.let { put("taskId", it) }
        put("hookStatus", hookStatus)
        put("scheduleStatus", record?.state ?: scheduleStatus)
        record?.let {
            put("title", it.title); put("schedule", it.schedule); put("nextRunAt", it.nextRunAt)
            put("lastDelivery", it.lastDelivery)
        }
        putAll(extra)
        error?.let { put("error", safeError(it)) }
    }
}
