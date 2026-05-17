package ru.souz.skills.registry

import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.paths.SandboxSouzPaths
import ru.souz.paths.SouzPaths
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.skills.bundle.FileSystemSkillBundleLoader
import ru.souz.skills.filesystem.SandboxSkillBundleFileSystem
import ru.souz.skills.filesystem.SkillBundleFsContext

data class FileSystemSkillRegistryConfig(
    val scope: SkillStorageScope = SkillStorageScope.SINGLE_USER,
)

enum class SkillStorageScope {
    SINGLE_USER,
    USER_SCOPED,
}

/**
 * Filesystem-backed [SkillRegistryRepository] for skill metadata, immutable bundles,
 * and validation records.
 *
 * Filesystem access stays behind [RuntimeSandbox] so the same repository works for local
 * and Docker-backed runtime paths.
 */
class FileSystemSkillRegistryRepository(
    private val sandboxResolver: (String) -> RuntimeSandbox,
    private val config: FileSystemSkillRegistryConfig = FileSystemSkillRegistryConfig(),
    private val clock: Clock = Clock.systemUTC(),
) : SkillRegistryRepository {
    constructor(
        sandbox: RuntimeSandbox,
        config: FileSystemSkillRegistryConfig = FileSystemSkillRegistryConfig(),
        clock: Clock = Clock.systemUTC(),
    ) : this(
        sandboxResolver = { sandbox },
        config = config,
        clock = clock,
    )

    constructor(
        sandboxResolver: ToolInvocationRuntimeSandboxResolver,
        config: FileSystemSkillRegistryConfig = FileSystemSkillRegistryConfig(),
        clock: Clock = Clock.systemUTC(),
    ) : this(
        sandboxResolver = { userId -> sandboxResolver.resolve(ToolInvocationMeta(userId = userId)) },
        config = config,
        clock = clock,
    )

    private val logger = LoggerFactory.getLogger(FileSystemSkillRegistryRepository::class.java)

    override suspend fun listSkills(userId: String): List<StoredSkill> = withContext(Dispatchers.IO) {
        val store = storeFor(userId)
        val skillsRoot = store.resolvePath(skillsRoot(store.paths, userId))
        if (!skillsRoot.exists || !skillsRoot.isDirectory) {
            logSkillRootUnavailable(userId, store, skillsRoot)
            return@withContext emptyList()
        }

        val skillRoots = store.fileSystem.listDescendants(
            root = skillsRoot,
            maxDepth = 1,
            includeHidden = true,
        )
            .filter { it.isDirectory && it.parentPath == skillsRoot.path }

        val skills = skillRoots
            .mapNotNull { skillRoot ->
                readStoredSkillOrNull(store, store.resolveChildPath(skillRoot, STORED_SKILL_FILE_NAME))
                    ?: readLooseStoredSkillOrNull(
                        store = store,
                        userId = userId,
                        skillRoot = skillRoot,
                    )
            }
            .sortedBy { it.skillId.value }

        logSkillsListed(userId, store, skillsRoot, skillRoots, skills)
        skills
    }

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? = withContext(Dispatchers.IO) {
        val store = storeFor(userId)
        readStoredSkillOrNull(store, metadataPath(store.paths, userId, skillId))
            ?: readLooseStoredSkillOrNull(
                store = store,
                userId = userId,
                skillRoot = store.resolvePath(skillRoot(store.paths, userId, skillId)),
            )
    }

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? =
        listSkills(userId).firstOrNull { it.manifest.name == name }

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill = withContext(Dispatchers.IO) {
        val store = storeFor(userId)
        val normalizedBundle = SkillBundle.fromFiles(bundle.skillId, bundle.files)
        val bundleHash = SkillBundleHasher.hash(normalizedBundle)
        val skillRoot = store.resolvePath(skillRoot(store.paths, userId, normalizedBundle.skillId))
        val metadataPath = store.resolvePath(metadataPath(store.paths, userId, normalizedBundle.skillId))
        val bundleRoot = store.resolvePath(bundleRoot(store.paths, userId, normalizedBundle.skillId, bundleHash))

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
        val metadata = readStoredSkillOrNull(store, metadataPath(store.paths, userId, skillId))
        if (metadata == null) {
            val looseBundle = loadLooseSkillBundleOrNull(
                store = store,
                userId = userId,
                skillId = skillId,
                skillRoot = store.resolvePath(skillRoot(store.paths, userId, skillId)),
            )
            if (looseBundle != null) {
                logBundleLoaded(
                    userId = userId,
                    store = store,
                    skillId = skillId,
                    metadata = StoredSkill(
                        userId = userId,
                        skillId = skillId,
                        manifest = looseBundle.manifest,
                        bundleHash = SkillBundleHasher.hash(looseBundle),
                        createdAt = LOOSE_SKILL_CREATED_AT,
                    ),
                    bundleRoot = store.resolvePath(skillRoot(store.paths, userId, skillId)),
                    bundle = looseBundle,
                )
                return@withContext looseBundle
            }
            logBundleMetadataMissing(userId, store, skillId)
            return@withContext null
        }
        val bundleRoot = store.resolvePath(bundleRoot(store.paths, userId, skillId, metadata.bundleHash))
        if (!bundleRoot.exists || !bundleRoot.isDirectory) {
            logBundleRootUnavailable(userId, store, skillId, metadata, bundleRoot)
            return@withContext null
        }

        val bundle = FileSystemSkillBundleLoader(
            fileSystem = SandboxSkillBundleFileSystem(store.fileSystem),
        ).loadDirectory(
            context = SkillBundleFsContext(userId = userId),
            skillId = metadata.skillId,
            rawRoot = bundleRoot.path,
        )
        logBundleLoaded(userId, store, skillId, metadata, bundleRoot, bundle)
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
                    userId = userId,
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
                userId = record.userId,
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
        val store = storeFor(userId)
        val policyRoot = store.resolvePath(
            validationPolicyRoot(
                paths = store.paths,
                userId = userId,
                skillId = skillId,
                policyVersion = policyVersion,
            )
        )
        if (!policyRoot.exists || !policyRoot.isDirectory) {
            return@withContext
        }

        store.fileSystem.listDescendants(
            root = policyRoot,
            maxDepth = 1,
            includeHidden = true,
        )
            .filter { it.isRegularFile && it.parentPath == policyRoot.path && it.name.endsWith(".json") }
            .forEach { path ->
                val record = readValidationOrNull(store, path) ?: return@forEach
                if (record.bundleHash == activeBundleHash || record.status != SkillValidationStatus.APPROVED) {
                    return@forEach
                }
                writeValidation(
                    store = store,
                    path = path,
                    record = record.copy(
                        status = SkillValidationStatus.STALE,
                        reasons = record.reasons + listOfNotNull(reason),
                    ),
                )
                logValidationMarkedStale(userId, store, skillId, record, activeBundleHash, policyVersion)
            }
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
        val bundle = loadLooseSkillBundleOrNull(
            store = store,
            userId = userId,
            skillId = skillId,
            skillRoot = skillRoot,
        ) ?: return null
        return StoredSkill(
            userId = userId,
            skillId = skillId,
            manifest = bundle.manifest,
            bundleHash = SkillBundleHasher.hash(bundle),
            createdAt = LOOSE_SKILL_CREATED_AT,
        )
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

        return runCatching {
            FileSystemSkillBundleLoader(
                fileSystem = SandboxSkillBundleFileSystem(store.fileSystem),
            ).loadDirectory(
                context = SkillBundleFsContext(userId = userId),
                skillId = skillId,
                rawRoot = skillRoot.path,
            )
        }.onFailure { error ->
            logLooseSkillBundleReadFailed(skillRoot, error)
        }.getOrNull()
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
                status = SkillValidationStatus.valueOf(stored.status),
                policyVersion = stored.policyVersion,
                validatorVersion = stored.validatorVersion,
                model = stored.model,
                reasons = stored.reasons,
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
            status = record.status.name,
            policyVersion = record.policyVersion,
            validatorVersion = record.validatorVersion,
            model = record.model,
            reasons = record.reasons,
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

    private fun skillsRoot(
        paths: SouzPaths,
        userId: String,
    ): Path = when (config.scope) {
        SkillStorageScope.SINGLE_USER -> paths.skillsDir
        SkillStorageScope.USER_SCOPED -> paths.skillsDir
            .resolve("users")
            .resolve(encodeSegment(userId))
            .resolve("skills")
    }

    private fun skillRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
    ): Path = skillsRoot(paths, userId)
        .resolve(requireSafePathSegment(skillId.value, "SkillId"))

    private fun bundleRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
        bundleHash: String,
    ): Path = skillRoot(paths, userId, skillId)
        .resolve(BUNDLES_DIRECTORY_NAME)
        .resolve(requireSafeBundleHash(bundleHash))

    private fun metadataPath(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
    ): Path = skillRoot(paths, userId, skillId).resolve(STORED_SKILL_FILE_NAME)

    private fun validationPolicyRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
        policyVersion: String,
    ): Path {
        val skillRoot = when (config.scope) {
            SkillStorageScope.SINGLE_USER -> paths.skillValidationsDir
                .resolve(requireSafePathSegment(skillId.value, "SkillId"))

            SkillStorageScope.USER_SCOPED -> paths.skillValidationsDir
                .resolve("users")
                .resolve(encodeSegment(userId))
                .resolve("skills")
                .resolve(requireSafePathSegment(skillId.value, "SkillId"))
        }
        return skillRoot
            .resolve("policies")
            .resolve(requireSafeRelativePath(policyVersion, "Policy version"))
    }

    private fun validationRecordPath(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
        policyVersion: String,
        bundleHash: String,
    ): Path = validationPolicyRoot(paths, userId, skillId, policyVersion)
        .resolve("${requireSafeBundleHash(bundleHash)}.json")

    private fun childPath(parent: String, child: String): String =
        Path.of(parent).resolve(child).normalize().toString()

    private fun logSkillRootUnavailable(
        userId: String,
        store: Store,
        skillsRoot: SandboxPathInfo,
    ) {
        logger.info(
            "Skill registry root unavailable user={} scope={} sandboxMode={} root={} exists={} isDirectory={}",
            userId,
            config.scope,
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

    private fun logSkillsListed(
        userId: String,
        store: Store,
        skillsRoot: SandboxPathInfo,
        skillRoots: List<SandboxPathInfo>,
        skills: List<StoredSkill>,
    ) {
        logger.info(
            "Skill registry listed {} skill(s) for user={} scope={} sandboxMode={} root={} candidateDirs={} ids={}",
            skills.size,
            userId,
            config.scope,
            store.sandboxMode,
            skillsRoot.path,
            skillRoots.size,
            skills.map { it.skillId.value },
        )
    }

    private fun logSkillSaved(
        userId: String,
        store: Store,
        metadataPath: SandboxPathInfo,
        storedSkill: StoredSkill,
    ) {
        logger.info(
            "Skill registry saved skill={} user={} scope={} sandboxMode={} hash={} metadata={}",
            storedSkill.skillId.value,
            userId,
            config.scope,
            store.sandboxMode,
            storedSkill.bundleHash.take(12),
            metadataPath.path,
        )
    }

    private fun logBundleMetadataMissing(
        userId: String,
        store: Store,
        skillId: SkillId,
    ) {
        logger.info(
            "Skill registry bundle metadata missing skill={} user={} scope={} sandboxMode={}",
            skillId.value,
            userId,
            config.scope,
            store.sandboxMode,
        )
    }

    private fun logBundleRootUnavailable(
        userId: String,
        store: Store,
        skillId: SkillId,
        metadata: StoredSkill,
        bundleRoot: SandboxPathInfo,
    ) {
        logger.warn(
            "Skill registry bundle root unavailable skill={} user={} scope={} sandboxMode={} hash={} root={} " +
                    "exists={} isDirectory={}",
            skillId.value,
            userId,
            config.scope,
            store.sandboxMode,
            metadata.bundleHash.take(12),
            bundleRoot.path,
            bundleRoot.exists,
            bundleRoot.isDirectory,
        )
    }

    private fun logBundleLoaded(
        userId: String,
        store: Store,
        skillId: SkillId,
        metadata: StoredSkill,
        bundleRoot: SandboxPathInfo,
        bundle: SkillBundle,
    ) {
        logger.info(
            "Skill registry loaded bundle skill={} user={} scope={} sandboxMode={} hash={} files={} root={}",
            skillId.value,
            userId,
            config.scope,
            store.sandboxMode,
            metadata.bundleHash.take(12),
            bundle.files.size,
            bundleRoot.path,
        )
    }

    private fun logValidationSaved(
        store: Store,
        path: SandboxPathInfo,
        record: SkillValidationRecord,
    ) {
        logger.info(
            "Skill registry saved validation skill={} user={} scope={} sandboxMode={} hash={} policy={} " +
                    "status={} findings={} path={}",
            record.skillId.value,
            record.userId,
            config.scope,
            store.sandboxMode,
            record.bundleHash.take(12),
            record.policyVersion,
            record.status,
            record.findings.size,
            path.path,
        )
    }

    private fun logValidationMarkedStale(
        userId: String,
        store: Store,
        skillId: SkillId,
        record: SkillValidationRecord,
        activeBundleHash: String,
        policyVersion: String,
    ) {
        logger.info(
            "Skill registry marked stale validation skill={} user={} scope={} sandboxMode={} staleHash={} " +
                    "activeHash={} policy={}",
            skillId.value,
            userId,
            config.scope,
            store.sandboxMode,
            record.bundleHash.take(12),
            activeBundleHash.take(12),
            policyVersion,
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
        val status: String,
        val policyVersion: String,
        val validatorVersion: String,
        val model: String?,
        val reasons: List<String> = emptyList(),
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

        fun encodeSegment(raw: String): String {
            require(raw.isNotBlank()) { "Storage path segment must not be blank." }
            return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        }
    }
}
