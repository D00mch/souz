package ru.souz.runtime.sandbox

import java.io.InputStream
import java.nio.file.Path
import org.slf4j.Logger

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
    fun resolveExistingFile(rawPath: String): SandboxPathInfo
    fun resolveExistingDirectory(rawPath: String): SandboxPathInfo
    fun isPathSafe(path: SandboxPathInfo): Boolean
    fun forbiddenPaths(): List<String>
    fun readBytes(path: SandboxPathInfo): ByteArray
    fun readText(path: SandboxPathInfo): String
    fun openInputStream(path: SandboxPathInfo): InputStream
    fun localPathOrNull(path: SandboxPathInfo): Path? = null
    fun writeText(path: SandboxPathInfo, content: String)
    fun writeTextAtomically(path: SandboxPathInfo, content: String, logger: Logger)
    fun createDirectory(path: SandboxPathInfo)
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
}
