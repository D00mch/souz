package ru.souz.service.observability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import org.slf4j.spi.LoggingEventBuilder
import ru.souz.agent.runtime.AgentExecutionLogContext
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolExecutionEvent
import ru.souz.llms.LLMResponse
import ru.souz.llms.plus
import java.util.UUID
import kotlin.coroutines.CoroutineContext

private const val STRUCTURED_LOGGER_NAME = "ru.souz.telemetry.structured"
private val telemetryLog = LoggerFactory.getLogger(STRUCTURED_LOGGER_NAME)
private val ZERO_USAGE = LLMResponse.Usage(0, 0, 0, 0)

object DesktopStructuredLoggingSession {
    val appSessionId: String = UUID.randomUUID().toString()
    val appStartedAtMs: Long = System.currentTimeMillis()
}

enum class ChatRequestSource(val wireName: String) {
    CHAT_UI("chat_ui"),
    VOICE_INPUT("voice_input"),
    TELEGRAM_BOT("telegram_bot"),
}

enum class ChatRequestStatus(val wireName: String) {
    SUCCESS("success"),
    ERROR("error"),
    CANCELLED("cancelled"),
}

enum class ChatConversationCloseReason(val wireName: String) {
    NEW_CONVERSATION("new_conversation"),
    CLEAR_CONTEXT("clear_context"),
    VIEW_MODEL_CLEARED("view_model_cleared"),
}

data class ChatRequestLogContext(
    val executionContext: AgentExecutionLogContext,
    val source: ChatRequestSource,
    val model: String,
    val provider: String,
    val inputLengthChars: Int,
    val attachedFilesCount: Int,
    val startedAtMs: Long,
) {
    val requestId: String get() = executionContext.requestId
    val conversationId: String get() = executionContext.conversationId
    val toolExecutionCount: Int get() = executionContext.toolExecutionCount

    fun asCoroutineContext(): CoroutineContext = executionContext.asCoroutineContext()
}

internal data class ChatConversationMetrics(
    val startedAtMs: Long,
    val startSource: ChatRequestSource,
    val requestCount: Int = 0,
    val toolCallCount: Int = 0,
    val tokenUsage: LLMResponse.Usage = ZERO_USAGE,
)

class StructuredLoggingAgentTelemetry : AgentTelemetry {
    override fun recordToolExecution(event: AgentToolExecutionEvent) {
        val builder = if (event.success) telemetryLog.atInfo() else telemetryLog.atWarn()
        builder
            .addKeyValue("event.domain", "souz.agent")
            .addKeyValue("event.name", "tool.execution")
            .addIfPresent("app.session.id", event.appSessionId)
            .addIfPresent("conversation.id", event.conversationId)
            .addIfPresent("request.id", event.requestId)
            .addIfPresent("request.source", event.requestSource)
            .addIfPresent("gen_ai.request.model", event.model)
            .addIfPresent("gen_ai.system", event.provider)
            .addKeyValue("tool.name", event.functionName)
            .addIfPresent("tool.category", event.toolCategory)
            .addKeyValue("tool.arguments.count", event.argumentKeys.size)
            .addKeyValue("tool.arguments.keys", event.argumentKeys.joinToString(","))
            .addKeyValue("tool.success", event.success)
            .addKeyValue("tool.duration.ms", event.durationMs)
            .addIfPresent("error.type", event.errorType)
            .log(if (event.success) "tool execution" else "tool execution failed")
    }
}

