package ru.souz.tool.skills

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.runBlocking
import ru.souz.agent.skills.activation.SkillId
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.skills.registry.SkillStorageScope
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolRunSkillCommand(
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
    private val skillStorageScope: SkillStorageScope = SkillStorageScope.SINGLE_USER,
) : ToolSetup<ToolRunSkillCommand.Input> {
    data class ActiveSkillInput(
        val skillId: String = "",
        val bundleHash: String = "",
        val supportingFiles: List<String> = emptyList(),
    )

    data class Input(
        @InputParamDescription("Activated Skill ID whose bundle contains the script/supporting file to use.")
        val skillId: String,
        @InputParamDescription("Runtime to execute: BASH, PYTHON, NODE, or PROCESS. Use BASH for shell scripts and PROCESS for argv commands.")
        val runtime: SandboxCommandRuntime = SandboxCommandRuntime.BASH,
        @InputParamDescription("Command argv for PROCESS runtime, for example [\"bash\", \"scripts/run.sh\"]. Leave empty for BASH/PYTHON/NODE.")
        val command: List<String> = emptyList(),
        @InputParamDescription("Inline script for BASH/PYTHON/NODE runtimes. For bundled scripts, call them by relative path, for example: bash scripts/run.sh")
        val script: String? = null,
        @InputParamDescription("Working directory relative to the selected skill bundle root. Defaults to the skill bundle root.")
        val workingDirectory: String? = null,
        @InputParamDescription("Environment variables to pass to the process.")
        val environment: Map<String, String> = emptyMap(),
        @InputParamDescription("Optional stdin passed to the command.")
        val stdin: String? = null,
        @InputParamDescription("Timeout in milliseconds. Defaults to 60000 and is capped at 300000.")
        val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        val activeSkills: List<ActiveSkillInput> = emptyList(),
    )

    override val name: String = NAME

    override val description: String = buildString {
        append("Run a command or script for one of the currently active Skills inside the Souz runtime sandbox. ")
        append("The working directory defaults to the selected skill bundle root, so supporting files can be used by relative path. ")
        append("Use only for files or instructions from active Skills, and only when a skill explicitly needs command execution. ")
        append("Do not use this tool just to list, inspect, or browse skill bundle files. ")
        append("Do not call it for instruction-only/template-only skills that can be followed from the system prompt.")
    }

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Run the active skill helper script",
            params = mapOf(
                "skillId" to "skill-id",
                "runtime" to SandboxCommandRuntime.BASH.name,
                "script" to "bash scripts/run.sh",
            ),
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "exitCode" to ReturnProperty("number", "Process exit code, or -1 on timeout."),
            "timedOut" to ReturnProperty("boolean", "Whether the command timed out."),
            "stdout" to ReturnProperty("string", "Captured stdout, truncated when too large."),
            "stderr" to ReturnProperty("string", "Captured stderr, truncated when too large."),
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking {
        suspendInvoke(input, meta)
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val skillId = SkillId(input.skillId.trim())
        val skill = input.activeSkills.firstOrNull { it.skillId == skillId.value }
            ?: throw BadInputException("Skill is not active for this turn: ${input.skillId}")
        val sandbox = sandboxResolver.resolve(meta)
        val skillRoot = resolveSkillRoot(sandbox = sandbox, skill = skill, userId = meta.userId)
        val workingDirectory = resolveWorkingDirectory(skillRoot, input.workingDirectory)
        val timeoutMillis = input.timeoutMillis.coerceIn(1L, MAX_TIMEOUT_MILLIS)
        val result = sandbox.commandExecutor.execute(
            SandboxCommandRequest(
                runtime = input.runtime,
                command = input.command,
                script = input.script,
                workingDirectory = workingDirectory,
                environment = defaultEnvironment(skill, skillRoot) + input.environment,
                stdin = input.stdin,
                timeoutMillis = timeoutMillis,
            )
        )
        return result.render()
    }

    private fun resolveSkillRoot(
        sandbox: RuntimeSandbox,
        skill: ActiveSkillInput,
        userId: String,
    ): String {
        val fileSystem = sandbox.fileSystem
        val bundleRoot = bundleRootPath(
            skillsRootPath = sandbox.runtimePaths.skillsDirPath,
            userId = userId,
            skill = skill,
        )
        val storedBundle = fileSystem.resolvePath(bundleRoot.toString())
        if (storedBundle.exists && storedBundle.isDirectory) {
            return fileSystem.resolveExistingDirectory(storedBundle.path).path
        }

        val looseRoot = skillRootPath(
            skillsRootPath = sandbox.runtimePaths.skillsDirPath,
            userId = userId,
            skillId = SkillId(skill.skillId),
        )
        val looseBundle = fileSystem.resolvePath(looseRoot.toString())
        if (looseBundle.exists && looseBundle.isDirectory) {
            return fileSystem.resolveExistingDirectory(looseBundle.path).path
        }

        throw BadInputException("Skill bundle root is unavailable for active skill: ${skill.skillId}")
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

    private fun bundleRootPath(
        skillsRootPath: String,
        userId: String,
        skill: ActiveSkillInput,
    ): Path = skillRootPath(skillsRootPath, userId, SkillId(skill.skillId))
        .resolve(BUNDLES_DIRECTORY_NAME)
        .resolve(skill.bundleHash)

    private fun skillRootPath(
        skillsRootPath: String,
        userId: String,
        skillId: SkillId,
    ): Path {
        val skillsRoot = Path.of(skillsRootPath)
        return when (skillStorageScope) {
            SkillStorageScope.SINGLE_USER -> skillsRoot.resolve(skillId.value)
            SkillStorageScope.USER_SCOPED -> skillsRoot
                .resolve("users")
                .resolve(encodeSegment(userId))
                .resolve("skills")
                .resolve(skillId.value)
        }
    }

    private fun defaultEnvironment(skill: ActiveSkillInput, skillRoot: String): Map<String, String> = mapOf(
        "SOUZ_SKILL_ID" to skill.skillId,
        "SOUZ_SKILL_ROOT" to skillRoot,
        "SOUZ_SKILL_SUPPORTING_FILES" to skill.supportingFiles.joinToString(","),
    )

    private fun SandboxCommandResult.render(): String = buildString {
        appendLine("exitCode: $exitCode")
        appendLine("timedOut: $timedOut")
        appendLine("stdout:")
        appendLine(stdout.truncateToolOutput())
        appendLine("stderr:")
        append(stderr.truncateToolOutput())
    }

    private fun String.truncateToolOutput(): String {
        if (length <= MAX_OUTPUT_CHARS) return this
        val truncatedChars = length - MAX_OUTPUT_CHARS
        return take(MAX_OUTPUT_CHARS) + "\n...[truncated $truncatedChars chars]"
    }

    private fun encodeSegment(raw: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))

    companion object {
        const val NAME = "RunSkillCommand"
        private const val BUNDLES_DIRECTORY_NAME = "bundles"
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 300_000L
        private const val MAX_OUTPUT_CHARS = 20_000
    }
}
