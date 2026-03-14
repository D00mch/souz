package ru.souz.tool.files

import org.kodein.di.DI
import org.kodein.di.instance
import ru.souz.db.SettingsProvider
import ru.souz.di.mainDiModule
import ru.souz.permissions.MacAppEnvironment
import ru.souz.service.files.FilesService
import ru.souz.tool.BadInputException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class FilesToolUtil(private val settingsProvider: SettingsProvider) : FilesService {

    override val homeStr: String
        get() = Companion.homeStr

    override val homeDirectory: File
        get() = Companion.homeDirectory

    val souzDocumentsDirectoryPath: Path
        get() = Companion.souzDocumentsDirectoryPath

    val souzTelegramControlDirectoryPath: Path
        get() = Companion.souzTelegramControlDirectoryPath

    val souzWebAssetsDirectoryPath: Path
        get() = Companion.souzWebAssetsDirectoryPath

    fun normalizeExistingFilePath(raw: String?): String? = Companion.normalizeExistingFilePath(raw)

    /**
     * Generally, we don't want Agent to mess around anything out of $HOME and everything user disallowed
     */
    override fun isPathSafe(file: File): Boolean {
        val canonicalPath = file.canonicalFile
        return file.canonicalPath.startsWith(homeStr) &&
                forbiddenDirectories().none { canonicalPath.startsWith(it) }
    }

    @Throws(BadInputException::class)
    override fun requirePathIsSave(file: File) {
        if (!isPathSafe(file)) {
            throw BadInputException("Access denied: File path must be within the home directory")
        }
    }

    fun resourceAsText(path: String): String = Companion.resourceAsText(path)

    override fun applyDefaultEnvs(path: String): String {
        if (path.startsWith("~")) {
            return path.replace("~", homeStr)
        }
        if (path == "home") return homeStr
        return path.replace("\$HOME", homeStr)
            .replace("HOME", homeStr)
    }

    /**
     * Validates that a unified diff patch (`---` / `+++` headers) touches exactly one file and that,
     * after applying the same `strip` logic as `patch -pN`, the resulting target path matches
     * [expectedFilePath].
     *
     * This is a guardrail for tools that apply patches inside a directory and want to ensure the patch
     * cannot unexpectedly target another file. It supports common diff header formats including:
     * - `a/file.txt` / `b/file.txt`
     * - bare paths (`file.txt`)
     * - quoted paths (for names with spaces)
     * - optional tab-separated timestamps after the path
     *
     * `/dev/null` headers are ignored to tolerate create/delete style patches while still validating
     * the non-null target path.
     *
     * Throws [BadInputException] when:
     * - no file headers are found
     * - more than one file is targeted
     * - the patch path is incompatible with [strip]
     * - the normalized target does not match [expectedFilePath]
     */
    fun validateUnifiedDiffTargetsSingleFile(
        patch: String,
        expectedFilePath: String,
        strip: Int,
    ) {
        val expectedFile = File(expectedFilePath).canonicalFile
        val touched = mutableSetOf<String>()

        if (patch.lines().any { line ->
                line.startsWith("*** Begin Patch") ||
                        line.startsWith("*** Update File:") ||
                        line.startsWith("*** End Patch")
            }
        ) {
            throw BadInputException(
                "Patch must be a unified diff with ---/+++ headers. " +
                        "Do not use the *** Begin Patch / *** End Patch wrapper."
            )
        }

        patch.lineSequence().forEach { line ->
            if (line.startsWith("@@") && !UNIFIED_DIFF_HUNK_HEADER.matches(line)) {
                throw BadInputException(
                    "Invalid hunk header '$line'. Expected format like '@@ -1,3 +1,4 @@'."
                )
            }

            val rawPath = extractUnifiedDiffHeaderPath(line) ?: return@forEach

            if (rawPath == "/dev/null") return@forEach

            val stripped = stripPathComponents(rawPath, strip)
                ?: throw BadInputException("Patch path '$rawPath' is incompatible with strip=$strip")
            val normalized = stripped.removePrefix("./")
            touched.add(normalized)
        }

        if (touched.isEmpty()) throw BadInputException("Patch has no file headers (---/+++).")
        if (touched.size != 1) throw BadInputException("Patch must target exactly one file; got: $touched")
        val target = touched.first()
        val absoluteTargetMatch = isAbsolutePath(target) &&
                runCatching { File(target).canonicalFile == expectedFile }.getOrDefault(false)
        val relativeTargetMatch = target == expectedFile.name
        if (!absoluteTargetMatch && !relativeTargetMatch) {
            throw BadInputException("Patch targets '$target', but tool path is '${expectedFile.path}'")
        }
    }


    /**
     * Moves file from [sourcePath] to [destinationPath].
     *
     * First we request [StandardCopyOption.ATOMIC_MOVE], which asks the filesystem to make
     * the move atomic (all-or-nothing: readers should see either source or destination state,
     * not a partially moved file). Some filesystems do not support atomic moves for a given
     * source/destination pair; in that case we fall back to a regular move.
     */
    fun moveWithAtomicFallback(sourcePath: Path, destinationPath: Path, logger: org.slf4j.Logger) {
        try {
            Files.move(sourcePath, destinationPath, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: AtomicMoveNotSupportedException) {
            logger.warn("Failed to make an atomic move", exception)
            Files.move(sourcePath, destinationPath)
        }
    }

    fun forbiddenDirectories(): List<File> {
        return settingsProvider.forbiddenFolders.mapNotNull { raw ->
            val expanded = applyDefaultEnvs(raw).trim()
            if (expanded.isBlank()) return@mapNotNull null
            val resolved = File(expanded).let { file ->
                if (file.isAbsolute) file else File(homeDirectory, file.path)
            }.canonicalFile
            if (!resolved.startsWith(homeDirectory)) return@mapNotNull null
            resolved
        }.distinct()
    }

    private fun extractUnifiedDiffHeaderPath(line: String): String? {
        val raw = when {
            line.startsWith("--- ") -> line.removePrefix("--- ")
            line.startsWith("+++ ") -> line.removePrefix("+++ ")
            else -> return null
        }.trimStart()

        if (raw.isEmpty()) return null

        return if (raw.startsWith("\"")) {
            parseQuotedPatchPath(raw) ?: raw.substringBefore('\t').trim()
        } else {
            raw.substringBefore('\t').trim()
        }
    }

    private fun parseQuotedPatchPath(raw: String): String? {
        val out = StringBuilder()
        var escaped = false

        for (i in 1 until raw.length) {
            val ch = raw[i]
            if (escaped) {
                out.append(ch)
                escaped = false
                continue
            }
            when (ch) {
                '\\' -> escaped = true
                '"' -> return out.toString()
                else -> out.append(ch)
            }
        }
        return null
    }

    private fun stripPathComponents(path: String, strip: Int): String? {
        var normalized = path
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        if (strip == 0) return normalized

        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.size <= strip) return null
        return parts.drop(strip).joinToString("/")
    }

    private fun isAbsolutePath(path: String): Boolean {
        if (path.startsWith("/")) return true
        return WINDOWS_ABSOLUTE_PATH.matches(path)
    }

    companion object {
        private val UNIFIED_DIFF_HUNK_HEADER =
            Regex("^@@ -\\d+(?:,\\d+)? \\+\\d+(?:,\\d+)? @@(?: .*)?$")
        private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")

        val homeStr: String get() = MacAppEnvironment.appDataHome
        val homeDirectory: File get() = File(homeStr).canonicalFile
        val documentsDirectoryPath: Path
            get() {
                val homePath = homeDirectory.toPath()
                val preferred = homePath.resolve("Documents")
                val fallback = homePath.resolve("documents")
                return when {
                    Files.isDirectory(preferred) -> preferred
                    Files.isDirectory(fallback) -> fallback
                    else -> preferred
                }
            }
        val souzDocumentsDirectoryPath: Path
            get() = documentsDirectoryPath.resolve("souz")
        val souzTelegramControlDirectoryPath: Path
            get() = souzDocumentsDirectoryPath.resolve("telegram")
        val souzWebAssetsDirectoryPath: Path
            get() = souzDocumentsDirectoryPath.resolve("web_assets")

        fun resourceStream(path: String): InputStream =
            Thread.currentThread().contextClassLoader.getResourceAsStream(path)
                ?: throw IOException("Certificate not found on classpath: $path")

        fun resourceAsText(path: String): String =
            resourceStream(path).bufferedReader().use { it.readText() }

        fun normalizeExistingFilePath(raw: String?): String? {
            val cleaned = raw
                ?.trim()
                ?.removeSurrounding("`")
                ?.removeSurrounding("\"")
                ?.removeSurrounding("'")
                ?.removePrefix("file://")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val expanded = if (cleaned.startsWith("~")) {
                cleaned.replaceFirst("~", homeStr)
            } else {
                cleaned
            }
            val file = File(expanded)
            return file.takeIf { it.exists() && it.isFile }?.canonicalPath
        }
    }
}

@Suppress("FunctionName")
fun ForbiddenFolder(fixedPath: String) =
    BadInputException("Forbidden directory: $fixedPath. User explicitly restricted this path. Inform him")

fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val filesToolUtil: FilesToolUtil by di.instance()

    val result = filesToolUtil.isPathSafe(File(
        filesToolUtil.applyDefaultEnvs("~/")
    ))
    println("Safe? $result")
}
