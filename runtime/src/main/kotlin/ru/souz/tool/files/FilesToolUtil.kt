package ru.souz.tool.files

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.slf4j.Logger
import ru.souz.db.SettingsProvider
import ru.souz.paths.DefaultSouzPaths
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.service.files.FilesService
import ru.souz.tool.BadInputException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class FilesToolUtil(
    private val sandbox: RuntimeSandbox,
) : FilesService {
    constructor(settingsProvider: SettingsProvider) : this(
        LocalRuntimeSandbox(
            scope = SandboxScope.localDefault(),
            settingsProvider = settingsProvider,
            homePath = DefaultSouzPaths.homeDirectory(),
            stateRoot = DefaultSouzPaths.defaultStateRoot(),
        )
    )

    val sandboxFileSystem: SandboxFileSystem
        get() = sandbox.fileSystem

    override val homeStr: String
        get() = sandbox.runtimePaths.homePath

    override val homeDirectory: File
        get() = File(homeStr).canonicalFile

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

    /**
     * Generally, we don't want Agent to mess around anything out of $HOME and everything user disallowed
     */
    override fun isPathSafe(file: File): Boolean {
        return sandboxFileSystem.isPathSafe(sandboxFileSystem.resolvePath(file.path))
    }

    fun isPathSafe(path: SandboxPathInfo): Boolean = sandboxFileSystem.isPathSafe(path)

    @Throws(BadInputException::class)
    override fun requirePathIsSave(file: File) {
        if (!isPathSafe(file)) {
            throw BadInputException("Access denied: File path must be within the home directory")
        }
    }

    fun resourceAsText(path: String): String = Companion.resourceAsText(path)

    override fun applyDefaultEnvs(path: String): String {
        return sandboxFileSystem.resolvePath(path).path
    }

    /**
     * Resolves a raw path into a canonical existing file and enforces Souz path-safety rules.
     *
     * @throws [BadInputException] for missing or non-file targets
     * @throws [ForbiddenFolder] when the path escapes the allowed area.
     */
    fun resolveSafeExistingFile(rawPath: String): SandboxPathInfo = sandboxFileSystem.resolveExistingFile(rawPath)

    fun resolveSafeExistingDirectory(rawPath: String): SandboxPathInfo = sandboxFileSystem.resolveExistingDirectory(rawPath)

    fun resolvePath(rawPath: String): SandboxPathInfo = sandboxFileSystem.resolvePath(rawPath)

    fun listDescendants(
        root: SandboxPathInfo,
        maxDepth: Int = Int.MAX_VALUE,
        includeHidden: Boolean = false,
    ): List<SandboxPathInfo> = sandboxFileSystem.listDescendants(root, maxDepth, includeHidden)

    fun readUtf8TextFile(path: SandboxPathInfo): String = sandboxFileSystem.readText(path)

    fun readBytes(path: SandboxPathInfo): ByteArray = sandboxFileSystem.readBytes(path)

    fun openInputStream(path: SandboxPathInfo): InputStream = sandboxFileSystem.openInputStream(path)

    fun openPdfDocument(path: SandboxPathInfo): CloseablePdfDocument {
        sandboxFileSystem.localPathOrNull(path)?.let { localPath ->
            return CloseablePdfDocument(
                document = Loader.loadPDF(localPath.toFile(), pdfMemoryUsageSetting().streamCache),
            )
        }

        val tempFile = Files.createTempFile(pdfScratchDirectory(), "pdf-read-", ".pdf")
        try {
            openInputStream(path).use { input ->
                Files.newOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            return CloseablePdfDocument(
                document = Loader.loadPDF(tempFile.toFile(), pdfMemoryUsageSetting().streamCache),
                onClose = { Files.deleteIfExists(tempFile) },
            )
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tempFile) }
            throw e
        }
    }

    fun writeUtf8TextFile(path: SandboxPathInfo, content: String) = sandboxFileSystem.writeText(path, content)

    fun createDirectory(path: SandboxPathInfo) = sandboxFileSystem.createDirectory(path)

    fun movePath(
        source: SandboxPathInfo,
        destination: SandboxPathInfo,
        replaceExisting: Boolean = false,
        createParents: Boolean = false,
        logger: Logger? = null,
    ) = sandboxFileSystem.move(source, destination, replaceExisting, createParents, logger)

    fun moveToTrash(path: SandboxPathInfo, logger: Logger? = null): SandboxPathInfo =
        sandboxFileSystem.moveToTrash(path, logger)

    /**
     * Loads a file that is eligible for in-place text editing.
     *
     * The file must be safe, present, not in the blocked extension list, not likely binary, and valid UTF-8.
     * The returned [EditableTextFile] includes both raw content and a normalized-to-raw offset index so
     * tools can match on `\n` internally while still preserving untouched raw bytes on write.
     */
    fun readEditableUtf8TextFile(file: SandboxPathInfo): EditableTextFile {
        if (!sandboxFileSystem.isPathSafe(file)) throw ForbiddenFolder(file.path)
        if (!file.exists || !file.isRegularFile) {
            throw BadInputException("Invalid file path: ${file.path}")
        }
        if (File(file.path).extension.lowercase() in NON_EDITABLE_EXTENSIONS) {
            val errMsg = "Only plain text, code, and config files can be edited. Unsupported file type:"
            throw BadInputException("$errMsg .${File(file.path).extension.lowercase()}")
        }

        val bytes = sandboxFileSystem.readBytes(file)
        if (isLikelyBinary(bytes)) {
            throw BadInputException("Only plain text, code, and config files can be edited.")
        }

        val rawText = try {
            decodeUtf8Strict(bytes)
        } catch (_: CharacterCodingException) {
            throw BadInputException("Only UTF-8 plain text, code, and config files can be edited.")
        }

        val normalizedTextIndex = buildNormalizedTextIndex(rawText)
        return EditableTextFile(
            path = file.path,
            rawText = rawText,
            normalizedTextIndex = normalizedTextIndex,
            preferredLineSeparator = detectPreferredLineSeparator(rawText),
        )
    }

    /**
     * Writes UTF-8 text through a temp file in the same directory and then moves it into place.
     *
     * Using a sibling temp file keeps the write on the same filesystem, which gives the best chance
     * for an atomic replace via [moveWithAtomicFallback].
     */
    fun writeUtf8TextFileAtomically(
        file: SandboxPathInfo,
        content: String,
        logger: Logger,
    ) = sandboxFileSystem.writeTextAtomically(file, content, logger)

    /**
     * Converts any CRLF or CR line endings to LF so text matching can use a single internal format.
     */
    fun normalizeLineEndings(text: String): String =
        buildNormalizedTextIndex(text).normalizedText

    /**
     * Builds an LF-normalized view of [text] together with a raw-offset lookup for each normalized boundary.
     *
     * The returned offsets allow callers to map a match range in normalized coordinates back to the exact
     * byte-preserving raw substring, which lets small edits avoid rewriting unrelated line endings.
     */
    fun buildNormalizedTextIndex(text: String): NormalizedTextIndex {
        val normalized = StringBuilder(text.length)
        val rawOffsets = ArrayList<Int>(text.length + 1)
        rawOffsets += 0

        var rawIndex = 0
        while (rawIndex < text.length) {
            when (val ch = text[rawIndex]) {
                '\r' -> {
                    if (rawIndex + 1 < text.length && text[rawIndex + 1] == '\n') {
                        normalized.append('\n')
                        rawIndex += 2
                    } else {
                        normalized.append('\n')
                        rawIndex += 1
                    }
                }

                else -> {
                    normalized.append(ch)
                    rawIndex += 1
                }
            }
            rawOffsets += rawIndex
        }

        return NormalizedTextIndex(
            normalizedText = normalized.toString(),
            rawOffsets = rawOffsets.toIntArray(),
        )
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
    fun moveWithAtomicFallback(
        sourcePath: Path,
        destinationPath: Path,
        logger: Logger,
        replaceExisting: Boolean = false,
    ) {
        sandboxFileSystem.move(
            source = resolvePath(sourcePath.toString()),
            destination = resolvePath(destinationPath.toString()),
            replaceExisting = replaceExisting,
            createParents = true,
            logger = logger,
        )
    }

    /**
     * Returns the configured forbidden folders as canonical paths under the user's home directory.
     *
     * Blank entries are ignored, relative entries are resolved from [homeDirectory], and paths outside
     * the home directory are discarded so later safety checks only compare against valid in-scope roots.
     */
    fun forbiddenDirectories(): List<File> {
        return sandboxFileSystem.forbiddenPaths().map(::File)
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

    fun detectPreferredLineSeparator(text: String): String = when {
        text.contains("\r\n") -> "\r\n"
        text.contains('\r') -> "\r"
        else -> "\n"
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

    private fun pdfScratchDirectory(): Path {
        val preferred = Path.of(sandbox.runtimePaths.stateRootPath).resolve("pdfbox")
        return runCatching {
            Files.createDirectories(preferred)
        }.getOrElse {
            Path.of(System.getProperty("java.io.tmpdir"))
        }
    }

    private fun pdfMemoryUsageSetting(): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(pdfScratchDirectory().toFile())

    companion object {
        private val UNIFIED_DIFF_HUNK_HEADER =
            Regex("^@@ -\\d+(?:,\\d+)? \\+\\d+(?:,\\d+)? @@(?: .*)?$")
        private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
        private const val BINARY_SAMPLE_SIZE = 1024
        private val NON_EDITABLE_EXTENSIONS = setOf(
            "7z",
            "avi",
            "bmp",
            "class",
            "doc",
            "docx",
            "dmg",
            "exe",
            "gif",
            "gz",
            "heic",
            "heif",
            "ico",
            "jar",
            "jpeg",
            "jpg",
            "key",
            "mov",
            "mp3",
            "mp4",
            "odp",
            "ods",
            "odt",
            "otf",
            "pdf",
            "png",
            "ppt",
            "pptx",
            "so",
            "svgz",
            "tar",
            "ttf",
            "wav",
            "webm",
            "webp",
            "xls",
            "xlsx",
            "zip",
        )

        val homeStr: String
            get() = listOf(
                System.getProperty("user.home"),
                System.getenv("HOME"),
            ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
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

        /**
         * Opens a bundled classpath resource as a stream.
         */
        fun resourceStream(path: String): InputStream =
            Thread.currentThread().contextClassLoader.getResourceAsStream(path)
                ?: throw IOException("Certificate not found on classpath: $path")

        /**
         * Reads a bundled classpath resource as text.
         */
        fun resourceAsText(path: String): String =
            resourceStream(path).bufferedReader().use { it.readText() }

        /**
         * Normalizes a user-provided path into a canonical absolute file path when that file already exists.
         *
         * This is intentionally conservative: it only succeeds for existing regular files and returns `null`
         * for blank inputs, directories, missing files, or values that do not parse into a concrete target.
         */
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

    /**
     * Snapshot of a text file prepared for edit operations.
     *
     * @property rawText preserves the original bytes decoded as UTF-8
     * @property normalizedTextIndex maps the LF-normalized text back to exact raw offsets
     * @property preferredLineSeparator records the fallback separator to use for newly inserted lines
     */
    data class EditableTextFile(
        val path: String,
        val rawText: String,
        val normalizedTextIndex: NormalizedTextIndex,
        val preferredLineSeparator: String,
    ) {
        val normalizedText: String
            get() = normalizedTextIndex.normalizedText
    }

    data class NormalizedTextIndex(
        val normalizedText: String,
        val rawOffsets: IntArray,
    )

    class CloseablePdfDocument(
        val document: PDDocument,
        private val onClose: () -> Unit = {},
    ) : AutoCloseable {
        override fun close() {
            var failure: Throwable? = null
            try {
                document.close()
            } catch (t: Throwable) {
                failure = t
            }
            try {
                onClose()
            } catch (t: Throwable) {
                if (failure == null) {
                    failure = t
                } else {
                    failure.addSuppressed(t)
                }
            }
            if (failure != null) {
                when (failure) {
                    is IOException -> throw failure
                    is RuntimeException -> throw failure
                    is Error -> throw failure
                    else -> throw RuntimeException(failure)
                }
            }
        }
    }
}

@Suppress("FunctionName")
fun ForbiddenFolder(fixedPath: String) =
    BadInputException("Forbidden directory: $fixedPath. User explicitly restricted this path. Inform him")
