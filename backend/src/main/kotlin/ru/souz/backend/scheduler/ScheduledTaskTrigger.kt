package ru.souz.backend.scheduler

import ru.souz.backend.channels.tool.SEND_MESSAGE_TO_CHANNEL_TOOL_NAME
import ru.souz.backend.channels.tool.ToolSendMessageToChannel
import ru.souz.backend.hooks.ScheduledHookDispatch
import ru.souz.jobs.JobRun

data class ScheduledTaskConfig(
    val runsPerUserPerDay: Int = 100,
    val concurrentRuns: Int = 16,
    val runTimeoutMillis: Long = 300_000,
) {
    init {
        require(runsPerUserPerDay > 0 && concurrentRuns > 0 && runTimeoutMillis > 0)
    }
}

internal suspend fun ScheduledHookDispatch.dispatchScheduledTask(job: JobRun) {
    run(
        userId = job.userId,
        title = job.title,
        content = scheduledTaskInput(job),
        occurrenceKey = job.occurrenceKey(),
        scheduledAt = job.scheduledAt,
        requiresDelivery = job.payload.path("requiresDelivery").asBoolean(true),
    )
}

/**
 * At most once shares the trigger key across retries. At least once gives each attempt a key,
 * allowing a retry after an uncertain outcome to launch again.
 */
private fun JobRun.occurrenceKey(): String {
    val attemptKey = if (payload.path("atMostOnce").asBoolean(false)) "" else ":attempt-$attempt"
    return "$jobId:$scheduledAt$attemptKey"
}

internal fun scheduledTaskInput(job: JobRun): String {
    val channelType = job.payload.path("replyChannelType").textValue()
    val channelId = job.payload.path("replyChannelId").textValue()
    // Naming every argument lets the model call the tool without guessing; the names follow the tool.
    val typeArgument = ToolSendMessageToChannel.Input::channelType.name
    val idArgument = ToolSendMessageToChannel.Input::channelId.name
    val textArgument = ToolSendMessageToChannel.Input::text.name
    val destination = if (channelType != null && channelId != null) {
        "If the task calls for a message to the user and names no other destination, send it to the " +
            "conversation where the task was created: $SEND_MESSAGE_TO_CHANNEL_TOOL_NAME with " +
            "$typeArgument=$channelType, $idArgument=$channelId and the message in $textArgument."
    } else {
        "If the task calls for a message to the user, send it with $SEND_MESSAGE_TO_CHANNEL_TOOL_NAME to the " +
            "destination the task names, passing $typeArgument, $idArgument and the message in $textArgument."
    }
    return listOf(
        job.payload.path("prompt").asText(),
        "",
        "This is scheduled task \"${job.title}\", occurrence ${job.scheduledAt}. It runs in a hidden " +
            "technical chat: your final answer is not shown to the user.",
        destination,
        "Send nothing when the task only asks for an action.",
    ).joinToString("\n")
}
