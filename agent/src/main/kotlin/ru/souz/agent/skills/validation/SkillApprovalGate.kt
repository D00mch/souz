package ru.souz.agent.skills.validation

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SKILL_MD_PATH
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.json.JsonUtils

class SkillApprovalGate(
    private val registryRepository: SkillRegistryRepository,
    private val llmValidator: SkillLlmValidator,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Input(
        val userId: String,
        val skillId: SkillId,
        val bundle: SkillBundle,
        val policy: SkillValidationPolicy = SkillValidationPolicy.default(),
    )

    sealed interface Result {
        data class Approved(
            val bundle: SkillBundle,
            val bundleHash: String,
            val record: SkillValidationRecord?,
        ) : Result

        data class Rejected(
            val bundleHash: String,
            val reason: String,
            val findings: List<SkillValidationFinding>,
        ) : Result
    }

    private val logger = LoggerFactory.getLogger(SkillApprovalGate::class.java)

    suspend fun ensureApproved(input: Input): Result {
        val bundleHash = SkillBundleHasher.hash(input.bundle)
        return try {
            ensureApproved(input, bundleHash)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn(
                "Skill approval failed skill={} user={} hash={} policy={}",
                input.skillId.value,
                input.userId,
                bundleHash.take(12),
                input.policy.policyVersion,
                error,
            )
            Result.Rejected(
                bundleHash = bundleHash,
                reason = "Skill validation failed for ${input.skillId.value}.",
                findings = listOf(
                    errorFinding(
                        code = "validation.failed",
                        message = error.message ?: "Skill validation failed.",
                    )
                ),
            )
        }
    }

    private suspend fun ensureApproved(
        input: Input,
        bundleHash: String,
    ): Result {
        registryRepository.invalidateOtherValidations(
            userId = input.userId,
            skillId = input.skillId,
            activeBundleHash = bundleHash,
            policyVersion = input.policy.policyVersion,
            reason = "Bundle hash changed or newer bundle became active.",
        )

        val cached = registryRepository.getValidation(
            userId = input.userId,
            skillId = input.skillId,
            bundleHash = bundleHash,
            policyVersion = input.policy.policyVersion,
        )
        when (cached?.status) {
            SkillValidationStatus.APPROVED -> {
                logger.info(
                    "Skill approval cache hit status=APPROVED skill={} user={} hash={} policy={}",
                    input.skillId.value,
                    input.userId,
                    bundleHash.take(12),
                    input.policy.policyVersion,
                )
                return Result.Approved(input.bundle, bundleHash, cached)
            }

            SkillValidationStatus.REJECTED -> {
                logger.warn(
                    "Skill approval cache hit status=REJECTED skill={} user={} hash={} policy={}",
                    input.skillId.value,
                    input.userId,
                    bundleHash.take(12),
                    input.policy.policyVersion,
                )
                return Result.Rejected(
                    bundleHash = bundleHash,
                    reason = cached.reasons.firstOrNull()
                        ?: "Skill validation previously rejected for ${input.skillId.value}.",
                    findings = cached.findings.ifEmpty {
                        listOf(
                            errorFinding(
                                code = "validation.cached_reject",
                                message = "Skill validation previously rejected for ${input.skillId.value}.",
                            )
                        )
                    },
                )
            }

            SkillValidationStatus.STALE,
            null,
            -> Unit
        }

        val structural = SkillStructuralValidator(input.policy).validate(input.bundle)
        if (structural.hasHardReject) {
            val record = rejectedRecord(
                input = input,
                bundleHash = bundleHash,
                reason = "Structural validation failed.",
                findings = structural.findings,
            )
            registryRepository.saveValidation(record)
            return Result.Rejected(bundleHash, "Structural validation failed.", structural.findings)
        }

        val static = SkillStaticValidator(input.policy).validate(input.bundle)
        if (static.hasHardReject) {
            val record = rejectedRecord(
                input = input,
                bundleHash = bundleHash,
                reason = "Static validation failed.",
                findings = static.findings,
            )
            registryRepository.saveValidation(record)
            return Result.Rejected(bundleHash, "Static validation failed.", static.findings)
        }

        val llmVerdict = llmValidator.validate(
            SkillLlmValidationInput(
                userId = input.userId,
                skillId = input.skillId,
                bundleHash = bundleHash,
                policy = input.policy,
                manifest = input.bundle.manifest,
                filePaths = input.bundle.files.map { it.normalizedPath },
                skillMarkdown = input.bundle.skillMarkdownFile.contentAsText(),
                supportingFileExcerpts = input.bundle.files
                    .filterNot { it.normalizedPath == SKILL_MD_PATH }
                    .associate { file ->
                        file.normalizedPath to file.contentAsText().take(input.policy.excerptCharsPerFile)
                    },
                structuralFindings = structural.findings,
                staticFindings = static.findings,
            )
        )
        val record = SkillValidationRecordFactory.build(
            userId = input.userId,
            skillId = input.skillId,
            bundleHash = bundleHash,
            policy = input.policy,
            structural = structural,
            static = static,
            llm = llmVerdict,
            createdAt = Instant.now(clock),
        )
        registryRepository.saveValidation(record)

        return if (record.status == SkillValidationStatus.APPROVED) {
            Result.Approved(input.bundle, bundleHash, record)
        } else {
            Result.Rejected(
                bundleHash = bundleHash,
                reason = record.reasons.firstOrNull() ?: "Skill validation rejected for ${input.skillId.value}.",
                findings = record.findings.ifEmpty {
                    listOf(
                        errorFinding(
                            code = "validation.rejected",
                            message = "Skill validation rejected for ${input.skillId.value}.",
                        )
                    )
                },
            )
        }
    }

    private fun rejectedRecord(
        input: Input,
        bundleHash: String,
        reason: String,
        findings: List<SkillValidationFinding>,
    ): SkillValidationRecord = SkillValidationRecord(
        userId = input.userId,
        skillId = input.skillId,
        bundleHash = bundleHash,
        status = SkillValidationStatus.REJECTED,
        policyVersion = input.policy.policyVersion,
        validatorVersion = input.policy.validatorVersion,
        reasons = listOf(reason),
        findings = findings,
        createdAt = Instant.now(clock),
    )

    companion object {
        fun from(
            registryRepository: SkillRegistryRepository,
            llmApi: LLMChatAPI,
            settingsProvider: AgentSettingsProvider,
            jsonUtils: JsonUtils,
        ): SkillApprovalGate = SkillApprovalGate(
            registryRepository = registryRepository,
            llmValidator = LlmSkillValidator(
                llmApi = llmApi,
                model = settingsProvider.gigaModel.alias,
                jsonUtils = jsonUtils,
            ),
        )
    }
}

private fun errorFinding(
    code: String,
    message: String,
): SkillValidationFinding = SkillValidationFinding(
    code = code,
    message = message,
    severity = SkillValidationSeverity.ERROR,
)
