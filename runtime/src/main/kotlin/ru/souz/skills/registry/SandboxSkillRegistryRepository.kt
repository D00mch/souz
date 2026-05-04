package ru.souz.skills.registry

import java.time.Clock
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.llms.ToolInvocationMeta
import ru.souz.paths.SandboxSouzPaths
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.skills.bundle.FileSystemSkillBundleLoader
import ru.souz.skills.filesystem.SandboxSkillBundleFileSystem
import ru.souz.skills.validation.FileSystemSkillValidationRepository

/**
 * [SkillRegistryRepository] wired to runtime sandboxes so skill storage,
 * loading, and validation use the same filesystem view as tools.
 */
class SandboxSkillRegistryRepository(
    private val sandboxResolver: (String) -> RuntimeSandbox,
    private val clock: Clock = Clock.systemUTC(),
) : SkillRegistryRepository {
    constructor(
        sandbox: RuntimeSandbox,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        sandboxResolver = { sandbox },
        clock = clock,
    )

    constructor(
        sandboxResolver: ToolInvocationRuntimeSandboxResolver,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        sandboxResolver = { userId -> sandboxResolver.resolve(ToolInvocationMeta(userId = userId)) },
        clock = clock,
    )

    override suspend fun listSkills(userId: String): List<StoredSkill> =
        skillsRepository(userId).listSkills(userId)

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? =
        skillsRepository(userId).getSkill(userId, skillId)

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? =
        skillsRepository(userId).getSkillByName(userId, name)

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill =
        skillsRepository(userId).saveSkillBundle(userId, bundle)

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? =
        skillsRepository(userId).loadSkillBundle(userId, skillId)

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = validationRepository(userId)
        .getValidation(userId, skillId, bundleHash, policyVersion)

    override suspend fun saveValidation(record: SkillValidationRecord) =
        validationRepository(record.userId).saveValidation(record)

    override suspend fun markValidationStatus(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
        status: SkillValidationStatus,
        reason: String?,
    ) = validationRepository(userId)
        .markValidationStatus(userId, skillId, bundleHash, policyVersion, status, reason)

    override suspend fun invalidateOtherValidations(
        userId: String,
        skillId: SkillId,
        activeBundleHash: String,
        policyVersion: String,
        reason: String?,
    ) = validationRepository(userId)
        .invalidateOtherValidations(userId, skillId, activeBundleHash, policyVersion, reason)

    private fun skillsRepository(userId: String): FileSystemSkillsRepository {
        val sandbox = sandboxResolver(userId)
        return FileSystemSkillsRepository(
            paths = SandboxSouzPaths(sandbox.runtimePaths),
            fileSystem = sandbox.fileSystem,
            loader = FileSystemSkillBundleLoader(
                fileSystem = SandboxSkillBundleFileSystem(sandbox.fileSystem),
            ),
            clock = clock,
        )
    }

    private fun validationRepository(userId: String): FileSystemSkillValidationRepository {
        val sandbox = sandboxResolver(userId)
        return FileSystemSkillValidationRepository(
            paths = SandboxSouzPaths(sandbox.runtimePaths),
            fileSystem = sandbox.fileSystem,
            clock = clock,
        )
    }
}
