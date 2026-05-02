package ru.souz.backend.execution.service

import java.time.Instant
import java.util.UUID
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.settings.model.EffectiveUserSettings
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.llms.restJsonMapper

internal data class PreparedChatTurn(
    val normalizedClientMessageId: String?,
    val effectiveSettings: EffectiveUserSettings,
    val execution: AgentExecution,
    val conversationKey: AgentConversationKey,
    val runtimeRequest: BackendConversationTurnRequest,
    val userMessageMetadata: Map<String, String>,
    val shouldReturnRunning: Boolean,
)

internal data class PreparedContinuationTurn(
    val conversationKey: AgentConversationKey,
    val runtimeRequest: BackendConversationTurnRequest,
    val streamingMessagesEnabled: Boolean,
    val toolEventsEnabled: Boolean,
)

internal class AgentExecutionRequestFactory(
    private val effectiveSettingsResolver: EffectiveSettingsResolver,
    private val featureFlags: BackendFeatureFlags,
) {
    suspend fun prepareChatTurn(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String? = null,
        requestOverrides: UserSettingsOverrides = UserSettingsOverrides(),
    ): PreparedChatTurn {
        val effectiveSettings = effectiveSettingsResolver.resolve(userId, requestOverrides)
        val normalizedClientMessageId = clientMessageId?.trim()?.takeIf { it.isNotEmpty() }
        val execution = AgentExecution(
            id = UUID.randomUUID(),
            userId = userId,
            chatId = chatId,
            userMessageId = null,
            assistantMessageId = null,
            status = AgentExecutionStatus.QUEUED,
            requestId = null,
            clientMessageId = normalizedClientMessageId,
            model = effectiveSettings.defaultModel,
            provider = effectiveSettings.defaultModel.provider,
            startedAt = Instant.now(),
            finishedAt = null,
            cancelRequested = false,
            errorCode = null,
            errorMessage = null,
            usage = null,
            metadata = executionMetadata(
                contextSize = effectiveSettings.contextSize,
                temperature = effectiveSettings.temperature,
                locale = effectiveSettings.locale.toLanguageTag(),
                timeZone = effectiveSettings.timeZone.id,
                systemPrompt = effectiveSettings.systemPrompt,
                streamingMessages = effectiveSettings.streamingMessages,
                showToolEvents = effectiveSettings.showToolEvents,
            ),
        )

        return PreparedChatTurn(
            normalizedClientMessageId = normalizedClientMessageId,
            effectiveSettings = effectiveSettings,
            execution = execution,
            conversationKey = conversationKey(userId, chatId),
            runtimeRequest = BackendConversationTurnRequest(
                prompt = content,
                model = effectiveSettings.defaultModel.alias,
                contextSize = effectiveSettings.contextSize,
                locale = effectiveSettings.locale.toLanguageTag(),
                timeZone = effectiveSettings.timeZone.id,
                executionId = execution.id.toString(),
                temperature = effectiveSettings.temperature,
                systemPrompt = effectiveSettings.systemPrompt,
                streamingMessages = effectiveSettings.streamingMessages,
            ),
            userMessageMetadata = userMessageMetadata(normalizedClientMessageId),
            shouldReturnRunning = effectiveSettings.streamingMessages && featureFlags.wsEvents,
        )
    }

    fun prepareContinuationTurn(
        execution: AgentExecution,
        option: Option,
    ): PreparedContinuationTurn {
        val runtimeRequest = createContinuationTurnRequest(execution, option)
        return PreparedContinuationTurn(
            conversationKey = conversationKey(execution.userId, execution.chatId),
            runtimeRequest = runtimeRequest,
            streamingMessagesEnabled = runtimeRequest.streamingMessages == true,
            toolEventsEnabled = executionMetadataBoolean(execution, METADATA_SHOW_TOOL_EVENTS) ?: false,
        )
    }

    fun createContinuationTurnRequest(
        execution: AgentExecution,
        option: Option,
    ): BackendConversationTurnRequest =
        BackendConversationTurnRequest(
            prompt = option.toContinuationInput(),
            model = execution.model?.alias
                ?: throw internalError("Execution model is missing."),
            contextSize = executionMetadataInt(execution, METADATA_CONTEXT_SIZE)
                ?: throw internalError("Execution contextSize is missing."),
            locale = execution.metadata[METADATA_LOCALE]
                ?: throw internalError("Execution locale is missing."),
            timeZone = execution.metadata[METADATA_TIME_ZONE]
                ?: throw internalError("Execution timeZone is missing."),
            executionId = execution.id.toString(),
            temperature = executionMetadataFloat(execution, METADATA_TEMPERATURE),
            systemPrompt = execution.metadata[METADATA_SYSTEM_PROMPT]?.takeIf { it.isNotEmpty() },
            streamingMessages = executionMetadataBoolean(execution, METADATA_STREAMING_MESSAGES),
        )

    fun createEventSink(
        userId: String,
        chatId: UUID,
        execution: AgentExecution,
        messageRepository: MessageRepository,
        optionRepository: OptionRepository,
        executionRepository: AgentExecutionRepository,
        eventService: AgentEventService,
        toolCallRepository: ToolCallRepository,
        streamingMessagesEnabled: Boolean,
        toolEventsEnabled: Boolean,
    ): BackendAgentRuntimeEventSink =
        BackendAgentRuntimeEventSink(
            userId = userId,
            chatId = chatId,
            executionId = execution.id,
            messageRepository = messageRepository,
            optionRepository = optionRepository,
            executionRepository = executionRepository,
            eventService = eventService,
            toolCallRepository = toolCallRepository,
            streamingMessagesEnabled = streamingMessagesEnabled,
            toolEventsEnabled = toolEventsEnabled,
            optionsEnabled = featureFlags.options,
            assistantMessageId = execution.assistantMessageId,
        )

    private fun conversationKey(userId: String, chatId: UUID): AgentConversationKey =
        AgentConversationKey(
            userId = userId,
            conversationId = chatId.toString(),
        )

    private fun userMessageMetadata(clientMessageId: String?): Map<String, String> =
        clientMessageId?.let { linkedMapOf("clientMessageId" to it) } ?: emptyMap()

    private fun executionMetadata(
        contextSize: Int,
        temperature: Float,
        locale: String,
        timeZone: String,
        systemPrompt: String?,
        streamingMessages: Boolean,
        showToolEvents: Boolean,
    ): Map<String, String> = buildMap {
        put(METADATA_CONTEXT_SIZE, contextSize.toString())
        put(METADATA_TEMPERATURE, temperature.toString())
        put(METADATA_LOCALE, locale)
        put(METADATA_TIME_ZONE, timeZone)
        put(METADATA_STREAMING_MESSAGES, streamingMessages.toString())
        put(METADATA_SHOW_TOOL_EVENTS, showToolEvents.toString())
        systemPrompt?.let { put(METADATA_SYSTEM_PROMPT, it) }
    }

    private fun executionMetadataInt(
        execution: AgentExecution,
        key: String,
    ): Int? = execution.metadata[key]?.toIntOrNull()

    private fun executionMetadataFloat(
        execution: AgentExecution,
        key: String,
    ): Float? = execution.metadata[key]?.toFloatOrNull()

    private fun executionMetadataBoolean(
        execution: AgentExecution,
        key: String,
    ): Boolean? = execution.metadata[key]?.toBooleanStrictOrNull()
}

