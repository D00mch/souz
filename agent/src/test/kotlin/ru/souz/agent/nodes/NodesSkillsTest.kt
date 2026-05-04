package ru.souz.agent.nodes

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.skills.SkillActivationPipeline
import ru.souz.agent.skills.activation.SkillContextInjector
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationSeverity
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.ToolInvocationMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodesSkillsTest {
    @Test
    fun `skills node returns updated context when pipeline returns Ready`() = runTest {
        val pipeline = mockk<SkillActivationPipeline>()
        val node = NodesSkills(pipeline).node()
        val original = baseContext(userId = "user-1")
        val updated = original.map(
            history = listOf(
                LLMRequest.Message(
                    role = LLMMessageRole.system,
                    content = """
                        system

                        ${SkillContextInjector.START_MARKER}
                        Injected skill instructions
                        ${SkillContextInjector.END_MARKER}
                    """.trimIndent(),
                )
            ) + original.history.drop(1)
        ) { it }
        coEvery {
            pipeline.run(
                SkillActivationPipeline.Input(
                    userId = "user-1",
                    context = original,
                )
            )
        } returns SkillActivationPipeline.Result.Ready(
            context = updated,
            activatedSkills = emptyList(),
            rejectedSkills = emptyList(),
            selectedSkillIds = emptyList(),
        )

        val result = node.execute(
            ctx = original,
            runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10),
        )

        assertSame(updated, result)
        assertEquals(original.activeTools, result.activeTools)
        assertTrue(result.history.first().content.contains(SkillContextInjector.START_MARKER))
    }

    @Test
    fun `skills node returns fallback context when pipeline returns Blocked`() = runTest {
        val pipeline = mockk<SkillActivationPipeline>()
        val node = NodesSkills(pipeline).node()
        val original = baseContext(userId = "user-1")
        val fallback = original.copy(
            systemPrompt = "fallback-system",
            history = listOf(LLMRequest.Message(LLMMessageRole.system, "fallback-system")) + original.history.drop(1),
        )
        val finding = SkillValidationFinding(
            code = "skills.validation.blocked",
            message = "Validator rejected the skill bundle",
            severity = SkillValidationSeverity.ERROR,
            filePath = "SKILL.md",
        )
        coEvery { pipeline.run(any()) } returns SkillActivationPipeline.Result.Blocked(
            reason = "Skill validation failed",
            findings = listOf(finding),
            selectedSkillIds = emptyList(),
        )
        every { pipeline.withoutSkills(original) } returns fallback

        val result = node.execute(
            ctx = original,
            runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10),
        )

        assertSame(fallback, result)
        assertEquals(original.activeTools, result.activeTools)
        assertEquals("fallback-system", result.systemPrompt)
        verify(exactly = 1) { pipeline.withoutSkills(original) }
    }

    @Test
    fun `tool invocation metadata rejects blank user id`() {
        listOf("", "   ").forEach { userId ->
            assertFailsWith<IllegalArgumentException> {
                ToolInvocationMeta(userId = userId)
            }
        }
    }

    @Test
    fun `skills node rethrows CancellationException`() = runTest {
        val pipeline = mockk<SkillActivationPipeline>()
        val node = NodesSkills(pipeline).node()
        val original = baseContext(userId = "user-1")
        coEvery { pipeline.run(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            node.execute(
                ctx = original,
                runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10),
            )
        }
    }

    @Test
    fun `skills node catches non cancellation exceptions and continues without skills`() = runTest {
        val pipeline = mockk<SkillActivationPipeline>()
        val node = NodesSkills(pipeline).node()
        val original = baseContext(userId = "user-1")
        val fallback = original.copy(
            systemPrompt = "fallback-system",
            history = listOf(LLMRequest.Message(LLMMessageRole.system, "fallback-system")) + original.history.drop(1),
        )
        coEvery { pipeline.run(any()) } throws IllegalStateException("boom")
        every { pipeline.withoutSkills(original) } returns fallback

        val result = node.execute(
            ctx = original,
            runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10),
        )

        assertSame(fallback, result)
        assertEquals(original.activeTools, result.activeTools)
        assertEquals("fallback-system", result.systemPrompt)
        assertTrue(result.history.none { it.content.contains("boom") })
        assertTrue(result.history.none { it.content.contains(SkillContextInjector.START_MARKER) })
        verify(exactly = 1) { pipeline.withoutSkills(original) }
    }

    private fun baseContext(userId: String): AgentContext<String> = AgentContext(
        input = "Summarize this paper",
        settings = AgentSettings(
            model = "gpt-5-nano",
            temperature = 0.1f,
            toolsByCategory = emptyMap(),
        ),
        history = listOf(
            LLMRequest.Message(LLMMessageRole.system, "system"),
            LLMRequest.Message(LLMMessageRole.user, "Summarize this paper"),
        ),
        activeTools = listOf(
            LLMRequest.Function(
                name = "tool.read_file",
                description = "Read file",
                parameters = LLMRequest.Parameters(
                    type = "object",
                    properties = emptyMap(),
                ),
            )
        ),
        systemPrompt = "system",
        toolInvocationMeta = ToolInvocationMeta(userId = userId),
    )
}
