package ru.souz.runtime.sandbox

enum class SandboxMode {
    LOCAL,
    DOCKER,
}

interface RuntimeSandbox {
    val mode: SandboxMode
    val scope: SandboxScope
    val runtimePaths: SandboxRuntimePaths
    val fileSystem: SandboxFileSystem
    val commandExecutor: SandboxCommandExecutor
}
