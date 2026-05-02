package ru.souz.skills.filesystem

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.tool.BadInputException
import ru.souz.tool.files.FilesToolUtil

class LocalSkillBundleFileSystem(
    private val filesToolUtil: FilesToolUtil,
) : SkillBundleFileSystem {
    override suspend fun resolveSafeDirectory(
        context: SkillBundleFsContext,
        rawPath: String,
    ): Path = withContext(Dispatchers.IO) {
        val cleaned = rawPath.trim()
        if (cleaned.isEmpty()) {
            throw SkillBundleException("Skill root path must not be blank.")
        }

        val resolved = runCatching {
            val expanded = filesToolUtil.applyDefaultEnvs(cleaned)
            val file = Path.of(expanded).toFile()
            if (!filesToolUtil.isPathSafe(file)) {
                throw SkillBundleException("Access denied for skill root: $rawPath")
            }
            val canonical = file.canonicalFile
            if (!canonical.exists() || !canonical.isDirectory) {
                throw SkillBundleException("Skill root is not a directory: $rawPath")
            }
            canonical.toPath()
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
        val canonicalRoot = root.toRealPath()
        val files = mutableListOf<Path>()

        Files.walkFileTree(canonicalRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                ensurePathSafe(dir)
                if (dir != canonicalRoot && Files.isSymbolicLink(dir)) {
                    val resolved = resolveSymlinkTarget(canonicalRoot, dir)
                    if (!resolved.startsWith(canonicalRoot)) {
                        throw SkillBundleException("Symbolic link escapes the skill root: ${canonicalRoot.relativize(dir)}")
                    }
                    throw SkillBundleException(
                        "Symbolic link directories are not allowed in skill bundles: ${canonicalRoot.relativize(dir)}"
                    )
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                ensurePathSafe(file)
                if (Files.isSymbolicLink(file)) {
                    val resolved = resolveSymlinkTarget(canonicalRoot, file)
                    if (!resolved.startsWith(canonicalRoot)) {
                        throw SkillBundleException("Symbolic link escapes the skill root: ${canonicalRoot.relativize(file)}")
                    }
                    if (!Files.isRegularFile(resolved)) {
                        throw SkillBundleException("Only regular files are allowed in skill bundles: ${canonicalRoot.relativize(file)}")
                    }
                } else if (!attrs.isRegularFile) {
                    throw SkillBundleException("Only regular files are allowed in skill bundles: ${canonicalRoot.relativize(file)}")
                }
                files.add(file)
                return FileVisitResult.CONTINUE
            }
        })

        files.sortedBy { canonicalRoot.relativize(it).toString().replace('\\', '/') }
    }

    override suspend fun readUtf8File(
        context: SkillBundleFsContext,
        path: Path,
        maxBytes: Long,
    ): String = withContext(Dispatchers.IO) {
        ensurePathSafe(path)
        val size = runCatching { Files.size(path) }
            .getOrElse { error -> throw SkillBundleException("Failed to stat skill file: $path", error) }
        if (size > maxBytes) {
            throw SkillBundleException("Skill file exceeds the max allowed size of $maxBytes bytes: $path")
        }

        val bytes = runCatching { Files.readAllBytes(path) }
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

    private fun ensurePathSafe(path: Path) {
        runCatching {
            if (!filesToolUtil.isPathSafe(path.toFile())) {
                throw SkillBundleException("Access denied for skill path: $path")
            }
        }.getOrElse { error ->
            when (error) {
                is SkillBundleException -> throw error
                else -> throw SkillBundleException("Failed to validate skill path safety: $path", error)
            }
        }
    }

    private fun resolveSymlinkTarget(
        root: Path,
        path: Path,
    ): Path {
        val resolved = runCatching { path.toRealPath() }
            .getOrElse { error -> throw SkillBundleException("Failed to resolve symbolic link: $path", error) }
        ensurePathSafe(resolved)
        if (!resolved.startsWith(root)) {
            throw SkillBundleException("Symbolic link escapes the skill root: ${root.relativize(path)}")
        }
        return resolved
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
