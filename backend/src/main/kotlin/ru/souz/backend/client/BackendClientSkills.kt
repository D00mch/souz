package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.bundle.SkillManifest
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.skills.MAX_STORED_SKILL_FILE_BYTES
import ru.souz.backend.skills.copySkillBundle
import ru.souz.backend.skills.requireSafeSkillId
import ru.souz.backend.skills.requireSkillUserId
import ru.souz.backend.skills.validateAndCopySkillBundle
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory

internal class BackendClientSkills(
    private val registry: ClientThreadRuntimeRegistry,
    private val toolCallRepository: ToolCallRepository,
    private val eventService: AgentEventService,
    private val now: () -> Instant = Instant::now,
    classLoader: ClassLoader = BackendClientSkills::class.java.classLoader,
) : AgentToolCatalog, SkillBundleProvider {
    private val skillsById: Map<SkillId, BundledClientSkill> = loadClientSkills(classLoader)

    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
        skillsById.values
            .map { skill -> skill.definition }
            .groupBy { it.category }
            .mapValues { (_, definitions) ->
                Collections.unmodifiableMap(
                    definitions.associate { definition ->
                        definition.skillId.value to ClientWebSocketSkill(
                            definition = definition,
                            registry = registry,
                            toolCallRepository = toolCallRepository,
                            eventService = eventService,
                            now = now,
                        )
                    },
                )
            }
            .let { Collections.unmodifiableMap(it) }

    override suspend fun listSkills(userId: String): List<StoredSkill> {
        requireSkillUserId(userId)
        return skillsById.values.map { skill ->
            StoredSkill(
                userId = userId,
                skillId = skill.bundle.skillId,
                manifest = skill.bundle.manifest,
                bundleHash = skill.bundleHash,
                createdAt = Instant.EPOCH,
            )
        }
    }

    override suspend fun listSkillInventoryIds(userId: String): List<SkillId> {
        requireSkillUserId(userId)
        return skillsById.keys.toList()
    }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? {
        requireSkillUserId(userId)
        requireSafeSkillId(skillId)
        return skillsById[skillId]?.bundle?.let(::copySkillBundle)
    }
}

