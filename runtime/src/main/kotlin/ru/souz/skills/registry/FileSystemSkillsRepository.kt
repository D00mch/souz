package ru.souz.skills.registry

import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.registry.SkillsRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.llms.restJsonMapper
import ru.souz.paths.DefaultSouzPaths
import ru.souz.paths.SouzPaths
import ru.souz.skills.bundle.FileSystemSkillBundleLoader
import ru.souz.skills.filesystem.SkillBundleFsContext
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Filesystem-backed [SkillsRepository] that persists stored skill metadata and
 * immutable bundle contents under the configured Souz state directories.
 *
 * Bundles are normalized, hashed, and written via temporary locations so saved
 * skill state remains stable across concurrent reads and process restarts.
 */
class FileSystemSkillsRepository(
    private val paths: SouzPaths = DefaultSouzPaths(),
    private val clock: Clock = Clock.systemUTC(),
    private val loader: FileSystemSkillBundleLoader,
) : SkillsRepository {
    private val logger = LoggerFactory.getLogger(FileSystemSkillsRepository::class.java)

    override suspend fun listSkills(userId: String): List<StoredSkill> = withContext(Dispatchers.IO) {
        val userRoot = SkillStoragePaths.userSkillsRoot(paths, userId)
        if (!userRoot.exists() || !userRoot.isDirectory()) {
            return@withContext emptyList()
        }

        userRoot.listDirectoryEntries()
            .filter { it.isDirectory() }
            .mapNotNull { skillRoot ->
                readStoredSkillOrNull(skillRoot.resolve(STORED_SKILL_FILE_NAME))
            }
            .sortedBy { it.skillId.value }
    }

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? = withContext(Dispatchers.IO) {
        readStoredSkillOrNull(SkillStoragePaths.metadataPath(paths, userId, skillId))
    }

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? =
        listSkills(userId).firstOrNull { it.manifest.name == name }

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill = withContext(Dispatchers.IO) {
        val normalizedBundle = SkillBundle.fromFiles(bundle.skillId, bundle.files)
        val bundleHash = SkillBundleHasher.hash(normalizedBundle)
        val skillRoot = SkillStoragePaths.skillRoot(paths, userId, normalizedBundle.skillId)
        val metadataPath = SkillStoragePaths.metadataPath(paths, userId, normalizedBundle.skillId)
        val bundleRoot = SkillStoragePaths.bundleRoot(paths, userId, normalizedBundle.skillId, bundleHash)

        val createdAt = readStoredSkillOrNull(metadataPath)?.createdAt ?: clock.instant()
        val storedSkill = StoredSkill(
            userId = userId,
            skillId = normalizedBundle.skillId,
            manifest = normalizedBundle.manifest,
            bundleHash = bundleHash,
            createdAt = createdAt,
        )

        skillRoot.createDirectories()
        writeBundleIfMissing(bundleRoot, normalizedBundle)
        writeStoredSkill(metadataPath, storedSkill)

        storedSkill
    }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? {
        val metadata = getSkill(userId, skillId) ?: return null
        val bundleRoot = SkillStoragePaths.bundleRoot(paths, userId, skillId, metadata.bundleHash)
        if (!bundleRoot.exists() || !bundleRoot.isDirectory()) {
            return null
        }

        return loader.loadDirectory(
            context = SkillBundleFsContext(userId = userId),
            skillId = metadata.skillId,
            rawRoot = bundleRoot.toString(),
        )
    }

    private fun writeBundleIfMissing(
        bundleRoot: Path,
        bundle: SkillBundle,
    ) {
        if (bundleRoot.exists()) return

        val parent = bundleRoot.parent ?: throw SkillBundleException("Bundle storage root has no parent: $bundleRoot")
        parent.createDirectories()
        val tempRoot = parent.resolve("${bundleRoot.fileName}.tmp-${UUID.randomUUID()}")

        try {
            writeBundle(tempRoot, bundle)
            moveDirectory(tempRoot, bundleRoot)
        } catch (_: FileAlreadyExistsException) {
            deleteRecursively(tempRoot)
        } catch (error: Throwable) {
            deleteRecursively(tempRoot)
            throw error
        }
    }

    private fun writeBundle(
        bundleRoot: Path,
        bundle: SkillBundle,
    ) {
        bundleRoot.createDirectories()
        bundle.files.forEach { file ->
            val target = bundleRoot.resolve(file.normalizedPath)
            val parent = target.parent ?: bundleRoot
            parent.createDirectories()
            Files.write(target, file.content)
        }
    }

    private fun writeStoredSkill(
        path: Path,
        storedSkill: StoredSkill,
    ) {
        val record = StoredSkillRecord(
            userId = storedSkill.userId,
            skillId = storedSkill.skillId.value,
            manifest = storedSkill.manifest,
            bundleHash = storedSkill.bundleHash,
            createdAt = storedSkill.createdAt.toString(),
        )
        val parent = path.parent ?: throw SkillBundleException("Stored skill metadata path has no parent: $path")
        parent.createDirectories()
        val tempPath = parent.resolve("${path.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.writeString(
                tempPath,
                restJsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(record),
            )
            try {
                Files.move(
                    tempPath,
                    path,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempPath)
        }
    }

    private fun readStoredSkillOrNull(path: Path): StoredSkill? {
        if (!path.exists()) return null
        return runCatching {
            val record: StoredSkillRecord = restJsonMapper.readValue(path.readText())
            StoredSkill(
                userId = record.userId,
                skillId = SkillId(record.skillId),
                manifest = record.manifest,
                bundleHash = record.bundleHash,
                createdAt = Instant.parse(record.createdAt),
            )
        }.onFailure { error ->
            logger.warn("Failed to read stored skill metadata from {}: {}", path, error.message)
        }.getOrNull()
    }

    private fun moveDirectory(
        source: Path,
        destination: Path,
    ) {
        try {
            Files.move(source, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { current ->
                Files.deleteIfExists(current)
            }
        }
    }

    private data class StoredSkillRecord(
        val userId: String,
        val skillId: String,
        val manifest: ru.souz.agent.skills.bundle.SkillManifest,
        val bundleHash: String,
        val createdAt: String,
    )

    private companion object {
        private const val STORED_SKILL_FILE_NAME = "stored-skill.json"
    }
}
