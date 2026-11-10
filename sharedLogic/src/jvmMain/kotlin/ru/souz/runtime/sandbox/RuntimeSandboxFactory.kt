package ru.souz.runtime.sandbox

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
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
    private val dockerSandboxCreator: (SandboxScope, Path, String, String) -> DockerRuntimeSandbox =
        { scope, hostRoot, imageName, containerName ->
            DockerRuntimeSandbox(
                scope = scope,
                hostRoot = hostRoot,
                imageName = imageName,
                containerName = containerName,
            )
        },
) : RuntimeSandboxFactory {
    override fun create(scope: SandboxScope): RuntimeSandbox = when (modeResolver.resolve()) {
        SandboxMode.LOCAL -> localSandbox(scope)

        SandboxMode.DOCKER -> {
            val hostRoot = dockerHostRoot.resolve(scope.storageKey())
            dockerSandboxCreator(
                scope,
                hostRoot,
                dockerImageName,
                DockerRuntimeSandbox.defaultContainerName(scope),
            )
        }
    }

    /** Reads existing storage without creating user directories or contacting Docker. */
    override fun fileSystem(scope: SandboxScope): SandboxFileSystem = when (modeResolver.resolve()) {
        SandboxMode.LOCAL -> localSandbox(scope).fileSystem
        SandboxMode.DOCKER -> DockerSandboxFileSystem(DockerSandboxLayout(dockerHostRoot.resolve(scope.storageKey())))
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
