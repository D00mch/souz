package ru.souz.android.python

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.souz.agent.skills.activation.SkillId
import ru.souz.android.sandbox.AndroidRuntimeSandbox
import ru.souz.android.settings.AndroidSettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.skills.registry.FileSystemSkillRegistryRepository
import ru.souz.tool.BadInputException
import ru.souz.tool.skills.ToolRunSkillCommand

@RunWith(AndroidJUnit4::class)
class AndroidPythonSkillToolIntegrationTest {
    private lateinit var context: Context
    private lateinit var sandbox: AndroidRuntimeSandbox
    private lateinit var skillRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sandbox = AndroidRuntimeSandbox(
            context = context,
            settingsProvider = AndroidSettingsProvider(context),
            pythonCommandRunner = ChaquopyPythonSkillRunner(context),
        )
        skillRoot = File(sandbox.runtimePaths.skillsDirPath, SKILL_ID).apply {
            deleteRecursively()
            mkdirs()
        }
        writePythonSkill(skillRoot)
    }

    @After
    fun tearDown() {
        skillRoot.deleteRecursively()
    }

    @Test
    fun loosePythonSkillRunsThroughRegistryAndRunSkillCommand() = runBlocking {
        val repository = FileSystemSkillRegistryRepository(sandbox = sandbox)
        val storedSkill = repository.getSkill(USER_ID, SkillId(SKILL_ID))
        val bundle = repository.loadSkillBundle(USER_ID, SkillId(SKILL_ID))
        val supportingFiles = bundle?.files
            ?.map { it.normalizedPath }
            ?.filterNot { it == "SKILL.md" }
            .orEmpty()

        assertEquals("android_python_echo", storedSkill?.manifest?.name)
        assertTrue(supportingFiles.contains("scripts/main.py"))

        val tool = ToolRunSkillCommand(
            sandboxResolver = ToolInvocationRuntimeSandboxResolver.fixed(sandbox),
        )
        val result = tool.suspendInvoke(
            ToolRunSkillCommand.Input(
                skillId = SKILL_ID,
                runtime = SandboxCommandRuntime.PYTHON,
                scriptPath = "scripts/main.py",
                args = listOf("alpha"),
                stdin = "payload",
                timeoutMillis = SUCCESS_TIMEOUT_MILLIS,
                activeSkills = listOf(
                    ToolRunSkillCommand.ActiveSkillInput(
                        skillId = SKILL_ID,
                        bundleHash = storedSkill?.bundleHash.orEmpty(),
                        supportingFiles = supportingFiles,
                    )
                ),
            ),
            ToolInvocationMeta(userId = USER_ID),
        )

        assertTrue(result, result.contains("exitCode: 0"))
        assertTrue(result, result.contains("helper-ok|alpha|payload|$SKILL_ID|"))
        assertTrue(result, result.contains("SOUZ_SKILL_ROOT"))
    }

    @Test
    fun reservedSkillEnvironmentOverridesAreRejected() = runBlocking {
        val repository = FileSystemSkillRegistryRepository(sandbox = sandbox)
        val storedSkill = requireNotNull(repository.getSkill(USER_ID, SkillId(SKILL_ID)))
        val tool = ToolRunSkillCommand(
            sandboxResolver = ToolInvocationRuntimeSandboxResolver.fixed(sandbox),
        )

        try {
            tool.suspendInvoke(
                ToolRunSkillCommand.Input(
                    skillId = SKILL_ID,
                    runtime = SandboxCommandRuntime.PYTHON,
                    scriptPath = "scripts/main.py",
                    environment = mapOf("SOUZ_SKILL_ROOT" to skillRoot.parentFile?.absolutePath.orEmpty()),
                    timeoutMillis = SUCCESS_TIMEOUT_MILLIS,
                    activeSkills = listOf(
                        ToolRunSkillCommand.ActiveSkillInput(
                            skillId = SKILL_ID,
                            bundleHash = storedSkill.bundleHash,
                            supportingFiles = listOf("helper.py", "scripts/main.py"),
                        )
                    ),
                ),
                ToolInvocationMeta(userId = USER_ID),
            )
            fail("Expected reserved environment override to be rejected.")
        } catch (error: BadInputException) {
            assertTrue(error.message.orEmpty().contains("SOUZ_SKILL_ROOT"))
        }
    }

    private fun writePythonSkill(root: File) {
        File(root, "SKILL.md").writeText(
            """
            ---
            name: android_python_echo
            description: Test skill for Android embedded Python execution.
            ---

            Use `scripts/main.py` through RunSkillCommand with the PYTHON runtime.
            """.trimIndent()
        )
        File(root, "scripts").mkdirs()
        File(root, "helper.py").writeText("VALUE = 'helper-ok'\n")
        File(root, "scripts/main.py").writeText(
            """
            import helper
            import os
            import sys

            stdin = sys.stdin.read()
            print(f"{helper.VALUE}|{sys.argv[1]}|{stdin}|{os.environ['SOUZ_SKILL_ID']}|SOUZ_SKILL_ROOT={os.environ['SOUZ_SKILL_ROOT']}")
            """.trimIndent()
        )
    }

    private companion object {
        const val USER_ID = "android-user"
        const val SKILL_ID = "android-python-echo"
        const val SUCCESS_TIMEOUT_MILLIS = 15_000L
    }
}
