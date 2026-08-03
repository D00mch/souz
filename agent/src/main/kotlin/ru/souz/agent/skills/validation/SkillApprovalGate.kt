package ru.souz.agent.skills.validation

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.json.JsonUtils

/**
 * Approval boundary for file-backed Skill bundles before their instructions are exposed or run.
 *
 * Callers provide the already loaded bundle. The gate owns only the approval flow: hash the bundle,
 * check the exact validation cache, run validators on a cache miss, and persist the final outcome.
 */
class SkillApprovalGate private constructor(
    private val validationStore: SkillValidationStore,
    private val validatorsProvider: () -> List<SkillValidator>,
    private val clock: Clock = Clock.systemUTC(),
) {
    constructor(
        validationStore: SkillValidationStore,
        llmValidator: SkillValidator,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        validationStore = validationStore,
        validatorsProvider = { defaultValidators(llmValidator) },
        clock = clock,
    )

    /** Fully loaded bundle plus the identity used to build the validation cache key. */
    data class Input(
        val userId: String,
        val skillId: SkillId,
        val bundle: SkillBundle,
        val policy: SkillValidationPolicy = SkillValidationPolicy.default(),
    )

    /** Gate outcome consumed by Skill detail and generic Skill execution tools. */
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
        val cached = validationStore.getValidation(
            userId = input.userId,
            skillId = input.skillId,
            bundleHash = bundleHash,
            policyVersion = input.policy.policyVersion,
        )
        if (cached != null) {
            if (cached.approved) {
                logger.info(
                    "Skill approval cache hit approved=true skill={} user={} hash={} policy={}",
                    input.skillId.value,
                    input.userId,
                    bundleHash.take(12),
                    input.policy.policyVersion,
                )
                return Result.Approved(input.bundle, bundleHash, cached)
            }

            logger.warn(
                "Skill approval cache hit approved=false skill={} user={} hash={} policy={}",
                input.skillId.value,
                input.userId,
                bundleHash.take(12),
                input.policy.policyVersion,
            )
            return Result.Rejected(
                bundleHash = bundleHash,
                reason = rejectionReason(cached.findings, input.skillId),
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

        val findings = validate(input, bundleHash)
        val record = SkillValidationRecord(
            userId = input.userId,
            skillId = input.skillId,
            bundleHash = bundleHash,
            policyVersion = input.policy.policyVersion,
            approved = findings.none { it.level == SkillValidationLevel.ERROR },
            findings = findings,
            createdAt = Instant.now(clock),
        )
        validationStore.saveValidation(record)

        return if (record.approved) {
            Result.Approved(input.bundle, bundleHash, record)
        } else {
            Result.Rejected(
                bundleHash = bundleHash,
                reason = rejectionReason(record.findings, input.skillId),
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

    private suspend fun validate(
        input: Input,
        bundleHash: String,
    ): List<SkillValidationFinding> {
        val findings = mutableListOf<SkillValidationFinding>()
        validatorsProvider().forEach { validator ->
            // Later validators see earlier findings; hard failures stop the chain before costly checks.
            findings += validator.validate(
                SkillValidationInput(
                    userId = input.userId,
                    skillId = input.skillId,
                    bundleHash = bundleHash,
                    policy = input.policy,
                    bundle = input.bundle,
                    previousFindings = findings.toList(),
                )
            )
            if (findings.any { it.level == SkillValidationLevel.ERROR }) {
                return findings
            }
        }
        return findings
    }

    private fun rejectionReason(
        findings: List<SkillValidationFinding>,
        skillId: SkillId,
    ): String = findings.firstOrNull { it.level == SkillValidationLevel.ERROR }?.message
        ?: "Skill validation rejected for ${skillId.value}."

    companion object {
        fun from(
            validationStore: SkillValidationStore,
            llmApi: LLMChatAPI,
            settingsProvider: AgentSettingsProvider,
            jsonUtils: JsonUtils,
        ): SkillApprovalGate = SkillApprovalGate(
            validationStore = validationStore,
            validatorsProvider = {
                defaultValidators(
                    LlmSkillValidator(
                        llmApi = llmApi,
                        model = settingsProvider.gigaModel.alias,
                        jsonUtils = jsonUtils,
                    )
                )
            },
        )

        private fun defaultValidators(llmValidator: SkillValidator): List<SkillValidator> =
            listOf(
                SkillStructuralValidator(),
                SkillStaticValidator(),
                llmValidator,
            )
    }
}

private fun errorFinding(
    code: String,
    message: String,
): SkillValidationFinding = SkillValidationFinding(
    code = code,
    message = message,
    level = SkillValidationLevel.ERROR,
)
