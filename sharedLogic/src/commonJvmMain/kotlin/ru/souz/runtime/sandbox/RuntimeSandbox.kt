package ru.souz.runtime.sandbox

enum class SandboxMode {
    LOCAL,
    DOCKER,
    ANDROID,
}

interface RuntimeSandbox {
    val mode: SandboxMode
    val scope: SandboxScope
    val runtimePaths: SandboxRuntimePaths
    val fileSystem: SandboxFileSystem
    val commandExecutor: SandboxCommandExecutor
}

fun interface RuntimeSandboxFactory {
    fun create(scope: SandboxScope): RuntimeSandbox
}
