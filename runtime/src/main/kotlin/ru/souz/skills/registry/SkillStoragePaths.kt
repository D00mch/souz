package ru.souz.skills.registry

import java.nio.charset.StandardCharsets
import java.util.Base64
import ru.souz.agent.skills.activation.SkillId
import ru.souz.paths.SouzPaths

internal object SkillStoragePaths {
    fun userSkillsRoot(
        paths: SouzPaths,
        userId: String,
    ) = paths.skillsDir
        .resolve("users")
        .resolve(encodeSegment(userId))
        .resolve("skills")

    fun skillRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
    ) = userSkillsRoot(paths, userId)
        .resolve(encodeSegment(skillId.value))

    fun bundleRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
    ) = skillRoot(paths, userId, skillId).resolve("bundle")

    fun metadataPath(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
    ) = skillRoot(paths, userId, skillId).resolve("stored-skill.json")

    fun validationPolicyRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
        policyVersion: String,
    ) = paths.skillValidationsDir
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
    ) = validationPolicyRoot(paths, userId, skillId, policyVersion).resolve("$bundleHash.json")

    private fun encodeSegment(raw: String): String {
        require(raw.isNotBlank()) { "Storage path segment must not be blank." }
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }
}
