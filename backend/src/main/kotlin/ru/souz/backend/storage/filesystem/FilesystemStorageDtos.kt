package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import ru.souz.agent.AgentId
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.model.OptionAnswer
import ru.souz.backend.options.model.OptionKind
import ru.souz.backend.options.model.OptionItem
import ru.souz.backend.options.model.OptionStatus
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventPayloadStorageCodec
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.AgentExecutionUsage
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.backend.settings.model.ToolPermission
import ru.souz.backend.settings.model.ToolPermissionMode
import ru.souz.backend.settings.model.UserMcpServer
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.user.model.UserRecord
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider

internal data class StoredUserRecord(
    val id: String,
    val createdAt: String,
    val lastSeenAt: String?,
)

internal data class StoredChat(
    val id: String,
    val userId: String,
    val title: String?,
    val archived: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

internal data class StoredChatMessage(
    val id: String,
    val userId: String,
    val chatId: String,
    val seq: Long,
    val role: String,
    val content: String,
    val metadata: Map<String, String>,
    val createdAt: String,
)

internal data class StoredAgentConversationState(
    val userId: String,
    val chatId: String,
    val schemaVersion: Int,
    val activeAgentId: String,
    val history: List<StoredLlmMessage>,
    val temperature: Float,
    val locale: String,
    val timeZone: String,
    val basedOnMessageSeq: Long,
    val updatedAt: String,
    val rowVersion: Long,
)

internal data class StoredLlmMessage(
    val role: String,
    val content: String,
    val functionsStateId: String? = null,
    val attachments: List<String>? = null,
    val name: String? = null,
)

internal data class StoredAgentExecution(
    val id: String,
    val userId: String,
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
    val usage: StoredAgentExecutionUsage?,
    val metadata: Map<String, String>,
)

internal data class StoredAgentExecutionUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val precachedTokens: Int,
)

internal data class StoredOption(
    val id: String,
    val userId: String,
    val chatId: String,
    val executionId: String,
    val kind: String,
    val title: String?,
    val selectionMode: String,
    val options: List<StoredOptionItem>,
    val payload: Map<String, String>,
    val status: String,
    val answer: StoredOptionAnswer?,
    val createdAt: String,
    val expiresAt: String?,
    val answeredAt: String?,
)

internal data class StoredOptionItem(
    val id: String,
    val label: String,
    val content: String? = null,
)

