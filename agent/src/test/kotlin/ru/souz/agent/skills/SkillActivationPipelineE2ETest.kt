package ru.souz.agent.skills

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.skills.activation.ActivatedSkill
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.implementations.bundle.SkillBundleLoader
import ru.souz.agent.skills.bundle.skillFixturePath
import ru.souz.agent.skills.implementations.registry.InMemorySkillRegistryRepository
import ru.souz.agent.skills.selection.LlmSkillSelector
import ru.souz.agent.skills.validation.LlmSkillValidator
import ru.souz.agent.skills.validation.SkillValidationPolicy
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.llms.json.JsonUtils
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.qwen.QwenChatAPI
import ru.souz.llms.restJsonMapper
import ru.souz.llms.tunnel.AiTunnelChatAPI
import ru.souz.runtime.di.runtimeCoreDiModule
import ru.souz.runtime.di.runtimeLlmDiModule
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class SkillActivationPipelineE2ETest {
    private lateinit var selectedModel: LLMModel

    @BeforeEach
    fun checkEnvironment() {
        Assumptions.assumeTrue(
            readEnv(SOUZ_AGENT_INTEGRATION_TESTS_ON).equals("true", ignoreCase = true),
            "Skipping skills E2E tests: set $SOUZ_AGENT_INTEGRATION_TESTS_ON=true",
        )
        Assumptions.assumeTrue(
            selectAvailableModel() != null,
            "Skipping skills E2E tests: no supported LLM API key is configured.",
        )
        selectedModel = checkNotNull(selectAvailableModel())
    }

    @Test
    fun `real llm selector and validator approve benign skill and skip unrelated request`() = runTest(timeout = 5.minutes) {
        val bundle = loadFixtureBundle()
        assertTrue(bundle.files.size > 1, "Expected multi-file checked-in skill fixture.")
        val integrationPolicy = SkillValidationPolicy.default().copy(minApprovalConfidence = 0.6)

        val repository = InMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, bundle)

        val llmApi = realLlmApi(selectedModel)
        val jsonUtils = JsonUtils(restJsonMapper)
        val pipeline = SkillActivationPipeline(
            registryRepository = repository,
            selector = LlmSkillSelector(llmApi = llmApi, model = selectedModel.alias, jsonUtils),
            llmValidator = LlmSkillValidator(llmApi = llmApi, model = selectedModel.alias, jsonUtils),
        )

        val skillRequest = """
            Summarize this academic paper using the appropriate paper summarization workflow.
            Title: Attention Is All You Need.
            Authors: Vaswani et al.
            Abstract: The dominant sequence transduction models are based on recurrent or convolutional neural networks that use an encoder-decoder structure. We propose the Transformer, a model architecture based entirely on attention mechanisms and show strong results on machine translation.
            Topic: method.
        """.trimIndent()

        val firstResult = pipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext(skillRequest),
                policy = integrationPolicy,
            )
        )
        val firstReady = assertIs<SkillActivationPipeline.Result.Ready>(firstResult)
        assertEquals(1, firstReady.activatedSkills.size)
        assertActivatedPaperSummarize(firstReady.activatedSkills.single())

        val secondResult = pipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = firstReady.context.map(
                    history = firstReady.context.history + LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = skillRequest,
                    )
                ) { skillRequest },
                policy = integrationPolicy,
            )
        )
        val secondReady = assertIs<SkillActivationPipeline.Result.Ready>(secondResult)
        val skillsMessages = secondReady.context.history.filter { it.content.contains("<souz_skills_context>") }
        assertEquals(1, skillsMessages.size)
        assertTrue(skillsMessages.single().content.contains("paper_summarize"))
        assertTrue(
            skillsMessages.single().content.contains("Academic paper summarization") ||
                skillsMessages.single().content.contains("dynamic SOP selection"),
        )

        val noSkillResult = pipeline.run(
            SkillActivationPipeline.Input(
                userId = USER_ID,
                context = baseContext("What is 2 + 2?"),
                policy = integrationPolicy,
            )
        )
        val noSkillReady = assertIs<SkillActivationPipeline.Result.Ready>(noSkillResult)
        assertTrue(noSkillReady.activatedSkills.isEmpty())
        assertTrue(noSkillReady.context.history.none { it.content.contains("<souz_skills_context>") })
    }

    private fun realLlmApi(model: LLMModel): LLMChatAPI {
        val settings = spyk(SettingsProviderImpl(ConfigStore)) {
            every { gigaModel } returns model
            every { requestTimeoutMillis } returns 60_000L
            every { temperature } returns 0.0f
        }
        val di = DI(allowSilentOverride = true) {
            bindSingleton<ObjectMapper>(tag = LOG_OBJECT_MAPPER_TAG) { restJsonMapper }
            import(runtimeCoreDiModule())
            bindSingleton<SettingsProvider>(overrides = true) { settings }
            import(runtimeLlmDiModule(logObjectMapperTag = LOG_OBJECT_MAPPER_TAG))
        }
        return when (model.provider) {
            LlmProvider.GIGA -> di.direct.instance<GigaRestChatAPI>()
            LlmProvider.QWEN -> di.direct.instance<QwenChatAPI>()
            LlmProvider.AI_TUNNEL -> di.direct.instance<AiTunnelChatAPI>()
            LlmProvider.ANTHROPIC -> di.direct.instance<AnthropicChatAPI>()
            LlmProvider.OPENAI -> di.direct.instance<OpenAIChatAPI>()
            LlmProvider.LOCAL -> error("Local model is not used in this E2E test.")
        }
    }

    private fun assertActivatedPaperSummarize(skill: ActivatedSkill) {
        assertEquals("paper-summarize-academic", skill.skillId.value)
        assertTrue(skill.instructionBody.contains("Paper Summarize Skill"))
        assertTrue(skill.manifest.name == "paper_summarize")
    }

    private fun loadFixtureBundle(): SkillBundle = SkillBundleLoader().loadDirectory(
        skillId = SkillId("paper-summarize-academic"),
        rootDirectory = skillFixturePath("paper-summarize-academic"),
    )

    private fun baseContext(userInput: String): AgentContext<String> = AgentContext(
        input = userInput,
        settings = AgentSettings(
            model = selectedModel.alias,
            temperature = 0.0f,
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

private fun selectAvailableModel(): LLMModel? = when {
    !readEnv("OPENAI_API_KEY").isNullOrBlank() -> LLMModel.OpenAIGpt5Nano
    !readEnv("AITUNNEL_KEY").isNullOrBlank() -> LLMModel.AiTunnelGpt5Nano
    !readEnv("QWEN_KEY").isNullOrBlank() -> LLMModel.QwenFlash
    !readEnv("ANTHROPIC_API_KEY").isNullOrBlank() -> LLMModel.AnthropicHaiku45
    !readEnv("GIGA_KEY").isNullOrBlank() -> LLMModel.Lite
    else -> null
}

private const val LOG_OBJECT_MAPPER_TAG = "skillsGraphE2ELogObjectMapper"
private const val SOUZ_AGENT_INTEGRATION_TESTS_ON = "SOUZ_AGENT_INTEGRATION_TESTS_ON"
private const val USER_ID = "skills-e2e-user"

private fun readEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
