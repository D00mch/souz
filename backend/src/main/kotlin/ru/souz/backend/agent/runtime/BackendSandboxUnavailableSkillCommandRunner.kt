package ru.souz.backend.agent.runtime

import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandError
import ru.souz.tool.skills.SkillCommandExecutor
import ru.souz.tool.skills.SkillCommandRunner

const val BACKEND_SANDBOX_UNAVAILABLE_ERROR_CODE = "sandbox_unavailable"
const val BACKEND_SANDBOX_UNAVAILABLE_MESSAGE =
    "Command-backed Skills are unavailable until the backend sandbox service is configured."

/** Command boundary that reports unavailability without touching a filesystem or starting a process. */
object BackendSandboxUnavailableSkillCommandRunner : SkillCommandRunner {
    override suspend fun execute(
        bundle: SkillBundle,
        bundleHash: String,
        arguments: SkillCommandExecutor.Args,
        meta: ToolInvocationMeta,
    ): SandboxCommandResult = SandboxCommandResult(
        exitCode = SANDBOX_UNAVAILABLE_EXIT_CODE,
        stdout = "",
        stderr = BACKEND_SANDBOX_UNAVAILABLE_MESSAGE,
        error = SandboxCommandError(
            code = BACKEND_SANDBOX_UNAVAILABLE_ERROR_CODE,
            message = BACKEND_SANDBOX_UNAVAILABLE_MESSAGE,
        ),
    )

    private const val SANDBOX_UNAVAILABLE_EXIT_CODE = 69
}
