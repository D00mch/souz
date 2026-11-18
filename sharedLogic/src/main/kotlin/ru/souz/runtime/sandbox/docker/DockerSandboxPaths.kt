package ru.souz.runtime.sandbox.docker

import ru.souz.runtime.sandbox.PreparedAttachmentMount
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.SandboxScope
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal class DockerSandboxLayout(
    hostRoot: Path,
    val containerRoot: String = CONTAINER_ROOT,
    preparedAttachments: PreparedAttachmentMount? = null,
) {
    val hostRoot: Path = normalizeHostPath(hostRoot)
    val hostPreparedAttachments: Path? = preparedAttachments?.hostDirectory?.let(::normalizeHostPath)
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
        hostStateRoot.resolve("skills"),
        hostStateRoot.resolve("skill-validations"),
        hostTrashRoot,
    )

    val runtimePaths: SandboxRuntimePaths = SandboxRuntimePaths(
        homePath = "$containerRoot/home",
        workspaceRootPath = "$containerRoot/workspace",
        stateRootPath = "$containerRoot/state",
        sessionsDirPath = "$containerRoot/state/sessions",
        skillsDirPath = "$containerRoot/state/skills",
        skillValidationsDirPath = "$containerRoot/state/skill-validations",
    )

    init {
        hostPreparedAttachments?.let { attachments ->
            require(!attachments.startsWith(this.hostRoot) && !this.hostRoot.startsWith(attachments))
        }
    }

    fun ensureHostDirectories() {
        (hostManagedRoots + listOfNotNull(hostPreparedAttachments)).forEach { path ->
            Files.createDirectories(path)
            check(!Files.isSymbolicLink(path)) {
                "Docker sandbox host root must not contain symlink-managed directories: $path"
            }
        }
    }

    fun containerPathToHostPath(containerPath: String): Path? {
        val normalized = normalizeContainerPath(containerPath) ?: return null
        val root = hostRootFor(normalized) ?: return null
        val prefix = if (isPreparedAttachmentPath(normalized)) PreparedAttachmentMount.SANDBOX_PATH else containerRoot
        val suffix = normalized.removePrefix(prefix).removePrefix("/")
        return root.resolve(suffix).normalize().takeIf { it.startsWith(root) }
    }

    fun hostPathToContainerPath(hostPath: Path): String {
        val normalized = hostPath.toAbsolutePath().normalize()
        val preparedRoot = hostPreparedAttachments?.takeIf(normalized::startsWith)
        val root = preparedRoot ?: hostRoot
        require(normalized.startsWith(root)) {
            "Host path is outside the mounted Docker sandbox root: $hostPath"
        }
        val relative = root.relativize(normalized).toString().replace('\\', '/')
        val prefix = if (preparedRoot != null) PreparedAttachmentMount.SANDBOX_PATH else containerRoot
        return if (relative.isEmpty()) prefix else "$prefix/$relative"
    }

    fun isPreparedAttachmentPath(path: String): Boolean =
        isUnderContainerRoot(path, PreparedAttachmentMount.SANDBOX_PATH)

    fun hostRootFor(path: String): Path? = when {
        isUnderContainerRoot(path, containerRoot) -> hostRoot
        isPreparedAttachmentPath(path) -> hostPreparedAttachments
        else -> null
    }

    companion object {
        const val CONTAINER_ROOT = "/souz"
    }
}

internal object DockerSandboxIds {
    const val MAX_CONTAINER_NAME_LENGTH: Int = 63

    fun defaultContainerName(scope: SandboxScope, withPreparedAttachments: Boolean = false): String {
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
        val prefix = if (withPreparedAttachments) "souz-runtime-ro-" else "souz-runtime-"
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

internal fun normalizeHostPath(path: Path): Path = runCatching {
    path.toRealPath()
}.getOrElse {
    path.toAbsolutePath().normalize()
}
