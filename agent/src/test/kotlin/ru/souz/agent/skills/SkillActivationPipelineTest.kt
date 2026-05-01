package ru.souz.agent.skills

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.activation.SkillContextInjector
import ru.souz.agent.skills.implementations.activation.FakeSkillLlmValidator
import ru.souz.agent.skills.implementations.activation.FakeSkillSelector
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.implementations.bundle.SkillBundleLoader
import ru.souz.agent.skills.bundle.skillFixturePath
import ru.souz.agent.skills.implementations.registry.InMemorySkillRegistryRepository
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.selection.SkillSelector
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillActivationPipelineTest {
    @Test
    fun `pipeline returns Ready with no activated skills when selector chooses zero skills`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(
            userId = USER_ID,
            bundle = fixtureBundle(),
        )
        val pipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = FakeSkillSelector(emptyList()),
            llmValidator = FakeSkillLlmValidator.approving(),
        )

        val result = pipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext("What is 2 + 2?"),
            )
        )

        val ready = assertIs<SkillActivationPipeline.Result.Ready>(result)
        assertTrue(ready.activatedSkills.isEmpty())
        assertTrue(ready.context.history.none { it.content.contains(SkillContextInjector.START_MARKER) })
    }

    @Test
    fun `pipeline validates and activates selected skill`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(
            userId = USER_ID,
            bundle = fixtureBundle(),
        )
        val validator = FakeSkillLlmValidator.approving()
        val pipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = validator,
        )

        val result = pipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext("Summarize this academic paper with the right workflow."),
            )
        )

        val ready = assertIs<SkillActivationPipeline.Result.Ready>(result)
        assertEquals(1, validator.invocationCount)
        assertEquals(1, ready.activatedSkills.size)
        val contextMessage = ready.context.history.single { it.content.contains(SkillContextInjector.START_MARKER) }
        assertEquals(LLMMessageRole.user, contextMessage.role)
        assertTrue(contextMessage.content.contains("paper_summarize"))
        assertTrue(contextMessage.content.contains("Academic paper summarization"))
    }

    @Test
    fun `pipeline uses cached approved validation if hash is unchanged`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, fixtureBundle())
        val validator = FakeSkillLlmValidator.approving()
        val pipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = validator,
        )

        repeat(2) {
            val result = pipeline.run(
                SkillActivationPipeline.Input(
                    userId = USER_ID,
                    context = baseContext("Summarize the paper."),
                )
            )
            assertIs<SkillActivationPipeline.Result.Ready>(result)
        }

        assertEquals(1, validator.invocationCount)
    }

    @Test
    fun `pipeline revalidates when hash changes`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, fixtureBundle())
        val validator = FakeSkillLlmValidator.approving()
        val pipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = validator,
        )

        val first = pipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext("Summarize the paper."),
            )
        )
        assertIs<SkillActivationPipeline.Result.Ready>(first)

        val originalBundle = fixtureBundle()
        val changedBundle = SkillBundle.fromFiles(
            skillId = originalBundle.skillId,
            files = originalBundle.files.map { file ->
                if (file.normalizedPath == "README.md") {
                    file.copy(content = (file.contentAsText() + "\nUpdated").encodeToByteArray())
                } else {
                    file
                }
            },
        )
        repository.saveSkillBundle(USER_ID, changedBundle)

        val second = pipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext("Summarize the paper again."),
            )
        )

        assertIs<SkillActivationPipeline.Result.Ready>(second)
        assertEquals(2, validator.invocationCount)
    }

    @Test
    fun `pipeline returns Blocked when selector chooses unknown skill`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, fixtureBundle())
        val missingSkill = SkillId("missing-skill")
        val pipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(missingSkill)),
            llmValidator = FakeSkillLlmValidator.approving(),
        )

        val result = pipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext("Summarize this paper."),
            )
        )

        val blocked = assertIs<SkillActivationPipeline.Result.Blocked>(result)
        assertEquals(listOf(missingSkill), blocked.selectedSkillIds)
        assertTrue(blocked.reason.contains("unknown skill id", ignoreCase = true))
    }

    @Test
    fun `pipeline returns Blocked when selector phase throws`() = runTest {
        val pipeline = SkillActivationPipeline(
            registryRepository = InMemorySkillRegistryRepository(),
            selector = { error("selector failed") },
            llmValidator = FakeSkillLlmValidator.approving(),
        )

        val result: SkillActivationPipeline.Result = pipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext("Summarize this paper."),
            )
        )

        val blocked = assertIs<SkillActivationPipeline.Result.Blocked>(result)
        assertTrue(blocked.reason.contains("SELECT_SKILLS"))
        assertEquals(SkillActivationPhase.SELECT_SKILLS.failureCode, blocked.findings.single().code)
    }

    @Test
    fun `pipeline returns Blocked when bundle load phase throws`() = runTest {
        val repository = InMemorySkillRegistryRepository().also {
            it.saveSkillBundle(USER_ID, fixtureBundle())
        }
        val throwingRepository = object : SkillRegistryRepository by repository {
            override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? {
                error("load failed")
            }
        }
        val pipeline = SkillActivationPipeline(
            registryRepository = throwingRepository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = FakeSkillLlmValidator.approving(),
        )

        val result = pipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext("Summarize this paper."),
            )
        )

        val blocked = assertIs<SkillActivationPipeline.Result.Blocked>(result)
        assertTrue(blocked.reason.contains("LOAD_BUNDLE"))
        assertEquals(SkillActivationPhase.LOAD_BUNDLE.failureCode, blocked.findings.single().code)
    }

    @Test
    fun `pipeline returns Blocked when validation rejects`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, fixtureBundle())
        val pipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = FakeSkillLlmValidator.rejecting("Unsafe"),
        )

        val result = pipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext("Summarize this paper."),
            )
        )

        val blocked = assertIs<SkillActivationPipeline.Result.Blocked>(result)
        assertTrue(blocked.findings.isNotEmpty())
        assertTrue(blocked.reason.contains("validation", ignoreCase = true))
    }

    @Test
    fun `pipeline uses cached rejected validation before revalidating`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, fixtureBundle())
        val rejectingValidator = FakeSkillLlmValidator.rejecting("Unsafe")
        val rejectingPipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = rejectingValidator,
        )

        val firstResult = rejectingPipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext("Summarize this paper."),
            )
        )
        assertIs<SkillActivationPipeline.Result.Blocked>(firstResult)
        assertEquals(1, rejectingValidator.invocationCount)

        val approvingValidator = FakeSkillLlmValidator.approving()
        val approvingPipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = approvingValidator,
        )

        val secondResult = approvingPipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext("Summarize this paper again."),
            )
        )

        val blocked = assertIs<SkillActivationPipeline.Result.Blocked>(secondResult)
        assertEquals(0, approvingValidator.invocationCount)
        assertTrue(blocked.reason.contains("previously rejected", ignoreCase = true))
    }

    @Test
    fun `skills context history message replaces old skills context message on repeated pipeline pass`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, fixtureBundle())
        val pipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = FakeSkillLlmValidator.approving(),
        )

        val first = assertIs<SkillActivationPipeline.Result.Ready>(
            pipeline.run(
                SkillActivationPipeline.Input(
                    userId = USER_ID,
                    context = baseContext("Summarize this paper."),
                )
            )
        )
        val second = assertIs<SkillActivationPipeline.Result.Ready>(
            pipeline.run(
                SkillActivationPipeline.Input(
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

        val skillMessages = second.context.history.filter { it.content.contains(SkillContextInjector.START_MARKER) }
        assertEquals(1, skillMessages.size)
        assertTrue(second.context.history.last().content.contains("Summarize another paper."))
    }

    private fun fixtureBundle(): SkillBundle = SkillBundleLoader().loadDirectory(
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
