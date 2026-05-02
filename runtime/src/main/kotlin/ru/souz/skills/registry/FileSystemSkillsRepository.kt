package ru.souz.skills.registry

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
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.registry.SkillsRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProviderImpl
import ru.souz.llms.restJsonMapper
import ru.souz.paths.DefaultSouzPaths
import ru.souz.paths.SouzPaths
import ru.souz.skills.bundle.FileSystemSkillBundleLoader
import ru.souz.skills.filesystem.LocalSkillBundleFileSystem
import ru.souz.skills.filesystem.SkillBundleFsContext
import ru.souz.tool.files.FilesToolUtil
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

class FileSystemSkillsRepository(
    private val paths: SouzPaths = DefaultSouzPaths(),
    private val clock: Clock = Clock.systemUTC(),
    private val loader: FileSystemSkillBundleLoader = FileSystemSkillBundleLoader(
        fileSystem = LocalSkillBundleFileSystem(FilesToolUtil(SettingsProviderImpl(ConfigStore)))
    ),
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
        val skillRoot = SkillStoragePaths.skillRoot(paths, userId, bundle.skillId)
        val bundleRoot = skillRoot.resolve(BUNDLE_DIR_NAME)
        val metadataPath = skillRoot.resolve(STORED_SKILL_FILE_NAME)

        val createdAt = readStoredSkillOrNull(metadataPath)?.createdAt ?: clock.instant()
        val storedSkill = StoredSkill(
            userId = userId,
            skillId = bundle.skillId,
            manifest = bundle.manifest,
            bundleHash = SkillBundleHasher.hash(bundle),
            createdAt = createdAt,
        )

        val parent = skillRoot.parent ?: throw SkillBundleException("Skill storage root has no parent: $skillRoot")
        parent.createDirectories()
        val tempRoot = parent.resolve("${skillRoot.fileName}.tmp-${UUID.randomUUID()}")

        try {
            writeBundle(tempRoot.resolve(BUNDLE_DIR_NAME), bundle)
            writeStoredSkill(tempRoot.resolve(STORED_SKILL_FILE_NAME), storedSkill)
            replaceDirectory(tempRoot, skillRoot)
        } catch (error: Throwable) {
            deleteRecursively(tempRoot)
            throw error
        }

        storedSkill
    }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? {
        val metadata = getSkill(userId, skillId) ?: return null
        val bundleRoot = SkillStoragePaths.bundleRoot(paths, userId, skillId)
        if (!bundleRoot.exists() || !bundleRoot.isDirectory()) {
            return null
        }

        return loader.loadDirectory(
            context = SkillBundleFsContext(userId = userId),
            skillId = metadata.skillId,
            rawRoot = bundleRoot.toString(),
        )
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
        Files.writeString(
            path,
            restJsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(record),
        )
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

    private fun replaceDirectory(
        source: Path,
        destination: Path,
    ) {
        deleteRecursively(destination)
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
        private const val BUNDLE_DIR_NAME = "bundle"
        private const val STORED_SKILL_FILE_NAME = "stored-skill.json"
    }
}
