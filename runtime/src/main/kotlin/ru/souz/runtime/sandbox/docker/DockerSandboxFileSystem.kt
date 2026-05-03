package ru.souz.runtime.sandbox.docker

import org.slf4j.Logger
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.tool.BadInputException
import ru.souz.tool.files.ForbiddenFolder
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

internal class DockerSandboxFileSystem(
    private val layout: DockerSandboxLayout,
    blockedContainerPaths: List<String> = emptyList(),
) : SandboxFileSystem {
    override val runtimePaths: SandboxRuntimePaths = layout.runtimePaths

    private val blockedPaths: List<String> = blockedContainerPaths
        .mapNotNull(::normalizeBlockedPath)
        .distinct()

    override fun resolvePath(rawPath: String): SandboxPathInfo {
        val cleaned = cleanRawPath(rawPath)
        if (cleaned.isEmpty()) {
            throw BadInputException("Path must not be blank")
        }
        val containerPath = expandAndNormalize(cleaned)
        val hostPath = layout.containerPathToHostPath(containerPath)
        val attributes = hostPath?.let(::readAttributes)
        return SandboxPathInfo(
            rawPath = rawPath,
            path = containerPath,
            name = if (containerPath == layout.containerRoot) layout.containerRoot.removePrefix("/") else containerPath.substringAfterLast('/'),
            parentPath = containerParent(containerPath),
            exists = attributes != null,
            isDirectory = attributes?.isDirectory == true,
            isRegularFile = attributes?.isRegularFile == true,
            isSymbolicLink = hostPath?.let(Files::isSymbolicLink) == true,
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
        val hostPath = layout.containerPathToHostPath(path.path) ?: return false
        val effectiveHostPath = resolveEffectiveHostPath(hostPath) ?: return false
        if (!effectiveHostPath.startsWith(layout.hostRoot)) {
            return false
        }
        return blockedPaths
            .mapNotNull(layout::containerPathToHostPath)
            .none(effectiveHostPath::startsWith)
    }

    override fun forbiddenPaths(): List<String> = blockedPaths

    override fun readBytes(path: SandboxPathInfo): ByteArray {
        requireReadableFile(path)
        return Files.readAllBytes(requireHostPath(path))
    }

    override fun readText(path: SandboxPathInfo): String = readBytes(path).toString(StandardCharsets.UTF_8)

    override fun openInputStream(path: SandboxPathInfo): InputStream {
        requireReadableFile(path)
        return Files.newInputStream(requireHostPath(path))
    }

    override fun writeText(path: SandboxPathInfo, content: String) {
        val hostPath = resolveWritableHostPath(path)
        hostPath.parent?.let(Files::createDirectories)
        Files.writeString(hostPath, content, StandardCharsets.UTF_8)
    }

    override fun writeTextAtomically(path: SandboxPathInfo, content: String, logger: Logger) {
        val hostPath = resolveWritableHostPath(path)
        val parent = hostPath.parent ?: throw BadInputException("File has no parent directory")
        Files.createDirectories(parent)
        val tempPath = Files.createTempFile(parent, "${path.name}.", ".tmp")
        try {
            Files.writeString(tempPath, content, StandardCharsets.UTF_8)
            movePath(tempPath, hostPath, replaceExisting = true, logger = logger)
        } finally {
            Files.deleteIfExists(tempPath)
        }
    }

    override fun createDirectory(path: SandboxPathInfo) {
        Files.createDirectories(resolveWritableHostPath(path))
    }

    override fun listDescendants(
        root: SandboxPathInfo,
        maxDepth: Int,
        includeHidden: Boolean,
    ): List<SandboxPathInfo> {
        val hostRoot = requireHostPath(root)
        requireSafePath(root)
        if (!root.exists || !root.isDirectory) {
            throw BadInputException("Invalid directory path: ${root.rawPath}")
        }

        return Files.walk(hostRoot, maxDepth)
            .use { stream ->
                stream
                    .filter { it != hostRoot }
                    .map { hostPath ->
                        val relative = hostRoot.relativize(hostPath)
                        hostPath to relative
                    }
                    .filter { (_, relative) ->
                        includeHidden || relative.none { segment -> segment.toString().startsWith('.') }
                    }
                    .map { (hostPath, _) ->
                        resolveHostPath(hostPath).also(::requireVisibleDescendant)
                    }
                    .toList()
            }
    }

    override fun move(
        source: SandboxPathInfo,
        destination: SandboxPathInfo,
        replaceExisting: Boolean,
        createParents: Boolean,
        logger: Logger?,
    ) {
        requireSafePath(source)
        if (!source.exists) {
            throw BadInputException("Invalid path: ${source.rawPath}")
        }
        val destinationHostPath = resolveWritableHostPath(destination)
        if (createParents) {
            destinationHostPath.parent?.let(Files::createDirectories)
        }
        movePath(
            sourcePath = requireHostPath(source),
            destinationPath = destinationHostPath,
            replaceExisting = replaceExisting,
            logger = logger,
        )
    }

    override fun moveToTrash(path: SandboxPathInfo, logger: Logger?): SandboxPathInfo {
        requireSafePath(path)
        if (!path.exists) {
            throw BadInputException("Invalid path: ${path.rawPath}")
        }
        val sourcePath = requireHostPath(path)
        Files.createDirectories(layout.hostTrashRoot)
        val destination = uniqueTrashTarget(layout.hostTrashRoot, sourcePath.name)
        movePath(
            sourcePath = sourcePath,
            destinationPath = destination,
            replaceExisting = false,
            logger = logger,
        )
        return resolveHostPath(destination)
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

    private fun requireVisibleDescendant(path: SandboxPathInfo) {
        if (path.isSymbolicLink) {
            return
        }
        if (!isPathSafe(path)) {
            throw BadInputException("Unsafe descendant path: ${path.path}")
        }
    }

    private fun requireHostPath(path: SandboxPathInfo): Path =
        layout.containerPathToHostPath(path.path)
            ?: throw BadInputException("Invalid path: ${path.rawPath}")

    private fun resolveWritableHostPath(path: SandboxPathInfo): Path {
        val hostPath = requireHostPath(path)
        val effective = resolveEffectiveHostPath(hostPath) ?: throw BadInputException("Invalid path: ${path.rawPath}")
        if (!effective.startsWith(layout.hostRoot) ||
            blockedPaths.mapNotNull(layout::containerPathToHostPath).any(effective::startsWith)
        ) {
            throw ForbiddenFolder(path.rawPath)
        }
        return effective
    }

    private fun resolveHostPath(hostPath: Path): SandboxPathInfo =
        resolvePath(layout.hostPathToContainerPath(hostPath))

    private fun normalizeBlockedPath(rawPath: String): String? {
        val cleaned = cleanRawPath(rawPath)
        if (cleaned.isBlank()) {
            return null
        }
        val normalized = expandAndNormalize(cleaned)
        return normalized.takeIf(::isUnderContainerRoot)
    }

    /**
     * Relative Docker sandbox paths resolve under `/souz/home` to match the
     * local sandbox behavior where bare relative paths are treated as home-rooted.
     */
    private fun expandAndNormalize(path: String): String {
        val expanded = when {
            path == "~" || path == "\$HOME" || path == "home" -> runtimePaths.homePath
            path.startsWith("~/") -> runtimePaths.homePath + path.removePrefix("~")
            path.startsWith("\$HOME/") -> runtimePaths.homePath + path.removePrefix("\$HOME")
            path.startsWith("/") -> path
            else -> "${runtimePaths.homePath}/$path"
        }
        return normalizeContainerPath(expanded) ?: throw BadInputException("Path must not be blank")
    }

    private fun cleanRawPath(rawPath: String): String = rawPath
        .trim()
        .removeSurrounding("`")
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .removePrefix("file://")
        .trim()

    private fun containerParent(path: String): String? {
        if (path == "/") {
            return null
        }
        val index = path.lastIndexOf('/')
        return when {
            index < 0 -> null
            index == 0 -> "/"
            else -> path.substring(0, index)
        }
    }

    private fun readAttributes(path: Path): BasicFileAttributes? =
        runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()

    private fun resolveEffectiveHostPath(candidate: Path): Path? {
        val normalized = candidate.toAbsolutePath().normalize()
        if (!normalized.startsWith(layout.hostRoot)) {
            return null
        }
        if (containsSymlinkBetween(layout.hostRoot, normalized)) {
            return null
        }
        val existingAncestor = generateSequence(normalized) { it.parent }
            .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            ?: return normalized
        if (Files.isSymbolicLink(existingAncestor)) {
            return null
        }
        val relativeSuffix = existingAncestor.relativize(normalized)
        val resolvedAncestor = runCatching {
            existingAncestor.toRealPath()
        }.getOrElse {
            existingAncestor.toAbsolutePath().normalize()
        }
        if (relativeSuffix.toString().isNotEmpty() && !Files.isDirectory(resolvedAncestor)) {
            return null
        }
        val effective = resolvedAncestor.resolve(relativeSuffix).normalize()
        return effective.takeIf { it.startsWith(layout.hostRoot) }
    }

    private fun containsSymlinkBetween(root: Path, target: Path): Boolean {
        var current: Path? = target
        while (current != null && current != root) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                return true
            }
            current = current.parent
        }
        return Files.exists(root, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(root)
    }

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
