package ru.souz.agent.skills

import org.slf4j.LoggerFactory
import ru.souz.agent.skills.activation.ActivatedSkill
import ru.souz.agent.skills.activation.SkillContextInjector
import ru.souz.agent.skills.bundle.SKILL_MD_PATH
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.selection.SkillSelectionInput
import ru.souz.agent.skills.selection.SkillSelector
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
import ru.souz.agent.state.AgentContext
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
            val selectedSkillIds: List<SkillId>,
        ) : Result

        data class Blocked(
            val reason: String,
            val findings: List<SkillValidationFinding>,
            val selectedSkillIds: List<SkillId>,
        ) : Result
    }

    private val logger = LoggerFactory.getLogger(SkillActivationPipeline::class.java)

    suspend fun run(input: Input): Result {
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
        } catch (t: Throwable) {
            logger.warn(
                "Skills phase {} failed for user={}, skill={}",
                state.phase,
                state.input.userId,
                state.currentSkillId?.value,
                t,
            )

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
        val selection = selector.select(
            SkillSelectionInput(
                userMessage = state.input.context.input,
                availableSkills = availableSkills,
            )
        )
        val selectedIds = selection.selectedSkillIds
        if (selectedIds.isEmpty()) {
            return state.copy(
                selectedSkillIds = emptyList(),
                phase = SkillActivationPhase.INJECT_CONTEXT,
            )
        }

        val availableById = availableSkills.associateBy { it.skillId }
        val unknownSkill = selectedIds.firstOrNull { it !in availableById }
        if (unknownSkill != null) {
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

        return state.copy(
            bundle = bundle,
            bundleHash = null,
            structural = null,
            static = null,
            phase = SkillActivationPhase.HASH_BUNDLE,
        )
    }

    private fun hashBundle(state: State): State = state.copy(
        bundleHash = SkillBundleHasher.hash(state.requireBundle()),
        phase = SkillActivationPhase.CHECK_CACHE,
    )

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
                logger.info("Using cached skill validation for {} ({})", skillId.value, bundleHash.take(12))
                state.copy(phase = SkillActivationPhase.ACTIVATE_SKILL)
            }

            SkillValidationStatus.REJECTED ->
                state.finishBlocked(
                    reason = "Skill validation previously rejected for ${skillId.value}",
                    findings = cached.findings.ifEmpty {
                        listOf(
                            errorFinding(
                                code = "validation.cached_reject",
                                message = "Skill validation previously rejected for ${skillId.value}",
                            )
                        )
                    },
                )

            SkillValidationStatus.STALE, null ->
                state.copy(phase = SkillActivationPhase.STRUCTURAL_VALIDATE)
        }
    }

    private suspend fun validateStructurally(state: State): State {
        val skillId = state.requireCurrentSkillId()
        val bundleHash = state.requireBundleHash()
        val structural = SkillStructuralValidator(state.input.policy).validate(state.requireBundle())
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
            return state.finishBlocked(
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
            return state.finishBlocked(
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
        if (record.status != SkillValidationStatus.APPROVED) {
            return state.finishBlocked(
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

        return state.copy(phase = SkillActivationPhase.ACTIVATE_SKILL)
    }

    private fun activateSkill(state: State): State = state.copy(
        activatedSkills = state.activatedSkills + state.requireBundle().toActivatedSkill(state.requireBundleHash()),
        phase = SkillActivationPhase.NEXT_SKILL,
    )

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
        return state.finishReady(updatedContext)
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
}
