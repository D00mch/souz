package ru.souz.skills.registry

import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.FileAlreadyExistsException
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
import ru.souz.paths.SouzPaths
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.skills.bundle.FileSystemSkillBundleLoader
import ru.souz.skills.filesystem.SkillBundleFsContext

/**
 * Filesystem-backed [SkillsRepository] that persists stored skill metadata and
 * immutable bundle contents under the configured Souz state directories.
 *
 * All filesystem access goes through [SandboxFileSystem], so the same storage
 * code works for both local and Docker-backed runtime paths.
 */
class FileSystemSkillsRepository(
    private val paths: SouzPaths,
    private val fileSystem: SandboxFileSystem,
    private val loader: FileSystemSkillBundleLoader,
    private val clock: Clock = Clock.systemUTC(),
) : SkillsRepository {
    private val logger = LoggerFactory.getLogger(FileSystemSkillsRepository::class.java)

    override suspend fun listSkills(userId: String): List<StoredSkill> = withContext(Dispatchers.IO) {
        val userRoot = resolvePath(SkillStoragePaths.userSkillsRoot(paths, userId))
        if (!userRoot.exists || !userRoot.isDirectory) {
            return@withContext emptyList()
        }

        fileSystem.listDescendants(
            root = userRoot,
            maxDepth = 1,
            includeHidden = true,
        )
            .filter { it.isDirectory && it.parentPath == userRoot.path }
            .mapNotNull { skillRoot ->
                readStoredSkillOrNull(resolveChildPath(skillRoot, STORED_SKILL_FILE_NAME))
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
        val skillRoot = resolvePath(SkillStoragePaths.skillRoot(paths, userId, normalizedBundle.skillId))
        val metadataPath = resolvePath(SkillStoragePaths.metadataPath(paths, userId, normalizedBundle.skillId))
        val bundleRoot = resolvePath(SkillStoragePaths.bundleRoot(paths, userId, normalizedBundle.skillId, bundleHash))

        val createdAt = readStoredSkillOrNull(metadataPath)?.createdAt ?: clock.instant()
        val storedSkill = StoredSkill(
            userId = userId,
            skillId = normalizedBundle.skillId,
            manifest = normalizedBundle.manifest,
            bundleHash = bundleHash,
            createdAt = createdAt,
        )

        fileSystem.createDirectory(skillRoot)
        writeBundleIfMissing(bundleRoot, normalizedBundle)
        writeStoredSkill(metadataPath, storedSkill)

        storedSkill
    }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? = withContext(Dispatchers.IO) {
        val metadata = getSkill(userId, skillId) ?: return@withContext null
        val bundleRoot = resolvePath(SkillStoragePaths.bundleRoot(paths, userId, skillId, metadata.bundleHash))
        if (!bundleRoot.exists || !bundleRoot.isDirectory) {
            return@withContext null
        }

        loader.loadDirectory(
            context = SkillBundleFsContext(userId = userId),
            skillId = metadata.skillId,
            rawRoot = bundleRoot.path,
        )
    }

    private fun writeBundleIfMissing(
        bundleRoot: SandboxPathInfo,
        bundle: SkillBundle,
    ) {
        if (bundleRoot.exists) return

        val parentPath = bundleRoot.parentPath
            ?: throw SkillBundleException("Bundle storage root has no parent: ${bundleRoot.path}")
        val parent = fileSystem.resolvePath(parentPath)
        fileSystem.createDirectory(parent)
        val tempRoot = fileSystem.resolvePath(childPath(parent.path, "${bundleRoot.name}.tmp-${UUID.randomUUID()}"))

        try {
            writeBundle(tempRoot, bundle)
            moveDirectory(refresh(tempRoot), refresh(bundleRoot))
        } catch (_: FileAlreadyExistsException) {
            deleteRecursively(refresh(tempRoot))
        } catch (error: Throwable) {
            deleteRecursively(refresh(tempRoot))
            throw error
        }
    }

    private fun writeBundle(
        bundleRoot: SandboxPathInfo,
        bundle: SkillBundle,
    ) {
        fileSystem.createDirectory(bundleRoot)
        val bundleRootPath = Path.of(bundleRoot.path).normalize()
        bundle.files.forEach { file ->
            val targetPath = bundleRootPath.resolve(file.normalizedPath).normalize()
            if (!targetPath.startsWith(bundleRootPath)) {
                throw SkillBundleException("Skill file path escapes bundle root: ${file.normalizedPath}")
            }
            fileSystem.writeBytes(fileSystem.resolvePath(targetPath.toString()), file.content)
        }
    }

    private fun writeStoredSkill(
        path: SandboxPathInfo,
        storedSkill: StoredSkill,
    ) {
        val record = StoredSkillRecord(
            userId = storedSkill.userId,
            skillId = storedSkill.skillId.value,
            manifest = storedSkill.manifest,
            bundleHash = storedSkill.bundleHash,
            createdAt = storedSkill.createdAt.toString(),
        )
        fileSystem.writeTextAtomically(
            path = path,
            content = restJsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(record),
            logger = logger,
        )
    }

    private fun readStoredSkillOrNull(path: Path): StoredSkill? =
        readStoredSkillOrNull(resolvePath(path))

    private fun readStoredSkillOrNull(path: SandboxPathInfo): StoredSkill? {
        if (!path.exists || !path.isRegularFile) return null
        return runCatching {
            val record: StoredSkillRecord = restJsonMapper.readValue(fileSystem.readText(path))
            StoredSkill(
                userId = record.userId,
                skillId = SkillId(record.skillId),
                manifest = record.manifest,
                bundleHash = record.bundleHash,
                createdAt = Instant.parse(record.createdAt),
            )
        }.onFailure { error ->
            logger.warn("Failed to read stored skill metadata from {}: {}", path.path, error.message)
        }.getOrNull()
    }

    private fun moveDirectory(
        source: SandboxPathInfo,
        destination: SandboxPathInfo,
    ) {
        fileSystem.move(
            source = source,
            destination = destination,
            logger = logger,
        )
    }

    private fun deleteRecursively(path: SandboxPathInfo) {
        runCatching {
            fileSystem.delete(path, recursively = true)
        }.onFailure { error ->
            logger.warn("Failed to clean up temporary skill bundle directory {}: {}", path.path, error.message)
        }
    }

    private fun resolvePath(path: Path): SandboxPathInfo = fileSystem.resolvePath(path.toString())

    private fun resolveChildPath(parent: SandboxPathInfo, child: String): SandboxPathInfo =
        fileSystem.resolvePath(childPath(parent.path, child))

    private fun refresh(path: SandboxPathInfo): SandboxPathInfo =
        fileSystem.resolvePath(path.path)

    private fun childPath(parent: String, child: String): String =
        Path.of(parent).resolve(child).normalize().toString()

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
