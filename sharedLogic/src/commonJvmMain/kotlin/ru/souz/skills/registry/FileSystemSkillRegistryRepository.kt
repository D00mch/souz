package ru.souz.skills.registry

import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillBundleParser
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationPolicy
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.paths.SandboxSouzPaths
import ru.souz.paths.SouzPaths
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.skills.bundle.FileSystemSkillBundleLoader
import ru.souz.skills.filesystem.SandboxSkillBundleFileSystem
import ru.souz.skills.filesystem.SkillBundleFsContext

/**
 * Filesystem-backed [SkillRegistryRepository] for skill metadata, immutable bundles,
 * and validation records.
 *
 * Filesystem access stays behind [RuntimeSandbox] so the same repository works for local
 * and Docker-backed runtime paths.
 */
class FileSystemSkillRegistryRepository(
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

    private val logger = LoggerFactory.getLogger(FileSystemSkillRegistryRepository::class.java)

    override suspend fun listSkills(userId: String): List<StoredSkill> = withContext(Dispatchers.IO) {
        val store = storeFor(userId)
        skillRoots(userId, store).mapNotNull { root ->
            readStoredSkillOrNull(store, store.resolveChildPath(root, STORED_SKILL_FILE_NAME))
                ?: readLooseStoredSkillOrNull(store, userId, root)
        }.sortedBy { it.skillId.value }
    }

    override suspend fun listSkillInventoryIds(userId: String): List<SkillId> = withContext(Dispatchers.IO) {
        val store = storeFor(userId)
        skillRoots(userId, store).mapNotNull { root ->
            readStoredSkillOrNull(store, store.resolveChildPath(root, STORED_SKILL_FILE_NAME))?.skillId
                ?: readLooseSkillInventoryIdOrNull(store, root)
        }.distinct().sortedBy { it.value }
    }

    override suspend fun listSkillDescriptions(userId: String): Map<SkillId, String> = withContext(Dispatchers.IO) {
        val store = storeFor(userId)
        val fileSystem = SandboxSkillBundleFileSystem(store.fileSystem)
        skillRoots(userId, store).mapNotNull { root ->
            try {
                val stored = readStoredSkillOrNull(store, store.resolveChildPath(root, STORED_SKILL_FILE_NAME))
                val id = stored?.skillId ?: readLooseSkillInventoryIdOrNull(store, root) ?: return@mapNotNull null
                val description = stored?.manifest?.description ?: SkillBundleParser.parseManifest(
                    fileSystem.readUtf8File(
                        SkillBundleFsContext(userId),
                        skillRoot(store.paths, id).resolve(SKILL_MARKDOWN_FILE_NAME),
                        SkillValidationPolicy.default().maxFileBytes.toLong(),
                    )
                ).description
                id to description
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.warn("Skill description unavailable skill={}: {}", root.name, error.message)
                null
            }
        }.toMap()
    }

    private fun skillRoots(userId: String, store: Store): List<SandboxPathInfo> {
        val root = store.resolvePath(store.paths.skillsDir)
        if (!root.exists || !root.isDirectory) {
            logSkillRootUnavailable(userId, store, root)
            return emptyList()
        }
        return store.fileSystem.listDescendants(root, maxDepth = 1, includeHidden = true)
            .filter { it.isDirectory && it.parentPath == root.path }
    }

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill = withContext(Dispatchers.IO) {
        val store = storeFor(userId)
        val normalizedBundle = SkillBundle.fromFiles(bundle.skillId, bundle.files)
        val bundleHash = SkillBundleHasher.hash(normalizedBundle)
        val skillRoot = store.resolvePath(skillRoot(store.paths, normalizedBundle.skillId))
        val metadataPath = store.resolvePath(metadataPath(store.paths, normalizedBundle.skillId))
        val bundleRoot = store.resolvePath(bundleRoot(store.paths, normalizedBundle.skillId, bundleHash))

        val createdAt = readStoredSkillOrNull(store, metadataPath)?.createdAt ?: clock.instant()
        val storedSkill = StoredSkill(
            userId = userId,
            skillId = normalizedBundle.skillId,
            manifest = normalizedBundle.manifest,
            bundleHash = bundleHash,
            createdAt = createdAt,
        )

        store.fileSystem.createDirectory(skillRoot)
        writeBundleIfMissing(store, bundleRoot, normalizedBundle)
        writeStoredSkill(store, metadataPath, storedSkill)
        logSkillSaved(userId, store, metadataPath, storedSkill)

        storedSkill
    }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? = withContext(Dispatchers.IO) {
        val store = storeFor(userId)
        val metadata = readStoredSkillOrNull(store, metadataPath(store.paths, skillId))
        val root = store.resolvePath(
            if (metadata == null) skillRoot(store.paths, skillId)
            else bundleRoot(store.paths, skillId, metadata.bundleHash),
        )
        val bundle = when {
            metadata == null -> loadLooseSkillBundleOrNull(store, userId, skillId, root)
            !root.exists || !root.isDirectory -> null
            else -> FileSystemSkillBundleLoader(SandboxSkillBundleFileSystem(store.fileSystem)).loadDirectory(
                context = SkillBundleFsContext(userId), skillId = skillId, rawRoot = root.path,
            )
        }
        logger.info(
            "Skill bundle lookup skill={} user={} sandboxMode={} root={} found={} files={}",
            skillId.value, userId, store.sandboxMode, root.path, bundle != null, bundle?.files?.size ?: 0,
        )
        bundle
    }

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = withContext(Dispatchers.IO) {
        val store = storeFor(userId)
        readValidationOrNull(
            store = store,
            path = store.resolvePath(
                validationRecordPath(
                    paths = store.paths,
                    skillId = skillId,
                    policyVersion = policyVersion,
                    bundleHash = bundleHash,
                )
            ),
        )
    }

    override suspend fun saveValidation(record: SkillValidationRecord) = withContext(Dispatchers.IO) {
        val store = storeFor(record.userId)
        val path = store.resolvePath(
            validationRecordPath(
                paths = store.paths,
                skillId = record.skillId,
                policyVersion = record.policyVersion,
                bundleHash = record.bundleHash,
            )
        )
        writeValidation(
            store = store,
            path = path,
            record = record,
        )
        logValidationSaved(store, path, record)
    }

    private fun writeBundleIfMissing(
        store: Store,
        bundleRoot: SandboxPathInfo,
        bundle: SkillBundle,
    ) {
        if (bundleRoot.exists) return

        val parentPath = bundleRoot.parentPath
            ?: throw SkillBundleException("Bundle storage root has no parent: ${bundleRoot.path}")
        val parent = store.fileSystem.resolvePath(parentPath)
        store.fileSystem.createDirectory(parent)
        val tempRoot = store.fileSystem.resolvePath(childPath(parent.path, "${bundleRoot.name}.tmp-${UUID.randomUUID()}"))

        try {
            writeBundle(store, tempRoot, bundle)
            moveDirectory(store, store.refresh(tempRoot), store.refresh(bundleRoot))
        } catch (_: FileAlreadyExistsException) {
            deleteRecursively(store, store.refresh(tempRoot))
        } catch (error: Throwable) {
            deleteRecursively(store, store.refresh(tempRoot))
            throw error
        }
    }

    private fun writeBundle(
        store: Store,
        bundleRoot: SandboxPathInfo,
        bundle: SkillBundle,
    ) {
        store.fileSystem.createDirectory(bundleRoot)
        val bundleRootPath = Path.of(bundleRoot.path).normalize()
        bundle.files.forEach { file ->
            val targetPath = bundleRootPath.resolve(file.normalizedPath).normalize()
            if (!targetPath.startsWith(bundleRootPath)) {
                throw SkillBundleException("Skill file path escapes bundle root: ${file.normalizedPath}")
            }
            store.fileSystem.writeBytes(store.fileSystem.resolvePath(targetPath.toString()), file.content)
        }
    }

    private fun writeStoredSkill(
        store: Store,
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
        store.fileSystem.writeTextAtomically(
            path = path,
            content = restJsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(record),
            logger = logger,
        )
    }

    private fun readStoredSkillOrNull(
        store: Store,
        path: Path,
    ): StoredSkill? = readStoredSkillOrNull(store, store.resolvePath(path))

    private fun readStoredSkillOrNull(
        store: Store,
        path: SandboxPathInfo,
    ): StoredSkill? {
        if (!path.exists || !path.isRegularFile) return null
        return runCatching {
            val record: StoredSkillRecord = restJsonMapper.readValue(store.fileSystem.readText(path))
            StoredSkill(
                userId = record.userId,
                skillId = SkillId(record.skillId),
                manifest = record.manifest,
                bundleHash = record.bundleHash,
                createdAt = Instant.parse(record.createdAt),
            )
        }.onFailure { error ->
            logStoredSkillMetadataReadFailed(path, error)
        }.getOrNull()
    }

    private suspend fun readLooseStoredSkillOrNull(
        store: Store,
        userId: String,
        skillRoot: SandboxPathInfo,
    ): StoredSkill? {
        val skillId = runCatching {
            SkillId(requireSafePathSegment(skillRoot.name, "SkillId"))
        }.getOrNull() ?: return null
        val bundle = try {
            loadLooseSkillBundleOrNull(store, userId, skillId, skillRoot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logLooseSkillBundleReadFailed(skillRoot, error)
            null
        } ?: return null
        return StoredSkill(
            userId = userId,
            skillId = skillId,
            manifest = bundle.manifest,
            bundleHash = SkillBundleHasher.hash(bundle),
            createdAt = LOOSE_SKILL_CREATED_AT,
        )
    }

    private fun readLooseSkillInventoryIdOrNull(
        store: Store,
        skillRoot: SandboxPathInfo,
    ): SkillId? {
        val skillId = runCatching {
            SkillId(requireSafePathSegment(skillRoot.name, "SkillId"))
        }.getOrNull() ?: return null
        if (!skillRoot.exists || !skillRoot.isDirectory) return null
        val skillMarkdown = store.resolveChildPath(skillRoot, SKILL_MARKDOWN_FILE_NAME)
        if (!skillMarkdown.exists || !skillMarkdown.isRegularFile) return null
        return skillId
    }

    private suspend fun loadLooseSkillBundleOrNull(
        store: Store,
        userId: String,
        skillId: SkillId,
        skillRoot: SandboxPathInfo,
    ): SkillBundle? {
        if (!skillRoot.exists || !skillRoot.isDirectory) return null
        val skillMarkdown = store.resolveChildPath(skillRoot, SKILL_MARKDOWN_FILE_NAME)
        if (!skillMarkdown.exists || !skillMarkdown.isRegularFile) return null

        return FileSystemSkillBundleLoader(SandboxSkillBundleFileSystem(store.fileSystem)).loadDirectory(
            context = SkillBundleFsContext(userId),
            skillId = skillId,
            rawRoot = skillRoot.path,
        )
    }

    private fun readValidationOrNull(
        store: Store,
        path: SandboxPathInfo,
    ): SkillValidationRecord? {
        if (!path.exists || !path.isRegularFile) return null
        return runCatching {
            val stored: StoredSkillValidationRecord = restJsonMapper.readValue(store.fileSystem.readText(path))
            SkillValidationRecord(
                userId = stored.userId,
                skillId = SkillId(stored.skillId),
                bundleHash = stored.bundleHash,
                policyVersion = stored.policyVersion,
                approved = stored.approved,
                findings = stored.findings,
                createdAt = Instant.parse(stored.createdAt),
            )
        }.onFailure { error ->
            logValidationRecordReadFailed(path, error)
        }.getOrNull()
    }

    private fun writeValidation(
        store: Store,
        path: SandboxPathInfo,
        record: SkillValidationRecord,
    ) {
        val stored = StoredSkillValidationRecord(
            userId = record.userId,
            skillId = record.skillId.value,
            bundleHash = record.bundleHash,
            policyVersion = record.policyVersion,
            approved = record.approved,
            findings = record.findings,
            createdAt = record.createdAt.toString(),
            updatedAt = clock.instant().toString(),
        )
        store.fileSystem.writeTextAtomically(
            path = path,
            content = restJsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(stored),
            logger = logger,
        )
    }

    private fun moveDirectory(
        store: Store,
        source: SandboxPathInfo,
        destination: SandboxPathInfo,
    ) {
        store.fileSystem.move(
            source = source,
            destination = destination,
            logger = logger,
        )
    }

    private fun deleteRecursively(
        store: Store,
        path: SandboxPathInfo,
    ) {
        runCatching {
            store.fileSystem.delete(path, recursively = true)
        }.onFailure { error ->
            logTemporaryBundleCleanupFailed(path, error)
        }
    }

    private fun storeFor(userId: String): Store {
        val sandbox = sandboxResolver(userId)
        return Store(
            paths = SandboxSouzPaths(sandbox.runtimePaths),
            fileSystem = sandbox.fileSystem,
            sandboxMode = sandbox.mode.name,
        )
    }

    private fun skillRoot(
        paths: SouzPaths,
        skillId: SkillId,
    ): Path = paths.skillsDir
        .resolve(requireSafePathSegment(skillId.value, "SkillId"))

    private fun bundleRoot(
        paths: SouzPaths,
        skillId: SkillId,
        bundleHash: String,
    ): Path = skillRoot(paths, skillId)
        .resolve(BUNDLES_DIRECTORY_NAME)
        .resolve(requireSafeBundleHash(bundleHash))

    private fun metadataPath(
        paths: SouzPaths,
        skillId: SkillId,
    ): Path = skillRoot(paths, skillId).resolve(STORED_SKILL_FILE_NAME)

    private fun validationPolicyRoot(
        paths: SouzPaths,
        skillId: SkillId,
        policyVersion: String,
    ): Path = paths.skillValidationsDir
        .resolve(requireSafePathSegment(skillId.value, "SkillId"))
        .resolve("policies")
        .resolve(requireSafeRelativePath(policyVersion, "Policy version"))

    private fun validationRecordPath(
        paths: SouzPaths,
        skillId: SkillId,
        policyVersion: String,
        bundleHash: String,
    ): Path = validationPolicyRoot(paths, skillId, policyVersion)
        .resolve("${requireSafeBundleHash(bundleHash)}.json")

    private fun childPath(parent: String, child: String): String =
        Path.of(parent).resolve(child).normalize().toString()

    private fun logSkillRootUnavailable(
        userId: String,
        store: Store,
        skillsRoot: SandboxPathInfo,
    ) {
        logger.info(
            "Skill registry root unavailable user={} sandboxMode={} root={} exists={} isDirectory={}",
            userId,
            store.sandboxMode,
            skillsRoot.path,
            skillsRoot.exists,
            skillsRoot.isDirectory,
        )
    }

    private fun logStoredSkillMetadataReadFailed(
        path: SandboxPathInfo,
        error: Throwable,
    ) {
        logger.warn("Failed to read stored skill metadata from {}: {}", path.path, error.message)
    }

    private fun logLooseSkillBundleReadFailed(
        path: SandboxPathInfo,
        error: Throwable,
    ) {
        logger.warn("Failed to read loose skill bundle from {}: {}", path.path, error.message)
    }

    private fun logValidationRecordReadFailed(
        path: SandboxPathInfo,
        error: Throwable,
    ) {
        logger.warn("Failed to read validation record from {}: {}", path.path, error.message)
    }

    private fun logTemporaryBundleCleanupFailed(
        path: SandboxPathInfo,
        error: Throwable,
    ) {
        logger.warn("Failed to clean up temporary skill bundle directory {}: {}", path.path, error.message)
    }

    private fun logSkillSaved(
        userId: String,
        store: Store,
        metadataPath: SandboxPathInfo,
        storedSkill: StoredSkill,
    ) {
        logger.info(
            "Skill registry saved skill={} user={} sandboxMode={} hash={} metadata={}",
            storedSkill.skillId.value,
            userId,
            store.sandboxMode,
            storedSkill.bundleHash.take(12),
            metadataPath.path,
        )
    }

    private fun logValidationSaved(
        store: Store,
        path: SandboxPathInfo,
        record: SkillValidationRecord,
    ) {
        logger.info(
            "Skill registry saved validation skill={} user={} sandboxMode={} hash={} policy={} " +
                    "approved={} findings={} path={}",
            record.skillId.value,
            record.userId,
            store.sandboxMode,
            record.bundleHash.take(12),
            record.policyVersion,
            record.approved,
            record.findings.size,
            path.path,
        )
    }

    private data class Store(
        val paths: SouzPaths,
        val fileSystem: SandboxFileSystem,
        val sandboxMode: String,
    ) {
        fun resolvePath(path: Path): SandboxPathInfo = fileSystem.resolvePath(path.toString())

        fun resolveChildPath(parent: SandboxPathInfo, child: String): SandboxPathInfo =
            fileSystem.resolvePath(childPath(parent.path, child))

        fun refresh(path: SandboxPathInfo): SandboxPathInfo =
            fileSystem.resolvePath(path.path)

        private fun childPath(parent: String, child: String): String =
            Path.of(parent).resolve(child).normalize().toString()
    }

    private data class StoredSkillRecord(
        val userId: String,
        val skillId: String,
        val manifest: ru.souz.agent.skills.bundle.SkillManifest,
        val bundleHash: String,
        val createdAt: String,
    )

    private data class StoredSkillValidationRecord(
        val userId: String,
        val skillId: String,
        val bundleHash: String,
        val policyVersion: String,
        val approved: Boolean,
        val findings: List<SkillValidationFinding> = emptyList(),
        val createdAt: String,
        val updatedAt: String,
    )

    private companion object {
        const val BUNDLES_DIRECTORY_NAME = "bundles"
        const val SKILL_MARKDOWN_FILE_NAME = "SKILL.md"
        const val STORED_SKILL_FILE_NAME = "stored-skill.json"

        val LOOSE_SKILL_CREATED_AT: Instant = Instant.EPOCH

        private val BUNDLE_HASH_REGEX = Regex("^[a-fA-F0-9]{64}$")
        private val PATH_SEGMENT_REGEX = Regex("^[A-Za-z0-9._-]+$")

        fun requireSafeBundleHash(bundleHash: String): String {
            require(bundleHash.matches(BUNDLE_HASH_REGEX)) {
                "Skill bundle hash must be a 64-character hex SHA-256 string."
            }
            return bundleHash
        }

        fun requireSafePathSegment(raw: String, label: String): String {
            require(raw.isNotBlank()) { "$label storage path segment must not be blank." }
            require(raw.matches(PATH_SEGMENT_REGEX) && raw != "." && raw != "..") {
                "$label must be a safe storage path segment containing only letters, digits, '.', '_', or '-'."
            }
            return raw
        }

        fun requireSafeRelativePath(raw: String, label: String): String {
            require(raw.isNotBlank()) { "$label storage path must not be blank." }
            require(!raw.startsWith("/")) { "$label storage path must be relative." }
            val segments = raw.split('/')
            require(segments.all { it.isNotEmpty() }) { "$label storage path must not contain empty segments." }
            segments.forEach { segment ->
                requireSafePathSegment(segment, label)
            }
            return raw
        }

    }
}
