package ru.souz.skills.validation

import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationRepository
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.llms.restJsonMapper
import ru.souz.paths.DefaultSouzPaths
import ru.souz.paths.SouzPaths
import ru.souz.skills.registry.SkillStoragePaths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * File-backed [SkillValidationRepository] that stores one JSON record per
 * user, skill, bundle hash, and validation policy version.
 *
 * Records are written atomically when possible so validation outcomes survive
 * restarts and can be shared across turns.
 */
class FileSystemSkillValidationRepository(
    private val paths: SouzPaths = DefaultSouzPaths(),
    private val clock: Clock = Clock.systemUTC(),
    private val pathsResolver: (String) -> SouzPaths = { paths },
) : SkillValidationRepository {
    private val logger = LoggerFactory.getLogger(FileSystemSkillValidationRepository::class.java)

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = withContext(Dispatchers.IO) {
        readRecordOrNull(
            SkillStoragePaths.validationRecordPath(
                paths = pathsResolver(userId),
                userId = userId,
                skillId = skillId,
                policyVersion = policyVersion,
                bundleHash = bundleHash,
            )
        )
    }

    override suspend fun saveValidation(record: SkillValidationRecord) = withContext(Dispatchers.IO) {
        val path = SkillStoragePaths.validationRecordPath(
            paths = pathsResolver(record.userId),
            userId = record.userId,
            skillId = record.skillId,
            policyVersion = record.policyVersion,
            bundleHash = record.bundleHash,
        )
        writeRecord(path, record)
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
        val paths = pathsResolver(userId)
        val policyRoot = SkillStoragePaths.validationPolicyRoot(
            paths = paths,
            userId = userId,
            skillId = skillId,
            policyVersion = policyVersion,
        )
        if (!policyRoot.exists() || !policyRoot.isDirectory()) {
            return@withContext
        }

        policyRoot.listDirectoryEntries("*.json")
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

    private fun readRecordOrNull(path: Path): SkillValidationRecord? {
        if (!path.exists()) return null
        return runCatching {
            val stored: StoredSkillValidationRecord = restJsonMapper.readValue(path.readText())
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
            logger.warn("Failed to read validation record from {}: {}", path, error.message)
        }.getOrNull()
    }

    private fun writeRecord(
        path: Path,
        record: SkillValidationRecord,
    ) {
        val parent = path.parent ?: error("Validation record path has no parent: $path")
        parent.createDirectories()
        val tempPath = parent.resolve("${path.fileName}.tmp-${UUID.randomUUID()}")
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

        try {
            Files.writeString(
                tempPath,
                restJsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(stored),
            )
            try {
                Files.move(tempPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempPath)
        }
    }

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
