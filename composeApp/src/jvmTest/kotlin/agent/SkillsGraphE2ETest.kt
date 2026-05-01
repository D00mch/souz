package agent

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
import ru.souz.agent.skills.ActivatedSkill
import ru.souz.agent.skills.LlmSkillSelector
import ru.souz.agent.skills.SkillBundle
import ru.souz.agent.skills.validation.SkillBundleHasher
import ru.souz.agent.skills.SkillBundleLoader
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.SkillRegistryRepository
import ru.souz.agent.skills.SkillsGraph
import ru.souz.agent.skills.SkillsGraphInput
import ru.souz.agent.skills.SkillsGraphResult
import ru.souz.agent.skills.StoredSkill
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.skills.validation.LlmSkillValidator
import ru.souz.agent.skills.validation.SkillValidationPolicy
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl
import ru.souz.di.mainDiModule
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillsGraphE2ETest {
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

        val repository = ComposeAppInMemorySkillRegistryRepository()
        repository.saveSkillBundle(USER_ID, bundle)

        val llmApi = realLlmApi(selectedModel)
        val jsonUtils= JsonUtils(restJsonMapper)
        val graph = SkillsGraph(
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

        val firstResult = graph.run(
            SkillsGraphInput(
                userId = USER_ID,
                context = baseContext(skillRequest),
                policy = SkillValidationPolicy.default(),
            )
        )
        val firstReady = assertIs<SkillsGraphResult.Ready>(firstResult)
        assertEquals(1, firstReady.activatedSkills.size)
        assertActivatedPaperSummarize(firstReady.activatedSkills.single())

        val secondResult = graph.run(
            SkillsGraphInput(
                userId = USER_ID,
                context = firstReady.context.map(
                    history = firstReady.context.history + LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = skillRequest,
                    )
                ) { skillRequest },
            )
        )
        val secondReady = assertIs<SkillsGraphResult.Ready>(secondResult)
        val skillsMessages = secondReady.context.history.filter { it.content.contains("<souz_skills_context>") }
        assertEquals(1, skillsMessages.size)
        assertTrue(skillsMessages.single().content.contains("paper_summarize"))
        assertTrue(
            skillsMessages.single().content.contains("Academic paper summarization") ||
                skillsMessages.single().content.contains("dynamic SOP selection"),
        )

        val noSkillResult = graph.run(
            SkillsGraphInput(
                userId = USER_ID,
                context = baseContext("What is 2 + 2?"),
                policy = SkillValidationPolicy.default(),
            )
        )
        val noSkillReady = assertIs<SkillsGraphResult.Ready>(noSkillResult)
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
            import(mainDiModule)
            bindSingleton<SettingsProvider>(overrides = true) { settings }
            bindSingleton<LLMChatAPI>(overrides = true) {
                when (model.provider) {
                    LlmProvider.GIGA -> instance<GigaRestChatAPI>()
                    LlmProvider.QWEN -> instance<QwenChatAPI>()
                    LlmProvider.AI_TUNNEL -> instance<AiTunnelChatAPI>()
                    LlmProvider.ANTHROPIC -> instance<AnthropicChatAPI>()
                    LlmProvider.OPENAI -> instance<OpenAIChatAPI>()
                    LlmProvider.LOCAL -> error("Local model is not used in this E2E test.")
                }
            }
        }
        return di.direct.instance()
    }

    private fun assertActivatedPaperSummarize(skill: ActivatedSkill) {
        assertEquals("paper-summarize-academic", skill.skillId.value)
        assertTrue(skill.instructionBody.contains("Paper Summarize Skill"))
        assertTrue(skill.manifest.name == "paper_summarize")
    }

    private fun loadFixtureBundle(): SkillBundle = SkillBundleLoader().loadDirectory(
        skillId = SkillId("paper-summarize-academic"),
        rootDirectory = fixtureRoot(),
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

    private fun fixtureRoot(): Path {
        val root = workspaceRoot()
        val fixture = root.resolve("agent/src/test/resources/skills/paper-summarize-academic")
        check(fixture.exists()) { "Fixture not found at $fixture" }
        return fixture
    }

    private fun workspaceRoot(): Path {
        var current: Path = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Unable to locate workspace root from ${System.getProperty("user.dir")}")
    }
}

private class ComposeAppInMemorySkillRegistryRepository : SkillRegistryRepository {
    private val bundles = linkedMapOf<Pair<String, SkillId>, SkillBundle>()
    private val validations = linkedMapOf<Key, SkillValidationRecord>()

    override suspend fun listSkills(userId: String): List<StoredSkill> = bundles.entries
        .filter { it.key.first == userId }
        .map { (_, bundle) -> bundle.toStoredSkill(userId) }

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? =
        bundles[userId to skillId]?.toStoredSkill(userId)

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? =
        bundles.entries.firstOrNull { (key, bundle) ->
            key.first == userId && bundle.manifest.name == name
        }?.value?.toStoredSkill(userId)

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill {
        bundles[userId to bundle.skillId] = bundle
        return bundle.toStoredSkill(userId)
    }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? = bundles[userId to skillId]

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = validations[Key(userId, skillId, bundleHash, policyVersion)]

    override suspend fun saveValidation(record: SkillValidationRecord) {
        validations[Key(record.userId, record.skillId, record.bundleHash, record.policyVersion)] = record
    }

    override suspend fun markValidationStatus(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
        status: SkillValidationStatus,
        reason: String?,
    ) {
        val key = Key(userId, skillId, bundleHash, policyVersion)
        val current = validations[key] ?: return
        validations[key] = current.copy(
            status = status,
            reasons = current.reasons + listOfNotNull(reason),
        )
    }

    override suspend fun invalidateOtherValidations(
        userId: String,
        skillId: SkillId,
        activeBundleHash: String,
        policyVersion: String,
        reason: String?,
    ) {
        validations.entries
            .filter { (key, record) ->
                key.userId == userId &&
                    key.skillId == skillId &&
                    key.policyVersion == policyVersion &&
                    key.bundleHash != activeBundleHash &&
                    record.status == SkillValidationStatus.APPROVED
            }
            .forEach { (key, record) ->
                validations[key] = record.copy(
                    status = SkillValidationStatus.STALE,
                    reasons = record.reasons + listOfNotNull(reason),
                )
            }
    }

    private fun SkillBundle.toStoredSkill(userId: String): StoredSkill = StoredSkill(
        userId = userId,
        skillId = skillId,
        manifest = manifest,
        bundleHash = SkillBundleHasher.hash(this),
        createdAt = Instant.EPOCH,
    )

    private data class Key(
        val userId: String,
        val skillId: SkillId,
        val bundleHash: String,
        val policyVersion: String,
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

private const val USER_ID = "skills-e2e-user"
private fun readEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
