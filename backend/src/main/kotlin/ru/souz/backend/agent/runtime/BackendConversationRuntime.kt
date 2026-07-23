package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.UUID
import ru.souz.agent.AgentContextFactory
import ru.souz.agent.AgentExecutionKernelFactory
import ru.souz.agent.AgentExecutor
import ru.souz.agent.AgentId
import ru.souz.agent.runtime.AgentToolBatch
import ru.souz.agent.runtime.AgentToolBatchResume
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.llm.BackendLlmExecutionContext
import ru.souz.db.SettingsProvider
import ru.souz.backend.permission.repository.PermissionWorkflowRepository
import ru.souz.backend.permission.repository.ClaimedPermissionContinuation
import ru.souz.backend.permission.repository.PermissionInvocationPhase
import ru.souz.backend.permission.model.PermissionRequestStatus
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.runtime.ApiClassifier
import ru.souz.tool.LocalRegexClassifier

/** Result of one backend agent execution turn plus final usage data. */
internal data class BackendConversationExecution(
    val output: String,
    val usage: LLMResponse.Usage,
    val session: AgentConversationSession,
)

/** Request-scoped backend conversation runtime rebuilt from the stored snapshot. */
internal class BackendConversationRuntime(
    private val key: AgentConversationKey,
    private val sessionRepository: AgentSessionRepository,
    private val settingsProvider: BackendConversationSettingsProvider,
    private val contextFactory: AgentContextFactory,
    private val executor: AgentExecutor,
    private val usageTrackingApi: CumulativeUsageTrackingChatApi,
    private val persistedSession: AgentConversationSession?,
    private val checkpointObjectMapper: ObjectMapper,
    private val permissionsEnabled: Boolean,
    private val permissionWorkflowRepository: PermissionWorkflowRepository?,
) {
    private val activeAgentId = contextFactory.normalizeAgentId(
        persistedSession?.activeAgentId ?: settingsProvider.activeAgentId
    )
    private val currentTemperature = persistedSession?.temperature ?: settingsProvider.temperature

    init {
        persistedSession?.let { session ->
            settingsProvider.restore(
                activeAgentId = activeAgentId,
                temperature = currentTemperature,
                locale = session.locale,
            )
        }
    }

    internal suspend fun execute(
        request: BackendConversationTurnRequest,
        persistSession: Boolean = true,
        eventSink: AgentRuntimeEventSink? = null,
    ): BackendConversationExecution {
        settingsProvider.applyRequest(
            request = request,
            activeAgentId = activeAgentId,
            temperature = currentTemperature,
        )

        val checkpointSink = if (permissionsEnabled) {
            val repository = checkNotNull(permissionWorkflowRepository) {
                "Permission workflow repository is required when permissions are enabled."
            }
            BackendAgentToolBatchCheckpointSink(
                executionId = request.executionId.toRequiredUuid("executionId"),
                userId = key.userId,
                chatId = key.conversationId.toRequiredUuid("conversationId"),
                activeAgentId = activeAgentId,
                originalPrompt = request.prompt,
                baseStateRowVersion = persistedSession?.rowVersion ?: 0L,
                workflowRepository = repository,
                objectMapper = checkpointObjectMapper,
            )
        } else {
            ru.souz.agent.runtime.AgentToolBatchCheckpointSink.NONE
        }

        val seedContext = contextFactory.create(
            agentId = activeAgentId,
            history = persistedSession?.history.orEmpty(),
            model = settingsProvider.gigaModel,
            contextSize = request.contextSize,
            temperature = settingsProvider.temperature,
            toolInvocationMeta = ToolInvocationMeta(
                userId = key.userId,
                conversationId = key.conversationId,
                requestId = request.executionId,
                locale = request.locale,
                timeZone = request.timeZone,
            ),
            toolBatchCheckpointSink = checkpointSink,
        )

        val result = executor.execute(
            agentId = activeAgentId,
            context = seedContext,
            input = request.prompt,
            eventSink = eventSink,
        )
        val nextAgentId = contextFactory.normalizeAgentId(settingsProvider.activeAgentId)
        val nextSession = AgentConversationSession(
            activeAgentId = nextAgentId,
            history = result.context.history,
            temperature = result.context.settings.temperature,
            locale = request.locale,
            timeZone = request.timeZone,
            basedOnMessageSeq = persistedSession?.basedOnMessageSeq ?: 0L,
            rowVersion = persistedSession?.rowVersion ?: 0L,
        )

        if (persistSession) {
            sessionRepository.save(key, nextSession)
        }

        return BackendConversationExecution(
            output = result.output,
            usage = usageTrackingApi.cumulativeUsage(),
            session = nextSession,
        )
    }

    internal suspend fun resumePermission(
        request: BackendConversationTurnRequest,
        continuation: ClaimedPermissionContinuation,
        eventSink: AgentRuntimeEventSink,
    ): BackendConversationExecution {
        settingsProvider.applyRequest(
            request = request,
            activeAgentId = activeAgentId,
            temperature = currentTemperature,
        )
        val checkpoint = continuation.checkpoint
        val snapshot = checkpointObjectMapper.readValue<BackendAgentContextCheckpointV1>(checkpoint.contextJson)
        require(snapshot.schemaVersion == BackendAgentContextCheckpointV1.SCHEMA_VERSION) {
            "Unsupported permission checkpoint schema ${snapshot.schemaVersion}."
        }
        val batch = checkpointObjectMapper.readValue<AgentToolBatch>(checkpoint.batchJson)
        val restoredAgentId = contextFactory.normalizeAgentId(AgentId.fromStorageValue(snapshot.activeAgentId))
        val checkpointSink = BackendAgentToolBatchCheckpointSink(
            executionId = continuation.execution.id,
            userId = continuation.execution.userId,
            chatId = continuation.execution.chatId,
            activeAgentId = restoredAgentId,
            originalPrompt = snapshot.originalPrompt,
            baseStateRowVersion = checkpoint.baseStateRowVersion,
            workflowRepository = checkNotNull(permissionWorkflowRepository),
            objectMapper = checkpointObjectMapper,
            initialRevision = checkpoint.revision,
        )
        val seed = contextFactory.create(
            agentId = restoredAgentId,
            history = snapshot.history,
            model = settingsProvider.gigaModel,
            contextSize = snapshot.contextSize,
            temperature = snapshot.temperature,
            toolInvocationMeta = ToolInvocationMeta(
                userId = key.userId,
                conversationId = key.conversationId,
                requestId = request.executionId,
                locale = request.locale,
                timeZone = request.timeZone,
            ),
            toolBatchCheckpointSink = checkpointSink,
        ).copy(
            history = snapshot.history,
            activeTools = snapshot.activeTools,
            systemPrompt = snapshot.systemPrompt,
        )
        require(checkpointCompatibilityKey(batch, seed, checkpointObjectMapper) == checkpoint.compatibilityKey) {
            "Permission checkpoint tool definitions are incompatible with this runtime."
        }
        val permissionRequest = continuation.permissionRequest
        val resume = when {
            continuation.invocation.phase == PermissionInvocationPhase.RESULT_STORED -> AgentToolBatchResume(
                batch = batch,
                nextInvocationIndex = checkpoint.nextOrdinal,
            )

            continuation.invocation.phase == PermissionInvocationPhase.PLANNED && permissionRequest == null -> {
                checkNotNull(permissionWorkflowRepository).beginInvocation(
                    executionId = continuation.execution.id,
                    invocationId = continuation.invocation.invocationId,
                )
                AgentToolBatchResume(
                    batch = batch,
                    nextInvocationIndex = checkpoint.nextOrdinal,
                )
            }

            checkNotNull(permissionRequest) {
                "A claimed tool invocation requires a resolved permission."
            }.status == PermissionRequestStatus.GRANTED -> AgentToolBatchResume(
                batch = batch,
                nextInvocationIndex = checkpoint.nextOrdinal,
                resumePermissionId = permissionRequest.id.toString(),
                startedInvocationIds = setOf(continuation.invocation.invocationId),
            )

            permissionRequest.status == PermissionRequestStatus.DENIED -> AgentToolBatchResume.denied(
                batch = batch,
                nextInvocationIndex = checkpoint.nextOrdinal,
                startedInvocationIds = setOf(continuation.invocation.invocationId),
            )

            else -> error("Only a resolved permission can resume an execution.")
        }
        val result = executor.resumeToolBatch(
            agentId = restoredAgentId,
            context = seed,
            resume = resume,
            eventSink = eventSink,
        )
        val nextAgentId = contextFactory.normalizeAgentId(settingsProvider.activeAgentId)
        val nextSession = AgentConversationSession(
            activeAgentId = nextAgentId,
            history = result.context.history,
            temperature = result.context.settings.temperature,
            locale = request.locale,
            timeZone = request.timeZone,
            basedOnMessageSeq = persistedSession?.basedOnMessageSeq ?: 0L,
            rowVersion = checkpoint.baseStateRowVersion,
        )
        return BackendConversationExecution(
            output = result.output,
            usage = usageTrackingApi.cumulativeUsage(),
            session = nextSession,
        )
    }

    internal fun currentUsage(): LLMResponse.Usage = usageTrackingApi.cumulativeUsage()
}

