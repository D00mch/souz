package ru.souz.backend.attachments

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import ru.souz.runtime.sandbox.SandboxFileSystem

internal suspend fun AttachmentStorage.prepare(userId: String, id: UUID, fs: SandboxFileSystem): String {
    val file = get(userId, id)
    val bytes = read(userId, id)
    // Rebuild from immutable S3 bytes even if a previous working copy has been edited.
    val root = fs.runtimePaths.workspaceRootPath ?: throw AttachmentException("attachment_workspace_unavailable")
    return runInterruptible(Dispatchers.IO) {
        val directory = fs.resolvePath("$root/attachments/$id")
        if (!fs.isPathSafe(directory) || directory.isSymbolicLink) throw AttachmentException("attachment_unsafe_path")
        fs.createDirectory(directory)
        val target = fs.resolvePath("${directory.path}/${file.name}")
        if (!fs.isPathSafe(target) || target.isSymbolicLink) throw AttachmentException("attachment_unsafe_path")
        fs.writeBytes(target, bytes)
        target.path
    }
}
