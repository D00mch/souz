package ru.souz.runtime.sandbox

import java.nio.file.Path

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

    /** Host storage for prepared attachment metadata and read-only mounts. */
    fun preparedAttachmentsRoot(): Path? = null
    fun preparedAttachments(scope: SandboxScope): PreparedAttachmentMount? = null
}

data class PreparedAttachmentMount(val hostDirectory: Path) {
    companion object {
        const val SANDBOX_PATH = "/souz-attachments"
    }
}
