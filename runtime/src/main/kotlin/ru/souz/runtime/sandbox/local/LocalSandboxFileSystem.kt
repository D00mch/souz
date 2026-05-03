package ru.souz.runtime.sandbox.local

import org.slf4j.Logger
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.tool.BadInputException
import ru.souz.tool.files.ForbiddenFolder
import java.awt.Desktop
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

internal class LocalSandboxFileSystem(
    private val settingsProvider: SettingsProvider,
    override val runtimePaths: SandboxRuntimePaths,
) : SandboxFileSystem {
    private val homeRoot: Path = runCatching {
        Path.of(runtimePaths.homePath).toRealPath()
    }.getOrElse {
        Path.of(runtimePaths.homePath).toAbsolutePath().normalize()
    }

    override fun resolvePath(rawPath: String): SandboxPathInfo {
        val cleaned = cleanRawPath(rawPath)
        if (cleaned.isEmpty()) {
            throw BadInputException("Path must not be blank")
        }
        val expanded = expandUserFacingPath(cleaned)
        val candidate = Path.of(expanded).let { if (it.isAbsolute) it else it.toAbsolutePath() }.normalize()
        val infoPath = if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            runCatching { candidate.toRealPath() }.getOrElse { candidate }
        } else {
            candidate
        }
        val attributes = readAttributes(infoPath)
        return SandboxPathInfo(
            rawPath = rawPath,
            path = infoPath.toString(),
            name = infoPath.fileName?.toString().orEmpty(),
            parentPath = infoPath.parent?.toString(),
            exists = attributes != null,
            isDirectory = attributes?.isDirectory == true,
            isRegularFile = attributes?.isRegularFile == true,
            isSymbolicLink = Files.isSymbolicLink(candidate),
            sizeBytes = attributes?.size(),
        )
    }

    override fun resolveExistingFile(rawPath: String): SandboxPathInfo {
        val resolved = resolvePath(rawPath)
        requireSafePath(resolved)
        if (!resolved.exists || !resolved.isRegularFile) {
            throw BadInputException("Invalid file path: $rawPath")
        }
        return resolved
    }

    override fun resolveExistingDirectory(rawPath: String): SandboxPathInfo {
        val resolved = resolvePath(rawPath)
        requireSafePath(resolved)
        if (!resolved.exists || !resolved.isDirectory) {
            throw BadInputException("Invalid directory path: $rawPath")
        }
        return resolved
    }

    override fun isPathSafe(path: SandboxPathInfo): Boolean {
        val candidate = Path.of(path.path).normalize()
        return candidate.startsWith(homeRoot) &&
            forbiddenPaths().map(Path::of).none(candidate::startsWith)
    }

    override fun forbiddenPaths(): List<String> {
        return settingsProvider.forbiddenFolders.mapNotNull { raw ->
            val cleaned = cleanRawPath(raw)
            if (cleaned.isBlank()) return@mapNotNull null
            val expanded = expandUserFacingPath(cleaned)
            val path = Path.of(expanded).let { if (it.isAbsolute) it else homeRoot.resolve(it) }.normalize()
            val canonical = runCatching {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    path.toRealPath()
                } else {
                    path
                }
            }.getOrDefault(path)
            if (!canonical.startsWith(homeRoot)) return@mapNotNull null
            canonical.toString()
        }.distinct()
    }

    override fun readBytes(path: SandboxPathInfo): ByteArray {
        requireReadableFile(path)
        return Files.readAllBytes(Path.of(path.path))
    }

    override fun readText(path: SandboxPathInfo): String = readBytes(path).toString(StandardCharsets.UTF_8)

    override fun openInputStream(path: SandboxPathInfo): InputStream {
        requireReadableFile(path)
        return Files.newInputStream(Path.of(path.path))
    }

    override fun writeText(path: SandboxPathInfo, content: String) {
        requireSafePath(path)
        val filePath = Path.of(path.path)
        filePath.parent?.let(Files::createDirectories)
        Files.writeString(filePath, content, StandardCharsets.UTF_8)
    }

    override fun writeTextAtomically(path: SandboxPathInfo, content: String, logger: Logger) {
        requireSafePath(path)
        val filePath = Path.of(path.path)
        val parent = filePath.parent ?: throw BadInputException("File has no parent directory")
        Files.createDirectories(parent)
        val tempPath = Files.createTempFile(parent, "${path.name}.", ".tmp")
        try {
            Files.writeString(tempPath, content, StandardCharsets.UTF_8)
            movePath(tempPath, filePath, replaceExisting = true, logger = logger)
        } finally {
            Files.deleteIfExists(tempPath)
        }
    }

    override fun createDirectory(path: SandboxPathInfo) {
        requireSafePath(path)
        Files.createDirectories(Path.of(path.path))
    }

    override fun listDescendants(
        root: SandboxPathInfo,
        maxDepth: Int,
        includeHidden: Boolean,
    ): List<SandboxPathInfo> {
        val rootPath = Path.of(root.path)
        requireSafePath(root)
        if (!root.exists || !root.isDirectory) {
            throw BadInputException("Invalid directory path: ${root.rawPath}")
        }

        return rootPath.toFile().walkTopDown()
            .onEnter { file ->
                val info = resolvePath(file.path)
                file.toPath() == rootPath || (isPathSafe(info) && (includeHidden || !file.name.startsWith('.')))
            }
            .maxDepth(maxDepth)
            .filter { it.toPath() != rootPath }
            .map { resolvePath(it.path) }
            .toList()
    }

    override fun move(
        source: SandboxPathInfo,
        destination: SandboxPathInfo,
        replaceExisting: Boolean,
        createParents: Boolean,
        logger: Logger?,
    ) {
        requireSafePath(source)
        requireSafePath(destination)
        if (!source.exists) {
            throw BadInputException("Invalid path: ${source.rawPath}")
        }
        val destinationPath = Path.of(destination.path)
        if (createParents) {
            destinationPath.parent?.let(Files::createDirectories)
        }
        movePath(
            sourcePath = Path.of(source.path),
            destinationPath = destinationPath,
            replaceExisting = replaceExisting,
            logger = logger,
        )
    }

    override fun moveToTrash(path: SandboxPathInfo, logger: Logger?): SandboxPathInfo {
        requireSafePath(path)
        if (!path.exists) {
            throw BadInputException("Invalid path: ${path.rawPath}")
        }
        val sourcePath = Path.of(path.path)
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            val moved = Desktop.getDesktop().moveToTrash(sourcePath.toFile())
            if (moved) {
                return resolvePath(path.path)
            }
            logger?.warn("Desktop trash operation reported failure for {}", path.path)
        }

        val trashDirectory = resolveTrashDirectory()
        val destination = uniqueTrashTarget(trashDirectory, sourcePath.fileName.toString())
        movePath(
            sourcePath = sourcePath,
            destinationPath = destination,
            replaceExisting = false,
            logger = logger,
        )
        return resolvePath(destination.toString())
    }

    private fun requireReadableFile(path: SandboxPathInfo) {
        requireSafePath(path)
        if (!path.exists || !path.isRegularFile) {
            throw BadInputException("Invalid file path: ${path.rawPath}")
        }
    }

    private fun requireSafePath(path: SandboxPathInfo) {
        if (!isPathSafe(path)) {
            throw ForbiddenFolder(path.rawPath)
        }
    }

    private fun cleanRawPath(rawPath: String): String = rawPath
        .trim()
        .removeSurrounding("`")
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .removePrefix("file://")
        .trim()

    private fun expandUserFacingPath(path: String): String {
        val home = runtimePaths.homePath
        val expanded = when {
            path.startsWith("~") -> path.replaceFirst("~", home)
            path.startsWith("\$HOME") -> path.replaceFirst("\$HOME", home)
            path == "home" -> home
            else -> path
        }
        return when {
            expanded.contains(home) -> expanded
            runCatching { Files.exists(Path.of(expanded)) }.getOrDefault(false) -> expanded
            Path.of(expanded).isAbsolute -> expanded
            else -> homeRoot.resolve(path).normalize().toString()
        }
    }

    private fun readAttributes(path: Path): BasicFileAttributes? =
        runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()

    private fun movePath(
        sourcePath: Path,
        destinationPath: Path,
        replaceExisting: Boolean,
        logger: Logger?,
    ) {
        val atomicOptions = buildList {
            add(StandardCopyOption.ATOMIC_MOVE)
            if (replaceExisting) add(StandardCopyOption.REPLACE_EXISTING)
        }.toTypedArray()
        val fallbackOptions = buildList {
            if (replaceExisting) add(StandardCopyOption.REPLACE_EXISTING)
        }.toTypedArray()
        try {
            Files.move(sourcePath, destinationPath, *atomicOptions)
        } catch (exception: AtomicMoveNotSupportedException) {
            logger?.warn("Failed to make an atomic move", exception)
            Files.move(sourcePath, destinationPath, *fallbackOptions)
        }
    }

    private fun resolveTrashDirectory(): Path {
        val candidates = listOf(
            homeRoot.resolve(".Trash"),
            homeRoot.resolve(".local/share/Trash/files"),
            Path.of(System.getProperty("java.io.tmpdir")).resolve("souz-trash"),
        )
        return candidates.firstOrNull { candidate ->
            runCatching {
                Files.createDirectories(candidate)
                true
            }.getOrDefault(false)
        } ?: throw BadInputException("Unable to resolve Trash directory")
    }

    private fun uniqueTrashTarget(trashDirectory: Path, originalFileName: String): Path {
        var target = trashDirectory.resolve(originalFileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return target
        }

        val file = File(originalFileName)
        val withSuffix = "${file.nameWithoutExtension}-${System.currentTimeMillis()}"
        val extensionSuffix = if (file.extension.isBlank()) "" else ".${file.extension}"
        target = trashDirectory.resolve(withSuffix + extensionSuffix)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw BadInputException("Unable to move file to Trash. Target exists: $target")
        }
        return target
    }
}
