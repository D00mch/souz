package ru.souz.runtime.sandbox.docker

import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxScope
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DockerRuntimeSandbox(
    override val scope: SandboxScope,
    val hostRoot: Path,
    val imageName: String = DEFAULT_IMAGE_NAME,
    val containerName: String = DockerSandboxIds.defaultContainerName(scope),
    private val autoStart: Boolean = true,
    private val removeContainerOnClose: Boolean = false,
    blockedContainerPaths: List<String> = emptyList(),
) : RuntimeSandbox, AutoCloseable {
    override val mode: SandboxMode = SandboxMode.DOCKER
    private val dockerCli: DockerCli = DockerCli()

    internal val layout: DockerSandboxLayout = DockerSandboxLayout(hostRoot)
    private val containerHandle by lazy {
        DockerContainerHandle(
            dockerCli = dockerCli,
            layout = layout,
            containerName = containerName,
            imageName = imageName,
            userSpec = DockerHostUser.currentOrNull(dockerCli),
        )
    }
    private var started = false
    private var startAttempted = false

    override val runtimePaths: SandboxRuntimePaths = layout.runtimePaths
    override val fileSystem: SandboxFileSystem = DockerSandboxFileSystem(
        layout = layout,
        blockedContainerPaths = blockedContainerPaths,
    )
    private val commands by lazy {
        DockerSandboxCommandExecutor(containerHandle = containerHandle, fileSystem = fileSystem)
    }
    override val commandExecutor = object : SandboxCommandExecutor {
        override suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult {
            withContext(Dispatchers.IO) { start() }
            return commands.execute(request)
        }
    }

    init {
        if (autoStart) {
            start()
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        layout.ensureHostDirectories()
        dockerCli.ensureAvailable()
        dockerCli.ensureImageAvailable(imageName)
        startAttempted = true
        containerHandle.ensureStarted()
        containerHandle.verifyRuntimeExecutables()
        started = true
    }

    @Synchronized
    override fun close() {
        if (removeContainerOnClose && startAttempted) {
            containerHandle.removeIfExists()
            started = false
        }
    }

    companion object {
        const val DEFAULT_IMAGE_NAME: String = "souz-runtime-sandbox:latest"

        fun defaultContainerName(scope: SandboxScope): String = DockerSandboxIds.defaultContainerName(scope)
    }
}
