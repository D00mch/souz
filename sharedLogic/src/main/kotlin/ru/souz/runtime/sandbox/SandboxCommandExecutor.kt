package ru.souz.runtime.sandbox

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
    val files: List<ru.souz.llms.FileContent> = emptyList(),
    val replyFiles: List<ru.souz.llms.FileContent> = emptyList(),
    val detachedReplyFiles: List<ru.souz.llms.FileReference> = emptyList(),
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val attachments: List<String> = emptyList(),
)

interface SandboxCommandExecutor {
    suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult
}
