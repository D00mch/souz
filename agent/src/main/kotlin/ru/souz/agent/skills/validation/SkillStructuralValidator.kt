package ru.souz.agent.skills.validation

import ru.souz.agent.skills.bundle.SKILL_MD_PATH
import ru.souz.agent.skills.bundle.SkillPathNormalizer

/**
 * Fast deterministic checks for bundle shape, normalized paths, and size limits.
 *
 * This validator runs before static pattern checks and LLM validation so malformed bundles fail
 * without sending their content to later validators.
 */
class SkillStructuralValidator : SkillValidator {
    override suspend fun validate(input: SkillValidationInput): List<SkillValidationFinding> {
        val findings = mutableListOf<SkillValidationFinding>()
        val bundle = input.bundle
        val policy = input.policy

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

        return findings
    }

    private fun error(code: String, message: String, filePath: String? = null) = SkillValidationFinding(
        code = code,
        message = message,
        level = SkillValidationLevel.ERROR,
        filePath = filePath,
    )
}