internal class ChatObservabilityTracker(
    private val onConversationStarted: (String, ChatRequestSource) -> Unit = ::logConversationStarted,
    private val onConversationFinished: (String, ChatConversationMetrics, ChatConversationCloseReason) -> Unit = ::logConversationFinished,
) {
    private val state = MutableStateFlow(ConversationTrackingState())

    fun ensureConversation(source: ChatRequestSource): String {
        val newConversationId = UUID.randomUUID().toString()
        val previous = state.getAndUpdate { current ->
            current.currentConversationId?.let { return@getAndUpdate current }
            current.copy(
                currentConversationId = newConversationId,
                metricsByConversationId = current.metricsByConversationId + (
                    newConversationId to ChatConversationMetrics(
                        startedAtMs = System.currentTimeMillis(),
                        startSource = source,
                    )
                ),
            )
        }
        if (previous.currentConversationId == null) {
            onConversationStarted(newConversationId, source)
            return newConversationId
        }
        return previous.currentConversationId
    }

    fun finishCurrentConversation(reason: ChatConversationCloseReason) {
        val previous = state.getAndUpdate { current ->
            val conversationId = current.currentConversationId ?: return@getAndUpdate current
            val hasActiveRequests = (current.activeRequestCounts[conversationId] ?: 0) > 0
            current.copy(
                currentConversationId = null,
                pendingClosures = if (hasActiveRequests) {
                    current.pendingClosures + (conversationId to reason)
                } else {
                    current.pendingClosures - conversationId
                },
                metricsByConversationId = if (hasActiveRequests) {
                    current.metricsByConversationId
                } else {
                    current.metricsByConversationId - conversationId
                },
            )
        }
        val conversationId = previous.currentConversationId ?: return
        if ((previous.activeRequestCounts[conversationId] ?: 0) == 0) {
            previous.metricsByConversationId[conversationId]?.let { metrics ->
                onConversationFinished(conversationId, metrics, reason)
            }
        }
    }

    fun markConversationRequestStarted(conversationId: String) {
        state.update { current ->
            current.copy(
                activeRequestCounts = current.activeRequestCounts + (
                    conversationId to ((current.activeRequestCounts[conversationId] ?: 0) + 1)
                )
            )
        }
    }

    fun recordConversationRequestFinished(
        conversationId: String,
        toolCallCount: Int,
        requestTokenUsage: LLMResponse.Usage,
    ) {
        state.update { current ->
            val metrics = current.metricsByConversationId[conversationId] ?: return@update current
            current.copy(
                metricsByConversationId = current.metricsByConversationId + (
                    conversationId to metrics.copy(
                        requestCount = metrics.requestCount + 1,
                        toolCallCount = metrics.toolCallCount + toolCallCount,
                        tokenUsage = metrics.tokenUsage + requestTokenUsage,
                    )
                )
            )
        }
    }

    fun finishPendingConversationIfNeeded(conversationId: String) {
        val previous = state.getAndUpdate { current ->
            val activeRequestCount = current.activeRequestCounts[conversationId] ?: 0
            if (activeRequestCount > 1) {
                current.copy(
                    activeRequestCounts = current.activeRequestCounts + (conversationId to (activeRequestCount - 1))
                )
            } else {
                val hasPendingClosure = current.pendingClosures.containsKey(conversationId)
                current.copy(
                    activeRequestCounts = current.activeRequestCounts - conversationId,
                    pendingClosures = current.pendingClosures - conversationId,
                    metricsByConversationId = if (hasPendingClosure) {
                        current.metricsByConversationId - conversationId
                    } else {
                        current.metricsByConversationId
                    },
                )
            }
        }
        if ((previous.activeRequestCounts[conversationId] ?: 0) <= 1) {
            val reason = previous.pendingClosures[conversationId] ?: return
            val metrics = previous.metricsByConversationId[conversationId] ?: return
            onConversationFinished(conversationId, metrics, reason)
        }
    }
}

fun newChatRequestLogContext(
    conversationId: String,
    source: ChatRequestSource,
    model: String,
    provider: String,
    inputLengthChars: Int,
    attachedFilesCount: Int,
): ChatRequestLogContext = ChatRequestLogContext(
    executionContext = AgentExecutionLogContext(
        appSessionId = DesktopStructuredLoggingSession.appSessionId,
        requestId = UUID.randomUUID().toString(),
        conversationId = conversationId,
        requestSource = source.wireName,
        model = model,
        provider = provider,
    ),
    source = source,
    model = model,
    provider = provider,
    inputLengthChars = inputLengthChars,
    attachedFilesCount = attachedFilesCount,
    startedAtMs = System.currentTimeMillis(),
)

fun logAppOpened() {
    telemetryLog.atInfo()
        .addKeyValue("event.domain", "souz.desktop")
        .addKeyValue("event.name", "app.lifecycle")
        .addKeyValue("app.lifecycle.state", "opened")
        .addKeyValue("app.session.id", DesktopStructuredLoggingSession.appSessionId)
        .log("app lifecycle")
}

fun logAppClosed() {
    telemetryLog.atInfo()
        .addKeyValue("event.domain", "souz.desktop")
        .addKeyValue("event.name", "app.lifecycle")
        .addKeyValue("app.lifecycle.state", "closed")
        .addKeyValue("app.session.id", DesktopStructuredLoggingSession.appSessionId)
        .addKeyValue("duration.ms", System.currentTimeMillis() - DesktopStructuredLoggingSession.appStartedAtMs)
        .log("app lifecycle")
}

