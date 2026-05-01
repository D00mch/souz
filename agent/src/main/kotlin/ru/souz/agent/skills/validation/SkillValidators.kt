package ru.souz.agent.skills.validation

import ru.souz.agent.skills.SKILL_MD_PATH
import ru.souz.agent.skills.SkillBundle
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.SkillPathNormalizer
import java.time.Instant

class SkillStructuralValidator(
    private val policy: SkillValidationPolicy,
) {
    fun validate(bundle: SkillBundle): SkillValidationResult {
        val findings = mutableListOf<SkillValidationFinding>()

        if (bundle.files.none { it.normalizedPath == SKILL_MD_PATH }) {
            findings += error("struct.missing_skill_md", "Skill bundle is missing SKILL.md")
        }
        if (bundle.manifest.name.isBlank()) {
            findings += error("struct.missing_name", "Skill manifest name is blank", SKILL_MD_PATH)
        }
        if (bundle.manifest.description.isBlank()) {
            findings += error("struct.missing_description", "Skill manifest description is blank", SKILL_MD_PATH)
        }

        val normalizedPaths = mutableSetOf<String>()
        var totalBytes = 0L
        bundle.files.forEach { file ->
            if (!normalizedPaths.add(file.normalizedPath)) {
                findings += error("struct.duplicate_path", "Duplicate normalized path: ${file.normalizedPath}", file.normalizedPath)
            }
            runCatching { SkillPathNormalizer.normalize(file.normalizedPath) }
                .onFailure {
                    findings += error("struct.invalid_path", it.message ?: "Invalid path", file.normalizedPath)
                }
            if (file.content.size > policy.maxFileBytes) {
                findings += error(
                    code = "struct.file_too_large",
                    message = "File exceeds max size of ${policy.maxFileBytes} bytes",
                    filePath = file.normalizedPath,
                )
            }
            totalBytes += file.content.size.toLong()
        }

        if (totalBytes > policy.maxBundleBytes) {
            findings += error(
                code = "struct.bundle_too_large",
                message = "Bundle exceeds max size of ${policy.maxBundleBytes} bytes",
            )
        }

        return SkillValidationResult(findings)
    }

    private fun error(code: String, message: String, filePath: String? = null) = SkillValidationFinding(
        code = code,
        message = message,
        severity = SkillValidationSeverity.ERROR,
        filePath = filePath,
    )
}

class SkillStaticValidator(
    private val policy: SkillValidationPolicy,
) {
    fun validate(bundle: SkillBundle): SkillValidationResult {
        val findings = mutableListOf<SkillValidationFinding>()

        bundle.files.forEach { file ->
            val content = file.contentAsText()
            RULES.forEach { rule ->
                if (rule.pattern.containsMatchIn(content)) {
                    findings += SkillValidationFinding(
                        code = rule.code,
                        message = rule.message,
                        severity = rule.severity,
                        filePath = file.normalizedPath,
                    )
                }
            }
        }

        return SkillValidationResult(findings)
    }

    private data class StaticRule(
        val code: String,
        val message: String,
        val severity: SkillValidationSeverity,
        val pattern: Regex,
    )

    private companion object {
        private val RULES = listOf(
            StaticRule(
                code = "static.prompt_injection",
                message = "Prompt injection pattern detected.",
                severity = SkillValidationSeverity.ERROR,
                pattern = Regex("""ignore\b.{0,80}\b(previous|prior|system|developer)\b.{0,40}\binstructions?\b""", RegexOption.IGNORE_CASE),
            ),
            StaticRule(
                code = "static.credential_exfiltration",
                message = "Credential exfiltration pattern detected.",
                severity = SkillValidationSeverity.ERROR,
                pattern = Regex("""(api[_ -]?key|token|secret|password).{0,80}(send|upload|exfiltrat|post|curl|wget)""", RegexOption.IGNORE_CASE),
            ),
            StaticRule(
                code = "static.private_key_reference",
                message = "Private key path reference detected.",
                severity = SkillValidationSeverity.ERROR,
                pattern = Regex("""(\.ssh|id_rsa|id_ed25519|known_hosts)""", RegexOption.IGNORE_CASE),
            ),
            StaticRule(
                code = "static.env_dump",
                message = "Environment dumping pattern detected.",
                severity = SkillValidationSeverity.ERROR,
                pattern = Regex("""(\bprintenv\b|\benv\b|/proc/self/environ|System\.getenv|process\.env)""", RegexOption.IGNORE_CASE),
            ),
            StaticRule(
                code = "static.destructive_command",
                message = "Destructive filesystem command detected.",
                severity = SkillValidationSeverity.ERROR,
                pattern = Regex("""rm\s+-rf|chmod\s+-R\s+777|mkfs\b|dd\s+if=.*\s+of=/dev/""", RegexOption.IGNORE_CASE),
            ),
            StaticRule(
                code = "static.network_exfiltration",
                message = "Suspicious network upload pattern detected.",
                severity = SkillValidationSeverity.ERROR,
                pattern = Regex("""(curl|wget).{0,120}(--data|--data-binary|--upload-file|-T\s|\b-F\b|--form|PUT\s+https?://|POST\s+https?://)""", RegexOption.IGNORE_CASE),
            ),
            StaticRule(
                code = "static.shell_obfuscation",
                message = "Shell obfuscation pattern detected.",
                severity = SkillValidationSeverity.ERROR,
                pattern = Regex("""(base64\s+-d|openssl\s+enc|python\s+-c).{0,120}\|\s*(sh|bash|zsh)""", RegexOption.IGNORE_CASE),
            ),
        )
    }
}

object SkillValidationReducer {
    fun reduce(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policy: SkillValidationPolicy,
        structural: SkillValidationResult,
        static: SkillValidationResult,
        llm: SkillLlmValidationVerdict,
        createdAt: Instant,
    ): SkillValidationRecord {
        val allFindings = structural.findings + static.findings + llm.findings
        val approved = !structural.hasHardReject &&
            !static.hasHardReject &&
            llm.decision == SkillLlmValidationDecision.APPROVE &&
            llm.confidence >= policy.minApprovalConfidence

        return SkillValidationRecord(
            userId = userId,
            skillId = skillId,
            bundleHash = bundleHash,
            status = if (approved) SkillValidationStatus.APPROVED else SkillValidationStatus.REJECTED,
            policyVersion = policy.policyVersion,
            validatorVersion = policy.validatorVersion,
            model = llm.model,
            reasons = llm.reasons,
            findings = allFindings,
            createdAt = createdAt,
        )
    }
}
