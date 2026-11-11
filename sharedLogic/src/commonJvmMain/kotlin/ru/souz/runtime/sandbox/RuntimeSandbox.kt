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

fun interface RuntimeSandboxFactory {
    fun create(scope: SandboxScope): RuntimeSandbox

    /** Optional storage-only view; null asks the invocation resolver to use its cached runtime. */
    fun fileSystem(scope: SandboxScope): SandboxFileSystem? = null
}
