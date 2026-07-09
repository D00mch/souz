package ru.souz.android.sandbox

import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxFileSystem

fun interface AndroidPythonCommandRunner {
    suspend fun execute(
        request: SandboxCommandRequest,
        fileSystem: SandboxFileSystem,
    ): SandboxCommandResult
}
