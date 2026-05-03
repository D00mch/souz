package ru.souz.skills.filesystem

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.BadInputException
import ru.souz.tool.files.FilesToolUtil
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo

/**
 * Local JVM implementation of [SkillBundleFileSystem] backed by the shared
 * sandbox filesystem abstraction.
 *
 * It rejects symlinks, non-regular files, binary content, and invalid UTF-8 so
 * skill bundles can be loaded from disk without escaping the allowed sandbox.
 */
class LocalSkillBundleFileSystem(
    private val sandboxFileSystemResolver: (SkillBundleFsContext) -> SandboxFileSystem,
) : SkillBundleFileSystem {
    constructor(sandboxFileSystem: SandboxFileSystem) : this({ sandboxFileSystem })

    constructor(filesToolUtil: FilesToolUtil) : this({ context ->
        filesToolUtil.sandboxFileSystem(
            ToolInvocationMeta(userId = context.userId)
        )
    })

    private fun sandboxFileSystem(context: SkillBundleFsContext): SandboxFileSystem =
        sandboxFileSystemResolver(context)

    override suspend fun resolveSafeDirectory(
        context: SkillBundleFsContext,
        rawPath: String,
    ): Path = withContext(Dispatchers.IO) {
        val cleaned = rawPath.trim()
        if (cleaned.isEmpty()) {
            throw SkillBundleException("Skill root path must not be blank.")
        }

        val resolved = runCatching {
            val sandboxFileSystem = sandboxFileSystem(context)
            val path = sandboxFileSystem.resolveExistingDirectory(cleaned)
            if (path.isSymbolicLink) {
                throw SkillBundleException("Symbolic link roots are not allowed in skill bundles: $rawPath")
            }
            if (!sandboxFileSystem.isPathSafe(path)) {
                throw SkillBundleException("Access denied for skill root: $rawPath")
            }
            Path.of(path.path)
        }

        resolved.getOrElse { error ->
            when (error) {
                is SkillBundleException -> throw error
                is BadInputException -> throw SkillBundleException(error.message ?: "Invalid skill root: $rawPath", error)
                else -> throw SkillBundleException("Failed to resolve skill root: $rawPath", error)
            }
        }
    }

    override suspend fun listRegularFiles(
        context: SkillBundleFsContext,
        root: Path,
    ): List<Path> = withContext(Dispatchers.IO) {
        val sandboxFileSystem = sandboxFileSystem(context)
        val canonicalRoot = sandboxFileSystem.resolveExistingDirectory(root.toString())
        sandboxFileSystem.listDescendants(canonicalRoot, includeHidden = true)
            .mapNotNull { entry ->
                ensurePathSafe(context, entry)
                if (entry.isSymbolicLink) {
                    val relative = root.relativize(Path.of(entry.path)).toString().replace('\\', '/')
                    val messagePrefix = if (entry.isDirectory) {
                        "Symbolic link directories are not allowed in skill bundles"
                    } else {
                        "Symbolic link files are not allowed in skill bundles"
                    }
                    throw SkillBundleException("$messagePrefix: $relative")
                }
                if (entry.isDirectory) {
                    return@mapNotNull null
                }
                if (!entry.isRegularFile) {
                    throw SkillBundleException(
                        "Only regular files are allowed in skill bundles: ${root.relativize(Path.of(entry.path)).toString().replace('\\', '/')}"
                    )
                }
                Path.of(entry.path)
            }
            .sortedBy { root.relativize(it).toString().replace('\\', '/') }
    }

    override suspend fun readUtf8File(
        context: SkillBundleFsContext,
        path: Path,
        maxBytes: Long,
    ): String = withContext(Dispatchers.IO) {
        val sandboxFileSystem = sandboxFileSystem(context)
        val sandboxPath = sandboxFileSystem.resolveExistingFile(path.toString())
        ensurePathSafe(context, sandboxPath)
        val size = sandboxPath.sizeBytes
            ?: throw SkillBundleException("Failed to stat skill file: $path")
        if (size > maxBytes) {
            throw SkillBundleException("Skill file exceeds the max allowed size of $maxBytes bytes: $path")
        }

        val bytes = runCatching { sandboxFileSystem.readBytes(sandboxPath) }
            .getOrElse { error -> throw SkillBundleException("Failed to read skill file: $path", error) }
        if (isLikelyBinary(bytes)) {
            throw SkillBundleException("Skill files must be UTF-8 text, but a binary file was found: $path")
        }

        try {
            decodeUtf8Strict(bytes)
        } catch (error: CharacterCodingException) {
            throw SkillBundleException("Skill files must be valid UTF-8 text: $path", error)
        }
    }

    private fun ensurePathSafe(context: SkillBundleFsContext, path: SandboxPathInfo) {
        val sandboxFileSystem = sandboxFileSystem(context)
        runCatching {
            if (!sandboxFileSystem.isPathSafe(path)) {
                throw SkillBundleException("Access denied for skill path: $path")
            }
        }.getOrElse { error ->
            when (error) {
                is SkillBundleException -> throw error
                else -> throw SkillBundleException("Failed to validate skill path safety: $path", error)
            }
        }
    }

    private fun isLikelyBinary(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return true
        if (bytes.isEmpty()) return false

        val sample = bytes.take(BINARY_SAMPLE_SIZE)
        val controlChars = sample.count { byte ->
            val value = byte.toInt() and 0xFF
            value < 0x20 && value !in setOf(0x09, 0x0A, 0x0C, 0x0D)
        }
        return controlChars * 5 > sample.size
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private companion object {
        private const val BINARY_SAMPLE_SIZE = 1024
    }
}
