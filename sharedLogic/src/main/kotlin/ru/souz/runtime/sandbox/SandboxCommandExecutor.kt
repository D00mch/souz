package ru.souz.runtime.sandbox

enum class SandboxCommandRuntime {
    PROCESS,
    BASH,
    PYTHON,
    NODE,
}

data class SandboxCommandRequest(
    val runtime: SandboxCommandRuntime = SandboxCommandRuntime.PROCESS,
    val command: List<String> = emptyList(),
    val script: String? = null,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val stdin: String? = null,
    val timeoutMillis: Long? = null,
)

data class SandboxCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

interface SandboxCommandExecutor {
    suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult
}
