package ru.souz.backend.storage.postgres

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PSQLException
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
import ru.souz.backend.settings.model.UserMcpServer
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.user.model.UserRecord
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider

internal const val ACTIVE_EXECUTION_CONSTRAINT: String = "agent_executions_one_active_per_chat_idx"
internal const val PRIMARY_KEY_CONSTRAINT: String = "agent_conversation_state_pkey"
internal val postgresStorageMapper = jacksonObjectMapper().findAndRegisterModules()

internal suspend fun <T> DataSource.read(block: (Connection) -> T): T =
    withContext(Dispatchers.IO) {
        connection.use(block)
    }

internal suspend fun <T> DataSource.write(block: (Connection) -> T): T =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (t: Throwable) {
                runCatching { connection.rollback() }
                throw t
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

internal fun Connection.ensureStateChat(userId: String, chatId: java.util.UUID, updatedAt: Instant) {
    prepareStatement(
        """
        insert into chats(id, user_id, title, archived, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?)
        on conflict (id) do nothing
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, chatId)
        statement.setString(2, userId)
        statement.setString(3, null)
        statement.setBoolean(4, true)
        statement.setInstant(5, updatedAt)
        statement.setInstant(6, updatedAt)
        statement.executeUpdate()
    }
}

internal fun Connection.lockChat(userId: String, chatId: java.util.UUID) {
    prepareStatement(
        "select id from chats where user_id = ? and id = ? for update"
    ).use { statement ->
        statement.setString(1, userId)
        statement.setObject(2, chatId)
        statement.executeQuery().use { }
    }
}

internal fun PreparedStatement.setInstant(index: Int, value: Instant?) {
    if (value == null) {
        setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
    } else {
        setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
    }
}

internal fun PreparedStatement.setJson(index: Int, value: String?) {
    if (value == null) {
        setNull(index, Types.OTHER)
    } else {
        setObject(index, value, Types.OTHER)
    }
}

internal fun ResultSet.instant(column: String): Instant =
    getObject(column, OffsetDateTime::class.java).toInstant()

internal fun ResultSet.toUserRecord(): UserRecord =
    UserRecord(
        id = getString("id"),
        createdAt = instant("created_at"),
        lastSeenAt = getObject("last_seen_at", OffsetDateTime::class.java)?.toInstant(),
    )

internal fun SQLException.isConstraintViolation(constraintName: String): Boolean =
    sqlState == "23505" && ((this as? PSQLException)?.serverErrorMessage?.constraint == constraintName ||
        message.orEmpty().contains(constraintName))

internal data class StoredSettingsPayload(
    val defaultModel: String?,
    val contextSize: Int?,
    val temperature: Float?,
    val locale: String?,
    val timeZone: String?,
    val systemPrompt: String?,
    val enabledTools: Set<String>?,
    val showToolEvents: Boolean?,
    val streamingMessages: Boolean?,
    val toolPermissions: Map<String, ToolPermission>,
    val mcp: Map<String, UserMcpServer>,
)

internal data class StoredConversationContext(
    val schemaVersion: Int,
    val activeAgentId: String,
    val history: List<LLMRequest.Message>,
    val temperature: Float,
    val locale: String,
    val timeZone: String,
)

internal data class StoredOptionAnswer(
    val selectedOptionIds: Set<String>,
    val freeText: String?,
    val metadata: Map<String, String>,
)

internal data class StoredExecutionUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val precachedTokens: Int,
)

internal fun Chat.toRow(): List<Any?> =
    listOf(id, userId, title, archived, createdAt, updatedAt)

internal fun ResultSet.toChat(): Chat =
    Chat(
        id = getObject("id", java.util.UUID::class.java),
        userId = getString("user_id"),
        title = getString("title"),
        archived = getBoolean("archived"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

internal fun ResultSet.toMessage(): ChatMessage =
    ChatMessage(
        id = getObject("id", java.util.UUID::class.java),
        userId = getString("user_id"),
        chatId = getObject("chat_id", java.util.UUID::class.java),
        seq = getLong("seq"),
        role = parseChatRole(getString("role")),
        content = getString("content"),
        metadata = postgresStorageMapper.readValue<Map<String, String>>(getString("metadata")),
        createdAt = instant("created_at"),
    )

internal fun ResultSet.toState(): AgentConversationState {
    val context = postgresStorageMapper.readValue<StoredConversationContext>(getString("context_json"))
    return AgentConversationState(
        userId = getString("user_id"),
        chatId = getObject("chat_id", java.util.UUID::class.java),
        schemaVersion = context.schemaVersion,
        activeAgentId = AgentId.fromStorageValue(context.activeAgentId),
        history = context.history,
        temperature = context.temperature,
        locale = context.locale.toLocaleOrDefault(),
        timeZone = context.timeZone.toZoneIdOrDefault(),
        basedOnMessageSeq = getLong("based_on_message_seq"),
        updatedAt = instant("updated_at"),
        rowVersion = getLong("row_version"),
    )
}

internal fun ResultSet.toExecution(): AgentExecution =
    AgentExecution(
        id = getObject("id", java.util.UUID::class.java),
        userId = getString("user_id"),
        chatId = getObject("chat_id", java.util.UUID::class.java),
        userMessageId = getObject("user_message_id", java.util.UUID::class.java),
        assistantMessageId = getObject("assistant_message_id", java.util.UUID::class.java),
        status = parseExecutionStatus(getString("status")),
        requestId = getString("request_id"),
        clientMessageId = getString("client_message_id"),
        model = getString("model").toModelOrNull(),
        provider = getString("provider").toProviderOrNull(),
        startedAt = instant("started_at"),
        finishedAt = getObject("finished_at", OffsetDateTime::class.java)?.toInstant(),
        cancelRequested = getBoolean("cancel_requested"),
        errorCode = getString("error_code"),
        errorMessage = getString("error_message"),
        usage = getString("usage_json")?.let { raw ->
            postgresStorageMapper.readValue<StoredExecutionUsage>(raw).let { usage ->
                AgentExecutionUsage(
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    totalTokens = usage.totalTokens,
                    precachedTokens = usage.precachedTokens,
                )
            }
        },
        metadata = postgresStorageMapper.readValue<Map<String, String>>(getString("metadata")),
    )

internal fun ResultSet.toOption(): Option =
    Option(
        id = getObject("id", java.util.UUID::class.java),
        userId = getString("user_id"),
        chatId = getObject("chat_id", java.util.UUID::class.java),
        executionId = getObject("execution_id", java.util.UUID::class.java),
        kind = parseOptionKind(getString("kind")),
        title = getString("title"),
        selectionMode = getString("selection_mode"),
        options = postgresStorageMapper.readValue<List<OptionItem>>(getString("options_json")),
        payload = postgresStorageMapper.readValue<Map<String, String>>(getString("payload_json")),
        status = parseOptionStatus(getString("status")),
        answer = getString("answer_json")?.let { raw ->
            postgresStorageMapper.readValue<StoredOptionAnswer>(raw).let { answer ->
                OptionAnswer(
                    selectedOptionIds = answer.selectedOptionIds,
                    freeText = answer.freeText,
                    metadata = answer.metadata,
                )
            }
        },
        createdAt = instant("created_at"),
        expiresAt = getObject("expires_at", OffsetDateTime::class.java)?.toInstant(),
        answeredAt = getObject("answered_at", OffsetDateTime::class.java)?.toInstant(),
    )

internal fun ResultSet.toEvent(): AgentEvent =
    parseEventType(getString("type")).let { eventType ->
        AgentEvent(
            id = getObject("id", java.util.UUID::class.java),
            userId = getString("user_id"),
            chatId = getObject("chat_id", java.util.UUID::class.java),
            executionId = getObject("execution_id", java.util.UUID::class.java),
            seq = getLong("seq"),
            type = eventType,
            payload = AgentEventPayloadStorageCodec.fromStorageJson(
                type = eventType,
                payload = postgresStorageMapper.readTree(getString("payload")),
            ),
            createdAt = instant("created_at"),
        )
    }

internal fun ResultSet.toToolCall(): ToolCall =
    ToolCall(
        userId = getString("user_id"),
        chatId = getObject("chat_id", java.util.UUID::class.java).toString(),
        executionId = getObject("execution_id", java.util.UUID::class.java).toString(),
        toolCallId = getString("tool_call_id"),
        name = getString("name"),
        status = parseToolCallStatus(getString("status")),
        argumentsJson = getString("arguments_json"),
        resultPreview = getString("result_preview"),
        error = getString("error"),
        startedAt = instant("started_at"),
        finishedAt = getObject("finished_at", OffsetDateTime::class.java)?.toInstant(),
        durationMs = getObject("duration_ms", java.lang.Long::class.java)?.toLong(),
    )

internal fun ResultSet.toUserSettings(): UserSettings {
    val payload = postgresStorageMapper.readValue<StoredSettingsPayload>(getString("settings_json"))
    return UserSettings(
        userId = getString("user_id"),
        defaultModel = payload.defaultModel.toModelOrNull(),
        contextSize = payload.contextSize,
        temperature = payload.temperature,
        locale = payload.locale.toLocaleOrNull(),
        timeZone = payload.timeZone.toZoneIdOrNull(),
        systemPrompt = payload.systemPrompt,
        enabledTools = payload.enabledTools,
        showToolEvents = payload.showToolEvents,
        streamingMessages = payload.streamingMessages,
        toolPermissions = payload.toolPermissions,
        mcp = payload.mcp,
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )
}

internal fun ResultSet.toUserProviderKey(): UserProviderKey =
    UserProviderKey(
        userId = getString("user_id"),
        provider = enumValueOf(getString("provider")),
        encryptedApiKey = getBytes("encrypted_api_key").toString(Charsets.UTF_8),
        keyHint = getString("key_hint"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

internal fun UserSettings.toSettingsJson(): String =
    postgresStorageMapper.writeValueAsString(
        StoredSettingsPayload(
            defaultModel = defaultModel?.alias,
            contextSize = contextSize,
            temperature = temperature,
            locale = locale?.toLanguageTag(),
            timeZone = timeZone?.id,
            systemPrompt = systemPrompt,
            enabledTools = enabledTools,
            showToolEvents = showToolEvents,
            streamingMessages = streamingMessages,
            toolPermissions = toolPermissions,
            mcp = mcp,
        )
    )

internal fun AgentConversationState.toContextJson(): String =
    postgresStorageMapper.writeValueAsString(
        StoredConversationContext(
            schemaVersion = schemaVersion,
            activeAgentId = activeAgentId.storageValue,
            history = history,
            temperature = temperature,
            locale = locale.toLanguageTag(),
            timeZone = timeZone.id,
        )
    )

internal fun AgentExecutionUsage.toUsageJson(): String =
    postgresStorageMapper.writeValueAsString(
        StoredExecutionUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            precachedTokens = precachedTokens,
        )
    )

internal fun OptionAnswer.toStoredJson(): String =
    postgresStorageMapper.writeValueAsString(
        StoredOptionAnswer(
            selectedOptionIds = selectedOptionIds,
            freeText = freeText,
            metadata = metadata,
        )
    )

internal fun parseChatRole(raw: String): ChatRole =
    ChatRole.entries.first { it.value == raw || it.name.equals(raw, ignoreCase = true) }

internal fun parseExecutionStatus(raw: String): AgentExecutionStatus =
    when {
        raw == "waiting_choice" -> AgentExecutionStatus.WAITING_OPTION
        else -> AgentExecutionStatus.entries.first { it.value == raw || it.name.equals(raw, ignoreCase = true) }
    }

internal fun parseOptionKind(raw: String): OptionKind =
    OptionKind.entries.first { it.value == raw || it.name.equals(raw, ignoreCase = true) }

internal fun parseOptionStatus(raw: String): OptionStatus =
    OptionStatus.entries.first { it.value == raw || it.name.equals(raw, ignoreCase = true) }

internal fun parseEventType(raw: String): AgentEventType =
    when {
        raw == "choice.requested" -> AgentEventType.OPTION_REQUESTED
        raw == "choice.answered" -> AgentEventType.OPTION_ANSWERED
        else -> AgentEventType.entries.first { it.value == raw || it.name.equals(raw, ignoreCase = true) }
    }

internal fun parseToolCallStatus(raw: String): ToolCallStatus =
    ToolCallStatus.entries.first { it.value == raw || it.name.equals(raw, ignoreCase = true) }

internal fun String?.toModelOrNull(): LLMModel? =
    this?.let { raw ->
        LLMModel.entries.firstOrNull { it.alias == raw || it.name.equals(raw, ignoreCase = true) }
    }

internal fun String?.toProviderOrNull(): LlmProvider? =
    this?.let { raw ->
        LlmProvider.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }

internal fun String?.toLocaleOrNull(): Locale? =
    this?.let { raw ->
        Locale.forLanguageTag(raw).takeIf { it.language.isNotBlank() }
    }

internal fun String.toLocaleOrDefault(): Locale =
    toLocaleOrNull() ?: Locale.forLanguageTag("ru-RU")

internal fun String?.toZoneIdOrNull(): ZoneId? =
    this?.let { raw -> runCatching { ZoneId.of(raw) }.getOrNull() }

internal fun String.toZoneIdOrDefault(): ZoneId =
    toZoneIdOrNull() ?: ZoneId.systemDefault()
