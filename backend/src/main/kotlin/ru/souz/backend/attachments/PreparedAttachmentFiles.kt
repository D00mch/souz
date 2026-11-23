package ru.souz.backend.attachments

import java.io.OutputStream
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import ru.souz.runtime.sandbox.PreparedAttachmentMount

/** Owns a host directory exposed to the sandbox only through a read-only mount. */
internal class PreparedAttachmentFiles(private val mount: PreparedAttachmentMount) {
    fun sandboxPath(id: UUID, name: String): String = "${PreparedAttachmentMount.SANDBOX_PATH}/$id/$name"

    fun target(id: UUID, name: String): Path {
        val directory = mount.hostDirectory.resolve(id.toString())
        val path = directory.resolve(name).normalize()
        if (path.parent != directory || path.fileName.toString() != name) {
            throw AttachmentException("attachment_path_forbidden")
        }
        return path
    }

    fun publish(path: Path, staging: Path, bytes: ByteArray) {
        Files.createDirectories(staging.parent)
        Files.deleteIfExists(staging)
        try {
            Files.write(staging, bytes, CREATE_NEW)
            Files.createDirectories(path.parent)
            // Linking publishes the finished bytes in one operation and fails if the final name already exists.
            Files.createLink(path, staging)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    fun contains(path: Path): Boolean {
        if (Files.isRegularFile(path, NOFOLLOW_LINKS)) return true
        if (Files.notExists(path, NOFOLLOW_LINKS)) return false
        throw AttachmentException("attachment_path_forbidden")
    }

    fun matchesSha256(path: Path, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(path, NOFOLLOW_LINKS), digest).use {
            it.transferTo(OutputStream.nullOutputStream())
        }
        return HexFormat.of().formatHex(digest.digest()) == expected
    }

    fun delete(path: Path): Boolean {
        val removed = contains(path) && Files.deleteIfExists(path)
        try {
            Files.deleteIfExists(path.parent)
        } catch (_: DirectoryNotEmptyException) {
            // Leave unexpected contents in the managed directory untouched.
        }
        return removed
    }
}