internal data class StoredOptionAnswer(
    val selectedOptionIds: Set<String>,
    val freeText: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

internal data class StoredAgentEvent(
    val id: String,
    val userId: String,
    val chatId: String,
    val executionId: String?,
    val seq: Long,
    val type: String,
    val payload: JsonNode,
    val createdAt: String,
)

internal data class StoredToolCall(
    val userId: String,
    val chatId: String,
    val executionId: String,
    val toolCallId: String,
    val name: String,
    val status: String,
    val argumentsJson: String,
    val resultPreview: String?,
    val error: String?,
    val startedAt: String,
    val finishedAt: String?,
    val durationMs: Long?,
)

internal data class StoredUserSettings(
    val userId: String,
    val defaultModel: String? = null,
    val contextSize: Int? = null,
    val temperature: Float? = null,
    val locale: String? = null,
    val timeZone: String? = null,
    val systemPrompt: String? = null,
    val enabledTools: Set<String>? = null,
    val showToolEvents: Boolean? = null,
    val streamingMessages: Boolean? = null,
    val toolPermissions: Map<String, StoredToolPermission> = emptyMap(),
    val mcp: Map<String, StoredUserMcpServer> = emptyMap(),
    val schemaVersion: Int = UserSettings.CURRENT_SCHEMA_VERSION,
    val onboardingCompletedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

internal data class StoredUserProviderKey(
    val userId: String,
    val provider: String,
    val encryptedApiKey: String,
    val keyHint: String,
    val createdAt: String,
    val updatedAt: String,
)

internal data class StoredToolPermission(
    val mode: String,
)

internal data class StoredUserMcpServer(
    val enabled: Boolean,
    val command: String? = null,
    val url: String? = null,
)

internal fun Chat.toStored(): StoredChat =
    StoredChat(
        id = id.toString(),
        userId = userId,
        title = title,
        archived = archived,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

internal fun StoredChat.toDomain(): Chat =
    Chat(
        id = UUID.fromString(id),
        userId = userId,
        title = title,
        archived = archived,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
    )

internal fun ChatMessage.toStored(): StoredChatMessage =
    StoredChatMessage(
        id = id.toString(),
        userId = userId,
        chatId = chatId.toString(),
        seq = seq,
        role = role.value,
        content = content,
        metadata = metadata,
        createdAt = createdAt.toString(),
    )

internal fun StoredChatMessage.toDomain(): ChatMessage =
    ChatMessage(
        id = UUID.fromString(id),
        userId = userId,
        chatId = UUID.fromString(chatId),
        seq = seq,
        role = parseChatRole(role),
        content = content,
        metadata = metadata,
        createdAt = Instant.parse(createdAt),
    )

internal fun AgentConversationState.toStored(): StoredAgentConversationState =
    StoredAgentConversationState(
        userId = userId,
        chatId = chatId.toString(),
        schemaVersion = schemaVersion,
        activeAgentId = activeAgentId.storageValue,
        history = history.map { it.toStored() },
        temperature = temperature,
        locale = locale.toLanguageTag(),
        timeZone = timeZone.id,
        basedOnMessageSeq = basedOnMessageSeq,
        updatedAt = updatedAt.toString(),
        rowVersion = rowVersion,
    )

internal fun StoredAgentConversationState.toDomain(): AgentConversationState =
    AgentConversationState(
        userId = userId,
        chatId = UUID.fromString(chatId),
        schemaVersion = schemaVersion,
        activeAgentId = AgentId.fromStorageValue(activeAgentId),
        history = history.map { it.toDomain() },
        temperature = temperature,
        locale = locale.toLocaleOrDefault(),
        timeZone = timeZone.toZoneIdOrDefault(),
        basedOnMessageSeq = basedOnMessageSeq,
        updatedAt = Instant.parse(updatedAt),
        rowVersion = rowVersion,
    )

internal fun LLMRequest.Message.toStored(): StoredLlmMessage =
    StoredLlmMessage(
        role = role.name,
        content = content,
        functionsStateId = functionsStateId,
        attachments = attachments,
        name = name,
    )

internal fun StoredLlmMessage.toDomain(): LLMRequest.Message =
    LLMRequest.Message(
        role = runCatching { LLMMessageRole.valueOf(role) }.getOrDefault(LLMMessageRole.user),
        content = content,
        functionsStateId = functionsStateId,
        attachments = attachments,
        name = name,
    )

internal fun AgentExecution.toStored(): StoredAgentExecution =
    StoredAgentExecution(
        id = id.toString(),
        userId = userId,
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
        usage = usage?.toStored(),
        metadata = metadata,
        )

internal fun StoredAgentExecution.toDomain(): AgentExecution =
    AgentExecution(
        id = UUID.fromString(id),
        userId = userId,
        chatId = UUID.fromString(chatId),
        userMessageId = userMessageId?.let(UUID::fromString),
        assistantMessageId = assistantMessageId?.let(UUID::fromString),
        status = parseExecutionStatus(status),
        requestId = requestId,
        clientMessageId = clientMessageId,
        model = model.toModelOrNull(),
        provider = provider?.let { runCatching { LlmProvider.valueOf(it) }.getOrNull() },
        startedAt = Instant.parse(startedAt),
        finishedAt = finishedAt?.let(Instant::parse),
        cancelRequested = cancelRequested,
        errorCode = errorCode,
        errorMessage = errorMessage,
        usage = usage?.toDomain(),
        metadata = metadata,
    )

internal fun AgentExecutionUsage.toStored(): StoredAgentExecutionUsage =
    StoredAgentExecutionUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        precachedTokens = precachedTokens,
    )

internal fun StoredAgentExecutionUsage.toDomain(): AgentExecutionUsage =
    AgentExecutionUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        precachedTokens = precachedTokens,
    )

internal fun Option.toStored(): StoredOption =
    StoredOption(
        id = id.toString(),
        userId = userId,
        chatId = chatId.toString(),
        executionId = executionId.toString(),
        kind = kind.value,
        title = title,
        selectionMode = selectionMode,
        options = options.map { it.toStored() },
        payload = payload,
        status = status.value,
        answer = answer?.toStored(),
        createdAt = createdAt.toString(),
        expiresAt = expiresAt?.toString(),
        answeredAt = answeredAt?.toString(),
    )

internal fun StoredOption.toDomain(): Option =
    Option(
        id = UUID.fromString(id),
        userId = userId,
        chatId = UUID.fromString(chatId),
        executionId = UUID.fromString(executionId),
        kind = parseOptionKind(kind),
        title = title,
        selectionMode = selectionMode,
        options = options.map { it.toDomain() },
        payload = payload,
        status = parseOptionStatus(status),
        answer = answer?.toDomain(),
        createdAt = Instant.parse(createdAt),
        expiresAt = expiresAt?.let(Instant::parse),
        answeredAt = answeredAt?.let(Instant::parse),
    )

internal fun OptionItem.toStored(): StoredOptionItem =
    StoredOptionItem(
        id = id,
        label = label,
        content = content,
    )

internal fun StoredOptionItem.toDomain(): OptionItem =
    OptionItem(
        id = id,
        label = label,
        content = content,
    )

internal fun OptionAnswer.toStored(): StoredOptionAnswer =
    StoredOptionAnswer(
        selectedOptionIds = selectedOptionIds,
        freeText = freeText,
        metadata = metadata,
    )

internal fun StoredOptionAnswer.toDomain(): OptionAnswer =
    OptionAnswer(
        selectedOptionIds = selectedOptionIds,
        freeText = freeText,
        metadata = metadata,
    )

internal fun AgentEvent.toStored(): StoredAgentEvent =
    StoredAgentEvent(
        id = id.toString(),
        userId = userId,
        chatId = chatId.toString(),
        executionId = executionId?.toString(),
        seq = seq,
        type = type.value,
        payload = AgentEventPayloadStorageCodec.toStorageJson(payload),
        createdAt = createdAt.toString(),
    )

internal fun StoredAgentEvent.toDomain(): AgentEvent =
    parseEventType(type).let { eventType ->
        AgentEvent(
            id = UUID.fromString(id),
            userId = userId,
            chatId = UUID.fromString(chatId),
            executionId = executionId?.let(UUID::fromString),
            seq = seq,
            type = eventType,
            payload = AgentEventPayloadStorageCodec.fromStorageJson(eventType, payload),
            createdAt = Instant.parse(createdAt),
        )
    }

internal fun ToolCall.toStored(): StoredToolCall =
    StoredToolCall(
        userId = userId,
        chatId = chatId,
        executionId = executionId,
        toolCallId = toolCallId,
        name = name,
        status = status.value,
        argumentsJson = argumentsJson,
        resultPreview = resultPreview,
        error = error,
        startedAt = startedAt.toString(),
        finishedAt = finishedAt?.toString(),
        durationMs = durationMs,
    )

internal fun StoredToolCall.toDomain(): ToolCall =
    ToolCall(
        userId = userId,
        chatId = chatId,
        executionId = executionId,
        toolCallId = toolCallId,
        name = name,
        status = parseToolCallStatus(status),
        argumentsJson = argumentsJson,
        resultPreview = resultPreview,
        error = error,
        startedAt = Instant.parse(startedAt),
        finishedAt = finishedAt?.let(Instant::parse),
        durationMs = durationMs,
    )

internal fun UserRecord.toStored(): StoredUserRecord =
    StoredUserRecord(
        id = id,
        createdAt = createdAt.toString(),
        lastSeenAt = lastSeenAt?.toString(),
    )

internal fun StoredUserRecord.toDomain(): UserRecord =
    UserRecord(
        id = id,
        createdAt = Instant.parse(createdAt),
        lastSeenAt = lastSeenAt?.let(Instant::parse),
    )

internal fun UserSettings.toStored(): StoredUserSettings =
    StoredUserSettings(
        userId = userId,
        defaultModel = defaultModel?.alias,
        contextSize = contextSize,
        temperature = temperature,
        locale = locale?.toLanguageTag(),
        timeZone = timeZone?.id,
        systemPrompt = systemPrompt,
        enabledTools = enabledTools,
        showToolEvents = showToolEvents,
        streamingMessages = streamingMessages,
        toolPermissions = toolPermissions.mapValues { (_, value) -> value.toStored() },
        mcp = mcp.mapValues { (_, value) -> value.toStored() },
        schemaVersion = schemaVersion,
        onboardingCompletedAt = onboardingCompletedAt?.toString(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

internal fun StoredUserSettings.toDomain(): UserSettings =
    UserSettings(
        userId = userId,
        defaultModel = defaultModel.toModelOrNull(),
        contextSize = contextSize,
        temperature = temperature,
        locale = locale?.toLocaleOrNull(),
        timeZone = timeZone?.toZoneIdOrNull(),
        systemPrompt = systemPrompt,
        enabledTools = enabledTools,
        showToolEvents = showToolEvents,
        streamingMessages = streamingMessages,
        toolPermissions = toolPermissions.mapValues { (_, value) -> value.toDomain() },
        mcp = mcp.mapValues { (_, value) -> value.toDomain() },
        schemaVersion = schemaVersion,
        onboardingCompletedAt = onboardingCompletedAt?.let(Instant::parse),
        createdAt = createdAt?.let(Instant::parse) ?: Instant.EPOCH,
        updatedAt = updatedAt?.let(Instant::parse) ?: Instant.EPOCH,
    )

internal fun ToolPermission.toStored(): StoredToolPermission =
    StoredToolPermission(mode = mode.name)

internal fun StoredToolPermission.toDomain(): ToolPermission =
    ToolPermission(
        mode = runCatching { ToolPermissionMode.valueOf(mode) }.getOrDefault(ToolPermissionMode.ASK)
    )

internal fun UserMcpServer.toStored(): StoredUserMcpServer =
    StoredUserMcpServer(
        enabled = enabled,
        command = command,
        url = url,
    )

internal fun StoredUserMcpServer.toDomain(): UserMcpServer =
    UserMcpServer(
        enabled = enabled,
        command = command,
        url = url,
    )

internal fun UserProviderKey.toStored(): StoredUserProviderKey =
    StoredUserProviderKey(
        userId = userId,
        provider = provider.name,
        encryptedApiKey = encryptedApiKey,
        keyHint = keyHint,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

internal fun StoredUserProviderKey.toDomainOrNull(): UserProviderKey? =
    provider.toProviderOrNull()?.let { normalizedProvider ->
        UserProviderKey(
            userId = userId,
            provider = normalizedProvider,
            encryptedApiKey = encryptedApiKey,
            keyHint = keyHint,
            createdAt = Instant.parse(createdAt),
            updatedAt = Instant.parse(updatedAt),
        )
    }

private fun parseChatRole(raw: String): ChatRole =
    ChatRole.entries.firstOrNull { it.value == raw || it.name.equals(raw, ignoreCase = true) }
        ?: error("Unsupported chat role '$raw'.")

private fun parseOptionKind(raw: String): OptionKind =
    OptionKind.entries.firstOrNull { it.value == raw || it.name.equals(raw, ignoreCase = true) }
        ?: error("Unsupported option kind '$raw'.")

private fun parseOptionStatus(raw: String): OptionStatus =
    OptionStatus.entries.firstOrNull { it.value == raw || it.name.equals(raw, ignoreCase = true) }
        ?: error("Unsupported option status '$raw'.")

private fun parseExecutionStatus(raw: String): AgentExecutionStatus =
    when {
        raw == "waiting_choice" -> AgentExecutionStatus.WAITING_OPTION
        else -> AgentExecutionStatus.entries.firstOrNull { it.value == raw || it.name.equals(raw, ignoreCase = true) }
            ?: error("Unsupported execution status '$raw'.")
    }

private fun parseEventType(raw: String): AgentEventType =
    when {
        raw == "choice.requested" -> AgentEventType.OPTION_REQUESTED
        raw == "choice.answered" -> AgentEventType.OPTION_ANSWERED
        else -> AgentEventType.entries.firstOrNull { it.value == raw || it.name.equals(raw, ignoreCase = true) }
            ?: error("Unsupported event type '$raw'.")
    }

private fun parseToolCallStatus(raw: String): ToolCallStatus =
    ToolCallStatus.entries.firstOrNull { it.value == raw || it.name.equals(raw, ignoreCase = true) }
        ?: error("Unsupported tool call status '$raw'.")

private fun String?.toModelOrNull(): LLMModel? =
    this?.let { raw ->
        LLMModel.entries.firstOrNull { it.alias == raw || it.name.equals(raw, ignoreCase = true) }
    }

private fun String?.toProviderOrNull(): ru.souz.llms.LlmProvider? =
    this?.let { raw ->
        ru.souz.llms.LlmProvider.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }

private fun String.toLocaleOrDefault(): Locale =
    toLocaleOrNull() ?: Locale.forLanguageTag("ru-RU")

private fun String?.toLocaleOrNull(): Locale? =
    this?.let { raw ->
        Locale.forLanguageTag(raw)
            .takeIf { it.language.isNotBlank() }
    }

private fun String.toZoneIdOrDefault(): ZoneId =
    toZoneIdOrNull() ?: ZoneId.systemDefault()

private fun String?.toZoneIdOrNull(): ZoneId? =
    this?.let { raw ->
        runCatching { ZoneId.of(raw) }.getOrNull()
    }
