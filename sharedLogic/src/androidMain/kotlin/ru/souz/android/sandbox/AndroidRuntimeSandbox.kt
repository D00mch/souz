package ru.souz.android.sandbox

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import ru.souz.db.SettingsProvider
import ru.souz.runtime.files.ForbiddenFolder
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.SandboxUserFacingPathNormalizer
import ru.souz.runtime.sandbox.startSandboxCommandOutputCapture
import ru.souz.tool.BadInputException
import kotlin.io.path.name

class AndroidRuntimeSandboxFactory(
    private val context: Context,
    private val settingsProvider: SettingsProvider,
    private val pythonCommandRunner: AndroidPythonCommandRunner? = null,
) : RuntimeSandboxFactory {
    override fun create(scope: SandboxScope): RuntimeSandbox =
        AndroidRuntimeSandbox(context.applicationContext, settingsProvider, scope, pythonCommandRunner)
}

class AndroidRuntimeSandbox(
    context: Context,
    settingsProvider: SettingsProvider,
    override val scope: SandboxScope = SandboxScope(userId = "android-user"),
    pythonCommandRunner: AndroidPythonCommandRunner? = null,
) : RuntimeSandbox {
    override val mode: SandboxMode = SandboxMode.ANDROID
    override val fileSystem: SandboxFileSystem = AndroidSandboxFileSystem(context, settingsProvider)
    override val runtimePaths: SandboxRuntimePaths = fileSystem.runtimePaths
    override val commandExecutor: SandboxCommandExecutor = AndroidSkillCommandExecutor(fileSystem, pythonCommandRunner)
}

class AndroidSkillCommandExecutor(
    private val fileSystem: SandboxFileSystem,
    private val pythonCommandRunner: AndroidPythonCommandRunner? = null,
) : SandboxCommandExecutor {
    override suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult {
        if (request.runtime == SandboxCommandRuntime.PYTHON) {
            return pythonCommandRunner?.execute(request, fileSystem)
                ?: unavailableRuntime(request.runtime)
        }
        if (request.runtime == SandboxCommandRuntime.NODE) {
            return unavailableRuntime(request.runtime)
        }

        return withContext(Dispatchers.IO) {
            val workingDirectory = request.workingDirectory
                ?.let(fileSystem::resolveExistingDirectory)
                ?.path
            val command = request.toProcessCommand()
            val process = ProcessBuilder(command).apply {
                workingDirectory?.let { directory(File(it)) }
                redirectErrorStream(false)
                environment().putAll(request.environment)
            }.start()
            val output = process.startSandboxCommandOutputCapture("android-skill-command")

            request.stdin?.let { input ->
                process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    writer.write(input)
                }
            } ?: process.outputStream.close()

            val timedOut = request.timeoutMillis?.let { timeout ->
                !process.waitFor(timeout, TimeUnit.MILLISECONDS)
            } ?: run {
                process.waitFor()
                false
            }

            if (timedOut) {
                process.destroyForcibly()
            }
            output.awaitDrainedOrClose()

            SandboxCommandResult(
                exitCode = if (timedOut) -1 else process.exitValue(),
                stdout = output.stdoutText(),
                stderr = output.stderrText(),
                timedOut = timedOut,
            )
        }
    }

    private fun SandboxCommandRequest.toProcessCommand(): List<String> = when (runtime) {
        SandboxCommandRuntime.PROCESS -> {
            if (command.isEmpty()) {
                throw BadInputException("command must not be empty for PROCESS runtime.")
            }
            command
        }

        SandboxCommandRuntime.BASH -> scriptPath
            ?.let { listOf(ANDROID_SHELL, fileSystem.resolveExistingFile(it).path) + args }
            ?: (listOf(
                ANDROID_SHELL,
                "-c",
                inlineShellCompatibility(requireNotNull(script) { "script is required for BASH runtime." }),
                "sh",
            ) + args)

        SandboxCommandRuntime.NODE -> error("Unsupported Android runtime reached process command creation: $runtime")
        SandboxCommandRuntime.PYTHON -> error("Unsupported Android runtime reached process command creation: $runtime")
    }

    private fun inlineShellCompatibility(script: String): String =
        $$"bash() { $$ANDROID_SHELL \"$@\"; }\n$$script"

    private fun unavailableRuntime(runtime: SandboxCommandRuntime): SandboxCommandResult =
        SandboxCommandResult(
            exitCode = 127,
            stdout = "",
            stderr = "$runtime execution is not available in the Android runtime sandbox.",
        )

    private companion object {
        const val ANDROID_SHELL = "/system/bin/sh"
    }
}

