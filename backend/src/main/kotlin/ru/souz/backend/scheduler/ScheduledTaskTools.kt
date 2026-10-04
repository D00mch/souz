package ru.souz.backend.scheduler

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory

/** Resolve the execution service on invocation: the catalog also participates in runtime construction. */
internal class ScheduledTaskTools(service: () -> ScheduledTaskService) : AgentToolCatalog {
    override val toolsByCategory = mapOf(ToolCategory.SCHEDULING to
        listOf("create", "get", "list", "update", "delete").associate { operation ->
            val name = "scheduled-task.$operation"
            name to object : LLMToolSetup {
                override val fn = LLMRequest.Function(name, descriptions.getValue(operation) + commonDescription, parameters(operation))
                override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                    message(functionCall.name, mapOf("status" to "failed", "error" to mapOf("code" to "task_context_missing")))
                override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message =
                    message(functionCall.name, service().invoke(operation, functionCall.arguments, meta))
            }
        })
}

private fun message(name: String, value: Any) = LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(value), name = name)
private val descriptions = mapOf(
    "create" to "Create a scheduled task: title, self-contained prompt, and schedule are required. A new hook runs the prompt in a fresh hidden chat at each occurrence. Souz adds the originating user channel as the default reply destination when available; use SendMessageToChannel only when the task calls for a user-facing message. For action-only requests, do not add success notifications to the task prompt unless the user asked for them. For child tasks created by a hook, explicitly carry over the required delivery destination into the prompt. ",
    "get" to "Read a scheduled task by taskId, including its prompt and latest execution result. ",
    "list" to "List scheduled tasks. includeInactive defaults to false; true also shows completed tasks and schedules with disabled or missing hooks. Integration upkeep (e.g. Gmail renewal) shares this list. ",
    "update" to "Update a taskId with a nonempty patch of title, prompt, schedule. Omitted fields are unchanged; null is invalid. The original default reply destination is preserved; put an explicit destination change in the task instruction. Cannot resume a disabled hook. ",
    "delete" to "Delete the selected taskId, disabling its hook before cancelling its schedule. Already running executions and independently created tasks remain. ",
)
private const val commonDescription = "Requires an active Orion WebSocket for this owner; optional channelId chooses one explicitly. " +
    "Only status=succeeded confirms completion. On partial, inspect get(taskId); do not blindly create a replacement. " +
    "A missing schedule does not authorize hook cleanup. Task purpose is not classified; select specific tasks, never equate all tasks with reminders."
private fun parameters(operation: String): LLMRequest.Parameters {
    val properties = linkedMapOf("channelId" to LLMRequest.Property("string", "Optional owned active Orion channel UUID for scheduler RPC; otherwise selected automatically. This does not select the reply destination."))
    if (operation in setOf("get", "update", "delete")) properties["taskId"] = LLMRequest.Property("string", "ID returned by create/list: task-<UUID>.")
    if (operation in setOf("create", "update")) {
        properties["title"] = LLMRequest.Property("string", "Nonblank title, at most 200 characters; stored on Orion.")
        properties["prompt"] = LLMRequest.Property("string", "Self-contained agent instruction; the saved prompt including Souz's delivery context is limited to 16384 characters. Include any required destination and context.")
        properties["schedule"] = LLMRequest.Property("object", "once: kind, runAt (future RFC3339 with offset). cron: kind, expression (Unix five numeric fields), timeZone (IANA); DOM or DOW must be *. " +
            if (operation == "create") "after: kind, seconds (positive integer); converted once by the backend clock." else "after is not accepted by update.",
            properties = mapOf(
                "kind" to LLMRequest.Property("string", enum = if (operation == "create") listOf("once", "cron", "after") else listOf("once", "cron")),
                "runAt" to LLMRequest.Property("string"), "expression" to LLMRequest.Property("string"),
                "timeZone" to LLMRequest.Property("string"), "seconds" to LLMRequest.Property("integer"),
            ))
    }
    if (operation == "list") properties["includeInactive"] = LLMRequest.Property("boolean", "Include finished, disabled and missing-hook tasks; default false.")
    return LLMRequest.Parameters("object", properties, when (operation) {
        "create" -> listOf("title", "prompt", "schedule")
        "list" -> emptyList()
        else -> listOf("taskId")
    })
}