/** Builds a request-scoped backend runtime on top of the shared agent kernel. */
class BackendConversationRuntimeFactory(
    private val baseSettingsProvider: SettingsProvider,
    private val llmApiFactory: suspend (BackendLlmExecutionContext) -> LLMChatAPI,
    private val sessionRepository: AgentSessionRepository,
    private val logObjectMapper: ObjectMapper,
    private val systemPrompt: String,
    private val toolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    private val toolsFilter: AgentToolsFilter = BackendNoopAgentToolsFilter,
    private val skillRegistryRepository: SkillRegistryRepository? = null,
    private val skillCommandTool: LLMToolSetup? = null,
    private val agentBackgroundScope: kotlinx.coroutines.CoroutineScope,
    private val featureFlags: BackendFeatureFlags = BackendFeatureFlags(),
    private val permissionWorkflowRepository: PermissionWorkflowRepository? = null,
) {
    internal suspend fun create(
        key: AgentConversationKey,
        request: BackendConversationTurnRequest,
        initialUsage: LLMResponse.Usage = LLMResponse.Usage(0, 0, 0, 0),
    ): BackendConversationRuntime {
        val persistedSession = sessionRepository.load(key)
        val settingsProvider = BackendConversationSettingsProvider(
            delegate = baseSettingsProvider,
            defaultSystemPrompt = request.systemPrompt ?: systemPrompt,
            locale = persistedSession?.locale ?: request.locale,
            useFewShotExamples = request.useFewShotExamples ?: baseSettingsProvider.useFewShotExamples,
            requestTimeoutMillis = request.requestTimeoutMillis ?: baseSettingsProvider.requestTimeoutMillis,
        )
        val requestScopedToolCatalog = BackendFewShotAwareToolCatalog(
            delegate = toolCatalog,
            settingsProvider = settingsProvider,
        )
        val delegateApi = llmApiFactory(
            BackendLlmExecutionContext(
                userId = key.userId,
                executionId = request.executionId ?: key.conversationId,
                settingsProvider = settingsProvider,
            )
        )
        val usageTrackingApi = CumulativeUsageTrackingChatApi(
            delegate = delegateApi,
            initialUsage = initialUsage,
        )
        val kernel = AgentExecutionKernelFactory(
            logObjectMapper = logObjectMapper,
            settingsProvider = settingsProvider,
            desktopInfoRepository = BackendNoopAgentDesktopInfoRepository,
            toolCatalog = requestScopedToolCatalog,
            toolsFilter = toolsFilter,
            defaultBrowserProvider = BackendNoopDefaultBrowserProvider,
            runtimeEnvironment = BackendRequestRuntimeEnvironment(
                localeTag = request.locale,
                timeZone = request.timeZone,
            ),
            mcpToolProvider = BackendNoopMcpToolProvider,
            skillCommandTool = skillCommandTool,
            telemetry = AgentTelemetry.NONE,
            errorMessages = BackendAgentErrorMessages,
            llmApi = usageTrackingApi,
            apiClassifier = ApiClassifier(delegateApi),
            localClassifier = LocalRegexClassifier,
            skillRegistryRepository = skillRegistryRepository ?: BackendNoopSkillRegistryRepository,
            captureScope = agentBackgroundScope,
        ).create()
        return BackendConversationRuntime(
            key = key,
            sessionRepository = sessionRepository,
            settingsProvider = settingsProvider,
            contextFactory = kernel.contextFactory,
            executor = kernel.executor,
            usageTrackingApi = usageTrackingApi,
            persistedSession = persistedSession,
            checkpointObjectMapper = logObjectMapper,
            permissionsEnabled = featureFlags.permissions,
            permissionWorkflowRepository = permissionWorkflowRepository,
        )
    }
}

private fun String?.toRequiredUuid(label: String): UUID =
    this?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        ?: error("Backend runtime requires a valid $label for durable permission checkpoints.")

private object BackendNoopSkillRegistryRepository : SkillRegistryRepository {
    override suspend fun listSkills(userId: String): List<StoredSkill> = emptyList()

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? = null

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? = null

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill =
        error("Skill registry repository is not configured for this backend runtime.")

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? = null

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = null

    override suspend fun saveValidation(record: SkillValidationRecord) = Unit

    override suspend fun markValidationStatus(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
        status: SkillValidationStatus,
        reason: String?,
    ) = Unit

    override suspend fun invalidateOtherValidations(
        userId: String,
        skillId: SkillId,
        activeBundleHash: String,
        policyVersion: String,
        reason: String?,
    ) = Unit
}