class AndroidSandboxFileSystem(
    context: Context,
    private val settingsProvider: SettingsProvider,
) : SandboxFileSystem {
    private val filesRoot: Path = context.filesDir.toPath().toAbsolutePath().normalize()
    private val homeRoot: Path = filesRoot.resolve("souz-home").normalize()
    private val workspaceRoot: Path = filesRoot.resolve("souz-workspace").normalize()
    private val stateRoot: Path = filesRoot.resolve("souz-state").normalize()
    private val sandboxRoots: List<Path> = listOf(homeRoot, workspaceRoot, stateRoot)
    private val allowedRoots: List<Path>
        get() = sandboxRoots.map { root ->
            resolveEffectivePath(root) ?: root.toAbsolutePath().normalize()
        }

    override val runtimePaths: SandboxRuntimePaths = SandboxRuntimePaths(
        homePath = homeRoot.toString(),
        workspaceRootPath = workspaceRoot.toString(),
        stateRootPath = stateRoot.toString(),
        sessionsDirPath = stateRoot.resolve("sessions").toString(),
        vectorIndexDirPath = stateRoot.resolve("vector-index").toString(),
        logsDirPath = stateRoot.resolve("logs").toString(),
        modelsDirPath = stateRoot.resolve("models").toString(),
        nativeLibsDirPath = stateRoot.resolve("native").toString(),
        skillsDirPath = stateRoot.resolve("skills").toString(),
        skillValidationsDirPath = stateRoot.resolve("skill-validations").toString(),
    )

    init {
        listOf(
            homeRoot,
            workspaceRoot,
            stateRoot,
            Paths.get(runtimePaths.sessionsDirPath),
            Paths.get(runtimePaths.vectorIndexDirPath),
            Paths.get(runtimePaths.logsDirPath),
            Paths.get(runtimePaths.modelsDirPath),
            Paths.get(runtimePaths.nativeLibsDirPath),
            Paths.get(runtimePaths.skillsDirPath),
            Paths.get(runtimePaths.skillValidationsDirPath),
            stateRoot.resolve("tmp"),
        ).forEach(Files::createDirectories)
    }

    override fun resolvePath(rawPath: String): SandboxPathInfo {
        val cleaned = cleanRawPath(rawPath)
        if (cleaned.isEmpty()) {
            throw BadInputException("Path must not be blank")
        }
        val candidate = Paths.get(SandboxUserFacingPathNormalizer.normalize(cleaned, runtimePaths))
            .toAbsolutePath()
            .normalize()
        val attributes = readAttributes(candidate)
        return SandboxPathInfo(
            rawPath = rawPath,
            path = candidate.toString(),
            name = candidate.fileName?.toString().orEmpty(),
            parentPath = candidate.parent?.toString(),
            exists = attributes != null,
            isDirectory = attributes?.isDirectory == true,
            isRegularFile = attributes?.isRegularFile == true,
            isSymbolicLink = attributes?.isSymbolicLink == true || Files.isSymbolicLink(candidate),
            sizeBytes = attributes?.size(),
        )
    }

    override fun resolveExistingFile(rawPath: String): SandboxPathInfo =
        resolvePath(rawPath).also { path ->
            requireSafePath(path)
            if (!path.exists || !path.isRegularFile) {
                throw BadInputException("Invalid file path: $rawPath")
            }
        }

    override fun resolveExistingDirectory(rawPath: String): SandboxPathInfo =
        resolvePath(rawPath).also { path ->
            requireSafePath(path)
            if (!path.exists || !path.isDirectory) {
                throw BadInputException("Invalid directory path: $rawPath")
            }
        }

    override fun isPathSafe(path: SandboxPathInfo): Boolean {
        val effectivePath = resolveEffectivePath(Paths.get(path.path)) ?: return false
        return allowedRoots.any(effectivePath::startsWith) &&
            forbiddenPaths().map { Paths.get(it) }.none(effectivePath::startsWith)
    }

    override fun forbiddenPaths(): List<String> =
        settingsProvider.forbiddenFolders.mapNotNull { raw ->
            val cleaned = cleanRawPath(raw)
            if (cleaned.isBlank()) return@mapNotNull null
            val candidate = Paths.get(SandboxUserFacingPathNormalizer.normalize(cleaned, runtimePaths))
                .toAbsolutePath()
                .normalize()
            val effectivePath = resolveEffectivePath(candidate) ?: return@mapNotNull null
            effectivePath
                .takeIf { path -> allowedRoots.any(path::startsWith) }
                ?.toString()
        }.distinct()

    override fun readBytes(path: SandboxPathInfo): ByteArray {
        requireReadableFile(path)
        return Files.readAllBytes(Paths.get(path.path))
    }

    override fun readText(path: SandboxPathInfo): String =
        readBytes(path).toString(StandardCharsets.UTF_8)

    override fun openInputStream(path: SandboxPathInfo): InputStream {
        requireReadableFile(path)
        return Files.newInputStream(Paths.get(path.path))
    }

    override fun localPathOrNull(path: SandboxPathInfo): Path? {
        requireSafePath(path)
        return Paths.get(path.path)
    }

    override fun writeBytes(path: SandboxPathInfo, content: ByteArray) {
        val filePath = resolveWritablePath(path)
        filePath.parent?.let(Files::createDirectories)
        Files.write(filePath, content)
    }

    override fun writeText(path: SandboxPathInfo, content: String) {
        val filePath = resolveWritablePath(path)
        filePath.parent?.let(Files::createDirectories)
        Files.write(filePath, content.toByteArray(StandardCharsets.UTF_8))
    }

    override fun writeTextAtomically(path: SandboxPathInfo, content: String, logger: Logger) {
        val filePath = resolveWritablePath(path)
        val parent = filePath.parent ?: throw BadInputException("File has no parent directory")
        Files.createDirectories(parent)
        val tempPath = Files.createTempFile(parent, "${path.name}.", ".tmp")
        try {
            Files.write(tempPath, content.toByteArray(StandardCharsets.UTF_8))
            movePath(tempPath, filePath, replaceExisting = true, logger = logger)
        } finally {
            Files.deleteIfExists(tempPath)
        }
    }

    override fun createDirectory(path: SandboxPathInfo) {
        Files.createDirectories(resolveWritablePath(path))
    }

    override fun delete(path: SandboxPathInfo, recursively: Boolean) {
        requireSafePath(path)
        if (!path.exists) return
        val sourcePath = Paths.get(path.path)
        if (!recursively || path.isSymbolicLink || !path.isDirectory) {
            Files.deleteIfExists(sourcePath)
            return
        }
        Files.walk(sourcePath).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    override fun listDescendants(
        root: SandboxPathInfo,
        maxDepth: Int,
        includeHidden: Boolean,
    ): List<SandboxPathInfo> {
        requireSafePath(root)
        if (!root.exists || !root.isDirectory) {
            throw BadInputException("Invalid directory path: ${root.rawPath}")
        }
        val rootPath = Paths.get(root.path)
        return Files.walk(rootPath, maxDepth)
            .use { stream ->
                stream
                    .filter { it != rootPath }
                    .map { resolvePath(it.toString()) }
                    .filter(::isPathSafe)
                    .filter { info -> includeHidden || !info.relativeSegments(rootPath).any { it.startsWith(".") } }
                    .sorted(Comparator.comparing<SandboxPathInfo, String> { it.path })
                    .collect(Collectors.toList())
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
        val sourcePath = Paths.get(source.path).toAbsolutePath().normalize()
        if (allowedRoots.any { it == sourcePath }) {
            throw BadInputException("Cannot move Android sandbox root: ${source.rawPath}")
        }
        val destinationPath = resolveWritablePath(destination)
        if (sourcePath == destinationPath || destinationPath.startsWith(sourcePath)) {
            throw BadInputException("Cannot move ${source.rawPath} into itself.")
        }
        if (createParents) {
            destinationPath.parent?.let(Files::createDirectories)
        } else {
            val parent = destinationPath.parent
            if (parent != null && !Files.isDirectory(parent)) {
                throw BadInputException("Destination parent does not exist: $parent")
            }
        }
        if (destination.exists && replaceExisting) {
            delete(destination, recursively = true)
        }
        movePath(sourcePath, destinationPath, replaceExisting = replaceExisting, logger = logger)
    }

    override fun moveToTrash(path: SandboxPathInfo, logger: Logger?): SandboxPathInfo {
        requireSafePath(path)
        if (!path.exists) {
            throw BadInputException("Invalid path: ${path.rawPath}")
        }
        val trashDirectory = stateRoot.resolve(".trash")
        Files.createDirectories(trashDirectory)
        val destination = uniqueTrashTarget(trashDirectory, Paths.get(path.path).name)
        movePath(Paths.get(path.path), destination, replaceExisting = false, logger = logger)
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

    private fun resolveWritablePath(path: SandboxPathInfo): Path {
        val effectivePath = resolveEffectivePath(Paths.get(path.path))
            ?: throw BadInputException("Invalid path: ${path.rawPath}")
        if (!allowedRoots.any(effectivePath::startsWith) ||
            forbiddenPaths().map { Paths.get(it) }.any(effectivePath::startsWith)
        ) {
            throw ForbiddenFolder(path.rawPath)
        }
        return effectivePath
    }

    private fun resolveEffectivePath(candidate: Path): Path? {
        val normalized = candidate.toAbsolutePath().normalize()
        val existingAncestor = generateSequence(normalized) { it.parent }
            .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            ?: return normalized
        val relativeSuffix = existingAncestor.relativize(normalized)
        val resolvedAncestor = runCatching {
            existingAncestor.toRealPath()
        }.getOrElse {
            existingAncestor.toAbsolutePath().normalize()
        }
        if (relativeSuffix.toString().isNotEmpty() && !Files.isDirectory(resolvedAncestor)) {
            return null
        }
        return resolvedAncestor.resolve(relativeSuffix).normalize()
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
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return target
        val file = File(originalFileName)
        val withSuffix = "${file.nameWithoutExtension}-${System.currentTimeMillis()}"
        val extensionSuffix = if (file.extension.isBlank()) "" else ".${file.extension}"
        target = trashDirectory.resolve(withSuffix + extensionSuffix)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw BadInputException("Unable to move file to Trash. Target exists: $target")
        }
        return target
    }

    private fun readAttributes(path: Path): BasicFileAttributes? =
        runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()

    private fun cleanRawPath(rawPath: String): String = rawPath
        .trim()
        .removeSurrounding("`")
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .removePrefix("file://")
        .trim()

    private fun SandboxPathInfo.relativeSegments(rootPath: Path): List<String> {
        val relative = rootPath.relativize(Paths.get(path))
        return relative.map { it.toString() }
    }
}
