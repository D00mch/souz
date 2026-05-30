package ru.souz.agent.skills.validation

import ru.souz.agent.skills.bundle.SKILL_MD_PATH
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillPathNormalizer

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
