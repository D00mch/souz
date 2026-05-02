package ru.souz.skills.registry

import java.nio.file.Path
import java.time.Clock
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationRepository
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProviderImpl
import ru.souz.paths.DefaultSouzPaths
import ru.souz.skills.bundle.FileSystemSkillBundleLoader
import ru.souz.skills.filesystem.LocalSkillBundleFileSystem
import ru.souz.skills.validation.FileSystemSkillValidationRepository
import ru.souz.tool.files.FilesToolUtil

/**
 * Default local [SkillRegistryRepository] wiring for desktop and JVM hosts.
 *
 * It composes the filesystem-backed skill bundle store with the filesystem
 * validation store behind the shared registry interface.
 */
class LocalSkillRegistryRepository(
    stateRoot: Path = DefaultSouzPaths.defaultStateRoot(),
    clock: Clock = Clock.systemUTC(),
    private val skillsRepository: FileSystemSkillsRepository = FileSystemSkillsRepository(
        paths = DefaultSouzPaths(stateRoot = stateRoot),
        clock = clock,
        loader = FileSystemSkillBundleLoader(
            fileSystem = LocalSkillBundleFileSystem(FilesToolUtil(SettingsProviderImpl(ConfigStore))),
        ),
    ),
    private val validationRepository: SkillValidationRepository = FileSystemSkillValidationRepository(
        paths = DefaultSouzPaths(stateRoot = stateRoot),
    ),
) : SkillRegistryRepository {
    override suspend fun listSkills(userId: String): List<StoredSkill> = skillsRepository.listSkills(userId)

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? =
        skillsRepository.getSkill(userId, skillId)

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? =
        skillsRepository.getSkillByName(userId, name)

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill =
        skillsRepository.saveSkillBundle(userId, bundle)

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? =
        skillsRepository.loadSkillBundle(userId, skillId)

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = validationRepository.getValidation(userId, skillId, bundleHash, policyVersion)

    override suspend fun saveValidation(record: SkillValidationRecord) =
        validationRepository.saveValidation(record)

    override suspend fun markValidationStatus(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
        status: SkillValidationStatus,
        reason: String?,
    ) = validationRepository.markValidationStatus(userId, skillId, bundleHash, policyVersion, status, reason)

    override suspend fun invalidateOtherValidations(
        userId: String,
        skillId: SkillId,
        activeBundleHash: String,
        policyVersion: String,
        reason: String?,
    ) = validationRepository.invalidateOtherValidations(userId, skillId, activeBundleHash, policyVersion, reason)
}
