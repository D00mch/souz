package ru.souz.backend.attachments

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.storage.postgres.hasActiveAttachmentOwnerExecution
import ru.souz.backend.storage.postgres.lockPreparedAttachmentOwner
import ru.souz.backend.storage.postgres.postgresStorageMapper
import ru.souz.backend.storage.postgres.tryLockPreparedAttachmentOwner
import ru.souz.backend.storage.postgres.write
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxScope
import kotlin.time.Duration.Companion.minutes

internal class PreparedAttachmentWorkspace(
    private val dataSource: DataSource,
    private val sandboxFactory: RuntimeSandboxFactory,
    private val runtime: PreparedAttachmentRuntime,
    private val clock: Clock,
) {
    suspend fun prepare(file: StoredAttachment, load: suspend () -> ByteArray): PreparedAttachment =
        prepare(file, bytes = null) ?: checkNotNull(prepare(file, load()))

    private suspend fun prepare(file: StoredAttachment, bytes: ByteArray?): PreparedAttachment? =
        dataSource.write { connection ->
            connection.lockPreparedAttachmentOwner(file.userId)
            val root = sandboxFactory.preparedAttachmentsRoot() ?: throw AttachmentException("attachment_workspace_unavailable")
            val files = filesFor(file.userId)
            val target = files.target(file.id, file.name)
            val manifest = manifestPath(root, file.userId)
            val copies = readRecords(manifest)?.copies.orEmpty().associateByTo(linkedMapOf()) { it.id }
            val previous = copies.remove(file.id)
            if (previous != null && previous.name != file.name) throw AttachmentException("attachment_path_conflict")
            val published = files.contains(target)
            // A publication that outlived its manifest can be adopted from the protected mount.
            if (published && (Files.size(target) != file.sizeBytes ||
                    (previous == null && !files.matchesSha256(target, file.sha256)))
            ) {
                throw AttachmentException("attachment_corrupt")
            }

            if (!hasRoom(copies.values, file.sizeBytes)) {
                copies.values.removeIf { !files.contains(files.target(it.id, it.name)) }
                if (!hasRoom(copies.values, file.sizeBytes)) {
                    logger.warn(
                        "Prepared attachment capacity reached: files={}, bytes={}",
                        copies.size, copies.values.sumOf { it.size },
                    )
                    throw AttachmentException("attachment_workspace_capacity_exceeded")
                }
            }
            if (!published && bytes == null) return@write null

            // An interrupted publication leaves either a tracked complete file or a retryable reservation.
            copies[file.id] = PreparedCopy(file.id, file.name, file.sizeBytes, nextExpiry())
            writeRecords(manifest, file.userId, copies.values)
            if (!published) {
                try {
                    files.publish(target, stagingPath(manifest), checkNotNull(bytes))
                } catch (_: FileAlreadyExistsException) {
                    throw AttachmentException("attachment_path_conflict")
                }
            }
            PreparedAttachment(file.toContent(), files.sandboxPath(file.id, file.name))
        }

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            try {
                cleanup()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.warn("Prepared attachment cleanup failed: exceptionClass={}", error.javaClass.name)
            }
            delay(1.minutes)
        }
    }

    internal suspend fun cleanup(): Int {
        val root = sandboxFactory.preparedAttachmentsRoot() ?: return 0
        val manifests = withContext(Dispatchers.IO) {
            try {
                Files.newDirectoryStream(root.resolve(RECORD_DIRECTORY), "*.json").use { it.toList() }
            } catch (_: NoSuchFileException) {
                emptyList()
            }
        }
        var removed = 0
        var trackedFiles = 0
        var trackedBytes = 0L
        var failures = 0
        for (manifest in manifests) {
            try {
                val owner = withContext(Dispatchers.IO) { readRecords(manifest)?.owner } ?: continue
                val result = dataSource.write { connection ->
                    if (!connection.tryLockPreparedAttachmentOwner(owner)) return@write null
                    val records = readRecords(manifest)?.copies.orEmpty()
                    Files.deleteIfExists(stagingPath(manifest))
                    val now = clock.instant()
                    if (records.none { !it.dueAt.isAfter(now) } ||
                        connection.hasActiveAttachmentOwnerExecution(owner, runtime::isAlive)
                    ) {
                        return@write Cleanup(records)
                    }
                    cleanOwner(filesFor(owner), manifest, owner, records, now)
                } ?: continue
                removed += result.removed
                trackedFiles += result.remaining.size
                trackedBytes += result.remaining.sumOf { it.size }
                failures += result.failures
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures++
                logger.warn("Prepared attachment workspace cleanup failed: exceptionClass={}", error.javaClass.name)
            }
        }
        logger.debug(
            "Prepared attachment cleanup: trackedFiles={}, reservedBytes={}, removed={}, failures={}",
            trackedFiles, trackedBytes, removed, failures,
        )
        return removed
    }

    private fun cleanOwner(
        files: PreparedAttachmentFiles,
        manifest: Path,
        owner: String,
        records: List<PreparedCopy>,
        now: Instant,
    ): Cleanup {
        var removed = 0
        var failures = 0
        val remaining = records.filter { record ->
            if (record.dueAt.isAfter(now)) return@filter true
            try {
                if (files.delete(files.target(record.id, record.name))) removed++
                false
            } catch (error: Exception) {
                failures++
                logger.warn("Prepared attachment file cleanup failed: exceptionClass={}", error.javaClass.name)
                true
            }
        }
        if (remaining != records) writeRecords(manifest, owner, remaining)
        return Cleanup(remaining, removed, failures)
    }

    private fun filesFor(owner: String): PreparedAttachmentFiles =
        sandboxFactory.preparedAttachments(SandboxScope(owner))?.let(::PreparedAttachmentFiles)
            ?: throw AttachmentException("attachment_workspace_unavailable")

    private fun nextExpiry(): Instant = clock.instant().plusSeconds(86_400)

    private fun hasRoom(records: Collection<PreparedCopy>, bytes: Long): Boolean =
        records.size < MAX_FILES && records.sumOf { it.size } + bytes <= MAX_BYTES

    private fun manifestPath(root: Path, owner: String): Path =
        root.resolve(RECORD_DIRECTORY).resolve("${owner.sha256Hex()}.json")

    private fun stagingPath(manifest: Path): Path = manifest.resolveSibling("${manifest.fileName}.pending")

    private fun readRecords(path: Path): Records? = try {
        Files.newInputStream(path).use { postgresStorageMapper.readValue(it, Records::class.java) }.also { records ->
            require(path.fileName == manifestPath(path.parent.parent, records.owner).fileName)
        }
    } catch (_: NoSuchFileException) {
        null
    }

    private fun writeRecords(path: Path, owner: String, copies: Collection<PreparedCopy>) {
        if (copies.isEmpty()) {
            Files.deleteIfExists(path)
            return
        }
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        try {
            Files.write(temporary, postgresStorageMapper.writeValueAsBytes(Records(owner, copies.toList())))
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private data class Records(val owner: String, val copies: List<PreparedCopy>)
    private data class Cleanup(val remaining: List<PreparedCopy>, val removed: Int = 0, val failures: Int = 0)
    private data class PreparedCopy(val id: UUID, val name: String, val size: Long, val dueAt: Instant)

    private companion object {
        const val RECORD_DIRECTORY = "records"
        const val MAX_FILES = 128
        const val MAX_BYTES = 512L * 1024 * 1024
        val logger = LoggerFactory.getLogger(PreparedAttachmentWorkspace::class.java)
    }
}
