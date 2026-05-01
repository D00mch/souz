package ru.souz.agent.skills

import org.slf4j.LoggerFactory
import ru.souz.agent.skills.validation.SkillBundleHasher
import ru.souz.agent.state.AgentContext
import ru.souz.agent.skills.validation.SkillLlmValidationInput
import ru.souz.agent.skills.validation.SkillLlmValidator
import ru.souz.agent.skills.validation.SkillStaticValidator
import ru.souz.agent.skills.validation.SkillStructuralValidator
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationPolicy
import ru.souz.agent.skills.validation.SkillValidationReducer
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationSeverity
import ru.souz.agent.skills.validation.SkillValidationStatus
import java.time.Clock
import java.time.Instant

data class SkillsGraphInput(
    val userId: String,
    val context: AgentContext<String>,
    val policy: SkillValidationPolicy = SkillValidationPolicy.default(),
)

sealed interface SkillsGraphResult {
    data class Ready(
        val context: AgentContext<String>,
        val activatedSkills: List<ActivatedSkill>,
        val selectedSkillIds: List<SkillId>,
    ) : SkillsGraphResult

    data class Blocked(
        val reason: String,
        val findings: List<SkillValidationFinding>,
        val selectedSkillIds: List<SkillId>,
    ) : SkillsGraphResult
}

