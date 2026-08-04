package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.events.service.AgentEventService
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

internal class BackendClientToolCatalogFactory(
    private val skillBundleProvider: SkillBundleProvider = BackendBundledClientSkillBundleProvider(),
    private val registry: ClientThreadRuntimeRegistry,
    private val toolCallRepository: ToolCallRepository,
    private val eventService: AgentEventService,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun create(userId: String = CLIENT_SKILL_OWNER): AgentToolCatalog =
        BackendClientToolCatalog(
            skills = loadClientSkillDefinitions(userId),
            registry = registry,
            toolCallRepository = toolCallRepository,
            eventService = eventService,
            now = now,
        )

    private suspend fun loadClientSkillDefinitions(userId: String): List<ClientSkillDefinition> =
        skillBundleProvider.listSkills(userId).map { storedSkill ->
            val bundle = requireNotNull(skillBundleProvider.loadSkillBundle(userId, storedSkill.skillId)) {
                "Bundled client Skill disappeared after listing: ${storedSkill.skillId.value}"
            }
            require(bundle.manifest.metadata[CLIENT_SKILL_TRANSPORT_METADATA] == CLIENT_SKILL_TRANSPORT) {
                "Bundled client Skill ${storedSkill.skillId.value} has invalid metadata.$CLIENT_SKILL_TRANSPORT_METADATA"
            }
            ClientSkillDefinition(
                name = storedSkill.skillId.value,
                category = bundle.manifest.clientCategory(),
                timeout = bundle.manifest.clientTimeout(),
                description = "${bundle.manifest.description}\n\n${bundle.skillMarkdownBody}",
            )
        }
}

private class BackendClientToolCatalog(
    skills: List<ClientSkillDefinition>,
    registry: ClientThreadRuntimeRegistry,
    toolCallRepository: ToolCallRepository,
    eventService: AgentEventService,
    now: () -> Instant,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = skills
        .groupBy { it.category }
        .mapValues { (_, skills) ->
            skills.associate { skill ->
                skill.name to ClientWebSocketSkill(skill, registry, toolCallRepository, eventService, now)
            }
        }
}

private class ClientWebSocketSkill(
    private val skill: ClientSkillDefinition,
    private val registry: ClientThreadRuntimeRegistry,
    private val toolCallRepository: ToolCallRepository,
    private val eventService: AgentEventService,
    private val now: () -> Instant,
) : LLMToolSetup {
    override val fn = LLMRequest.Function(
        name = skill.name,
        description = skill.description,
        parameters = LLMRequest.Parameters("object"),
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
        val deadlineAt = startedAt.plus(skill.timeout)
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
    val name: String,
    val category: ToolCategory,
    val timeout: Duration,
    val description: String,
)

private class BackendBundledClientSkillBundleProvider(
    classLoader: ClassLoader = BackendClientToolCatalogFactory::class.java.classLoader,
) : SkillBundleProvider {
    private val bundles: Map<SkillId, SkillBundle> = loadBundledClientSkillBundles(classLoader)

    override suspend fun listSkills(userId: String): List<StoredSkill> = bundles.values
        .map { bundle -> bundle.toStoredSkill(userId) }
        .sortedBy { it.skillId.value }

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? =
        bundles[skillId]?.toStoredSkill(userId)

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? =
        bundles.values.firstOrNull { it.manifest.name == name }?.toStoredSkill(userId)

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? = bundles[skillId]

    private fun SkillBundle.toStoredSkill(userId: String): StoredSkill = StoredSkill(
        userId = userId,
        skillId = skillId,
        manifest = manifest,
        bundleHash = SkillBundleHasher.hash(this),
        createdAt = Instant.EPOCH,
    )
}

private fun loadBundledClientSkillBundles(classLoader: ClassLoader): Map<SkillId, SkillBundle> {
    val entries = requireNotNull(classLoader.getResourceAsStream(CLIENT_SKILL_INDEX)) {
        "Missing bundled client Skill index: $CLIENT_SKILL_INDEX"
    }.bufferedReader().useLines { lines ->
        lines
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
    }

    val bundles = entries.map { entry ->
        require('/' !in entry && '\\' !in entry) { "Invalid bundled client Skill entry: $entry" }
        val resourcePath = "$CLIENT_SKILL_ROOT/$entry/SKILL.md"
        val content = requireNotNull(classLoader.getResourceAsStream(resourcePath)) {
            "Missing bundled client Skill resource: $resourcePath"
        }.use { it.readBytes() }
        val manifestBundle = SkillBundle.fromFiles(
            skillId = SkillId(entry),
            files = listOf(SkillFile(normalizedPath = "SKILL.md", content = content)),
        )
        val skillId = manifestBundle.manifest.metadata[CLIENT_SKILL_ID_METADATA]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("Bundled client Skill $entry is missing metadata.$CLIENT_SKILL_ID_METADATA")
        require(manifestBundle.manifest.metadata[CLIENT_SKILL_TRANSPORT_METADATA] == CLIENT_SKILL_TRANSPORT) {
            "Bundled client Skill $entry has invalid metadata.$CLIENT_SKILL_TRANSPORT_METADATA"
        }
        val bundle = SkillBundle.fromFiles(
            skillId = SkillId(skillId),
            files = manifestBundle.files,
        )
        bundle.skillId to bundle
    }
    val duplicate = bundles.groupingBy { it.first.value }.eachCount().entries.firstOrNull { it.value > 1 }
    require(duplicate == null) { "Duplicate bundled client Skill ID: ${duplicate?.key}" }
    return bundles.toMap()
}

private fun ru.souz.agent.skills.bundle.SkillManifest.clientCategory(): ToolCategory {
    val raw = metadata[CLIENT_SKILL_CATEGORY_METADATA]?.trim().orEmpty()
    return ToolCategory.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: error("Client Skill $name has an invalid metadata.$CLIENT_SKILL_CATEGORY_METADATA: $raw")
}

private fun ru.souz.agent.skills.bundle.SkillManifest.clientTimeout(): Duration {
    val raw = metadata[CLIENT_SKILL_TIMEOUT_METADATA]?.trim().orEmpty()
    return runCatching { Duration.parse(raw) }.getOrNull()
        ?.takeIf { !it.isZero && !it.isNegative }
        ?: error("Client Skill $name has an invalid metadata.$CLIENT_SKILL_TIMEOUT_METADATA: $raw")
}

private const val CLIENT_SKILL_TRANSPORT_METADATA = "souz.transport"
private const val CLIENT_SKILL_TRANSPORT = "client-websocket"
private const val CLIENT_SKILL_CATEGORY_METADATA = "souz.category"
private const val CLIENT_SKILL_TIMEOUT_METADATA = "souz.timeout"
private const val CLIENT_SKILL_ID_METADATA = "souz.skill-id"
private const val CLIENT_SKILL_ROOT = "skills/client"
private const val CLIENT_SKILL_INDEX = "$CLIENT_SKILL_ROOT/index.txt"
private const val CLIENT_SKILL_OWNER = "backend-client"
