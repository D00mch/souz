package ru.souz.backend.agent.runtime

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import ru.souz.agent.runtime.AgentExecutionPause
import ru.souz.agent.runtime.AgentToolInvocationAttributes
import ru.souz.backend.permission.model.PermissionRequest
import ru.souz.backend.permission.model.PermissionRequestStatus
import ru.souz.backend.permission.repository.PermissionWorkflowRepository
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolPermissionRequester
import ru.souz.tool.ToolPermissionResult

data class BackendPermissionDraft(
    val userId: String,
    val chatId: UUID,
    val executionId: UUID,
    val invocationId: UUID,
    val toolCallId: String?,
    val toolName: String,
    val description: String,
    val displayParams: Map<String, String>,
    val promptHash: String,
) : AgentExecutionPause("Tool execution is waiting for client permission.")

/**
 * Backend requester used only by explicitly opted-in tools.
 *
 * A first invocation unwinds with a durable pause draft. A resumed invocation must carry the
 * exact persisted permission ID and is authorized only when every correlation field still
 * matches, preventing a stale grant from being applied to changed code or parameters.
 */
class BackendDurableToolPermissionRequester(
    private val workflowRepository: PermissionWorkflowRepository,
) : ToolPermissionRequester {
    override suspend fun requestPermission(
        description: String,
        displayParams: Map<String, String>,
        meta: ToolInvocationMeta,
    ): ToolPermissionResult {
        val normalizedDescription = normalizeDescription(description)
        val normalizedParams = normalizeDisplayParams(displayParams)
        val userId = meta.userId
        val chatId = meta.conversationId.toRequiredUuid("conversationId")
        val executionId = (
            meta.attributes[AgentToolInvocationAttributes.EXECUTION_ID] ?: meta.requestId
        ).toRequiredUuid("executionId")
        val invocationId = meta.attributes[AgentToolInvocationAttributes.INVOCATION_ID]
            .toRequiredUuid("toolInvocationId")
        val toolName = meta.attributes[AgentToolInvocationAttributes.TOOL_NAME]
            ?.takeIf(String::isNotBlank)
            ?: error("Permission-aware invocation is missing its tool name.")
        val toolCallId = meta.attributes[AgentToolInvocationAttributes.PROVIDER_TOOL_CALL_ID]
        val promptHash = permissionPromptHash(normalizedDescription, normalizedParams)
        val resumePermissionId = meta.attributes[AgentToolInvocationAttributes.RESUME_PERMISSION_ID]
            ?.toRequiredUuid("resumePermissionId")

        if (resumePermissionId == null) {
            throw BackendPermissionDraft(
                userId = userId,
                chatId = chatId,
                executionId = executionId,
                invocationId = invocationId,
                toolCallId = toolCallId,
                toolName = toolName,
                description = normalizedDescription,
                displayParams = normalizedParams,
                promptHash = promptHash,
            )
        }

        val request = workflowRepository.getOwned(userId, resumePermissionId)
            ?: error("Permission continuation does not exist or is not owned by this user.")
        requirePermissionMatches(
            request = request,
            chatId = chatId,
            executionId = executionId,
            invocationId = invocationId,
            toolName = toolName,
            toolCallId = toolCallId,
            description = normalizedDescription,
            displayParams = normalizedParams,
        )
        return when (request.status) {
            PermissionRequestStatus.GRANTED -> ToolPermissionResult.Ok
            PermissionRequestStatus.DENIED -> ToolPermissionResult.No(USER_DISAPPROVED_MESSAGE)
            PermissionRequestStatus.PENDING -> error("Permission continuation resumed before a decision.")
            PermissionRequestStatus.CANCELLED -> error("Permission continuation was cancelled.")
        }
    }
}

private fun requirePermissionMatches(
    request: PermissionRequest,
    chatId: UUID,
    executionId: UUID,
    invocationId: UUID,
    toolName: String,
    toolCallId: String?,
    description: String,
    displayParams: Map<String, String>,
) {
    require(
        request.chatId == chatId &&
            request.executionId == executionId &&
            request.invocationId == invocationId &&
            request.toolName == toolName &&
            request.toolCallId == toolCallId &&
            request.description == description &&
            request.displayParams == displayParams
    ) { "Permission continuation no longer matches the stored invocation and prompt." }
}

internal fun normalizeDescription(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty()) { "Permission description must not be blank." }
    require(normalized.length <= PermissionRequest.MAX_DESCRIPTION_LENGTH) {
        "Permission description is too long."
    }
    return normalized
}

internal fun normalizeDisplayParams(values: Map<String, String>): Map<String, String> {
    require(values.size <= PermissionRequest.MAX_DISPLAY_PARAMS) { "Too many permission display parameters." }
    return linkedMapOf<String, String>().apply {
        values.toSortedMap().forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            require(key.isNotEmpty()) { "Permission display parameter keys must not be blank." }
            require(key.length <= PermissionRequest.MAX_DISPLAY_PARAM_KEY_LENGTH) {
                "Permission display parameter key is too long."
            }
            require(value.length <= PermissionRequest.MAX_DISPLAY_PARAM_VALUE_LENGTH) {
                "Permission display parameter value is too long."
            }
            put(key, value)
        }
    }
}

internal fun permissionPromptHash(description: String, displayParams: Map<String, String>): String {
    val canonical = buildString {
        append(description)
        append('\n')
        displayParams.toSortedMap().forEach { (key, value) ->
            append(key.length).append(':').append(key)
            append(value.length).append(':').append(value).append('\n')
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun String?.toRequiredUuid(label: String): UUID =
    this?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        ?: error("Permission-aware invocation is missing a valid $label.")

private const val USER_DISAPPROVED_MESSAGE = "User disapproved"