private class ClientWebSocketSkill(
    definition: ClientSkillDefinition,
    private val registry: ClientThreadRuntimeRegistry,
    private val toolCallRepository: ToolCallRepository,
    private val eventService: AgentEventService,
    private val now: () -> Instant,
) : LLMToolSetup {
    private val timeout = definition.timeout

    override val fn = LLMRequest.Function(
        name = definition.skillId.value,
        description = definition.description,
        parameters = LLMRequest.Parameters(type = "object"),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        errorMessage(functionCall.name, "client_context_missing", "Client tool context is unavailable.")

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        val threadId = meta.requestId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: return errorMessage(functionCall.name, "client_context_missing", "Thread ID is unavailable.")
        val chatId = meta.conversationId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: return errorMessage(functionCall.name, "client_context_missing", "Chat ID is unavailable.")
        val toolCallId = UUID.randomUUID().toString()
        val pending = PendingClientTool(toolCallId)
        val device = when (val beginTool = registry.beginTool(threadId, pending)) {
            BeginClientToolResult.Missing ->
                return errorMessage(functionCall.name, "client_context_missing", "Client device is unavailable.")
            BeginClientToolResult.Busy ->
                return errorMessage(functionCall.name, "client_tool_busy", "Another client tool call is already pending.")
            is BeginClientToolResult.Started -> beginTool.device
        }
        val startedAt = now()
        val deadlineAt = startedAt.plus(timeout)
        val arguments = restJsonMapper.valueToTree<JsonNode>(functionCall.arguments)
        val context = ToolCallContext(meta.userId, chatId.toString(), threadId.toString(), toolCallId)
        var clientCallStarted = false
        try {
            toolCallRepository.startClientCall(
                context = context,
                name = fn.name,
                deviceId = device.deviceId,
                argumentsJson = restJsonMapper.writeValueAsString(arguments),
                deadlineAt = deadlineAt,
                startedAt = startedAt,
            )
            clientCallStarted = true
            registry.awaitAcceptedInputAcks(threadId)
            eventService.appendDurable(
                userId = meta.userId,
                chatId = chatId,
                executionId = threadId,
                type = AgentEventType.TOOL_CALL_STARTED,
                payload = PublicToolCallStartedPayload(
                    toolCallId = toolCallId,
                    name = fn.name,
                    deviceId = device.deviceId,
                    arguments = arguments,
                    deadlineAt = deadlineAt.toString(),
                ),
            )
            val outcome = awaitResultUntilDeadline(context, threadId, toolCallId, pending, deadlineAt)
            return LLMRequest.Message(
                role = LLMMessageRole.function,
                content = when (outcome.status) {
                    "succeeded" -> restJsonMapper.writeValueAsString(outcome.result)
                    else -> restJsonMapper.writeValueAsString(
                        mapOf(
                            "status" to outcome.status,
                            "error" to (outcome.error ?: ClientError("client_tool_failed", "Client tool failed.")),
                        )
                    )
                },
                name = functionCall.name,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                cancel(context)
            }
            throw cancelled
        } catch (error: Exception) {
            if (clientCallStarted) {
                withContext(NonCancellable) {
                    failStartedCall(context, error)
                }
            }
            return errorMessage(functionCall.name, "client_tool_failed", error.message ?: "Client tool failed.")
        } finally {
            registry.clearTool(threadId, toolCallId)
        }
    }

    private suspend fun awaitResultUntilDeadline(
        context: ToolCallContext,
        threadId: UUID,
        toolCallId: String,
        pending: PendingClientTool,
        deadlineAt: Instant,
    ): ClientToolOutcome {
        val remainingMillis = Duration.between(now(), deadlineAt).toMillis()
        val completed = if (remainingMillis > 0) {
            withTimeoutOrNull(remainingMillis) { pending.result.await() }
        } else {
            null
        }
        return completed ?: timeOut(context, threadId, toolCallId, pending)
    }

    private suspend fun failStartedCall(context: ToolCallContext, cause: Exception) {
        val error = ClientError("client_tool_failed", cause.message ?: "Client tool failed.")
        toolCallRepository.completeClientCall(
            context = context,
            status = ToolCallStatus.FAILED,
            resultJson = null,
            errorJson = restJsonMapper.writeValueAsString(error),
            payloadHash = PublicPayloadHash.ofValue(mapOf("status" to "failed", "error" to error)),
        )
    }

    private suspend fun timeOut(
        context: ToolCallContext,
        threadId: UUID,
        toolCallId: String,
        pending: PendingClientTool,
    ): ClientToolOutcome {
        val error = ClientError("client_tool_timed_out", "Client tool result deadline expired.")
        val errorNode = restJsonMapper.valueToTree<JsonNode>(error)
        val payloadHash = PublicPayloadHash.ofValue(mapOf("status" to "timed_out", "error" to error))
        val completed = toolCallRepository.completeClientCall(
            context = context,
            status = ToolCallStatus.TIMED_OUT,
            resultJson = null,
            errorJson = restJsonMapper.writeValueAsString(errorNode),
            payloadHash = payloadHash,
            receivedAt = now(),
        )
        if (completed == null) {
            val storedOutcome = toolCallRepository.get(context)
                ?.takeIf { it.target == "client" && it.status != ToolCallStatus.RUNNING }
                ?.toClientToolOutcome()
            if (storedOutcome != null) {
                registry.finishTool(threadId, toolCallId, storedOutcome)
                return storedOutcome
            }
            val outcome = ClientToolOutcome("timed_out", null, error)
            registry.finishTool(threadId, toolCallId, outcome)
            return outcome
        }
        val outcome = ClientToolOutcome("timed_out", null, error)
        registry.finishTool(threadId, toolCallId, outcome)
        return outcome
    }

    private suspend fun cancel(context: ToolCallContext) {
        val error = ClientError("client_tool_cancelled", "Client tool call was cancelled with its thread.")
        toolCallRepository.completeClientCall(
            context = context,
            status = ToolCallStatus.CANCELLED,
            resultJson = null,
            errorJson = restJsonMapper.writeValueAsString(error),
            payloadHash = PublicPayloadHash.ofValue(mapOf("status" to "cancelled", "error" to error)),
        )
    }

    private fun errorMessage(functionName: String, code: String, message: String): LLMRequest.Message =
        LLMRequest.Message(
            role = LLMMessageRole.function,
            content = restJsonMapper.writeValueAsString(mapOf("error" to ClientError(code, message))),
            name = functionName,
        )

    private fun ToolCall.toClientToolOutcome(): ClientToolOutcome =
        ClientToolOutcome(
            status = status.value,
            result = resultJson?.let { restJsonMapper.readTree(it) },
            error = errorJson?.let { stored ->
                runCatching { restJsonMapper.readValue(stored, ClientError::class.java) }
                    .getOrElse { ClientError("client_tool_failed", "Client tool failed.") }
            },
        )
}

private data class ClientSkillDefinition(
    val skillId: SkillId,
    val description: String,
    val category: ToolCategory,
    val timeout: Duration,
)

private data class BundledClientSkill(
    val bundle: SkillBundle,
    val bundleHash: String,
    val definition: ClientSkillDefinition,
)

