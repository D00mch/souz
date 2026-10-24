package ru.souz.backend.attachments

import java.util.UUID
import ru.souz.backend.crypto.sha256Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import ru.souz.agent.spi.AgentAttachmentAccess
import ru.souz.agent.spi.PreparedAttachment
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation
import ru.souz.llms.FileReference
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver

class BackendAttachmentAccess(
    private val storage: AttachmentStorage,
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
    private val config: AttachmentStorageConfig,
) : AgentAttachmentAccess {
    override suspend fun prepare(meta: ToolInvocationMeta, fileId: String): PreparedAttachment = safely {
        val id = attachmentId(fileId)
        val file = storage.get(meta.userId, id)
        val fs = sandboxResolver.resolve(meta).fileSystem
        PreparedAttachment(file.toContent(), storage.prepare(meta.userId, id, fs))
    }

    override suspend fun register(meta: ToolInvocationMeta, path: String?, fileId: String?, presentation: FilePresentation): FileContent = safely {
        require((path != null) xor (fileId != null))
        val file = if (fileId != null) storage.get(meta.userId, attachmentId(fileId)) else {
            val fs = sandboxResolver.resolve(meta).fileSystem
            val resolved = fs.resolveExistingFile(requireNotNull(path))
            if (resolved.isSymbolicLink) throw AttachmentException("attachment_unsafe_path")
            val bytes = runInterruptible(Dispatchers.IO) {
                fs.openInputStream(resolved).use { it.readAttachmentBytes(config.maxFileBytes) }
            }
            val request = requireNotNull(meta.requestId) { "attachment_execution_required" }
            val key = "$request:output:${bytes.sha256Hex()}:${resolved.name.toByteArray().sha256Hex()}"
            storage.save(meta.userId, key, resolved.name, bytes.inputStream())
        }
        if ((presentation == FilePresentation.image && !file.mimeType.startsWith("image/")) ||
            ((presentation == FilePresentation.voice || presentation == FilePresentation.audio) && !file.mimeType.startsWith("audio/"))) {
            throw AttachmentException("attachment_presentation_mismatch")
        }
        file.toContent(presentation)
    }

    private suspend fun <T> safely(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (known: AttachmentException) {
        throw known
    } catch (_: Exception) {
        throw AttachmentException("attachment_operation_failed")
    }
}

internal fun attachmentId(raw: String): UUID = try {
    UUID.fromString(raw).also { if (it.toString() != raw) throw IllegalArgumentException() }
} catch (_: IllegalArgumentException) {
    throw AttachmentException("attachment_not_found")
}

internal fun StoredAttachment.toContent(presentation: FilePresentation = FilePresentation.document) =
    FileContent(FileReference(id.toString()), name, mimeType, sizeBytes, presentation)
