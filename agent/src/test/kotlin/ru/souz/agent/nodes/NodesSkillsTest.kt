package ru.souz.agent.nodes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import ru.souz.agent.graph.Node
import ru.souz.agent.skill.AgentSkill
import ru.souz.agent.skill.AgentSkillMatch
import ru.souz.agent.skill.AgentSkillSource
import ru.souz.agent.skill.AgentSkillSummary
import ru.souz.agent.skill.matchesName
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.graph.GraphRuntime
import ru.souz.graph.RetryPolicy
import ru.souz.llms.LLMRequest

class NodesSkillsTest {
    @Test
    fun `slash invocation activates full skill even when model invocation is disabled`() = runTest {
        val skill = skill(
            name = "weather",
            disableModelInvocation = true,
            userInvocable = true,
        )
        val repository = FakeSkillRepository(
            matches = emptyList(),
            skills = listOf(skill),
        )

        val result = NodesSkills(repository)
            .resolve()
            .execute(seedContext("/weather Moscow"), graphRuntime())

        assertEquals("Moscow", result.input)
        assertEquals(1, repository.loadCalls)
        assertNotNull(result.turnState.activeSkill)
        assertEquals("weather", result.turnState.activeSkill?.skill?.summary?.name)
        assertEquals("Moscow", result.turnState.activeSkill?.remainingInput)
    }

    @Test
    fun `summary retrieval stays lazy until activation`() = runTest {
        val summary = skillSummary(name = "weather")
        val repository = FakeSkillRepository(
            matches = listOf(AgentSkillMatch(summary, score = 0.7f)),
            skills = listOf(skill(name = "weather")),
        )

        val result = NodesSkills(repository)
            .resolve()
            .execute(seedContext("weather in Moscow"), graphRuntime())

        assertEquals("weather in Moscow", result.input)
        assertEquals(0, repository.loadCalls)
        assertEquals(1, result.turnState.relevantSkills.size)
        assertEquals(null, result.turnState.activeSkill)
    }

    private fun seedContext(input: String): AgentContext<String> = AgentContext(
        input = input,
        settings = AgentSettings(
            model = "test-model",
            temperature = 0.0f,
            toolsByCategory = emptyMap(),
            contextSize = 1024,
        ),
        history = emptyList<LLMRequest.Message>(),
        activeTools = emptyList(),
        systemPrompt = "system",
    )

    private fun graphRuntime(): GraphRuntime = GraphRuntime(
        retryPolicy = RetryPolicy(),
        maxSteps = 8,
    )

    private fun skill(
        name: String,
        disableModelInvocation: Boolean = false,
        userInvocable: Boolean = true,
    ): AgentSkill = AgentSkill(
        summary = skillSummary(
            name = name,
            disableModelInvocation = disableModelInvocation,
            userInvocable = userInvocable,
        ),
        body = "# $name\nUse curl.",
    )

    private fun skillSummary(
        name: String,
        disableModelInvocation: Boolean = false,
        userInvocable: Boolean = true,
    ): AgentSkillSummary = AgentSkillSummary(
        name = name,
        description = "Weather helper",
        whenToUse = "Use for weather questions",
        disableModelInvocation = disableModelInvocation,
        userInvocable = userInvocable,
        allowedTools = setOf("Bash"),
        requiresBins = listOf("curl"),
        supportedOs = listOf("linux"),
        source = AgentSkillSource.WORKSPACE,
        folderName = name,
    )

    private class FakeSkillRepository(
        private val matches: List<AgentSkillMatch>,
        private val skills: List<AgentSkill>,
    ) : AgentDesktopInfoRepository {
        var loadCalls: Int = 0
            private set

        override suspend fun search(query: String, limit: Int) = emptyList<ru.souz.db.StorredData>()

        override suspend fun searchSkills(query: String, limit: Int): List<AgentSkillMatch> = matches.take(limit)

        override suspend fun loadSkill(name: String): AgentSkill? {
            loadCalls += 1
            return skills.firstOrNull { it.summary.matchesName(name) }
        }
    }
}
