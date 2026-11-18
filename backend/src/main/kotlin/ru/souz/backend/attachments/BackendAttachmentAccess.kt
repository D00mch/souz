package ru.souz.backend.attachments

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.slf4j.Logger
import org.slf4j.LoggerFactory
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
 * Workspace transfers require Docker isolation; stored-file selection uses owner-scoped storage directly.
 */
class BackendAttachmentAccess internal constructor(
    private val storage: AttachmentStorage,
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
    private val preparedWorkspace: PreparedAttachmentWorkspace,
) {
    suspend fun prepare(meta: ToolInvocationMeta, fileId: String): PreparedAttachment = safely("prepare") {
        isolatedFileSystem(meta)
        val file = storage.get(meta.userId, attachmentId(fileId))
        preparedWorkspace.prepare(file) { storage.read(file) }
    }

    /** Saves a completed sandbox file; the same bytes and name within one execution reuse the stored object. */
    suspend fun save(meta: ToolInvocationMeta, path: String, presentation: FilePresentation): FileContent = safely("save") {
        val executionId = meta.requestId?.let(UUID::fromString) ?: throw AttachmentException("attachment_execution_required")
        val fs = isolatedFileSystem(meta)
        val resolved = fs.resolvePath(path)
        if (!fs.isPathSafe(resolved) || resolved.isSymbolicLink) throw AttachmentException("attachment_path_forbidden")
        if (!resolved.exists || !resolved.isRegularFile) throw AttachmentException("attachment_path_not_found")
        val file = runInterruptible(Dispatchers.IO) { fs.openInputStream(resolved) }.use { input ->
            storage.saveWorkspaceOutput(meta.userId, executionId, resolved.name, input)
        }
        file.toContent(presentation)
    }

    suspend fun select(meta: ToolInvocationMeta, fileId: String, presentation: FilePresentation): FileContent = safely("select") {
        storage.get(meta.userId, attachmentId(fileId)).toContent(presentation)
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
