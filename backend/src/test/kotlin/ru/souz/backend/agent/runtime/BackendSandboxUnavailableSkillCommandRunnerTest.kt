package ru.souz.backend.agent.runtime

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.skills.SkillCommandExecutor
import ru.souz.tool.skills.ToolInvokeSkill

class BackendSandboxUnavailableSkillCommandRunnerTest {
    @Test
    fun `returns the same non-success sandbox unavailable result for every command runtime`() = runTest {
        val results = SandboxCommandRuntime.entries.map { runtime ->
            BackendSandboxUnavailableSkillCommandRunner.execute(
                bundle = testBundle(),
                bundleHash = "ignored-bundle-hash",
                arguments = SkillCommandExecutor.Args(
                    runtime = runtime,
                    command = listOf("should-not-run"),
                    script = "should-not-run",
                    scriptPath = "scripts/should-not-run.sh",
                ),
                meta = ToolInvocationMeta(userId = "user-a", conversationId = "conversation-a"),
            )
        }

        assertEquals(1, results.distinct().size)
        val result = results.first()
        assertFalse(result.exitCode == 0)
        assertEquals("", result.stdout)
        assertFalse(result.timedOut)
        assertEquals(BACKEND_SANDBOX_UNAVAILABLE_MESSAGE, result.stderr)
        assertEquals(BACKEND_SANDBOX_UNAVAILABLE_ERROR_CODE, result.error?.code)
        assertEquals(BACKEND_SANDBOX_UNAVAILABLE_MESSAGE, result.error?.message)
    }

    @Test
    fun `run skill command serializes the structured sandbox unavailable result`() = runTest {
        val bundle = testBundle()
        val tool = ToolInvokeSkill(
            toolCatalog = BackendNoopAgentToolCatalog,
            toolsFilter = RuntimePassThroughToolsFilter,
            skillBundleProvider = object : ru.souz.agent.skills.registry.SkillBundleProvider {
                override suspend fun listSkills(userId: String) =
                    emptyList<ru.souz.agent.skills.registry.StoredSkill>()

                override suspend fun loadSkillBundle(userId: String, skillId: SkillId) =
                    bundle.takeIf { it.skillId == skillId }

                override suspend fun listSkillInventoryIds(userId: String) = listOf(bundle.skillId)
            },
            commandExecutor = BackendSandboxUnavailableSkillCommandRunner,
        )

        val message = tool.invoke(
            LLMResponse.FunctionCall(
                name = ToolInvokeSkill.NAME,
                arguments = mapOf(
                    "skillId" to bundle.skillId.value,
                    "arguments" to mapOf("runtime" to SandboxCommandRuntime.BASH.name),
                ),
            ),
            ToolInvocationMeta(userId = "user-a", conversationId = "conversation-a"),
        )
        val result = restJsonMapper.readTree(message.content)

        assertEquals(BACKEND_SANDBOX_UNAVAILABLE_ERROR_CODE, result["error"]["code"].asText())
        assertEquals(BACKEND_SANDBOX_UNAVAILABLE_MESSAGE, result["error"]["message"].asText())
        assertFalse(result["exitCode"].asInt() == 0)
    }

    private fun testBundle(): SkillBundle = SkillBundle.fromFiles(
        skillId = SkillId("unavailable-command-test"),
        files = listOf(
            SkillFile(
                normalizedPath = "SKILL.md",
                content = """
                    ---
                    name: unavailable-command-test
                    description: Must never execute on a backend pod.
                    ---

                    Run a command.
                """.trimIndent().toByteArray(),
            ),
        ),
    )
}
