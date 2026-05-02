package ru.souz.backend.http

import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.service.ChatSummary
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.options.service.AnswerOptionResult
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionUsage
import ru.souz.backend.keys.model.UserProviderKeyView
import ru.souz.backend.execution.service.CancelExecutionResult
import ru.souz.backend.settings.model.EffectiveUserSettings
import ru.souz.llms.restJsonMapper

internal data class BackendV1SettingsResponse(
    val settings: BackendV1SettingsDto,
)

internal data class BackendV1SettingsPatchRequest(
    val defaultModel: String? = null,
    val contextSize: Int? = null,
    val temperature: Float? = null,
    val locale: String? = null,
    val timeZone: String? = null,
    val systemPrompt: String? = null,
    val enabledTools: List<String>? = null,
    val showToolEvents: Boolean? = null,
    val streamingMessages: Boolean? = null,
)

internal data class BackendV1SettingsDto(
    val defaultModel: String,
    val contextSize: Int,
    val temperature: Float,
    val locale: String,
    val timeZone: String,
    val systemPrompt: String?,
    val enabledTools: List<String>,
    val showToolEvents: Boolean,
    val streamingMessages: Boolean,
)

internal data class BackendV1ProviderKeysResponse(
    val items: List<BackendV1ProviderKeyDto>,
)

internal data class BackendV1PutProviderKeyRequest(
    val apiKey: String = "",
)

internal data class BackendV1PutProviderKeyResponse(
    val providerKey: BackendV1ProviderKeyDto,
)

internal data class BackendV1ProviderKeyDto(
    val provider: String,
    val configured: Boolean,
    val keyHint: String?,
    val updatedAt: String?,
)

internal data class BackendV1ChatsResponse(
    val items: List<BackendV1ChatDto>,
    val nextCursor: String?,
)

internal data class BackendV1CreateChatRequest(
    val title: String? = null,
)

internal data class BackendV1CreateChatResponse(
    val chat: BackendV1ChatDto,
)

internal data class BackendV1ChatDto(
    val id: String,
    val title: String?,
    val archived: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val lastMessagePreview: String? = null,
)

internal data class BackendV1MessagesResponse(
    val items: List<BackendV1MessageDto>,
    val nextBeforeSeq: Long?,
)

internal data class BackendV1CreateMessageRequest(
    val content: String = "",
    val clientMessageId: String? = null,
    val options: BackendV1MessageOptionsRequest? = null,
)

internal data class BackendV1MessageOptionsRequest(
    val model: String? = null,
    val contextSize: Int? = null,
    val temperature: Float? = null,
    val locale: String? = null,
    val timeZone: String? = null,
    val systemPrompt: String? = null,
)

internal data class BackendV1CreateMessageResponse(
    val message: BackendV1MessageDto,
    val assistantMessage: BackendV1MessageDto?,
    val execution: BackendV1ExecutionDto,
)

internal data class BackendV1CancelExecutionResponse(
    val execution: BackendV1ExecutionDto,
)