class SkillsGraph(
    private val registryRepository: SkillRegistryRepository,
    private val selector: SkillSelector,
    private val llmValidator: SkillLlmValidator,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(SkillsGraph::class.java)

    suspend fun run(input: SkillsGraphInput): SkillsGraphResult {
        val availableSkills = registryRepository.listSkills(input.userId)
        val selection = selector.select(
            SkillSelectionInput(
                userMessage = input.context.input,
                availableSkills = availableSkills,
            )
        )
        val selectedIds = selection.selectedSkillIds.distinct()
        if (selectedIds.isEmpty()) {
            val cleanedContext = SkillsContextInjector.inject(input.context, activatedSkills = emptyList())
            return SkillsGraphResult.Ready(
                context = cleanedContext,
                activatedSkills = emptyList(),
                selectedSkillIds = emptyList(),
            )
        }

        val availableById = availableSkills.associateBy { it.skillId }
        val unknownSkill = selectedIds.firstOrNull { it !in availableById }
        if (unknownSkill != null) {
            return SkillsGraphResult.Blocked(
                reason = "Skill selector returned an unknown skill id: ${unknownSkill.value}",
                findings = listOf(
                    SkillValidationFinding(
                        code = "selector.unknown_skill",
                        message = "Unknown selected skill id: ${unknownSkill.value}",
                        severity = SkillValidationSeverity.ERROR,
                    )
                ),
                selectedSkillIds = selectedIds,
            )
        }

        val activatedSkills = mutableListOf<ActivatedSkill>()
        for (skillId in selectedIds) {
            val bundle = try {
                registryRepository.loadSkillBundle(input.userId, skillId)
                    ?: return blocked(
                        reason = "Skill bundle not found for ${skillId.value}",
                        code = "bundle.missing",
                        selectedSkillIds = selectedIds,
                    )
            } catch (t: Throwable) {
                logger.warn("Failed to load skill bundle for {}", skillId.value, t)
                return blocked(
                    reason = "Failed to load skill bundle for ${skillId.value}",
                    code = "bundle.load_error",
                    selectedSkillIds = selectedIds,
                )
            }

            val bundleHash = try {
                SkillBundleHasher.hash(bundle)
            } catch (t: Throwable) {
                logger.warn("Failed to hash skill bundle for {}", skillId.value, t)
                return blocked(
                    reason = "Failed to hash skill bundle for ${skillId.value}",
                    code = "bundle.hash_error",
                    selectedSkillIds = selectedIds,
                )
            }
            registryRepository.invalidateOtherValidations(
                userId = input.userId,
                skillId = skillId,
                activeBundleHash = bundleHash,
                policyVersion = input.policy.policyVersion,
                reason = "Bundle hash changed or newer bundle became active.",
            )

            val cached = registryRepository.getValidation(
                userId = input.userId,
                skillId = skillId,
                bundleHash = bundleHash,
                policyVersion = input.policy.policyVersion,
            )
            when (cached?.status) {
                SkillValidationStatus.APPROVED -> {
                    logger.info("Using cached skill validation for {} ({})", skillId.value, bundleHash.take(12))
                    activatedSkills += bundle.toActivatedSkill(bundleHash)
                    continue
                }

                SkillValidationStatus.REJECTED -> {
                    return SkillsGraphResult.Blocked(
                        reason = "Skill validation previously rejected for ${skillId.value}",
                        findings = cached.findings.ifEmpty {
                            listOf(
                                SkillValidationFinding(
                                    code = "validation.cached_reject",
                                    message = "Skill validation previously rejected for ${skillId.value}",
                                    severity = SkillValidationSeverity.ERROR,
                                )
                            )
                        },
                        selectedSkillIds = selectedIds,
                    )
                }

                SkillValidationStatus.STALE, null -> Unit
            }

            val structural = SkillStructuralValidator(input.policy).validate(bundle)
            if (structural.hasHardReject) {
                val record = SkillValidationRecord(
                    userId = input.userId,
                    skillId = skillId,
                    bundleHash = bundleHash,
                    status = SkillValidationStatus.REJECTED,
                    policyVersion = input.policy.policyVersion,
                    validatorVersion = input.policy.validatorVersion,
                    reasons = listOf("Structural validation failed."),
                    findings = structural.findings,
                    createdAt = Instant.now(clock),
                )
                registryRepository.saveValidation(record)
                return SkillsGraphResult.Blocked(
                    reason = "Skill validation blocked by structural validator for ${skillId.value}",
                    findings = structural.findings,
                    selectedSkillIds = selectedIds,
                )
            }

            val static = SkillStaticValidator(input.policy).validate(bundle)
            if (static.hasHardReject) {
                val record = SkillValidationRecord(
                    userId = input.userId,
                    skillId = skillId,
                    bundleHash = bundleHash,
                    status = SkillValidationStatus.REJECTED,
                    policyVersion = input.policy.policyVersion,
                    validatorVersion = input.policy.validatorVersion,
                    reasons = listOf("Static validation failed."),
                    findings = static.findings,
                    createdAt = Instant.now(clock),
                )
                registryRepository.saveValidation(record)
                return SkillsGraphResult.Blocked(
                    reason = "Skill validation blocked by static validator for ${skillId.value}",
                    findings = static.findings,
                    selectedSkillIds = selectedIds,
                )
            }

            val llmVerdict = llmValidator.validate(
                SkillLlmValidationInput(
                    userId = input.userId,
                    skillId = skillId,
                    bundleHash = bundleHash,
                    policy = input.policy,
                    manifest = bundle.manifest,
                    filePaths = bundle.files.map { it.normalizedPath },
                    skillMarkdown = bundle.skillMarkdownFile.contentAsText(),
                    supportingFileExcerpts = bundle.files
                        .filterNot { it.normalizedPath == SKILL_MD_PATH }
                        .associate { file ->
                            file.normalizedPath to file.contentAsText().take(input.policy.excerptCharsPerFile)
                        },
                    structuralFindings = structural.findings,
                    staticFindings = static.findings,
                )
            )
            val record = SkillValidationReducer.reduce(
                userId = input.userId,
                skillId = skillId,
                bundleHash = bundleHash,
                policy = input.policy,
                structural = structural,
                static = static,
                llm = llmVerdict,
                createdAt = Instant.now(clock),
            )
            registryRepository.saveValidation(record)
            if (record.status != SkillValidationStatus.APPROVED) {
                return SkillsGraphResult.Blocked(
                    reason = "Skill validation rejected for ${skillId.value}",
                    findings = record.findings.ifEmpty {
                        listOf(
                            SkillValidationFinding(
                                code = "validation.rejected",
                                message = "Skill validation rejected for ${skillId.value}",
                                severity = SkillValidationSeverity.ERROR,
                            )
                        )
                    },
                    selectedSkillIds = selectedIds,
                )
            }

            activatedSkills += bundle.toActivatedSkill(bundleHash)
        }

        val updatedContext = SkillsContextInjector.inject(input.context, activatedSkills)
        return SkillsGraphResult.Ready(
            context = updatedContext,
            activatedSkills = activatedSkills,
            selectedSkillIds = selectedIds,
        )
    }

    private fun blocked(
        reason: String,
        code: String,
        selectedSkillIds: List<SkillId>,
    ) = SkillsGraphResult.Blocked(
        reason = reason,
        findings = listOf(
            SkillValidationFinding(
                code = code,
                message = reason,
                severity = SkillValidationSeverity.ERROR,
            )
        ),
        selectedSkillIds = selectedSkillIds,
    )

    private fun SkillBundle.toActivatedSkill(bundleHash: String): ActivatedSkill = ActivatedSkill(
        skillId = skillId,
        manifest = manifest,
        bundleHash = bundleHash,
        instructionBody = skillMarkdownBody,
        supportingFiles = files.map { it.normalizedPath }.filterNot { it == SKILL_MD_PATH },
    )
}
