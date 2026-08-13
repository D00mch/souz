package ru.souz.backend.skills

import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.bundle.SkillFile

private const val MAX_SKILL_FILES = 64
internal const val MAX_STORED_SKILL_FILE_BYTES = 128 * 1024
private const val MAX_STORED_SKILL_BUNDLE_BYTES = 512 * 1024
private val safePathSegmentPattern = Regex("^[A-Za-z0-9._-]+$")
private val bundleHashPattern = Regex("^[a-fA-F0-9]{64}$")

internal fun requireSkillUserId(userId: String) {
    require(userId.isNotBlank()) { "User ID must not be blank." }
}

internal fun requireSafeSkillId(skillId: SkillId) {
    require(
        skillId.value.matches(safePathSegmentPattern) &&
            skillId.value != "." &&
            skillId.value != ".."
    ) {
        "SkillId must contain only letters, digits, '.', '_', or '-'."
    }
}

internal fun normalizeSkillBundleHash(bundleHash: String): String {
    require(bundleHash.matches(bundleHashPattern)) {
        "Skill bundle hash must be a 64-character hex SHA-256 string."
    }
    return bundleHash.lowercase()
}

internal fun requireSkillValidationPolicyVersion(policyVersion: String) {
    require(policyVersion.isNotBlank()) { "Policy version must not be blank." }
    require(!policyVersion.startsWith('/')) { "Policy version must be relative." }
    val segments = policyVersion.split('/')
    require(segments.all { it.isNotEmpty() }) {
        "Policy version must not contain empty segments."
    }
    segments.forEach { segment ->
        require(
            segment.matches(safePathSegmentPattern) &&
                segment != "." &&
                segment != ".."
        ) {
            "Policy version segments may contain only letters, digits, '.', '_', or '-'."
        }
    }
}

internal fun validateAndCopySkillBundle(bundle: SkillBundle): SkillBundle {
    requireSafeSkillId(bundle.skillId)
    val copy = copySkillBundle(bundle)
    if (copy.files.size > MAX_SKILL_FILES) {
        throw SkillBundleException(
            "Too many files in skill bundle: ${copy.files.size}. Max allowed: $MAX_SKILL_FILES"
        )
    }

    var totalBytes = 0L
    copy.files.forEach { file ->
        if (file.content.size > MAX_STORED_SKILL_FILE_BYTES) {
            throw SkillBundleException(
                "Skill file exceeds the max allowed size of " +
                    "$MAX_STORED_SKILL_FILE_BYTES bytes: ${file.normalizedPath}"
            )
        }
        totalBytes += file.content.size.toLong()
        if (totalBytes > MAX_STORED_SKILL_BUNDLE_BYTES.toLong()) {
            throw SkillBundleException(
                "Skill bundle exceeds the max allowed size of " +
                    "$MAX_STORED_SKILL_BUNDLE_BYTES bytes."
            )
        }
    }
    return copy
}

internal fun copySkillBundle(bundle: SkillBundle): SkillBundle = SkillBundle.fromFiles(
    skillId = bundle.skillId,
    files = bundle.files.map { file ->
        SkillFile(file.normalizedPath, file.content.copyOf())
    },
)
