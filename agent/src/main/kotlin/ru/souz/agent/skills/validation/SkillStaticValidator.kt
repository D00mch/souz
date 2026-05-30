package ru.souz.agent.skills.validation

import ru.souz.agent.skills.bundle.SkillBundle

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
                pattern = Regex(
                    """ignore\b.{0,80}\b(previous|prior|system|developer)\b.{0,40}\binstructions?\b""",
                    RegexOption.IGNORE_CASE
                ),
            ),
            StaticRule(
                code = "static.credential_exfiltration",
                message = "Credential exfiltration pattern detected.",
                severity = SkillValidationSeverity.ERROR,
                pattern = Regex(
                    """(api[_ -]?key|token|secret|password).{0,80}(send|upload|exfiltrat|post|curl|wget)""",
                    RegexOption.IGNORE_CASE
                ),
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
                pattern = Regex(
                    """(\bprintenv\b|\benv\b|/proc/self/environ|System\.getenv|process\.env)""",
                    RegexOption.IGNORE_CASE
                ),
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
                pattern = Regex(
                    """(curl|wget).{0,120}(--data|--data-binary|--upload-file|-T\s|\b-F\b|--form|PUT\s+https?://|POST\s+https?://)""",
                    RegexOption.IGNORE_CASE
                ),
            ),
            StaticRule(
                code = "static.shell_obfuscation",
                message = "Shell obfuscation pattern detected.",
                severity = SkillValidationSeverity.ERROR,
                pattern = Regex(
                    """(base64\s+-d|openssl\s+enc|python\s+-c).{0,120}\|\s*(sh|bash|zsh)""",
                    RegexOption.IGNORE_CASE
                ),
            ),
        )
    }
}
