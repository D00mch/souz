package ru.souz.runtime.sandbox

import com.fasterxml.jackson.annotation.JsonIgnore
import ru.souz.llms.FileContent
import ru.souz.llms.FileReference

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
    val files: List<FileContent> = emptyList(),
    val replyFiles: List<FileContent> = emptyList(),
    val detachedReplyFiles: List<FileReference> = emptyList(),
    @get:JsonIgnore
    val attachments: List<String> = emptyList(),
)

interface SandboxCommandExecutor {
    suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult
}
