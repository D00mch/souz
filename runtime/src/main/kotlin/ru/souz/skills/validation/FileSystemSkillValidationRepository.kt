package ru.souz.skills.validation

import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationRepository
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.llms.restJsonMapper
import ru.souz.paths.SouzPaths
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.skills.registry.SkillStoragePaths

/**
 * File-backed [SkillValidationRepository] that stores one JSON record per
 * user, skill, bundle hash, and validation policy version.
 *
 * Reads and writes go through [SandboxFileSystem] so validation state follows
 * the same runtime-visible paths as skill bundles.
 */
class FileSystemSkillValidationRepository(
    private val paths: SouzPaths,
    private val fileSystem: SandboxFileSystem,
    private val clock: Clock = Clock.systemUTC(),
) : SkillValidationRepository {
    private val logger = LoggerFactory.getLogger(FileSystemSkillValidationRepository::class.java)

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = withContext(Dispatchers.IO) {
        readRecordOrNull(
            resolvePath(
                SkillStoragePaths.validationRecordPath(
                    paths = paths,
                    userId = userId,
                    skillId = skillId,
                    policyVersion = policyVersion,
                    bundleHash = bundleHash,
                )
            )
        )
    }

    override suspend fun saveValidation(record: SkillValidationRecord) = withContext(Dispatchers.IO) {
        writeRecord(
            path = resolvePath(
                SkillStoragePaths.validationRecordPath(
                    paths = paths,
                    userId = record.userId,
                    skillId = record.skillId,
                    policyVersion = record.policyVersion,
                    bundleHash = record.bundleHash,
                )
            ),
            record = record,
        )
    }

    override suspend fun markValidationStatus(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
        status: SkillValidationStatus,
        reason: String?,
    ) = withContext(Dispatchers.IO) {
        val current = getValidation(userId, skillId, bundleHash, policyVersion) ?: return@withContext
        saveValidation(
            current.copy(
                status = status,
                reasons = current.reasons + listOfNotNull(reason),
            )
        )
    }

    override suspend fun invalidateOtherValidations(
        userId: String,
        skillId: SkillId,
        activeBundleHash: String,
        policyVersion: String,
        reason: String?,
    ) = withContext(Dispatchers.IO) {
        val policyRoot = resolvePath(
            SkillStoragePaths.validationPolicyRoot(
                paths = paths,
                userId = userId,
                skillId = skillId,
                policyVersion = policyVersion,
            )
        )
        if (!policyRoot.exists || !policyRoot.isDirectory) {
            return@withContext
        }

        fileSystem.listDescendants(
            root = policyRoot,
            maxDepth = 1,
            includeHidden = true,
        )
            .filter { it.isRegularFile && it.parentPath == policyRoot.path && it.name.endsWith(".json") }
            .forEach { path ->
                val record = readRecordOrNull(path) ?: return@forEach
                if (record.bundleHash == activeBundleHash || record.status != SkillValidationStatus.APPROVED) {
                    return@forEach
                }
                writeRecord(
                    path = path,
                    record = record.copy(
                        status = SkillValidationStatus.STALE,
                        reasons = record.reasons + listOfNotNull(reason),
                    ),
                )
            }
    }

    private fun readRecordOrNull(path: SandboxPathInfo): SkillValidationRecord? {
        if (!path.exists || !path.isRegularFile) return null
        return runCatching {
            val stored: StoredSkillValidationRecord = restJsonMapper.readValue(fileSystem.readText(path))
            SkillValidationRecord(
                userId = stored.userId,
                skillId = SkillId(stored.skillId),
                bundleHash = stored.bundleHash,
                status = SkillValidationStatus.valueOf(stored.status),
                policyVersion = stored.policyVersion,
                validatorVersion = stored.validatorVersion,
                model = stored.model,
                reasons = stored.reasons,
                findings = stored.findings,
                createdAt = Instant.parse(stored.createdAt),
            )
        }.onFailure { error ->
            logger.warn("Failed to read validation record from {}: {}", path.path, error.message)
        }.getOrNull()
    }

    private fun writeRecord(
        path: SandboxPathInfo,
        record: SkillValidationRecord,
    ) {
        val stored = StoredSkillValidationRecord(
            userId = record.userId,
            skillId = record.skillId.value,
            bundleHash = record.bundleHash,
            status = record.status.name,
            policyVersion = record.policyVersion,
            validatorVersion = record.validatorVersion,
            model = record.model,
            reasons = record.reasons,
            findings = record.findings,
            createdAt = record.createdAt.toString(),
            updatedAt = clock.instant().toString(),
        )
        fileSystem.writeTextAtomically(
            path = path,
            content = restJsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(stored),
            logger = logger,
        )
    }

    private fun resolvePath(path: Path): SandboxPathInfo = fileSystem.resolvePath(path.toString())

    private data class StoredSkillValidationRecord(
        val userId: String,
        val skillId: String,
        val bundleHash: String,
        val status: String,
        val policyVersion: String,
        val validatorVersion: String,
        val model: String?,
        val reasons: List<String> = emptyList(),
        val findings: List<SkillValidationFinding> = emptyList(),
        val createdAt: String,
        val updatedAt: String,
    )
}
