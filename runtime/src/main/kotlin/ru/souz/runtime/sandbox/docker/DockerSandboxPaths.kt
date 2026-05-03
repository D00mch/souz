package ru.souz.runtime.sandbox.docker

import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.SandboxScope
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal class DockerSandboxLayout(
    hostRoot: Path,
    val containerRoot: String = CONTAINER_ROOT,
) {
    val hostRoot: Path = runCatching {
        hostRoot.toRealPath()
    }.getOrElse {
        hostRoot.toAbsolutePath().normalize()
    }
    val hostHomeRoot: Path = this.hostRoot.resolve("home")
    val hostWorkspaceRoot: Path = this.hostRoot.resolve("workspace")
    val hostStateRoot: Path = this.hostRoot.resolve("state")
    val hostTrashRoot: Path = this.hostRoot.resolve("trash")
    val hostManagedRoots: List<Path> = listOf(
        this.hostRoot,
        hostHomeRoot,
        hostWorkspaceRoot,
        hostStateRoot,
        hostStateRoot.resolve("sessions"),
        hostStateRoot.resolve("vector-index"),
        hostStateRoot.resolve("logs"),
        hostStateRoot.resolve("models"),
        hostStateRoot.resolve("native"),
        hostStateRoot.resolve("skills"),
        hostStateRoot.resolve("skill-validations"),
        hostTrashRoot,
    )

    val runtimePaths: SandboxRuntimePaths = SandboxRuntimePaths(
        homePath = "$containerRoot/home",
        workspaceRootPath = "$containerRoot/workspace",
        stateRootPath = "$containerRoot/state",
        sessionsDirPath = "$containerRoot/state/sessions",
        vectorIndexDirPath = "$containerRoot/state/vector-index",
        logsDirPath = "$containerRoot/state/logs",
        modelsDirPath = "$containerRoot/state/models",
        nativeLibsDirPath = "$containerRoot/state/native",
        skillsDirPath = "$containerRoot/state/skills",
        skillValidationsDirPath = "$containerRoot/state/skill-validations",
    )

    fun ensureHostDirectories() {
        hostManagedRoots.forEach { path ->
            Files.createDirectories(path)
            check(!Files.isSymbolicLink(path)) {
                "Docker sandbox host root must not contain symlink-managed directories: $path"
            }
        }
    }

    fun containerPathToHostPath(containerPath: String): Path? {
        val normalized = normalizeContainerPath(containerPath) ?: return null
        if (!isUnderContainerRoot(normalized)) {
            return null
        }
        val suffix = normalized.removePrefix(containerRoot).removePrefix("/")
        val resolved = if (suffix.isEmpty()) {
            hostRoot
        } else {
            hostRoot.resolve(suffix)
        }.normalize()
        return resolved.takeIf { it.startsWith(hostRoot) }
    }

    fun hostPathToContainerPath(hostPath: Path): String {
        val normalized = hostPath.toAbsolutePath().normalize()
        require(normalized.startsWith(hostRoot)) {
            "Host path is outside the mounted Docker sandbox root: $hostPath"
        }
        val relative = hostRoot.relativize(normalized)
        return if (relative.nameCount == 0) {
            containerRoot
        } else {
            "$containerRoot/${relative.toString().replace('\\', '/')}"
        }
    }

    companion object {
        const val CONTAINER_ROOT = "/souz"
    }
}

internal object DockerSandboxIds {
    const val MAX_CONTAINER_NAME_LENGTH: Int = 63
    private const val PREFIX = "souz-runtime"

    fun defaultContainerName(scope: SandboxScope): String {
        val raw = buildString {
            append(scope.userId)
            scope.conversationId?.let {
                append('-')
                append(it)
            }
        }
        val slug = raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "scope" }
        val hash = digest(raw).take(10)
        val prefix = "$PREFIX-"
        val suffix = "-$hash"
        val availableSlugLength = (MAX_CONTAINER_NAME_LENGTH - prefix.length - suffix.length).coerceAtLeast(1)
        val trimmedSlug = slug.take(availableSlugLength).trim('-').ifEmpty { "scope" }
        return "$prefix$trimmedSlug$suffix"
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
}

internal object DockerHostUser {
    fun currentOrNull(dockerCli: DockerCli): String? {
        val uid = dockerCli.runHostCommand(listOf("id", "-u")).stdout.trim().takeIf(String::isNotBlank) ?: return null
        val gid = dockerCli.runHostCommand(listOf("id", "-g")).stdout.trim().takeIf(String::isNotBlank) ?: return null
        return "$uid:$gid"
    }
}

internal fun DockerCli.runHostCommand(command: List<String>): DockerCliResult {
    val process = ProcessBuilder(command)
        .redirectErrorStream(false)
        .start()
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return DockerCliResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
}

internal fun normalizeContainerPath(path: String): String? {
    if (path.isBlank()) {
        return null
    }
    val segments = ArrayDeque<String>()
    val absolute = path.startsWith("/")
    path.split('/')
        .filter(String::isNotEmpty)
        .forEach { segment ->
            when (segment) {
                "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast() else if (!absolute) segments.addLast("..")
                else -> segments.addLast(segment)
            }
        }
    val normalized = segments.joinToString("/")
    return when {
        absolute && normalized.isEmpty() -> "/"
        absolute -> "/$normalized"
        normalized.isEmpty() -> "."
        else -> normalized
    }
}

internal fun isUnderContainerRoot(path: String, root: String = DockerSandboxLayout.CONTAINER_ROOT): Boolean =
    path == root || path.startsWith("$root/")
