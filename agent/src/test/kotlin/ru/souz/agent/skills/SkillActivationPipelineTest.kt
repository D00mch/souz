package ru.souz.agent.skills

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.activation.SkillContextInjector
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.implementations.bundle.skillFixturePath
import ru.souz.agent.skills.implementations.activation.FakeSkillLlmValidator
import ru.souz.agent.skills.implementations.activation.FakeSkillSelector
import ru.souz.agent.skills.implementations.bundle.SkillBundleLoader
import ru.souz.agent.skills.implementations.registry.InMemorySkillRegistryRepository
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.validation.SkillLlmValidationDecision
import ru.souz.agent.skills.validation.SkillLlmValidationVerdict
import ru.souz.agent.skills.validation.SkillLlmValidator
import ru.souz.agent.skills.validation.SkillRiskLevel
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationSeverity
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertTrue(ready.rejectedSkills.isEmpty())
        assertEquals(ready.context.systemPrompt, "system")
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
        assertTrue(ready.rejectedSkills.isEmpty())
        val systemMessage = ready.context.history.first()
        assertTrue(systemMessage.content.contains(SkillContextInjector.START_MARKER))
        assertEquals(LLMMessageRole.system, systemMessage.role)
        assertTrue(systemMessage.content.contains("paper_summarize"))
        assertTrue(systemMessage.content.contains("Academic paper summarization"))
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
    fun `pipeline rethrows cancellation instead of converting it into Blocked`() = runTest {
        val pipeline = SkillActivationPipeline(
            registryRepository = InMemorySkillRegistryRepository(),
            selector = { throw CancellationException("cancelled") },
            llmValidator = FakeSkillLlmValidator.approving(),
        )

        assertFailsWith<CancellationException> {
            pipeline.run(
                SkillActivationPipeline.Input(
                    userId = USER_ID,
                    context = baseContext("Summarize this paper."),
                )
            )
        }
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
    fun `pipeline records a rejected skill instead of blocking the whole request`() = runTest {
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

        val ready = assertIs<SkillActivationPipeline.Result.Ready>(result)
        assertTrue(ready.activatedSkills.isEmpty())
        assertEquals(1, ready.rejectedSkills.size)
        assertTrue(ready.rejectedSkills.single().findings.isNotEmpty())
        assertTrue(ready.rejectedSkills.single().reason.contains("validation", ignoreCase = true))
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
        val firstReady = assertIs<SkillActivationPipeline.Result.Ready>(firstResult)
        assertEquals(1, firstReady.rejectedSkills.size)
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

        val secondReady = assertIs<SkillActivationPipeline.Result.Ready>(secondResult)
        assertEquals(0, approvingValidator.invocationCount)
        assertEquals(1, secondReady.rejectedSkills.size)
        assertTrue(secondReady.rejectedSkills.single().reason.contains("previously rejected", ignoreCase = true))
    }

    @Test
    fun `pipeline removes prior skills context when no skills are selected later`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, fixtureBundle())
        val activatingPipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(SkillId("paper-summarize-academic"))),
            llmValidator = FakeSkillLlmValidator.approving(),
        )
        val removingPipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = FakeSkillSelector(emptyList()),
            llmValidator = FakeSkillLlmValidator.approving(),
        )

        val first = assertIs<SkillActivationPipeline.Result.Ready>(
            activatingPipeline.run(
                SkillActivationPipeline.Input(
                    userId = USER_ID,
                    context = baseContext("Summarize this paper."),
                )
            )
        )
        val second = assertIs<SkillActivationPipeline.Result.Ready>(
            removingPipeline.run(
                SkillActivationPipeline.Input(
                    userId = USER_ID,
                    context = first.context.map(
                        history = first.context.history +
                            LLMRequest.Message(
                                role = LLMMessageRole.user,
                                content = "What is 2 + 2?",
                            )
                    ) { "What is 2 + 2?" },
                )
            )
        )

        assertEquals("system", second.context.systemPrompt)
        assertEquals("system", second.context.history.first().content)
        assertTrue(second.context.history.none { it.content.contains(SkillContextInjector.START_MARKER) })
    }

    @Test
    fun `pipeline keeps approved skills when one selected skill is rejected`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        val approvedA = SkillId("paper-a")
        val rejected = SkillId("paper-b")
        val approvedC = SkillId("paper-c")
        repository.saveSkillBundle(USER_ID, fixtureBundle(approvedA))
        repository.saveSkillBundle(USER_ID, fixtureBundle(rejected))
        repository.saveSkillBundle(USER_ID, fixtureBundle(approvedC))
        val validator = SkillLlmValidator { input ->
            if (input.skillId == rejected) rejectingVerdict("Unsafe") else approvingVerdict()
        }
        val pipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = FakeSkillSelector(listOf(approvedA, rejected, approvedC)),
            llmValidator = validator,
        )

        val result = pipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext("Summarize this paper."),
            )
        )

        val ready = assertIs<SkillActivationPipeline.Result.Ready>(result)
        assertEquals(listOf(approvedA, approvedC), ready.activatedSkills.map { it.skillId })
        assertEquals(listOf(rejected), ready.rejectedSkills.map { it.skillId })
        val skillContext = ready.context.history.first()
        assertTrue(skillContext.content.contains(SkillContextInjector.START_MARKER))
        assertTrue(skillContext.content.contains(approvedA.value))
        assertTrue(skillContext.content.contains(approvedC.value))
        assertTrue(!skillContext.content.contains(rejected.value))
    }

    private fun fixtureBundle(skillId: SkillId = SkillId("paper-summarize-academic")): SkillBundle = SkillBundleLoader().loadDirectory(
        skillId = skillId,
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

    private fun approvingVerdict(): SkillLlmValidationVerdict = SkillLlmValidationVerdict(
        decision = SkillLlmValidationDecision.APPROVE,
        confidence = 0.96,
        riskLevel = SkillRiskLevel.LOW,
        reasons = listOf("Benign"),
        requestedCapabilities = listOf("paper summarization"),
        suspiciousFiles = emptyList(),
        findings = emptyList(),
        model = "fake",
    )

    private fun rejectingVerdict(reason: String): SkillLlmValidationVerdict = SkillLlmValidationVerdict(
        decision = SkillLlmValidationDecision.REJECT,
        confidence = 0.99,
        riskLevel = SkillRiskLevel.HIGH,
        reasons = listOf(reason),
        requestedCapabilities = listOf("unknown"),
        suspiciousFiles = listOf("SKILL.md"),
        findings = listOf(
            SkillValidationFinding(
                code = "llm.reject",
                message = reason,
                severity = SkillValidationSeverity.ERROR,
                filePath = "SKILL.md",
            )
        ),
        model = "fake",
    )
}

private const val USER_ID = "skills-test-user"
