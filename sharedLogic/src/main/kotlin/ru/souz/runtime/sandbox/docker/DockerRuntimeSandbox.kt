package ru.souz.runtime.sandbox.docker

import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.SandboxScope
import java.nio.file.Path

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
    private val containerHandle = DockerContainerHandle(
        dockerCli = dockerCli,
        layout = layout,
        containerName = containerName,
        imageName = imageName,
        userSpec = DockerHostUser.currentOrNull(dockerCli),
    )

    override val runtimePaths: SandboxRuntimePaths = layout.runtimePaths
    override val fileSystem: SandboxFileSystem = DockerSandboxFileSystem(
        layout = layout,
        blockedContainerPaths = blockedContainerPaths,
    )
    override val commandExecutor: SandboxCommandExecutor = DockerSandboxCommandExecutor(
        containerHandle = containerHandle,
        fileSystem = fileSystem,
    )

    init {
        if (autoStart) {
            start()
        }
    }

    @Synchronized
    fun start() {
        layout.ensureHostDirectories()
        dockerCli.ensureAvailable()
        dockerCli.ensureImageAvailable(imageName)
        containerHandle.ensureStarted()
        containerHandle.verifyRuntimeExecutables()
    }

    override fun close() {
        if (removeContainerOnClose) {
            containerHandle.removeIfExists()
        }
    }

    companion object {
        const val DEFAULT_IMAGE_NAME: String = "souz-runtime-sandbox:latest"

        fun defaultContainerName(scope: SandboxScope): String = DockerSandboxIds.defaultContainerName(scope)
    }
}
