package ru.souz.backend.execution.service

import java.time.Instant
import java.util.UUID
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendAgentTurn
import ru.souz.backend.agent.model.BackendAgentTurnInput
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionRuntimeConfig
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
    val effectiveSettings: EffectiveUserSettings,
    val execution: AgentExecution,
    val conversationKey: AgentConversationKey,
    val turn: BackendAgentTurn,
    val userMessageMetadata: Map<String, String>,
    val shouldReturnRunning: Boolean,
)

internal data class PreparedContinuationTurn(
    val conversationKey: AgentConversationKey,
    val turn: BackendAgentTurn,
    val streamingMessagesEnabled: Boolean,
    val toolEventsEnabled: Boolean,
)

internal class AgentExecutionRequestFactory(
    private val effectiveSettingsResolver: EffectiveSettingsResolver,
    private val featureFlags: BackendFeatureFlags,
) {
    suspend fun prepareChatTurn(userId: String, chatId: UUID, content: String, clientMessageId: String? = null, requestOverrides: UserSettingsOverrides = UserSettingsOverrides()): PreparedChatTurn {
        val effectiveSettings = effectiveSettingsResolver.resolve(userId, requestOverrides)
        val normalizedClientMessageId = clientMessageId?.trim()?.takeIf { it.isNotEmpty() }
        val config = AgentExecutionRuntimeConfig(
            modelAlias = effectiveSettings.defaultModel.alias,
            contextSize = effectiveSettings.contextSize,
            temperature = effectiveSettings.temperature,
            locale = effectiveSettings.locale.toLanguageTag(),
            timeZone = effectiveSettings.timeZone.id,
            systemPrompt = effectiveSettings.systemPrompt,
            streamingMessages = effectiveSettings.streamingMessages,
            showToolEvents = effectiveSettings.showToolEvents,
        )
        val execution = AgentExecution(
            id = UUID.randomUUID(), userId = userId, chatId = chatId, userMessageId = null, assistantMessageId = null,
            status = AgentExecutionStatus.QUEUED, requestId = null, clientMessageId = normalizedClientMessageId,
            model = effectiveSettings.defaultModel, provider = effectiveSettings.defaultModel.provider,
            startedAt = Instant.now(), finishedAt = null, cancelRequested = false, errorCode = null, errorMessage = null,
            usage = null, metadata = executionMetadata(config),
        )
        return PreparedChatTurn(
            effectiveSettings = effectiveSettings,
            execution = execution,
            conversationKey = conversationKey(userId, chatId),
            turn = BackendAgentTurn(userId, chatId, execution.id, chatId.toString(), BackendAgentTurnInput.UserMessage(content), config),
            userMessageMetadata = userMessageMetadata(normalizedClientMessageId),
            shouldReturnRunning = effectiveSettings.streamingMessages && featureFlags.wsEvents,
        )
    }

    fun prepareContinuationTurn(execution: AgentExecution, option: Option): PreparedContinuationTurn {
        val config = executionRuntimeConfig(execution)
        return PreparedContinuationTurn(
            conversationKey = conversationKey(execution.userId, execution.chatId),
            turn = BackendAgentTurn(execution.userId, execution.chatId, execution.id, execution.chatId.toString(), BackendAgentTurnInput.OptionAnswer(option.id, option.continuationPayloadJson()), config),
            streamingMessagesEnabled = config.streamingMessages,
            toolEventsEnabled = config.showToolEvents,
        )
    }

    fun createEventSink(userId: String, chatId: UUID, execution: AgentExecution, messageRepository: MessageRepository, optionRepository: OptionRepository, executionRepository: AgentExecutionRepository, eventService: AgentEventService, toolCallRepository: ToolCallRepository, streamingMessagesEnabled: Boolean, toolEventsEnabled: Boolean): BackendAgentRuntimeEventSink =
        BackendAgentRuntimeEventSink(userId, chatId, execution.id, messageRepository, optionRepository, executionRepository, eventService, toolCallRepository, streamingMessagesEnabled, toolEventsEnabled, featureFlags.options, execution.assistantMessageId)

    private fun conversationKey(userId: String, chatId: UUID) = AgentConversationKey(userId, chatId.toString())
    private fun userMessageMetadata(clientMessageId: String?) = clientMessageId?.let { linkedMapOf("clientMessageId" to it) } ?: emptyMap()

    private fun executionMetadata(config: AgentExecutionRuntimeConfig): Map<String, String> = buildMap {
        put(METADATA_RUNTIME_CONFIG, restJsonMapper.writeValueAsString(config))
        put("contextSize", config.contextSize.toString())
        config.temperature?.let { put("temperature", it.toString()) }
        put("locale", config.locale)
        put("timeZone", config.timeZone)
        put("streamingMessages", config.streamingMessages.toString())
        put("showToolEvents", config.showToolEvents.toString())
        config.systemPrompt?.let { put("systemPrompt", it) }
    }

    private fun executionRuntimeConfig(execution: AgentExecution): AgentExecutionRuntimeConfig {
        execution.metadata[METADATA_RUNTIME_CONFIG]?.let { return restJsonMapper.readValue(it, AgentExecutionRuntimeConfig::class.java) }
        return AgentExecutionRuntimeConfig(
            modelAlias = execution.model?.alias ?: throw internalError("Execution model is missing."),
            contextSize = execution.metadata["contextSize"]?.toIntOrNull() ?: throw internalError("Execution contextSize is missing."),
            temperature = execution.metadata["temperature"]?.toFloatOrNull(),
            locale = execution.metadata["locale"] ?: throw internalError("Execution locale is missing."),
            timeZone = execution.metadata["timeZone"] ?: throw internalError("Execution timeZone is missing."),
            systemPrompt = execution.metadata["systemPrompt"]?.takeIf { it.isNotEmpty() },
            streamingMessages = execution.metadata["streamingMessages"]?.toBooleanStrictOrNull() ?: false,
            showToolEvents = execution.metadata["showToolEvents"]?.toBooleanStrictOrNull() ?: false,
        )
    }
}

private fun Option.continuationPayloadJson(): String = toContinuationInput().removePrefix("__option_answer__ ")
private fun Option.toContinuationInput(): String {
    val answer = answer ?: error("Option answer is required for continuation.")
    val optionById = options.associateBy { it.id }
    val selectedOptions = answer.selectedOptionIds.mapNotNull(optionById::get).map { linkedMapOf("id" to it.id, "label" to it.label, "content" to it.content) }
    val payload = linkedMapOf<String, Any?>("type" to "option_answer", "optionId" to id.toString(), "kind" to kind.value, "selectionMode" to selectionMode, "selectedOptionIds" to answer.selectedOptionIds.toList(), "selectedOptions" to selectedOptions, "freeText" to answer.freeText, "metadata" to answer.metadata)
    return "__option_answer__ ${restJsonMapper.writeValueAsString(payload)}"
}
private fun internalError(message: String) = BackendV1Exception(io.ktor.http.HttpStatusCode.InternalServerError, "internal_error", message)
private const val METADATA_RUNTIME_CONFIG = "runtimeConfig"
