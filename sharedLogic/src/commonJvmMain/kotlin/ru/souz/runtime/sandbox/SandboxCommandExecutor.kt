package ru.souz.runtime.sandbox

import com.fasterxml.jackson.annotation.JsonInclude

enum class SandboxCommandRuntime {
    PROCESS,

    /**
     * Shell script runtime. Local and Docker sandboxes use GNU Bash when available.
     */
    BASH,

    PYTHON,
    NODE,
}

data class SandboxCommandRequest(
    val runtime: SandboxCommandRuntime = SandboxCommandRuntime.PROCESS,
    val command: List<String> = emptyList(),
    val script: String? = null,
    val scriptPath: String? = null,
    val args: List<String> = emptyList(),
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
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val error: SandboxCommandError? = null,
)

data class SandboxCommandError(
    val code: String,
    val message: String,
)

interface SandboxCommandExecutor {
    suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult
}
