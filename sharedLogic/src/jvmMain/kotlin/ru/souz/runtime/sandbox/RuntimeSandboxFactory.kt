package ru.souz.runtime.sandbox

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import ru.souz.db.SettingsProvider
import ru.souz.paths.DefaultSouzPaths
import ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox
import ru.souz.runtime.sandbox.docker.DockerSandboxFileSystem
import ru.souz.runtime.sandbox.docker.DockerSandboxLayout
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox

class RuntimeSandboxModeResolver(
    private val rawValueProvider: () -> String? = { System.getenv(SANDBOX_MODE_ENV) },
) {
    fun resolve(): SandboxMode {
        val rawValue = rawValueProvider()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return SandboxMode.LOCAL
        return when (rawValue.lowercase()) {
            "local" -> SandboxMode.LOCAL
            "docker" -> SandboxMode.DOCKER
            else -> error(
                "Unsupported $SANDBOX_MODE_ENV value '$rawValue'. Expected one of: local, docker."
            )
        }
    }

    private companion object {
        const val SANDBOX_MODE_ENV = "SOUZ_SANDBOX_MODE"
    }
}

class DefaultRuntimeSandboxFactory(
    private val settingsProvider: SettingsProvider,
    private val modeResolver: RuntimeSandboxModeResolver = RuntimeSandboxModeResolver(),
    private val localHomePath: Path = DefaultSouzPaths.homeDirectory(),
    private val localStateRoot: Path = DefaultSouzPaths.defaultStateRoot(),
    private val localWorkspaceRoot: Path? = runCatching { Path.of(System.getProperty("user.dir")) }.getOrNull(),
    private val dockerHostRoot: Path = defaultDockerHostRoot(localStateRoot),
    private val dockerImageName: String = DockerRuntimeSandbox.DEFAULT_IMAGE_NAME,
    private val preparedAttachmentsEnabled: Boolean = false,
    private val dockerSandboxCreator: (SandboxScope, Path, String, String, PreparedAttachmentMount?) -> DockerRuntimeSandbox =
        { scope, hostRoot, imageName, containerName, attachments ->
            DockerRuntimeSandbox(
                scope = scope,
                hostRoot = hostRoot,
                imageName = imageName,
                containerName = containerName,
                preparedAttachments = attachments,
            )
        },
) : RuntimeSandboxFactory {
    override fun create(scope: SandboxScope): RuntimeSandbox = when (modeResolver.resolve()) {
        SandboxMode.LOCAL -> localSandbox(scope)

        SandboxMode.DOCKER -> {
            val hostRoot = dockerHostRoot.resolve(scope.storageKey())
            val attachments = attachmentMount(scope)
            dockerSandboxCreator(
                scope,
                hostRoot,
                dockerImageName,
                DockerRuntimeSandbox.defaultContainerName(scope, withPreparedAttachments = attachments != null),
                attachments,
            )
        }
    }

    /** Reads existing storage without creating user directories or contacting Docker. */
    override fun fileSystem(scope: SandboxScope): SandboxFileSystem = when (modeResolver.resolve()) {
        SandboxMode.LOCAL -> localSandbox(scope).fileSystem
        SandboxMode.DOCKER -> DockerSandboxFileSystem(
            DockerSandboxLayout(dockerHostRoot.resolve(scope.storageKey()), preparedAttachments = attachmentMount(scope)),
        )
    }

    override fun preparedAttachmentsRoot(): Path? =
        attachmentRoot.takeIf { preparedAttachmentsEnabled && modeResolver.resolve() == SandboxMode.DOCKER }

    override fun preparedAttachments(scope: SandboxScope): PreparedAttachmentMount? =
        if (modeResolver.resolve() == SandboxMode.DOCKER) attachmentMount(scope) else null

    private val attachmentRoot = dockerHostRoot.toAbsolutePath().normalize().resolve(".managed-attachments")

    private fun attachmentMount(scope: SandboxScope): PreparedAttachmentMount? {
        if (!preparedAttachmentsEnabled) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.storageKey().toByteArray(StandardCharsets.UTF_8))
        return PreparedAttachmentMount(attachmentRoot.resolve("files").resolve(HexFormat.of().formatHex(digest)))
    }

    private fun localSandbox(scope: SandboxScope) = LocalRuntimeSandbox(
        scope = scope,
        settingsProvider = settingsProvider,
        homePath = localHomePath,
        stateRoot = localStateRoot,
        workspaceRoot = localWorkspaceRoot,
    )

    companion object {
        fun defaultDockerHostRoot(stateRoot: Path = DefaultSouzPaths.defaultStateRoot()): Path =
            stateRoot.resolve("runtime-sandboxes").resolve("docker")
    }
}

private fun SandboxScope.storageKey(): String {
    val raw = buildString {
        append(userId)
        conversationId?.let {
            append('\n')
            append(it)
        }
    }
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
}
