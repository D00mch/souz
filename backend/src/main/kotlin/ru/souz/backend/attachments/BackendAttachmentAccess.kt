package ru.souz.backend.attachments

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.souz.backend.crypto.sha256Hex
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import software.amazon.awssdk.services.s3.model.S3Exception

data class PreparedAttachment(val attachment: FileContent, val path: String)

/**
 * Moves owner-scoped stored files between S3 and the invoking user's sandbox.
 * Only Docker sandboxes isolate users' workspaces, so every operation rejects other sandbox modes.
 */
class BackendAttachmentAccess(
    private val storage: AttachmentStorage,
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
) {
    suspend fun prepare(meta: ToolInvocationMeta, fileId: String): PreparedAttachment = safely("prepare") {
        val fs = isolatedFileSystem(meta)
        val (file, bytes) = storage.load(meta.userId, attachmentId(fileId))
        // Rebuild from immutable S3 bytes even if a previous working copy has been edited.
        val root = fs.runtimePaths.workspaceRootPath ?: throw AttachmentException("attachment_workspace_unavailable")
        val path = runInterruptible(Dispatchers.IO) {
            val directory = fs.resolvePath("$root/attachments/${file.id}")
            if (!fs.isPathSafe(directory) || directory.isSymbolicLink) throw AttachmentException("attachment_path_forbidden")
            fs.createDirectory(directory)
            val target = fs.resolvePath("${directory.path}/${file.name}")
            if (!fs.isPathSafe(target) || target.isSymbolicLink) throw AttachmentException("attachment_path_forbidden")
            fs.writeBytes(target, bytes)
            target.path
        }
        PreparedAttachment(file.toContent(), path)
    }

    /** Saves a completed sandbox file; the same bytes and name within one execution reuse the stored object. */
    suspend fun save(meta: ToolInvocationMeta, path: String, presentation: FilePresentation): FileContent = safely("save") {
        val request = meta.requestId ?: throw AttachmentException("attachment_execution_required")
        val fs = isolatedFileSystem(meta)
        val resolved = fs.resolvePath(path)
        if (!fs.isPathSafe(resolved) || resolved.isSymbolicLink) throw AttachmentException("attachment_path_forbidden")
        if (!resolved.exists || !resolved.isRegularFile) throw AttachmentException("attachment_path_not_found")
        val nameHash = resolved.name.toByteArray().sha256Hex()
        val file = runInterruptible(Dispatchers.IO) { fs.openInputStream(resolved) }.use { input ->
            storage.save(meta.userId, resolved.name, input) { contentHash -> "$request:output:$contentHash:$nameHash" }
        }
        file.toContent(presentation)
    }

    suspend fun select(meta: ToolInvocationMeta, fileId: String, presentation: FilePresentation): FileContent = safely("select") {
        requireSupportedSandbox(meta)
        storage.get(meta.userId, attachmentId(fileId)).toContent(presentation)
    }

    fun requireSupportedSandbox(meta: ToolInvocationMeta) {
        isolatedFileSystem(meta)
    }

    private fun isolatedFileSystem(meta: ToolInvocationMeta): SandboxFileSystem {
        val sandbox = sandboxResolver.resolve(meta)
        if (sandbox.mode != SandboxMode.DOCKER) throw AttachmentException("attachment_sandbox_unsupported")
        return sandbox.fileSystem
    }

    private suspend fun <T> safely(operation: String, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (known: AttachmentException) {
        throw known
    } catch (error: Exception) {
        // Never log exception messages, response bodies or signed transport details.
        val s3 = error as? S3Exception
        logger.warn("Attachment {} failed: exceptionClass={}, statusCode={}, s3ErrorCode={}",
            operation, error.javaClass.name, s3?.statusCode(), s3?.awsErrorDetails()?.errorCode())
        throw AttachmentException("attachment_operation_failed")
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(BackendAttachmentAccess::class.java)
    }
}

internal fun attachmentId(raw: String): UUID = try {
    UUID.fromString(raw).also { if (it.toString() != raw) throw IllegalArgumentException() }
} catch (_: IllegalArgumentException) {
    throw AttachmentException("attachment_not_found")
}

internal fun StoredAttachment.toContent(presentation: FilePresentation = FilePresentation.document) =
    FileContent(id.toString(), name, mimeType, sizeBytes, presentation).also { it.requireMatchingPresentation() }

/** Image presentations need image bytes; voice and audio presentations need audio bytes. */
internal fun FileContent.requireMatchingPresentation() {
    val matches = when (presentation) {
        FilePresentation.image -> mimeType.startsWith("image/")
        FilePresentation.voice, FilePresentation.audio -> mimeType.startsWith("audio/")
        FilePresentation.document -> true
    }
    if (!matches) throw AttachmentException("attachment_presentation_mismatch")
}
