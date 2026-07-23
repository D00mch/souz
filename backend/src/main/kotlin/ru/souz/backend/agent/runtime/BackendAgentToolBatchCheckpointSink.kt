package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import ru.souz.agent.AgentId
import ru.souz.agent.runtime.AgentToolBatch
import ru.souz.agent.runtime.AgentToolBatchCheckpoint
import ru.souz.agent.runtime.AgentToolBatchCheckpointPhase
import ru.souz.agent.runtime.AgentToolBatchCheckpointSink
import ru.souz.agent.state.AgentContext
import ru.souz.backend.permission.repository.PermissionWorkflowRepository
import ru.souz.backend.permission.repository.PlannedToolInvocation
import ru.souz.backend.permission.repository.SaveToolBatchCommand
import ru.souz.llms.LLMRequest

internal data class BackendAgentContextCheckpointV1(
    val schemaVersion: Int = SCHEMA_VERSION,
    val activeAgentId: String,
    val originalPrompt: String,
    val history: List<LLMRequest.Message>,
    val systemPrompt: String,
    val model: String,
    val temperature: Float,
    val contextSize: Int,
    val activeTools: List<LLMRequest.Function>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Maps agent semantic batch checkpoints onto the backend's invocation ledger. */
internal class BackendAgentToolBatchCheckpointSink(
    private val executionId: UUID,
    private val userId: String,
    private val chatId: UUID,
    private val activeAgentId: AgentId,
    private val originalPrompt: String,
    private val baseStateRowVersion: Long,
    private val workflowRepository: PermissionWorkflowRepository,
    private val objectMapper: ObjectMapper,
    initialRevision: Long = 0,
) : AgentToolBatchCheckpointSink {
    private var revision: Long = initialRevision
    private var batchId: UUID? = null

    override suspend fun save(checkpoint: AgentToolBatchCheckpoint, context: AgentContext<*>) {
        when (checkpoint.phase) {
            AgentToolBatchCheckpointPhase.PLANNED -> savePlanned(checkpoint, context)
            AgentToolBatchCheckpointPhase.RESUMING -> {
                batchId = checkpoint.batch.batchId
            }
            AgentToolBatchCheckpointPhase.RESULT_STORED -> storeResult(checkpoint, context)
        }
    }

    private suspend fun savePlanned(
        checkpoint: AgentToolBatchCheckpoint,
        context: AgentContext<*>,
    ) {
        if (batchId == checkpoint.batch.batchId) return
        revision += 1
        batchId = checkpoint.batch.batchId
        val contextSnapshot = contextSnapshot(context, checkpoint.history)
        workflowRepository.saveToolBatch(
            SaveToolBatchCommand(
                executionId = executionId,
                userId = userId,
                chatId = chatId,
                schemaVersion = BackendAgentContextCheckpointV1.SCHEMA_VERSION,
                revision = revision,
                contextJson = objectMapper.writeValueAsString(contextSnapshot),
                batchJson = objectMapper.writeValueAsString(checkpoint.batch),
                nextOrdinal = checkpoint.nextInvocationIndex,
                baseStateRowVersion = baseStateRowVersion,
                compatibilityKey = checkpointCompatibilityKey(checkpoint.batch, context, objectMapper),
                invocations = checkpoint.batch.invocations.map { invocation ->
                    val argumentsJson = objectMapper.writeValueAsString(invocation.functionCall.arguments)
                    val toolDefinition = context.activeTools.firstOrNull { it.name == invocation.functionCall.name }
                        ?: context.settings.tools.byName[invocation.functionCall.name]?.fn
                        ?: error("Checkpointed tool ${invocation.functionCall.name} is unavailable.")
                    PlannedToolInvocation(
                        invocationId = invocation.invocationId,
                        ordinal = invocation.batchIndex,
                        providerCallId = invocation.providerToolCallId,
                        toolName = invocation.functionCall.name,
                        argumentsJson = argumentsJson,
                        argumentsHash = sha256(argumentsJson),
                        toolDefinitionHash = sha256(objectMapper.writeValueAsString(toolDefinition)),
                    )
                },
            )
        )
        checkpoint.batch.invocations.getOrNull(checkpoint.nextInvocationIndex)?.let { invocation ->
            workflowRepository.beginInvocation(executionId, invocation.invocationId)
        }
    }

    private suspend fun storeResult(
        checkpoint: AgentToolBatchCheckpoint,
        context: AgentContext<*>,
    ) {
        require(batchId == null || batchId == checkpoint.batch.batchId) {
            "Tool result belongs to a different checkpoint batch."
        }
        batchId = checkpoint.batch.batchId
        val completedIndex = checkpoint.nextInvocationIndex - 1
        require(completedIndex >= 0) { "Result checkpoint has no completed invocation." }
        val invocation = checkpoint.batch.invocations[completedIndex]
        val result = checkpoint.history.lastOrNull()
            ?: error("Result checkpoint does not contain the exact function-result message.")
        workflowRepository.storeToolResult(
            executionId = executionId,
            invocationId = invocation.invocationId,
            resultMessageJson = objectMapper.writeValueAsString(result),
            contextJson = objectMapper.writeValueAsString(contextSnapshot(context, checkpoint.history)),
            nextOrdinal = checkpoint.nextInvocationIndex,
        )
        val nextInvocation = checkpoint.batch.invocations.getOrNull(checkpoint.nextInvocationIndex)
        if (nextInvocation != null) {
            workflowRepository.beginInvocation(executionId, nextInvocation.invocationId)
        } else {
            workflowRepository.markGraphResuming(
                executionId = executionId,
                contextJson = objectMapper.writeValueAsString(contextSnapshot(context, checkpoint.history)),
            )
        }
    }

    private fun contextSnapshot(
        context: AgentContext<*>,
        history: List<LLMRequest.Message>,
    ): BackendAgentContextCheckpointV1 = BackendAgentContextCheckpointV1(
        activeAgentId = activeAgentId.storageValue,
        originalPrompt = originalPrompt,
        history = history,
        systemPrompt = context.systemPrompt,
        model = context.settings.model,
        temperature = context.settings.temperature,
        contextSize = context.settings.contextSize,
        activeTools = context.activeTools,
    )

}

internal fun checkpointCompatibilityKey(
    batch: AgentToolBatch,
    context: AgentContext<*>,
    objectMapper: ObjectMapper,
): String {
    val definitions = batch.invocations.map { invocation ->
        context.settings.tools.byName[invocation.functionCall.name]?.fn
            ?: context.activeTools.firstOrNull { it.name == invocation.functionCall.name }
            ?: error("Checkpointed tool ${invocation.functionCall.name} is unavailable.")
    }
    return sha256(objectMapper.writeValueAsString(definitions))
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
