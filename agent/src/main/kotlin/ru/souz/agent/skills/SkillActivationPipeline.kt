package ru.souz.agent.skills

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.agent.skills.activation.ActivatedSkill
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.activation.SkillContextInjector
import ru.souz.agent.skills.bundle.SKILL_MD_PATH
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.selection.LlmSkillSelector
import ru.souz.agent.skills.selection.SkillSelectionInput
import ru.souz.agent.skills.selection.SkillSelector
import ru.souz.agent.skills.validation.LlmSkillValidator
import ru.souz.agent.skills.validation.SkillLlmValidationVerdict
import ru.souz.agent.skills.validation.SkillLlmValidationInput
import ru.souz.agent.skills.validation.SkillLlmValidator
import ru.souz.agent.skills.validation.SkillStaticValidator
import ru.souz.agent.skills.validation.SkillStructuralValidator
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationPolicy
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationRecordFactory
import ru.souz.agent.skills.validation.SkillValidationResult
import ru.souz.agent.skills.validation.SkillValidationSeverity
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.json.JsonUtils
import java.time.Clock
import java.time.Instant

class SkillActivationPipeline(
    private val registryRepository: SkillRegistryRepository,
    private val selector: SkillSelector,
    private val llmValidator: SkillLlmValidator,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Input(
        val userId: String,
        val context: AgentContext<String>,
        val policy: SkillValidationPolicy = SkillValidationPolicy.default(),
    )

    sealed interface Result {
        data class Ready(
            val context: AgentContext<String>,
            val activatedSkills: List<ActivatedSkill>,
            val rejectedSkills: List<RejectedSkill>,
            val selectedSkillIds: List<SkillId>,
        ) : Result

        data class Blocked(
            val reason: String,
            val findings: List<SkillValidationFinding>,
            val selectedSkillIds: List<SkillId>,
        ) : Result
    }

    data class RejectedSkill(
        val skillId: SkillId,
        val reason: String,
        val findings: List<SkillValidationFinding>,
    )

    private val logger = LoggerFactory.getLogger(SkillActivationPipeline::class.java)

    /** Make sure no Skills are injected. */
    fun withoutSkills(ctx: AgentContext<String>): AgentContext<String> =
        SkillContextInjector.clear(ctx)

    /** Inject skills */
    suspend fun run(input: Input): Result {
        logActivationStarted(input)

        var state = State(input)

        while (state.phase != SkillActivationPhase.DONE) {
            state = advanceSafely(state)
        }

        return state.result ?: blocked(
            reason = "Skills processing finished without a result.",
            code = "skills.missing_result",
            selectedSkillIds = state.selectedSkillIds,
        )
    }

    private suspend fun advanceSafely(state: State): State {
        return try {
            advance(state)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logPhaseFailed(state, t)

            state.finishBlocked(
                reason = "Skills processing failed during ${state.phase}.",
                code = state.phase.failureCode,
            )
        }
    }

    private suspend fun advance(state: State): State =
        when (state.phase) {
            SkillActivationPhase.SELECT_SKILLS -> selectSkills(state)
            SkillActivationPhase.LOAD_BUNDLE -> loadBundle(state)
            SkillActivationPhase.HASH_BUNDLE -> hashBundle(state)
            SkillActivationPhase.CHECK_CACHE -> checkCache(state)
            SkillActivationPhase.STRUCTURAL_VALIDATE -> validateStructurally(state)
            SkillActivationPhase.STATIC_VALIDATE -> validateStatically(state)
            SkillActivationPhase.LLM_VALIDATE -> validateWithLlm(state)
            SkillActivationPhase.ACTIVATE_SKILL -> activateSkill(state)
            SkillActivationPhase.NEXT_SKILL -> nextSkill(state)
            SkillActivationPhase.INJECT_CONTEXT -> injectContext(state)
            SkillActivationPhase.DONE -> state
        }

    private suspend fun selectSkills(state: State): State {
        val availableSkills = registryRepository.listSkills(state.input.userId)
        logRegistryResult(state, availableSkills)
        val selection = selector.select(
            SkillSelectionInput(
                userMessage = state.input.context.input,
                availableSkills = availableSkills,
            )
        )
        val selectedIds = selection.selectedSkillIds
        if (selectedIds.isEmpty()) {
            logNoSkillsSelected(state, availableSkills.size, selection.rationale)
            return state.copy(
                selectedSkillIds = emptyList(),
                phase = SkillActivationPhase.INJECT_CONTEXT,
            )
        }

        logSkillsSelected(state, selectedIds)

        val availableById = availableSkills.associateBy { it.skillId }
        val unknownSkill = selectedIds.firstOrNull { it !in availableById }
        if (unknownSkill != null) {
            logUnknownSkillSelected(state, unknownSkill, selectedIds, availableSkills)
            return state.copy(selectedSkillIds = selectedIds).finishBlocked(
                reason = "Skill selector returned an unknown skill id: ${unknownSkill.value}",
                findings = listOf(
                    errorFinding(
                        code = "selector.unknown_skill",
                        message = "Unknown selected skill id: ${unknownSkill.value}",
                    )
                ),
            )
        }

        return state.copy(
            selectedSkillIds = selectedIds,
            currentIndex = 0,
            phase = SkillActivationPhase.LOAD_BUNDLE,
        )
    }

    private suspend fun loadBundle(state: State): State {
        val skillId = state.requireCurrentSkillId()
        val bundle = registryRepository.loadSkillBundle(state.input.userId, skillId)
            ?: return state.finishBlocked(
                reason = "Skill bundle not found for ${skillId.value}",
                code = "bundle.missing",
            )

        logBundleLoaded(state, skillId, bundle)

        return state.copy(
            bundle = bundle,
            bundleHash = null,
            structural = null,
            static = null,
            phase = SkillActivationPhase.HASH_BUNDLE,
        )
    }

    private fun hashBundle(state: State): State {
        val skillId = state.requireCurrentSkillId()
        val bundleHash = SkillBundleHasher.hash(state.requireBundle())
        logBundleHashed(state, skillId, bundleHash)
        return state.copy(
            bundleHash = bundleHash,
            phase = SkillActivationPhase.CHECK_CACHE,
        )
    }

    private suspend fun checkCache(state: State): State {
        val skillId = state.requireCurrentSkillId()
        val bundleHash = state.requireBundleHash()

        registryRepository.invalidateOtherValidations(
            userId = state.input.userId,
            skillId = skillId,
            activeBundleHash = bundleHash,
            policyVersion = state.input.policy.policyVersion,
            reason = "Bundle hash changed or newer bundle became active.",
        )

        val cached: SkillValidationRecord? = registryRepository.getValidation(
            userId = state.input.userId,
            skillId = skillId,
            bundleHash = bundleHash,
            policyVersion = state.input.policy.policyVersion,
        )

        return when (cached?.status) {
            SkillValidationStatus.APPROVED -> {
                logValidationCacheHit(state, skillId, bundleHash, cached)
                state.copy(phase = SkillActivationPhase.ACTIVATE_SKILL)
            }

            SkillValidationStatus.REJECTED -> {
                logValidationCacheHit(state, skillId, bundleHash, cached)
                rejectCurrentSkill(
                    state = state,
                    reason = "Skill validation previously rejected for ${skillId.value}",
                    findings = listOf(
                        errorFinding(
                            code = "validation.cached_reject",
                            message = "Skill validation previously rejected for ${skillId.value}",
                        )
                    ),
                )
            }

            SkillValidationStatus.STALE -> {
                logStaleValidationCacheHit(state, skillId, bundleHash)
                state.copy(phase = SkillActivationPhase.STRUCTURAL_VALIDATE)
            }

            null -> {
                logValidationCacheMiss(state, skillId, bundleHash)
                state.copy(phase = SkillActivationPhase.STRUCTURAL_VALIDATE)
            }
        }
    }

    private suspend fun validateStructurally(state: State): State {
        val skillId = state.requireCurrentSkillId()
        val bundleHash = state.requireBundleHash()
        val structural = SkillStructuralValidator(state.input.policy).validate(state.requireBundle())
        logStructuralValidation(state, skillId, bundleHash, structural)
        if (structural.hasHardReject) {
            registryRepository.saveValidation(
                SkillValidationRecord(
                    userId = state.input.userId,
                    skillId = skillId,
                    bundleHash = bundleHash,
                    status = SkillValidationStatus.REJECTED,
                    policyVersion = state.input.policy.policyVersion,
                    validatorVersion = state.input.policy.validatorVersion,
                    reasons = listOf("Structural validation failed."),
                    findings = structural.findings,
                    createdAt = Instant.now(clock),
                )
            )
            return rejectCurrentSkill(
                state = state,
                reason = "Skill validation blocked by structural validator for ${skillId.value}",
                findings = structural.findings,
            )
        }

        return state.copy(
            structural = structural,
            phase = SkillActivationPhase.STATIC_VALIDATE,
        )
    }

    private suspend fun validateStatically(state: State): State {
        val skillId = state.requireCurrentSkillId()
        val bundleHash = state.requireBundleHash()
        val static = SkillStaticValidator(state.input.policy).validate(state.requireBundle())
        logStaticValidation(state, skillId, bundleHash, static)
        if (static.hasHardReject) {
            registryRepository.saveValidation(
                SkillValidationRecord(
                    userId = state.input.userId,
                    skillId = skillId,
                    bundleHash = bundleHash,
                    status = SkillValidationStatus.REJECTED,
                    policyVersion = state.input.policy.policyVersion,
                    validatorVersion = state.input.policy.validatorVersion,
                    reasons = listOf("Static validation failed."),
                    findings = static.findings,
                    createdAt = Instant.now(clock),
                )
            )
            return rejectCurrentSkill(
                state = state,
                reason = "Skill validation blocked by static validator for ${skillId.value}",
                findings = static.findings,
            )
        }

        return state.copy(
            static = static,
            phase = SkillActivationPhase.LLM_VALIDATE,
        )
    }

    private suspend fun validateWithLlm(state: State): State {
        if (true) return state.copy(phase = SkillActivationPhase.ACTIVATE_SKILL)

        val bundle = state.requireBundle()
        val skillId = state.requireCurrentSkillId()
        val bundleHash = state.requireBundleHash()
        val structural = state.requireStructural()
        val static = state.requireStatic()

        val llmVerdict = llmValidator.validate(
            SkillLlmValidationInput(
                userId = state.input.userId,
                skillId = skillId,
                bundleHash = bundleHash,
                policy = state.input.policy,
                manifest = bundle.manifest,
                filePaths = bundle.files.map { it.normalizedPath },
                skillMarkdown = bundle.skillMarkdownFile.contentAsText(),
                supportingFileExcerpts = bundle.files
                    .filterNot { it.normalizedPath == SKILL_MD_PATH }
                    .associate { file ->
                        file.normalizedPath to file.contentAsText().take(state.input.policy.excerptCharsPerFile)
                    },
                structuralFindings = structural.findings,
                staticFindings = static.findings,
            )
        )
        val record = SkillValidationRecordFactory.build(
            userId = state.input.userId,
            skillId = skillId,
            bundleHash = bundleHash,
            policy = state.input.policy,
            structural = structural,
            static = static,
            llm = llmVerdict,
            createdAt = Instant.now(clock),
        )
        registryRepository.saveValidation(record)
        logLlmValidationResult(state, skillId, bundleHash, llmVerdict, record)
        return when {
            record.status == SkillValidationStatus.APPROVED -> {
                state.copy(phase = SkillActivationPhase.ACTIVATE_SKILL)
            }
            else -> {
                rejectCurrentSkill(
                    state = state,
                    reason = "Skill validation rejected for ${skillId.value}",
                    findings = record.findings.ifEmpty {
                        listOf(
                            errorFinding(
                                code = "validation.rejected",
                                message = "Skill validation rejected for ${skillId.value}",
                            )
                        )
                    },
                )
            }
        }
    }

    private fun activateSkill(state: State): State {
        val newSkills = state.requireBundle().toActivatedSkill(state.requireBundleHash())
        logSkillActivated(state, newSkills)
        return state.copy(
            activatedSkills = state.activatedSkills + newSkills,
            phase = SkillActivationPhase.NEXT_SKILL,
        )
    }

    private fun nextSkill(state: State): State {
        val nextIndex = state.currentIndex + 1
        return state.copy(
            currentIndex = nextIndex,
            bundle = null,
            bundleHash = null,
            structural = null,
            static = null,
            phase = if (nextIndex < state.selectedSkillIds.size) SkillActivationPhase.LOAD_BUNDLE else SkillActivationPhase.INJECT_CONTEXT,
        )
    }

    private fun injectContext(state: State): State {
        val updatedContext = SkillContextInjector.inject(state.input.context, state.activatedSkills)
        logActivationFinished(state)
        return state.finishReady(updatedContext)
    }

    private fun rejectCurrentSkill(
        state: State,
        reason: String,
        findings: List<SkillValidationFinding>,
    ): State {
        val skillId = state.requireCurrentSkillId()
        logSkillRejected(state, skillId, reason, findings)
        return nextSkill(
            state.copy(
                rejectedSkills = state.rejectedSkills + RejectedSkill(
                    skillId = skillId,
                    reason = reason,
                    findings = findings,
                ),
                phase = SkillActivationPhase.NEXT_SKILL,
            )
        )
    }

    private fun blocked(
        reason: String,
        code: String,
        selectedSkillIds: List<SkillId>,
    ) = Result.Blocked(
        reason = reason,
        findings = listOf(errorFinding(code, reason)),
        selectedSkillIds = selectedSkillIds,
    )

    private fun SkillBundle.toActivatedSkill(bundleHash: String): ActivatedSkill = ActivatedSkill(
        skillId = skillId,
        manifest = manifest,
        bundleHash = bundleHash,
        instructionBody = skillMarkdownBody,
        supportingFiles = files.map { it.normalizedPath }.filterNot { it == SKILL_MD_PATH },
    )

    private fun errorFinding(code: String, message: String): SkillValidationFinding = SkillValidationFinding(
        code = code,
        message = message,
        severity = SkillValidationSeverity.ERROR,
    )

    private fun SkillValidationResult.errorCount(): Int =
        findings.count { it.severity == SkillValidationSeverity.ERROR }

    private fun logActivationStarted(input: Input) {
        logger.info(
            "Skill activation started for user={} conversationId={} requestId={} policy={} " +
                    "validator={} minApprovalConfidence={}",
            input.userId,
            input.context.toolInvocationMeta.conversationId,
            input.context.toolInvocationMeta.requestId,
            input.policy.policyVersion,
            input.policy.validatorVersion,
            input.policy.minApprovalConfidence,
        )
    }

    private fun logPhaseFailed(state: State, error: Throwable) {
        logger.warn(
            "Skills phase {} failed for user={}, skill={}",
            state.phase,
            state.input.userId,
            state.currentSkillId?.value,
            error,
        )
    }

    private fun logRegistryResult(state: State, availableSkills: List<StoredSkill>) {
        logger.info(
            "Skill registry returned {} skill(s) for user={} ids={}",
            availableSkills.size,
            state.input.userId,
            availableSkills.map { it.skillId.value },
        )
    }

    private fun logNoSkillsSelected(
        state: State,
        availableSkillCount: Int,
        rationale: String,
    ) {
        logger.info(
            "Skill activation selected no skills for user={} available={} rationale={}",
            state.input.userId,
            availableSkillCount,
            rationale,
        )
    }

    private fun logSkillsSelected(state: State, selectedIds: List<SkillId>) {
        logger.info(
            "Skill activation selected {} skill(s) for user={} ids={}",
            selectedIds.size,
            state.input.userId,
            selectedIds.map { it.value },
        )
    }

    private fun logUnknownSkillSelected(
        state: State,
        unknownSkill: SkillId,
        selectedIds: List<SkillId>,
        availableSkills: List<StoredSkill>,
    ) {
        logger.warn(
            "Skill selector returned unknown skill id={} for user={} selected={} available={}",
            unknownSkill.value,
            state.input.userId,
            selectedIds.map { it.value },
            availableSkills.map { it.skillId.value },
        )
    }

    private fun logBundleLoaded(
        state: State,
        skillId: SkillId,
        bundle: SkillBundle,
    ) {
        logger.info(
            "Skill bundle loaded skill={} user={} files={} supportingFiles={}",
            skillId.value,
            state.input.userId,
            bundle.files.size,
            bundle.files.count { it.normalizedPath != SKILL_MD_PATH },
        )
    }

    private fun logBundleHashed(
        state: State,
        skillId: SkillId,
        bundleHash: String,
    ) {
        logger.info(
            "Skill bundle hashed skill={} user={} hash={}",
            skillId.value,
            state.input.userId,
            bundleHash.take(12),
        )
    }

    private fun logValidationCacheHit(
        state: State,
        skillId: SkillId,
        bundleHash: String,
        cached: SkillValidationRecord,
    ) {
        logger.info(
            "Skill validation cache hit status={} skill={} user={} hash={} policy={} findings={} reasons={}",
            cached.status,
            skillId.value,
            state.input.userId,
            bundleHash.take(12),
            state.input.policy.policyVersion,
            cached.findings.size,
            cached.reasons.size,
        )
    }

    private fun logStaleValidationCacheHit(
        state: State,
        skillId: SkillId,
        bundleHash: String,
    ) {
        logger.info(
            "Skill validation cache hit status=STALE skill={} user={} hash={} policy={}",
            skillId.value,
            state.input.userId,
            bundleHash.take(12),
            state.input.policy.policyVersion,
        )
    }

    private fun logValidationCacheMiss(
        state: State,
        skillId: SkillId,
        bundleHash: String,
    ) {
        logger.info(
            "Skill validation cache miss skill={} user={} hash={} policy={}",
            skillId.value,
            state.input.userId,
            bundleHash.take(12),
            state.input.policy.policyVersion,
        )
    }

    private fun logStructuralValidation(
        state: State,
        skillId: SkillId,
        bundleHash: String,
        result: SkillValidationResult,
    ) {
        logger.info(
            "Skill structural validation skill={} user={} hash={} findings={} errors={}",
            skillId.value,
            state.input.userId,
            bundleHash.take(12),
            result.findings.size,
            result.errorCount(),
        )
    }

    private fun logStaticValidation(
        state: State,
        skillId: SkillId,
        bundleHash: String,
        result: SkillValidationResult,
    ) {
        logger.info(
            "Skill static validation skill={} user={} hash={} findings={} errors={}",
            skillId.value,
            state.input.userId,
            bundleHash.take(12),
            result.findings.size,
            result.errorCount(),
        )
    }

    private fun logLlmValidationResult(
        state: State,
        skillId: SkillId,
        bundleHash: String,
        verdict: SkillLlmValidationVerdict,
        record: SkillValidationRecord,
    ) {
        logger.info(
            "Skill LLM validation result skill={} user={} hash={} decision={} confidence={} " +
                    "minApprovalConfidence={} finalStatus={} findings={} errors={} model={}",
            skillId.value,
            state.input.userId,
            bundleHash.take(12),
            verdict.decision,
            verdict.confidence,
            state.input.policy.minApprovalConfidence,
            record.status,
            record.findings.size,
            record.findings.count { it.severity == SkillValidationSeverity.ERROR },
            record.model,
        )
    }

    private fun logSkillActivated(state: State, skill: ActivatedSkill) {
        logger.info(
            "Skill activated skill={} user={} hash={}",
            skill.skillId.value,
            state.input.userId,
            skill.bundleHash.take(12),
        )
    }

    private fun logActivationFinished(state: State) {
        logger.info(
            "Skill activation finished for user={} selected={} activated={} rejected={} activatedIds={} rejectedIds={}",
            state.input.userId,
            state.selectedSkillIds.size,
            state.activatedSkills.size,
            state.rejectedSkills.size,
            state.activatedSkills.map { it.skillId.value },
            state.rejectedSkills.map { it.skillId.value },
        )
    }

    private fun logSkillRejected(
        state: State,
        skillId: SkillId,
        reason: String,
        findings: List<SkillValidationFinding>,
    ) {
        logger.warn(
            "Skill rejected skill={} user={} reason={} findings={} errors={}",
            skillId.value,
            state.input.userId,
            reason,
            findings.size,
            findings.count { it.severity == SkillValidationSeverity.ERROR },
        )
    }

    private data class State(
        val input: Input,
        val phase: SkillActivationPhase = SkillActivationPhase.SELECT_SKILLS,
        val selectedSkillIds: List<SkillId> = emptyList(),
        val currentIndex: Int = 0,
        val bundle: SkillBundle? = null,
        val bundleHash: String? = null,
        val structural: SkillValidationResult? = null,
        val static: SkillValidationResult? = null,
        val activatedSkills: List<ActivatedSkill> = emptyList(),
        val rejectedSkills: List<RejectedSkill> = emptyList(),
        val result: Result? = null,
    ) {
        val currentSkillId: SkillId?
            get() = selectedSkillIds.getOrNull(currentIndex)
    }

    private fun State.finishReady(context: AgentContext<String>): State =
        copy(
            phase = SkillActivationPhase.DONE,
            result = Result.Ready(
                context = context,
                activatedSkills = activatedSkills,
                rejectedSkills = rejectedSkills,
                selectedSkillIds = selectedSkillIds,
            ),
        )

    private fun State.finishBlocked(
        reason: String,
        code: String,
    ): State = finishBlocked(reason, listOf(errorFinding(code, reason)))

    private fun State.finishBlocked(reason: String, findings: List<SkillValidationFinding>): State = copy(
        phase = SkillActivationPhase.DONE,
        result = Result.Blocked(
            reason = reason,
            findings = findings,
            selectedSkillIds = selectedSkillIds,
        ),
    )

    private fun State.requireCurrentSkillId(): SkillId =
        currentSkillId ?: error("Current skill is missing in phase $phase")

    private fun State.requireBundle(): SkillBundle =
        bundle ?: error("Bundle is missing in phase $phase")

    private fun State.requireBundleHash(): String =
        bundleHash ?: error("Bundle hash is missing in phase $phase")

    private fun State.requireStructural(): SkillValidationResult =
        structural ?: error("Structural validation is missing in phase $phase")

    private fun State.requireStatic(): SkillValidationResult =
        static ?: error("Static validation is missing in phase $phase")

    companion object {
        internal fun from(
            registryRepository: SkillRegistryRepository,
            llmApi: LLMChatAPI,
            settingsProvider: AgentSettingsProvider,
            jsonUtils: JsonUtils,
        ): SkillActivationPipeline = SkillActivationPipeline(
            registryRepository = registryRepository,
            selector = LlmSkillSelector(
                llmApi = llmApi,
                model = settingsProvider.gigaModel.alias,
                jsonUtils = jsonUtils,
            ),
            llmValidator = LlmSkillValidator(
                llmApi = llmApi,
                model = settingsProvider.gigaModel.alias,
                jsonUtils = jsonUtils,
            ),
        )
    }
}
