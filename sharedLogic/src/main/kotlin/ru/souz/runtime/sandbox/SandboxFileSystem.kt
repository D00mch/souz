package ru.souz.runtime.sandbox

import java.io.InputStream
import java.nio.file.Path
import org.slf4j.Logger
import ru.souz.runtime.files.ForbiddenFolder
import ru.souz.tool.BadInputException

data class SandboxPathInfo(
    val rawPath: String,
    val path: String,
    val name: String,
    val parentPath: String?,
    val exists: Boolean,
    val isDirectory: Boolean,
    val isRegularFile: Boolean,
    val isSymbolicLink: Boolean,
    val sizeBytes: Long? = null,
) {
    val sandboxPath: String
        get() = path
}

interface SandboxFileSystem {
    val runtimePaths: SandboxRuntimePaths

    fun resolvePath(rawPath: String): SandboxPathInfo
    fun resolveExistingFile(rawPath: String): SandboxPathInfo = resolveExistingPath(rawPath, expectDirectory = false)
    fun resolveExistingDirectory(rawPath: String): SandboxPathInfo = resolveExistingPath(rawPath, expectDirectory = true)
    fun isPathSafe(path: SandboxPathInfo): Boolean
    fun forbiddenPaths(): List<String>
    fun readBytes(path: SandboxPathInfo): ByteArray
    fun readText(path: SandboxPathInfo): String
    fun openInputStream(path: SandboxPathInfo): InputStream
    fun localPathOrNull(path: SandboxPathInfo): Path? = null
    fun writeBytes(path: SandboxPathInfo, content: ByteArray)
    fun writeText(path: SandboxPathInfo, content: String)
    fun writeTextAtomically(path: SandboxPathInfo, content: String, logger: Logger)
    fun createDirectory(path: SandboxPathInfo)
    fun delete(path: SandboxPathInfo, recursively: Boolean = false)
    fun listDescendants(
        root: SandboxPathInfo,
        maxDepth: Int = Int.MAX_VALUE,
        includeHidden: Boolean = false,
    ): List<SandboxPathInfo>

    fun move(
        source: SandboxPathInfo,
        destination: SandboxPathInfo,
        replaceExisting: Boolean = false,
        createParents: Boolean = false,
        logger: Logger? = null,
    )

    fun moveToTrash(path: SandboxPathInfo, logger: Logger? = null): SandboxPathInfo

    private fun resolveExistingPath(rawPath: String, expectDirectory: Boolean): SandboxPathInfo {
        val resolved = resolvePath(rawPath)
        if (!isPathSafe(resolved)) throw ForbiddenFolder(resolved.rawPath)
        val hasExpectedType = if (expectDirectory) resolved.isDirectory else resolved.isRegularFile
        if (!resolved.exists || !hasExpectedType) {
            val expected = if (expectDirectory) "directory" else "file"
            throw BadInputException("Invalid $expected path: $rawPath")
        }
        return resolved
    }
}
