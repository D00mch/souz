package ru.souz.tool.skills

import io.mockk.every
import io.mockk.mockk
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.skills.registry.SkillStorageScope
import ru.souz.tool.BadInputException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class ToolRunSkillCommandTest {
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
        skillRoot.resolve("SKILL.md").writeText("---\nname: paper\n---\n")
        skillRoot.resolve("scripts").createDirectories()
        skillRoot.resolve("scripts/echo.sh").writeText(
            "printf 'skill=%s pwd=%s input=%s' \"${'$'}SOUZ_SKILL_ID\" \"${'$'}PWD\" \"${'$'}(cat)\""
        )
        val sandbox = createSandbox(home = home, stateRoot = stateRoot)
        val tool = ToolRunSkillCommand(
            sandboxResolver = ToolInvocationRuntimeSandboxResolver.fixed(sandbox),
        )

        val result = tool.suspendInvoke(
            ToolRunSkillCommand.Input(
                skillId = "paper-summarize-academic",
                runtime = SandboxCommandRuntime.BASH,
                script = "bash scripts/echo.sh",
                stdin = "hello",
                timeoutMillis = 1_000,
                activeSkills = listOf(activeSkill("paper-summarize-academic")),
            ),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertContains(result, "exitCode: 0")
        assertContains(result, "skill=paper-summarize-academic")
        assertContains(result, "pwd=${skillRoot.toRealPath()}")
        assertContains(result, "input=hello")
    }

    @Test
    fun `uses meta user id for user scoped skill storage`() = runTest {
        val userId = "backend-user"
        val skillId = "backend-skill"
        val bundleHash = "b".repeat(64)
        val home = createTempDirectory("skill-command-user-home-")
        val stateRoot = home.resolve("state").createDirectories()
        val bundleRoot = stateRoot
            .resolve("skills/users/${encodeSegment(userId)}/skills/$skillId/bundles/$bundleHash")
            .createDirectories()
        bundleRoot.resolve("scripts").createDirectories()
        bundleRoot.resolve("scripts/pwd.sh").writeText("printf '%s' \"${'$'}PWD\"")
        val sandbox = createSandbox(home = home, stateRoot = stateRoot)
        val tool = ToolRunSkillCommand(
            sandboxResolver = ToolInvocationRuntimeSandboxResolver.fixed(sandbox),
            skillStorageScope = SkillStorageScope.USER_SCOPED,
        )

        val result = tool.suspendInvoke(
            ToolRunSkillCommand.Input(
                skillId = skillId,
                runtime = SandboxCommandRuntime.BASH,
                script = "bash scripts/pwd.sh",
                timeoutMillis = 1_000,
                activeSkills = listOf(activeSkill(skillId, bundleHash)),
            ),
            ToolInvocationMeta(userId = userId),
        )

        assertContains(result, "exitCode: 0")
        assertContains(result, bundleRoot.toRealPath().toString())
    }

    @Test
    fun `rejects inactive skill id`() = runTest {
        val home = createTempDirectory("skill-command-inactive-home-")
        val sandbox = createSandbox(home = home, stateRoot = home.resolve("state").createDirectories())
        val tool = ToolRunSkillCommand(
            sandboxResolver = ToolInvocationRuntimeSandboxResolver.fixed(sandbox),
        )

        assertFailsWith<BadInputException> {
            tool.suspendInvoke(
                ToolRunSkillCommand.Input(
                    skillId = "inactive-skill",
                    script = "pwd",
                    timeoutMillis = 1_000,
                    activeSkills = listOf(activeSkill("active-skill")),
                ),
                ToolInvocationMeta(userId = "user-1"),
            )
        }
    }

    private fun createSandbox(
        home: Path,
        stateRoot: Path,
    ): LocalRuntimeSandbox {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        return LocalRuntimeSandbox(
            scope = SandboxScope(userId = "user-1"),
            settingsProvider = settingsProvider,
            homePath = home,
            stateRoot = stateRoot,
        )
    }

    private fun activeSkill(
        skillId: String,
        bundleHash: String = "a".repeat(64),
    ): ToolRunSkillCommand.ActiveSkillInput = ToolRunSkillCommand.ActiveSkillInput(
        skillId = skillId,
        bundleHash = bundleHash,
        supportingFiles = listOf("scripts/echo.sh"),
    )

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)

    private fun encodeSegment(raw: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
}
