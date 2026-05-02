package ru.souz.skills.registry

import java.nio.charset.StandardCharsets
import java.util.Base64
import ru.souz.agent.skills.activation.SkillId
import ru.souz.paths.SouzPaths
import java.nio.file.Path

internal object SkillStoragePaths {
    fun userSkillsRoot(
        paths: SouzPaths,
        userId: String,
    ): Path = paths.skillsDir
        .resolve("users")
        .resolve(encodeSegment(userId))
        .resolve("skills")

    fun skillRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
    ): Path = userSkillsRoot(paths, userId)
        .resolve(encodeSegment(skillId.value))

    fun bundleDirectoryRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
    ): Path = skillRoot(paths, userId, skillId).resolve("bundles")

    fun bundleRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
        bundleHash: String,
    ): Path = bundleDirectoryRoot(paths, userId, skillId).resolve(requireSafeBundleHash(bundleHash))

    fun metadataPath(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
    ): Path = skillRoot(paths, userId, skillId).resolve("stored-skill.json")

    fun validationPolicyRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
        policyVersion: String,
    ): Path = paths.skillValidationsDir
        .resolve("users")
        .resolve(encodeSegment(userId))
        .resolve("skills")
        .resolve(encodeSegment(skillId.value))
        .resolve("policies")
        .resolve(encodeSegment(policyVersion))

    fun validationRecordPath(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
        policyVersion: String,
        bundleHash: String,
    ): Path = validationPolicyRoot(paths, userId, skillId, policyVersion)
        .resolve("${requireSafeBundleHash(bundleHash)}.json")

    private fun requireSafeBundleHash(bundleHash: String): String {
        require(bundleHash.matches(BUNDLE_HASH_REGEX)) {
            "Skill bundle hash must be a 64-character hex SHA-256 string."
        }
        return bundleHash
    }

    private fun encodeSegment(raw: String): String {
        require(raw.isNotBlank()) { "Storage path segment must not be blank." }
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private val BUNDLE_HASH_REGEX = Regex("^[a-fA-F0-9]{64}$")
}
