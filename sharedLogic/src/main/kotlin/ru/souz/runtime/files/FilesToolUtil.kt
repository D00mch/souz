package ru.souz.runtime.files

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.slf4j.Logger
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.ToolInvocationSandboxScopeResolver
import ru.souz.runtime.sandbox.FactoryBackedToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.path.extension

class FilesToolUtil(
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
) {
    constructor(sandbox: RuntimeSandbox) : this(
        ToolInvocationRuntimeSandboxResolver.fixed(sandbox)
    )

    constructor(settingsProvider: SettingsProvider) : this(
        sandboxFactory = DefaultRuntimeSandboxFactory(settingsProvider = settingsProvider),
        scopeResolver = ToolInvocationSandboxScopeResolver {
            SandboxScope.localDefault()
        },
    )

    constructor(
        sandboxFactory: RuntimeSandboxFactory,
        scopeResolver: ToolInvocationSandboxScopeResolver,
    ) : this(
        FactoryBackedToolInvocationRuntimeSandboxResolver(
            sandboxFactory = sandboxFactory,
            scopeResolver = scopeResolver,
        )
    )

    fun runtimeSandbox(meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): RuntimeSandbox =
        sandboxResolver.resolve(meta)

    fun sandboxFileSystem(meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): SandboxFileSystem =
        runtimeSandbox(meta).fileSystem

    val homeStr: String
        get() = homeStr(ToolInvocationMeta.localDefault())

    fun homeStr(meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): String =
        runtimeSandbox(meta).runtimePaths.homePath

    val homeDirectory: File
        get() = File(homeStr).canonicalFile

    val souzDocumentsDirectoryPath: Path
        get() = souzDocumentsDirectoryPath(ToolInvocationMeta.localDefault())

    fun souzDocumentsDirectoryPath(meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): Path =
        Path.of(resolveSouzDocumentsDirectory(meta).path)

    /**
     * Generally, we don't want Agent to mess around anything out of $HOME and everything user disallowed
     */
    fun isPathSafe(file: File): Boolean {
        return isPathSafe(file, ToolInvocationMeta.localDefault())
    }

    fun isPathSafe(file: File, meta: ToolInvocationMeta): Boolean {
        val fileSystem = sandboxFileSystem(meta)
        return fileSystem.isPathSafe(fileSystem.resolvePath(file.path))
    }

    fun isPathSafe(path: SandboxPathInfo, meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): Boolean =
        sandboxFileSystem(meta).isPathSafe(path)

    fun resourceAsText(path: String): String = Companion.resourceAsText(path)

    fun applyDefaultEnvs(path: String): String {
        return applyDefaultEnvs(path, ToolInvocationMeta.localDefault())
    }

    fun applyDefaultEnvs(path: String, meta: ToolInvocationMeta): String =
        sandboxFileSystem(meta).resolvePath(path).path

    /**
     * Resolves a raw path into a canonical existing file and enforces Souz path-safety rules.
     *
     * @throws [BadInputException] for missing or non-file targets
     * @throws [ForbiddenFolder] when the path escapes the allowed area.
     */
    fun resolveSafeExistingFile(rawPath: String): SandboxPathInfo =
        resolveSafeExistingFile(rawPath, ToolInvocationMeta.localDefault())

    fun resolveSafeExistingFile(rawPath: String, meta: ToolInvocationMeta): SandboxPathInfo =
        sandboxFileSystem(meta).resolveExistingFile(rawPath)

    fun resolveSafeExistingDirectory(rawPath: String, meta: ToolInvocationMeta): SandboxPathInfo =
        sandboxFileSystem(meta).resolveExistingDirectory(rawPath)

    fun resolvePath(rawPath: String): SandboxPathInfo = resolvePath(rawPath, ToolInvocationMeta.localDefault())

    fun resolvePath(rawPath: String, meta: ToolInvocationMeta): SandboxPathInfo =
        sandboxFileSystem(meta).resolvePath(rawPath)

    fun listDescendants(
        root: SandboxPathInfo,
        maxDepth: Int = Int.MAX_VALUE,
        includeHidden: Boolean = false,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ): List<SandboxPathInfo> = sandboxFileSystem(meta).listDescendants(root, maxDepth, includeHidden)

    fun readUtf8TextFile(path: SandboxPathInfo, meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): String =
        sandboxFileSystem(meta).readText(path)

    fun readBytes(path: SandboxPathInfo, meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): ByteArray =
        sandboxFileSystem(meta).readBytes(path)

    fun openInputStream(path: SandboxPathInfo, meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): InputStream =
        sandboxFileSystem(meta).openInputStream(path)

    fun localPathOrNull(path: SandboxPathInfo, meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): Path? =
        sandboxFileSystem(meta).localPathOrNull(path)

    fun openPdfDocument(path: SandboxPathInfo, meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): CloseablePdfDocument {
        localPathOrNull(path, meta)?.let { localPath ->
            return CloseablePdfDocument(
                document = Loader.loadPDF(localPath.toFile(), pdfMemoryUsageSetting(meta).streamCache),
            )
        }

        val tempFile = Files.createTempFile(pdfScratchDirectory(meta), "pdf-read-", ".pdf")
        try {
            openInputStream(path, meta).use { input ->
                Files.newOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            return CloseablePdfDocument(
                document = Loader.loadPDF(tempFile.toFile(), pdfMemoryUsageSetting(meta).streamCache),
                onClose = { Files.deleteIfExists(tempFile) },
            )
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tempFile) }
            throw e
        }
    }

    fun writeBytes(
        path: SandboxPathInfo,
        content: ByteArray,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ) = sandboxFileSystem(meta).writeBytes(path, content)

    fun writeUtf8TextFile(
        path: SandboxPathInfo,
        content: String,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ) = sandboxFileSystem(meta).writeText(path, content)

    fun createDirectory(path: SandboxPathInfo, meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()) =
        sandboxFileSystem(meta).createDirectory(path)

    fun movePath(
        source: SandboxPathInfo,
        destination: SandboxPathInfo,
        replaceExisting: Boolean = false,
        createParents: Boolean = false,
        logger: Logger? = null,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ) = sandboxFileSystem(meta).move(source, destination, replaceExisting, createParents, logger)

    fun moveToTrash(
        path: SandboxPathInfo,
        logger: Logger? = null,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ): SandboxPathInfo = sandboxFileSystem(meta).moveToTrash(path, logger)

    /**
     * Loads a file that is eligible for in-place text editing.
     *
     * The file must be safe, present, not in the blocked extension list, not likely binary, and valid UTF-8.
     * The returned [EditableTextFile] includes both raw content and a normalized-to-raw offset index so
     * tools can match on `\n` internally while still preserving untouched raw bytes on write.
     */
    fun readEditableUtf8TextFile(
        file: SandboxPathInfo,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ): EditableTextFile {
        val fileSystem = sandboxFileSystem(meta)
        if (!fileSystem.isPathSafe(file)) throw ForbiddenFolder(file.path)
        if (!file.exists || !file.isRegularFile) {
            throw BadInputException("Invalid file path: ${file.path}")
        }
        if (File(file.path).extension.lowercase() in NON_EDITABLE_EXTENSIONS) {
            val errMsg = "Only plain text, code, and config files can be edited. Unsupported file type:"
            throw BadInputException("$errMsg .${File(file.path).extension.lowercase()}")
        }

        val bytes = fileSystem.readBytes(file)
        if (bytes.isLikelyBinary()) {
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
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ) = sandboxFileSystem(meta).writeTextAtomically(file, content, logger)

    fun resolveDocumentsDirectory(meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): SandboxPathInfo {
        val preferred = resolvePath("~/Documents", meta)
        if (preferred.exists && preferred.isDirectory) {
            return preferred
        }
        val fallback = resolvePath("~/documents", meta)
        if (fallback.exists && fallback.isDirectory) {
            return fallback
        }
        return preferred
    }

    fun resolveSouzDocumentsDirectory(meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): SandboxPathInfo =
        resolvePath("${resolveDocumentsDirectory(meta).path}/souz", meta)

    fun resolveSouzWebAssetsDirectory(meta: ToolInvocationMeta = ToolInvocationMeta.localDefault()): SandboxPathInfo =
        resolvePath("${resolveSouzDocumentsDirectory(meta).path}/web_assets", meta)

    fun <T> withReadableLocalPath(
        path: SandboxPathInfo,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
        prefix: String = "souz-read-",
        suffix: String = path.name.safeTempSuffix(),
        block: (Path) -> T,
    ): T {
        localPathOrNull(path, meta)?.let { return block(it) }
        val tempFile = Files.createTempFile(pdfScratchDirectory(meta), prefix, suffix)
        try {
            Files.write(tempFile, readBytes(path, meta))
            return block(tempFile)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    suspend fun <T> withReadableLocalPathSuspend(
        path: SandboxPathInfo,
        meta: ToolInvocationMeta,
        prefix: String = "souz-read-",
        suffix: String = path.name.safeTempSuffix(),
        block: suspend (Path) -> T,
    ): T {
        withContext(Dispatchers.IO) { localPathOrNull(path, meta) }?.let { return block(it) }

        val tempFile = withContext(Dispatchers.IO) {
            val tempFile = Files.createTempFile(pdfScratchDirectory(meta), prefix, suffix)
            try {
                Files.write(tempFile, readBytes(path, meta))
                tempFile
            } catch (error: Throwable) {
                runCatching { Files.deleteIfExists(tempFile) }
                throw error
            }
        }
        try {
            return block(tempFile)
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(tempFile) }
        }
    }

    fun <T> withWritableLocalPath(
        path: SandboxPathInfo,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
        prefix: String = "souz-write-",
        suffix: String = path.name.safeTempSuffix(),
        block: (Path) -> T,
    ): T {
        localPathOrNull(path, meta)?.let { localPath ->
            localPath.parent?.let(Files::createDirectories)
            return block(localPath)
        }
        val tempFile = Files.createTempFile(pdfScratchDirectory(meta), prefix, suffix)
        return try {
            val result = block(tempFile)
            writeBytes(path, Files.readAllBytes(tempFile), meta)
            result
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

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
     * Moves file from [sourcePath] to [destinationPath].
     *
     * First we request [java.nio.file.StandardCopyOption.ATOMIC_MOVE], which asks the filesystem to make
     * the move atomic (all-or-nothing: readers should see either source or destination state,
     * not a partially moved file). Some filesystems do not support atomic moves for a given
     * source/destination pair; in that case we fall back to a regular move.
     */
    fun moveWithAtomicFallback(
        sourcePath: Path,
        destinationPath: Path,
        logger: Logger,
        replaceExisting: Boolean = false,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ) {
        val fileSystem = sandboxFileSystem(meta)
        fileSystem.move(
            source = resolvePath(sourcePath.toString(), meta),
            destination = resolvePath(destinationPath.toString(), meta),
            replaceExisting = replaceExisting,
            createParents = true,
            logger = logger,
        )
    }

    fun detectPreferredLineSeparator(text: String): String = when {
        text.contains("\r\n") -> "\r\n"
        text.contains('\r') -> "\r"
        else -> "\n"
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun pdfScratchDirectory(meta: ToolInvocationMeta): Path {
        val preferred = runCatching {
            localPathOrNull(resolvePath(runtimeSandbox(meta).runtimePaths.stateRootPath, meta), meta)
                ?.resolve("pdfbox")
        }.getOrNull()
        return runCatching {
            Files.createDirectories(preferred ?: Path.of(System.getProperty("java.io.tmpdir")).resolve("souz-pdfbox"))
        }.getOrElse {
            Path.of(System.getProperty("java.io.tmpdir"))
        }
    }

    private fun pdfMemoryUsageSetting(meta: ToolInvocationMeta): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(pdfScratchDirectory(meta).toFile())

    companion object {
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

        private fun String.safeTempSuffix(): String {
            val extension = Path.of(this).extension.takeIf(String::isNotBlank)
            return if (extension == null) {
                ".tmp"
            } else {
                ".$extension"
            }
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
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as NormalizedTextIndex

            if (normalizedText != other.normalizedText) return false
            if (!rawOffsets.contentEquals(other.rawOffsets)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = normalizedText.hashCode()
            result = 31 * result + rawOffsets.contentHashCode()
            return result
        }
    }

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
