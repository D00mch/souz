package ru.souz.runtime.sandbox.docker

import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.SandboxScope

/**
 * Placeholder for the future Docker-backed sandbox.
 *
 * The shared sandbox contracts are ready for a second runtime mode, but the
 * Docker implementation is intentionally deferred for this step.
 */
class DockerRuntimeSandbox : RuntimeSandbox {
    override val mode: SandboxMode = SandboxMode.DOCKER
    override val scope: SandboxScope
        get() = error("Docker sandbox is not implemented yet.")
    override val runtimePaths: SandboxRuntimePaths
        get() = error("Docker sandbox is not implemented yet.")
    override val fileSystem: SandboxFileSystem
        get() = error("Docker sandbox is not implemented yet.")
    override val commandExecutor: SandboxCommandExecutor
        get() = error("Docker sandbox is not implemented yet.")
}