fun logConversationStarted(
    conversationId: String,
    source: ChatRequestSource,
) {
    telemetryLog.atInfo()
        .addKeyValue("event.domain", "souz.chat")
        .addKeyValue("event.name", "conversation.lifecycle")
        .addKeyValue("conversation.state", "started")
        .addKeyValue("app.session.id", DesktopStructuredLoggingSession.appSessionId)
        .addKeyValue("conversation.id", conversationId)
        .addKeyValue("request.source", source.wireName)
        .log("conversation lifecycle")
}

internal fun logConversationFinished(
    conversationId: String,
    metrics: ChatConversationMetrics,
    reason: ChatConversationCloseReason,
) {
    telemetryLog.atInfo()
        .addKeyValue("event.domain", "souz.chat")
        .addKeyValue("event.name", "conversation.lifecycle")
        .addKeyValue("conversation.state", "finished")
        .addKeyValue("app.session.id", DesktopStructuredLoggingSession.appSessionId)
        .addKeyValue("conversation.id", conversationId)
        .addKeyValue("conversation.close.reason", reason.wireName)
        .addKeyValue("conversation.start.source", metrics.startSource.wireName)
        .addKeyValue("conversation.request.count", metrics.requestCount)
        .addKeyValue("tool.calls.count", metrics.toolCallCount)
        .addKeyValue("duration.ms", System.currentTimeMillis() - metrics.startedAtMs)
        .withUsage("gen_ai.usage", metrics.tokenUsage)
        .log("conversation lifecycle")
}

fun logRequestStarted(context: ChatRequestLogContext) {
    telemetryLog.atInfo()
        .addKeyValue("event.domain", "souz.chat")
        .addKeyValue("event.name", "request.lifecycle")
        .addKeyValue("request.state", "started")
        .withRequestContext(context)
        .addKeyValue("request.input.length.chars", context.inputLengthChars)
        .addKeyValue("request.attached_files.count", context.attachedFilesCount)
        .log("request lifecycle")
}

fun logRequestFinished(
    context: ChatRequestLogContext,
    status: ChatRequestStatus,
    responseLengthChars: Int?,
    errorType: String?,
    requestTokenUsage: LLMResponse.Usage,
    sessionTokenUsage: LLMResponse.Usage,
) {
    val builder = when (status) {
        ChatRequestStatus.ERROR -> telemetryLog.atWarn()
        ChatRequestStatus.CANCELLED -> telemetryLog.atInfo()
        ChatRequestStatus.SUCCESS -> telemetryLog.atInfo()
    }

    builder
        .addKeyValue("event.domain", "souz.chat")
        .addKeyValue("event.name", "request.lifecycle")
        .addKeyValue("request.state", "finished")
        .withRequestContext(context)
        .addKeyValue("request.status", status.wireName)
        .addKeyValue("duration.ms", System.currentTimeMillis() - context.startedAtMs)
        .addKeyValue("request.input.length.chars", context.inputLengthChars)
        .addIfPresent("request.output.length.chars", responseLengthChars)
        .addKeyValue("request.attached_files.count", context.attachedFilesCount)
        .addKeyValue("tool.calls.count", context.toolExecutionCount)
        .withUsage("gen_ai.usage", requestTokenUsage)
        .withUsage("session.gen_ai.usage", sessionTokenUsage)
        .addIfPresent("error.type", errorType)
        .log("request lifecycle")
}

private fun LoggingEventBuilder.withRequestContext(context: ChatRequestLogContext): LoggingEventBuilder = apply {
    addKeyValue("app.session.id", DesktopStructuredLoggingSession.appSessionId)
    addKeyValue("conversation.id", context.conversationId)
    addKeyValue("request.id", context.requestId)
    addKeyValue("request.source", context.source.wireName)
    addKeyValue("gen_ai.request.model", context.model)
    addKeyValue("gen_ai.system", context.provider)
}

private fun LoggingEventBuilder.withUsage(prefix: String, usage: LLMResponse.Usage): LoggingEventBuilder = apply {
    addKeyValue("$prefix.input_tokens", usage.promptTokens)
    addKeyValue("$prefix.output_tokens", usage.completionTokens)
    addKeyValue("$prefix.total_tokens", usage.totalTokens)
    addKeyValue("$prefix.cached_input_tokens", usage.precachedTokens)
}

private fun LoggingEventBuilder.addIfPresent(key: String, value: Any?): LoggingEventBuilder = apply {
    if (value != null) {
        addKeyValue(key, value)
    }
}

private data class ConversationTrackingState(
    val currentConversationId: String? = null,
    val pendingClosures: Map<String, ChatConversationCloseReason> = emptyMap(),
    val activeRequestCounts: Map<String, Int> = emptyMap(),
    val metricsByConversationId: Map<String, ChatConversationMetrics> = emptyMap(),
)
