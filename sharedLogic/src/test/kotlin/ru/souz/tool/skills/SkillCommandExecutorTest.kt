package ru.souz.tool.skills

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.tool.BadInputException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkillCommandExecutorTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `executes command from loose active skill root`() = runTest {
        val home = createTempDirectory("skill-command-home-")
        val stateRoot = home.resolve("state").createDirectories()
        val skillRoot = stateRoot.resolve("skills/paper-summarize-academic").createDirectories()
        skillRoot.resolve("scripts").createDirectories()
        skillRoot.resolve("scripts/echo.sh").writeText(
            "printf 'skill=%s pwd=%s input=%s' \"${'$'}SOUZ_SKILL_ID\" \"${'$'}PWD\" \"${'$'}(cat)\""
        )
        val executor = SkillCommandExecutor(
            ToolInvocationRuntimeSandboxResolver.fixed(createSandbox(home, stateRoot))
        )

        val result = executor.execute(
            activeSkill = activeSkill("paper-summarize-academic"),
            arguments = SkillCommandArguments(
                runtime = SandboxCommandRuntime.BASH,
                script = "bash scripts/echo.sh",
                stdin = "hello",
                timeoutMillis = 1_000,
            ),
            meta = ToolInvocationMeta(userId = "user-1"),
        )

        assertEquals(0, result.exitCode)
        assertContains(result.stdout, "skill=paper-summarize-academic")
        assertContains(result.stdout, "pwd=${skillRoot.toRealPath()}")
        assertContains(result.stdout, "input=hello")
    }

    @Test
    fun `rejects script path outside active skill root`() = runTest {
        val home = createTempDirectory("skill-command-escape-home-")
        val stateRoot = home.resolve("state").createDirectories()
        stateRoot.resolve("skills/path-skill").createDirectories()
        stateRoot.resolve("outside.sh").writeText("printf bad")
        val executor = SkillCommandExecutor(
            ToolInvocationRuntimeSandboxResolver.fixed(createSandbox(home, stateRoot))
        )

        assertFailsWith<BadInputException> {
            executor.execute(
                activeSkill = activeSkill("path-skill"),
                arguments = SkillCommandArguments(scriptPath = "../outside.sh", timeoutMillis = 1_000),
                meta = ToolInvocationMeta(userId = "user-1"),
            )
        }
    }

    private fun createSandbox(home: Path, stateRoot: Path): LocalRuntimeSandbox {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        return LocalRuntimeSandbox(
            scope = SandboxScope(userId = "user-1"),
            settingsProvider = settingsProvider,
            homePath = home,
            stateRoot = stateRoot,
        )
    }

    private fun activeSkill(skillId: String): ActiveSkillBinding = ActiveSkillBinding(
        skillId = SkillId(skillId),
        bundleHash = "a".repeat(64),
        supportingFiles = listOf("scripts/echo.sh"),
    )

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)
}
