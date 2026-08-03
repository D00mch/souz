package ru.souz.backend.client

import java.time.Instant
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationStatus

internal fun bundledClientSkillRegistry(
    delegate: SkillRegistryRepository,
): SkillRegistryRepository = BundledClientSkillRegistryRepository(
    delegate = delegate,
    bundledSkills = loadBundledClientSkills(),
)

private fun loadBundledClientSkills(): List<SkillBundle> {
    val classLoader = BackendClientToolCatalogFactory::class.java.classLoader
    val entries = requireNotNull(classLoader.getResourceAsStream(CLIENT_SKILL_INDEX)) {
        "Missing bundled client Skill index: $CLIENT_SKILL_INDEX"
    }.bufferedReader().useLines { lines ->
        lines
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
    }

    return entries.map { entry ->
        require('/' !in entry && '\\' !in entry) { "Invalid bundled client Skill entry: $entry" }
        val resourcePath = "$CLIENT_SKILL_ROOT/$entry/SKILL.md"
        val content = requireNotNull(classLoader.getResourceAsStream(resourcePath)) {
            "Missing bundled client Skill resource: $resourcePath"
        }.use { it.readBytes() }
        val provisional = SkillBundle.fromFiles(
            skillId = SkillId(entry),
            files = listOf(SkillFile(normalizedPath = "SKILL.md", content = content)),
        )
        val skillId = provisional.manifest.metadata[CLIENT_SKILL_ID_METADATA]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("Bundled client Skill $entry is missing metadata.$CLIENT_SKILL_ID_METADATA")
        require(provisional.manifest.metadata[CLIENT_SKILL_TRANSPORT_METADATA] == CLIENT_SKILL_TRANSPORT) {
            "Bundled client Skill $entry has invalid metadata.$CLIENT_SKILL_TRANSPORT_METADATA"
        }
        SkillBundle.fromFiles(
            skillId = SkillId(skillId),
            files = provisional.files,
        )
    }.also { skills ->
        val duplicate = skills.groupingBy { it.skillId }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicate == null) { "Duplicate bundled client Skill ID: ${duplicate?.key}" }
    }
}

private class BundledClientSkillRegistryRepository(
    private val delegate: SkillRegistryRepository,
    bundledSkills: List<SkillBundle>,
) : SkillRegistryRepository {
    private val bundledById = bundledSkills.associateBy { it.skillId }

    override suspend fun listSkills(userId: String): List<StoredSkill> {
        val delegated = delegate.listSkills(userId)
        val delegatedIds = delegated.mapTo(hashSetOf()) { it.skillId }
        return (delegated + bundledById.values
            .filterNot { it.skillId in delegatedIds }
            .map { it.toStoredSkill(userId) })
            .sortedBy { it.skillId.value }
    }

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? =
        delegate.getSkill(userId, skillId) ?: bundledById[skillId]?.toStoredSkill(userId)

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? =
        delegate.getSkillByName(userId, name)
            ?: bundledById.values.firstOrNull { it.manifest.name == name }?.toStoredSkill(userId)

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill =
        delegate.saveSkillBundle(userId, bundle)

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? =
        if (delegate.getSkill(userId, skillId) != null) {
            delegate.loadSkillBundle(userId, skillId)
        } else {
            bundledById[skillId]
        }

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = delegate.getValidation(userId, skillId, bundleHash, policyVersion)

    override suspend fun saveValidation(record: SkillValidationRecord) = delegate.saveValidation(record)

    override suspend fun markValidationStatus(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
        status: SkillValidationStatus,
        reason: String?,
    ) = delegate.markValidationStatus(userId, skillId, bundleHash, policyVersion, status, reason)

    override suspend fun invalidateOtherValidations(
        userId: String,
        skillId: SkillId,
        activeBundleHash: String,
        policyVersion: String,
        reason: String?,
    ) = delegate.invalidateOtherValidations(userId, skillId, activeBundleHash, policyVersion, reason)

    private fun SkillBundle.toStoredSkill(userId: String): StoredSkill = StoredSkill(
        userId = userId,
        skillId = skillId,
        manifest = manifest,
        bundleHash = SkillBundleHasher.hash(this),
        createdAt = Instant.EPOCH,
    )
}

internal const val CLIENT_SKILL_TRANSPORT_METADATA = "souz.transport"
internal const val CLIENT_SKILL_TRANSPORT = "client-websocket"
internal const val CLIENT_SKILL_CATEGORY_METADATA = "souz.category"
internal const val CLIENT_SKILL_TIMEOUT_METADATA = "souz.timeout"

private const val CLIENT_SKILL_ID_METADATA = "souz.skill-id"
private const val CLIENT_SKILL_ROOT = "skills/client"
private const val CLIENT_SKILL_INDEX = "$CLIENT_SKILL_ROOT/index.txt"