private fun loadClientSkills(classLoader: ClassLoader): Map<SkillId, BundledClientSkill> {
    val entries = requireNotNull(classLoader.getResourceAsStream(CLIENT_SKILL_INDEX)) {
        "Missing bundled client Skill index: $CLIENT_SKILL_INDEX"
    }.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
    }
    require(entries.isNotEmpty()) { "Bundled client Skill index is empty: $CLIENT_SKILL_INDEX" }

    val duplicateEntry = entries.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
    require(duplicateEntry == null) { "Duplicate bundled client Skill index entry: ${duplicateEntry?.key}" }

    val skillsById = linkedMapOf<SkillId, BundledClientSkill>()
    entries.forEach { entry ->
        require(CLIENT_SKILL_ENTRY_PATTERN.matches(entry)) {
            "Invalid bundled client Skill index entry: $entry"
        }
        val resourcePath = "$CLIENT_SKILL_ROOT/$entry/SKILL.md"
        val content = requireNotNull(classLoader.getResourceAsStream(resourcePath)) {
            "Missing bundled client Skill resource: $resourcePath"
        }.use { stream ->
            stream.readNBytes(MAX_STORED_SKILL_FILE_BYTES + 1).also { bytes ->
                require(bytes.size <= MAX_STORED_SKILL_FILE_BYTES) {
                    "Bundled client Skill exceeds $MAX_STORED_SKILL_FILE_BYTES bytes: $resourcePath"
                }
            }
        }
        val indexedBundle = SkillBundle.fromFiles(
            skillId = SkillId(entry),
            files = listOf(SkillFile(normalizedPath = "SKILL.md", content = content)),
        )
        val canonicalId = requireNotNull(
            indexedBundle.manifest.metadata[CLIENT_SKILL_ID_METADATA]
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
        ) {
            "Bundled client Skill $entry is missing metadata.$CLIENT_SKILL_ID_METADATA"
        }
        val transport = indexedBundle.manifest.metadata[CLIENT_SKILL_TRANSPORT_METADATA]?.trim()
        require(transport == CLIENT_SKILL_TRANSPORT) {
            "Bundled client Skill $entry has invalid metadata.$CLIENT_SKILL_TRANSPORT_METADATA: $transport"
        }
        val bundle = validateAndCopySkillBundle(
            SkillBundle.fromFiles(
                skillId = SkillId(canonicalId),
                files = indexedBundle.files,
            )
        )
        val definition = ClientSkillDefinition(
            skillId = bundle.skillId,
            description = "${bundle.manifest.description}\n\n${bundle.skillMarkdownBody}",
            category = bundle.manifest.clientCategory(),
            timeout = bundle.manifest.clientTimeout(),
        )
        val bundledSkill = BundledClientSkill(
            bundle = bundle,
            bundleHash = SkillBundleHasher.hash(bundle),
            definition = definition,
        )
        require(skillsById.put(bundle.skillId, bundledSkill) == null) {
            "Duplicate bundled client Skill ID: ${bundle.skillId.value}"
        }
    }
    return Collections.unmodifiableMap(skillsById)
}

private fun SkillManifest.clientCategory(): ToolCategory {
    val rawCategory = metadata[CLIENT_SKILL_CATEGORY_METADATA]?.trim().orEmpty()
    return requireNotNull(
        ToolCategory.entries.firstOrNull { category -> category.name.equals(rawCategory, ignoreCase = true) },
    ) {
        "Client Skill $name has invalid metadata.$CLIENT_SKILL_CATEGORY_METADATA: $rawCategory"
    }
}

private fun SkillManifest.clientTimeout(): Duration {
    val rawTimeout = metadata[CLIENT_SKILL_TIMEOUT_METADATA]?.trim().orEmpty()
    return requireNotNull(
        runCatching { Duration.parse(rawTimeout) }
            .getOrNull()
            ?.takeIf { timeout -> !timeout.isZero && !timeout.isNegative },
    ) {
        "Client Skill $name has invalid metadata.$CLIENT_SKILL_TIMEOUT_METADATA: $rawTimeout"
    }
}

private const val CLIENT_SKILL_TRANSPORT_METADATA = "souz.transport"
private const val CLIENT_SKILL_TRANSPORT = "client-websocket"
private const val CLIENT_SKILL_CATEGORY_METADATA = "souz.category"
private const val CLIENT_SKILL_TIMEOUT_METADATA = "souz.timeout"
private const val CLIENT_SKILL_ID_METADATA = "souz.skill-id"
private const val CLIENT_SKILL_ROOT = "skills/client"
private const val CLIENT_SKILL_INDEX = "$CLIENT_SKILL_ROOT/index.txt"
private val CLIENT_SKILL_ENTRY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
