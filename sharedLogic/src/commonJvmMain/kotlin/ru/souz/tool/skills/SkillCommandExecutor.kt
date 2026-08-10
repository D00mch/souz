package ru.souz.tool.skills

import java.nio.file.Path
import ru.souz.agent.skills.SkillId
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.BadInputException
import ru.souz.tool.InputParamDescription

internal data class SkillCommandArguments(
    @InputParamDescription("Runtime to execute: BASH, PYTHON, NODE, or PROCESS. Use BASH for shell scripts and PROCESS for argv commands.")
    val runtime: SandboxCommandRuntime = SandboxCommandRuntime.BASH,
    @InputParamDescription("Command argv for PROCESS runtime, for example [\"bash\", \"scripts/run.sh\"]. Leave empty for BASH/PYTHON/NODE.")
    val command: List<String> = emptyList(),
    @InputParamDescription("Inline script for BASH/PYTHON/NODE runtimes. For bundled scripts, call them by relative path, for example: bash scripts/run.sh")
    val script: String? = null,
    @InputParamDescription("Path to a bundled script inside the active Skill root. Prefer this over inline script when running supporting scripts.")
    val scriptPath: String? = null,
    @InputParamDescription("Arguments to pass to scriptPath, or to the inline script process as positional arguments.")
    val args: List<String> = emptyList(),
    @InputParamDescription("Working directory relative to the selected skill bundle root. Defaults to the skill bundle root.")
    val workingDirectory: String? = null,
    @InputParamDescription("Environment variables to pass to the process.")
    val environment: Map<String, String> = emptyMap(),
    @InputParamDescription("Optional stdin passed to the command.")
    val stdin: String? = null,
    @InputParamDescription("Timeout in milliseconds. Defaults to 60000 and is capped at 300000.")
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
)

internal data class ActiveSkillBinding(
    val skillId: SkillId,
    val bundleHash: String,
    val supportingFiles: List<String> = emptyList(),
)

class SkillCommandExecutor(
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
) {
    internal suspend fun execute(
        activeSkill: ActiveSkillBinding,
        arguments: SkillCommandArguments,
        meta: ToolInvocationMeta,
    ): SandboxCommandResult {
        val sandbox = sandboxResolver.resolve(meta)
        val skillRoot = resolveSkillRoot(sandbox, activeSkill)
        val workingDirectory = resolveWorkingDirectory(skillRoot, arguments.workingDirectory)
        val scriptPath = resolveScriptPath(sandbox, skillRoot, arguments.scriptPath)
        return sandbox.commandExecutor.execute(
            SandboxCommandRequest(
                runtime = arguments.runtime,
                command = arguments.command,
                script = arguments.script,
                scriptPath = scriptPath,
                args = arguments.args,
                workingDirectory = workingDirectory,
                environment = defaultEnvironment(activeSkill, skillRoot) + arguments.environment,
                stdin = arguments.stdin,
                timeoutMillis = arguments.timeoutMillis.coerceIn(1L, MAX_TIMEOUT_MILLIS),
            )
        )
    }

    private fun resolveSkillRoot(
        sandbox: RuntimeSandbox,
        activeSkill: ActiveSkillBinding,
    ): String {
        val fileSystem = sandbox.fileSystem
        val bundleRoot = bundleRootPath(sandbox.runtimePaths.skillsDirPath, activeSkill)
        val storedBundle = fileSystem.resolvePath(bundleRoot.toString())
        if (storedBundle.exists && storedBundle.isDirectory) {
            return fileSystem.resolveExistingDirectory(storedBundle.path).path
        }

        val looseRoot = skillRootPath(sandbox.runtimePaths.skillsDirPath, activeSkill.skillId)
        val looseBundle = fileSystem.resolvePath(looseRoot.toString())
        if (looseBundle.exists && looseBundle.isDirectory) {
            return fileSystem.resolveExistingDirectory(looseBundle.path).path
        }

        throw BadInputException("Skill bundle root is unavailable for active skill: ${activeSkill.skillId.value}")
    }

    private fun resolveWorkingDirectory(skillRoot: String, rawWorkingDirectory: String?): String {
        val skillRootPath = Path.of(skillRoot).normalize()
        val relative = rawWorkingDirectory
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "."
        val workingDirectory = skillRootPath.resolve(relative).normalize()
        if (!workingDirectory.startsWith(skillRootPath)) {
            throw BadInputException("workingDirectory must stay inside the selected skill bundle root.")
        }
        return workingDirectory.toString()
    }

    private fun resolveScriptPath(
        sandbox: RuntimeSandbox,
        skillRoot: String,
        rawScriptPath: String?,
    ): String? {
        val scriptPath = rawScriptPath
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val skillRootPath = Path.of(skillRoot).normalize()
        val resolved = skillRootPath.resolve(scriptPath).normalize()
        if (!resolved.startsWith(skillRootPath)) {
            throw BadInputException("scriptPath must stay inside the selected skill bundle root.")
        }
        return sandbox.fileSystem.resolveExistingFile(resolved.toString()).path
    }

    private fun bundleRootPath(
        skillsRootPath: String,
        activeSkill: ActiveSkillBinding,
    ): Path = skillRootPath(skillsRootPath, activeSkill.skillId)
        .resolve(BUNDLES_DIRECTORY_NAME)
        .resolve(activeSkill.bundleHash)

    private fun skillRootPath(
        skillsRootPath: String,
        skillId: SkillId,
    ): Path = Path.of(skillsRootPath).resolve(skillId.value)

    private fun defaultEnvironment(
        activeSkill: ActiveSkillBinding,
        skillRoot: String,
    ): Map<String, String> = mapOf(
        "SOUZ_SKILL_ID" to activeSkill.skillId.value,
        "SOUZ_SKILL_ROOT" to skillRoot,
        "SOUZ_SKILL_SUPPORTING_FILES" to activeSkill.supportingFiles.joinToString(","),
    )
}

private const val BUNDLES_DIRECTORY_NAME = "bundles"
private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
private const val MAX_TIMEOUT_MILLIS = 300_000L
