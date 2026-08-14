package ru.souz.backend.http

import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus

internal fun awaitExecutionStatus(
    context: RouteTestContext,
    userId: String,
    chatId: UUID,
    status: AgentExecutionStatus,
): AgentExecution = runBlocking {
    eventually("execution in chat $chatId to reach $status") {
        context.executionRepository.listByChat(userId, chatId).singleOrNull()
            ?.takeIf { it.status == status }
    }
}

internal fun awaitExecutionStatus(
    context: RouteTestContext,
    userId: String,
    chatId: UUID,
    executionId: UUID,
    status: AgentExecutionStatus,
): AgentExecution = runBlocking {
    eventually("execution $executionId to reach $status") {
        context.executionRepository.getByChat(userId, chatId, executionId)
            ?.takeIf { it.status == status }
    }
}

internal fun awaitVisibleMessages(
    context: RouteTestContext,
    userId: String,
    chatId: UUID,
    expectedSize: Int,
): List<ChatMessage> = runBlocking {
    eventually("$expectedSize visible messages for chat $chatId") {
        context.messageRepository.list(userId, chatId)
            .takeIf { it.size >= expectedSize }
    }
}

internal fun awaitEvents(
    context: RouteTestContext,
    userId: String,
    chatId: UUID,
    expectedSize: Int,
): List<AgentEvent> = runBlocking {
    eventually("$expectedSize events for chat $chatId") {
        context.eventRepository.listByChat(userId, chatId)
            .takeIf { it.size >= expectedSize }
    }
}

internal suspend fun <T : Any> eventually(
    description: String,
    block: suspend () -> T?,
): T {
    val timeout = TimeSource.Monotonic.markNow() + 2.seconds
    while (true) {
        block()?.let { return it }
        if (timeout.hasPassedNow()) {
            throw AssertionError("Timed out waiting for $description.")
        }
        delay(10)
    }
}
