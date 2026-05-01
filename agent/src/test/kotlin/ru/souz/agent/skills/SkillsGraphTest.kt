package ru.souz.agent.skills

import kotlinx.coroutines.test.runTest
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.skills.validation.SkillValidationPolicy
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillsGraphTest {
    @Test
    fun `graph returns Ready with no activated skills when selector chooses zero skills`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(
            userId = USER_ID,
            bundle = fixtureBundle(),
        )
        val graph = SkillsGraph(
            registryRepository = repository,
            selector = FakeSkillSelector(emptyList()),
            llmValidator = FakeSkillLlmValidator.approving(),
        )

        val result = graph.run(
            SkillsGraphInput(
                userId = USER_ID,
                context = baseContext("What is 2 + 2?"),
            )
        )

        val ready = assertIs<SkillsGraphResult.Ready>(result)
        assertTrue(ready.activatedSkills.isEmpty())
        assertTrue(ready.context.history.none { it.content.contains("<souz_skills_context>") })
    }

    @Test
    fun `graph validates and activates selected skill`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(
            userId = USER_ID,
            bundle = fixtureBundle(),
        )
        val validator = FakeSkillLlmValidator.approving()
        val graph = SkillsGraph(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = validator,
        )

        val result = graph.run(
            SkillsGraphInput(
                userId = USER_ID,
                context = baseContext("Summarize this academic paper with the right workflow."),
            )
        )

        val ready = assertIs<SkillsGraphResult.Ready>(result)
        assertEquals(1, validator.invocationCount)
        assertEquals(1, ready.activatedSkills.size)
        val contextMessage = ready.context.history.single { it.content.contains("<souz_skills_context>") }
        assertEquals(LLMMessageRole.user, contextMessage.role)
        assertTrue(contextMessage.content.contains("paper_summarize"))
        assertTrue(contextMessage.content.contains("Academic paper summarization"))
    }

    @Test
    fun `graph uses cached approved validation if hash is unchanged`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, fixtureBundle())
        val validator = FakeSkillLlmValidator.approving()
        val graph = SkillsGraph(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = validator,
        )

        repeat(2) {
            val result = graph.run(
                SkillsGraphInput(
                    userId = USER_ID,
                    context = baseContext("Summarize the paper."),
                )
            )
            assertIs<SkillsGraphResult.Ready>(result)
        }

        assertEquals(1, validator.invocationCount)
    }

    @Test
    fun `graph revalidates when hash changes`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, fixtureBundle())
        val validator = FakeSkillLlmValidator.approving()
        val graph = SkillsGraph(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = validator,
        )

        val first = graph.run(
            SkillsGraphInput(
                userId = USER_ID,
                context = baseContext("Summarize the paper."),
            )
        )
        assertIs<SkillsGraphResult.Ready>(first)

        val changedBundle = SkillBundle.fromFiles(
            skillId = fixtureBundle().skillId,
            files = fixtureBundle().files.map { file ->
                if (file.normalizedPath == "README.md") {
                    file.copy(content = (file.contentAsText() + "\nUpdated").encodeToByteArray())
                } else {
                    file
                }
            },
        )
        repository.saveSkillBundle(USER_ID, changedBundle)

        val second = graph.run(
            SkillsGraphInput(
                userId = USER_ID,
                context = baseContext("Summarize the paper again."),
            )
        )

        assertIs<SkillsGraphResult.Ready>(second)
        assertEquals(2, validator.invocationCount)
    }

    @Test
    fun `graph returns Blocked when validation rejects`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, fixtureBundle())
        val graph = SkillsGraph(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = FakeSkillLlmValidator.rejecting("Unsafe"),
        )

        val result = graph.run(
            SkillsGraphInput(
                userId = USER_ID,
                context = baseContext("Summarize this paper."),
            )
        )

        val blocked = assertIs<SkillsGraphResult.Blocked>(result)
        assertTrue(blocked.findings.isNotEmpty())
        assertTrue(blocked.reason.contains("validation", ignoreCase = true))
    }

    @Test
    fun `skills context history message replaces old skills context message on repeated graph pass`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, fixtureBundle())
        val graph = SkillsGraph(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = FakeSkillLlmValidator.approving(),
        )

        val first = assertIs<SkillsGraphResult.Ready>(
            graph.run(
                SkillsGraphInput(
                    userId = USER_ID,
                    context = baseContext("Summarize this paper."),
                )
            )
        )
        val second = assertIs<SkillsGraphResult.Ready>(
            graph.run(
                    SkillsGraphInput(
                        userId = USER_ID,
                        context = first.context.map(
                            history = first.context.history +
                                LLMRequest.Message(
                                    role = LLMMessageRole.assistant,
                                    content = "Previous answer",
                                ) +
                                LLMRequest.Message(
                                    role = LLMMessageRole.user,
                                    content = "Summarize another paper.",
                                )
                        ) { "Summarize another paper." },
                    )
                )
            )

        val skillMessages = second.context.history.filter { it.content.contains("<souz_skills_context>") }
        assertEquals(1, skillMessages.size)
        assertTrue(second.context.history.last().content.contains("Summarize another paper."))
    }

    private fun fixtureBundle(): SkillBundle = SkillBundleLoader.loadDirectory(
        skillId = SkillId("paper-summarize-academic"),
        rootDirectory = skillFixturePath("paper-summarize-academic"),
    )

    private fun baseContext(userInput: String): AgentContext<String> = AgentContext(
        input = userInput,
        settings = AgentSettings(
            model = "gpt-5-nano",
            temperature = 0.1f,
            toolsByCategory = emptyMap(),
        ),
        history = listOf(
            LLMRequest.Message(LLMMessageRole.system, "system"),
            LLMRequest.Message(LLMMessageRole.user, userInput),
        ),
        activeTools = emptyList(),
        systemPrompt = "system",
    )
}

private const val USER_ID = "skills-test-user"