private fun Option.toContinuationInput(): String {
    val answer = answer ?: error("Option answer is required for continuation.")
    val optionById = options.associateBy { it.id }
    val selectedOptions = answer.selectedOptionIds.mapNotNull(optionById::get).map { option ->
        linkedMapOf(
            "id" to option.id,
            "label" to option.label,
            "content" to option.content,
        )
    }
    val payload = linkedMapOf<String, Any?>(
        "type" to "option_answer",
        "optionId" to id.toString(),
        "kind" to kind.value,
        "selectionMode" to selectionMode,
        "selectedOptionIds" to answer.selectedOptionIds.toList(),
        "selectedOptions" to selectedOptions,
        "freeText" to answer.freeText,
        "metadata" to answer.metadata,
    )
    return "$OPTION_CONTINUATION_PREFIX ${restJsonMapper.writeValueAsString(payload)}"
}

private fun internalError(message: String): BackendV1Exception =
    BackendV1Exception(
        status = io.ktor.http.HttpStatusCode.InternalServerError,
        code = "internal_error",
        message = message,
    )

private const val METADATA_CONTEXT_SIZE = "contextSize"
private const val METADATA_TEMPERATURE = "temperature"
private const val METADATA_LOCALE = "locale"
private const val METADATA_TIME_ZONE = "timeZone"
private const val METADATA_SYSTEM_PROMPT = "systemPrompt"
private const val METADATA_STREAMING_MESSAGES = "streamingMessages"
private const val METADATA_SHOW_TOOL_EVENTS = "showToolEvents"
private const val OPTION_CONTINUATION_PREFIX = "__option_answer__"