internal data class BackendV1AnswerOptionRequest(
    val selectedOptionIds: List<String> = emptyList(),
    val freeText: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

internal data class BackendV1AnswerOptionResponse(
    val option: BackendV1OptionDto,
    val execution: BackendV1ExecutionDto,
)

internal data class BackendV1OptionDto(
    val id: String,
    val status: String,
)

internal data class BackendV1EventsResponse(
    val items: List<BackendV1EventDto>,
)

internal data class BackendV1MessageDto(
    val id: String,
    val chatId: String,
    val seq: Long,
    val role: String,
    val content: String,
    val metadata: Map<String, String>,
    val createdAt: String,
)

internal data class BackendV1ExecutionDto(
    val id: String,
    val chatId: String,
    val userMessageId: String?,
    val assistantMessageId: String?,
    val status: String,
    val requestId: String?,
    val clientMessageId: String?,
    val model: String?,
    val provider: String?,
    val startedAt: String,
    val finishedAt: String?,
    val cancelRequested: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val usage: BackendV1ExecutionUsageDto?,
    val metadata: Map<String, String>,
)

internal data class BackendV1ExecutionUsageDto(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val precachedTokens: Int,
)

internal data class BackendV1OptionItemDto(
    val id: String,
    val label: String,
    val content: String? = null,
)

internal data class BackendV1EventDto(
    val seq: Long,
    val chatId: String,
    val executionId: String?,
    val type: String,
    val payload: Map<String, Any?>,
    val createdAt: String,
)

internal fun EffectiveUserSettings.toDto(): BackendV1SettingsDto =
    BackendV1SettingsDto(
        defaultModel = defaultModel.alias,
        contextSize = contextSize,
        temperature = temperature,
        locale = locale.toLanguageTag(),
        timeZone = timeZone.id,
        systemPrompt = systemPrompt,
        enabledTools = enabledTools.toList(),
        showToolEvents = showToolEvents,
        streamingMessages = streamingMessages,
    )

internal fun UserProviderKeyView.toDto(): BackendV1ProviderKeyDto =
    BackendV1ProviderKeyDto(
        provider = provider.name.lowercase(),
        configured = configured,
        keyHint = keyHint,
        updatedAt = updatedAt?.toString(),
    )

internal fun ChatSummary.toDto(): BackendV1ChatDto =
    chat.toDto(lastMessagePreview = lastMessagePreview)

internal fun Chat.toDto(lastMessagePreview: String? = null): BackendV1ChatDto =
    BackendV1ChatDto(
        id = id.toString(),
        title = title,
        archived = archived,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        lastMessagePreview = lastMessagePreview,
    )

internal fun SendMessageResult.toResponse(): BackendV1CreateMessageResponse =
    BackendV1CreateMessageResponse(
        message = userMessage.toDto(),
        assistantMessage = assistantMessage?.toDto(),
        execution = execution.toDto(),
    )

internal fun CancelExecutionResult.toResponse(): BackendV1CancelExecutionResponse =
    BackendV1CancelExecutionResponse(
        execution = execution.toDto(),
    )

internal fun AnswerOptionResult.toResponse(): BackendV1AnswerOptionResponse =
    BackendV1AnswerOptionResponse(
        option = BackendV1OptionDto(
            id = option.id.toString(),
            status = option.status.value,
        ),
        execution = execution.toDto(),
    )

internal fun ChatMessage.toDto(): BackendV1MessageDto =
    BackendV1MessageDto(
        id = id.toString(),
        chatId = chatId.toString(),
        seq = seq,
        role = role.value,
        content = content,
        metadata = metadata,
        createdAt = createdAt.toString(),
    )

internal fun AgentExecution.toDto(): BackendV1ExecutionDto =
    BackendV1ExecutionDto(
        id = id.toString(),
        chatId = chatId.toString(),
        userMessageId = userMessageId?.toString(),
        assistantMessageId = assistantMessageId?.toString(),
        status = status.value,
        requestId = requestId,
        clientMessageId = clientMessageId,
        model = model?.alias,
        provider = provider?.name,
        startedAt = startedAt.toString(),
        finishedAt = finishedAt?.toString(),
        cancelRequested = cancelRequested,
        errorCode = errorCode,
        errorMessage = errorMessage,
        usage = usage?.toDto(),
        metadata = emptyMap(),
    )

internal fun AgentExecutionUsage.toDto(): BackendV1ExecutionUsageDto =
    BackendV1ExecutionUsageDto(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        precachedTokens = precachedTokens,
    )

internal fun AgentEvent.toDto(): BackendV1EventDto =
    BackendV1EventDto(
        seq = seq,
        chatId = chatId.toString(),
        executionId = executionId?.toString(),
        type = type.value,
        payload = payload.toTransportPayload(type),
        createdAt = createdAt.toString(),
    )

private fun Map<String, String>.toTransportPayload(type: AgentEventType): Map<String, Any?> =
    when (type) {
        AgentEventType.MESSAGE_CREATED,
        AgentEventType.MESSAGE_COMPLETED,
        -> buildPayload {
            copyIfPresent("messageId")
            copyLongIfPresent("seq")
            copyIfPresent("role")
            copyIfPresent("content")
        }

        AgentEventType.MESSAGE_DELTA -> buildPayload {
            copyIfPresent("messageId")
            copyLongIfPresent("seq")
            copyIfPresent("delta")
        }

        AgentEventType.EXECUTION_STARTED -> buildPayload {
            copyIfPresent("executionId")
            copyIfPresent("userMessageId")
            copyIfPresent("model")
            copyIfPresent("provider")
            copyBooleanIfPresent("streamingMessages")
        }

        AgentEventType.EXECUTION_FINISHED -> buildPayload {
            copyIfPresent("executionId")
            copyIfPresent("assistantMessageId")
            copyIfPresent("status")
            copyIntIfPresent("promptTokens")
            copyIntIfPresent("completionTokens")
            copyIntIfPresent("totalTokens")
            copyIntIfPresent("precachedTokens")
        }

        AgentEventType.EXECUTION_FAILED -> buildPayload {
            copyIfPresent("executionId")
            copyIfPresent("assistantMessageId")
            copyIfPresent("errorCode")
            copyIfPresent("errorMessage")
        }

        AgentEventType.EXECUTION_CANCELLED -> buildPayload {
            copyIfPresent("executionId")
            copyIfPresent("assistantMessageId")
        }

        AgentEventType.OPTION_REQUESTED -> buildPayload {
            copyIfPresent("optionId")
            copyIfPresent("optionId", sourceKey = "choiceId")
            copyIfPresent("kind")
            copyIfPresent("title")
            copyIfPresent("selectionMode")
            copyJsonIfPresent<List<BackendV1OptionItemDto>>("options")
        }

        AgentEventType.OPTION_ANSWERED -> buildPayload {
            copyIfPresent("optionId")
            copyIfPresent("optionId", sourceKey = "choiceId")
            copyIfPresent("status")
            copyJsonIfPresent<List<String>>("selectedOptionIds")
            copyIfPresent("freeText")
            copyJsonIfPresent<Map<String, String>>("metadata")
        }

        AgentEventType.TOOL_CALL_STARTED -> buildPayload {
            copyIfPresent("toolCallId")
            copyIfPresent("name")
            copyJsonValueIfPresent("argumentsPreview")
        }

        AgentEventType.TOOL_CALL_FINISHED -> buildPayload {
            copyIfPresent("toolCallId")
            copyIfPresent("name")
            copyIfPresent("status")
            copyLongIfPresent("durationMs")
            copyJsonValueIfPresent("resultPreview")
        }

        AgentEventType.TOOL_CALL_FAILED -> buildPayload {
            copyIfPresent("toolCallId")
            copyIfPresent("name")
            copyIfPresent("status")
            copyIfPresent("error")
            copyLongIfPresent("durationMs")
        }
    }

private inline fun Map<String, String>.buildPayload(
    block: PayloadBuilder.() -> Unit,
): Map<String, Any?> =
    PayloadBuilder(this).apply(block).values

private class PayloadBuilder(
    private val source: Map<String, String>,
) {
    val values: MutableMap<String, Any?> = LinkedHashMap()

    fun copyIfPresent(
        key: String,
        sourceKey: String = key,
    ) {
        source[sourceKey]?.let { values[key] = it }
    }

    fun copyLongIfPresent(
        key: String,
        sourceKey: String = key,
    ) {
        source[sourceKey]?.toLongOrNull()?.let { values[key] = it }
    }

    fun copyIntIfPresent(
        key: String,
        sourceKey: String = key,
    ) {
        source[sourceKey]?.toIntOrNull()?.let { values[key] = it }
    }

    fun copyBooleanIfPresent(
        key: String,
        sourceKey: String = key,
    ) {
        source[sourceKey]?.toBooleanStrictOrNull()?.let { values[key] = it }
    }

    inline fun <reified T> copyJsonIfPresent(
        key: String,
        sourceKey: String = key,
    ) {
        source[sourceKey]?.takeIf { it.isNotBlank() }?.let { rawValue ->
            values[key] = restJsonMapper.readValue(rawValue, object : com.fasterxml.jackson.core.type.TypeReference<T>() {})
        }
    }

    fun copyJsonValueIfPresent(
        key: String,
        sourceKey: String = key,
    ) {
        source[sourceKey]?.takeIf { it.isNotBlank() }?.let { rawValue ->
            values[key] = runCatching { restJsonMapper.readValue(rawValue, Any::class.java) }
                .getOrElse { rawValue }
        }
    }
}
