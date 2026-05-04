package ru.souz.backend.settings.service

import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.storage.memory.MemoryUserProviderKeyRepository
import ru.souz.backend.storage.memory.MemoryUserSettingsRepository
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LocalModelAvailability
import ru.souz.llms.LlmProvider
import ru.souz.tool.ToolCategory

class EffectiveSettingsResolverTest {
    @Test
    fun `resolver combines defaults with persisted user settings`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            gigaChatKey = "giga-key"
            qwenChatKey = "qwen-key"
            useStreaming = true
            contextSize = 24_000
            temperature = 0.6f
        }
        val repository = MemoryUserSettingsRepository()
        repository.save(
                UserSettings(
                    userId = "user-a",
                    defaultModel = LLMModel.QwenMax,
                    contextSize = null,
                    temperature = 0.2f,
                    locale = Locale.forLanguageTag("en-US"),
                    timeZone = null,
                    systemPrompt = "be brief",
                    enabledTools = setOf("ListFiles"),
                    showToolEvents = false,
                    streamingMessages = true,
                    toolPermissions = emptyMap(),
                    mcp = emptyMap(),
                )
        )

        val effective = resolver(
            settingsProvider = settingsProvider,
            repository = repository,
            featureFlags = BackendFeatureFlags(
                streamingMessages = true,
                toolEvents = true,
            ),
        ).resolve("user-a")

        assertEquals(LLMModel.QwenMax, effective.defaultModel)
        assertEquals(24_000, effective.contextSize)
        assertEquals(0.2f, effective.temperature)
        assertEquals(Locale.forLanguageTag("en-US"), effective.locale)
        assertEquals(ZoneId.systemDefault(), effective.timeZone)
        assertEquals("be brief", effective.systemPrompt)
        assertEquals(setOf("ListFiles"), effective.enabledTools)
        assertFalse(effective.showToolEvents)
        assertTrue(effective.streamingMessages)
    }

    @Test
    fun `resolver normalizes unavailable default model using key aware fallback`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            regionProfile = "ru"
            qwenChatKey = "qwen-key"
            gigaChatKey = null
            openaiKey = null
        }
        val repository = MemoryUserSettingsRepository()
        repository.save(
                UserSettings(
                    userId = "user-a",
                    defaultModel = LLMModel.OpenAIGpt52,
                )
        )

        val effective = resolver(settingsProvider = settingsProvider, repository = repository).resolve("user-a")

        assertEquals(LLMModel.QwenMax, effective.defaultModel)
    }

    @Test
    fun `resolver treats user managed key as valid provider access during normalization`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            regionProfile = "ru"
            gigaChatKey = "giga-key"
            openaiKey = null
        }
        val repository = MemoryUserSettingsRepository()
        val providerKeyRepository = MemoryUserProviderKeyRepository()
        repository.save(
            UserSettings(
                userId = "user-a",
                defaultModel = LLMModel.OpenAIGpt52,
            )
        )
        providerKeyRepository.save(
            UserProviderKey(
                userId = "user-a",
                provider = LlmProvider.OPENAI,
                encryptedApiKey = "enc-openai-user-a",
                keyHint = "...1234",
            )
        )

        val effective = resolver(
            settingsProvider = settingsProvider,
            repository = repository,
            userProviderKeyRepository = providerKeyRepository,
        ).resolve("user-a")

        assertEquals(LLMModel.OpenAIGpt52, effective.defaultModel)
    }

    @Test
    fun `resolver keeps selected local model when it is available`() = runTest {
        val repository = MemoryUserSettingsRepository()
        repository.save(
            UserSettings(
                userId = "user-a",
                defaultModel = LLMModel.LocalGemma4_E2B_It,
            )
        )

        val effective = resolver(
            repository = repository,
            localModelAvailability = localModels(
                available = listOf(LLMModel.LocalGemma4_E2B_It, LLMModel.LocalQwen3_4B_Instruct_2507),
                default = LLMModel.LocalQwen3_4B_Instruct_2507,
            ),
        ).resolve("user-a")

        assertEquals(LLMModel.LocalGemma4_E2B_It, effective.defaultModel)
    }

    @Test
    fun `resolver falls back to available local default when remote providers are inaccessible`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            regionProfile = "en"
            openaiKey = null
            anthropicKey = null
            qwenChatKey = null
        }
        val repository = MemoryUserSettingsRepository()
        repository.save(
            UserSettings(
                userId = "user-a",
                defaultModel = LLMModel.OpenAIGpt52,
            )
        )

        val effective = resolver(
            settingsProvider = settingsProvider,
            repository = repository,
            localModelAvailability = localModels(
                available = listOf(LLMModel.LocalGemma4_E4B_It),
                default = LLMModel.LocalGemma4_E4B_It,
            ),
        ).resolve("user-a")

        assertEquals(LLMModel.LocalGemma4_E4B_It, effective.defaultModel)
    }

    @Test
    fun `feature flags can disable streaming and tool events`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            gigaChatKey = "giga-key"
            useStreaming = true
        }
        val repository = MemoryUserSettingsRepository()
        repository.save(
                UserSettings(
                    userId = "user-a",
                    showToolEvents = true,
                    streamingMessages = true,
                )
        )

        val effective = resolver(
            settingsProvider = settingsProvider,
            repository = repository,
            featureFlags = BackendFeatureFlags(
                streamingMessages = false,
                toolEvents = false,
            ),
        ).resolve("user-a")

        assertFalse(effective.streamingMessages)
        assertFalse(effective.showToolEvents)
    }

    @Test
    fun `unsupported enabled tools are filtered out`() = runTest {
        val repository = MemoryUserSettingsRepository()
        repository.save(
                UserSettings(
                    userId = "user-a",
                    enabledTools = setOf("ListFiles", "OpenBrowser", "SendTelegramMessage"),
                )
        )

        val effective = resolver(repository = repository).resolve("user-a")

        assertEquals(setOf("ListFiles"), effective.enabledTools)
    }

    @Test
    fun `missing locale and time zone fall back to stable defaults`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            regionProfile = "en"
        }

        val effective = resolver(settingsProvider = settingsProvider).resolve("user-a")

        assertEquals(Locale.forLanguageTag("en-US"), effective.locale)
        assertEquals(ZoneId.systemDefault(), effective.timeZone)
    }

    private fun resolver(
        settingsProvider: TestSettingsProvider = TestSettingsProvider().apply { gigaChatKey = "giga-key" },
        repository: MemoryUserSettingsRepository = MemoryUserSettingsRepository(),
        userProviderKeyRepository: MemoryUserProviderKeyRepository = MemoryUserProviderKeyRepository(),
        featureFlags: BackendFeatureFlags = BackendFeatureFlags(
            streamingMessages = true,
            toolEvents = true,
        ),
        localModelAvailability: LocalModelAvailability = unavailableLocalModels(),
    ): EffectiveSettingsResolver =
        EffectiveSettingsResolver(
            baseSettingsProvider = settingsProvider,
            userSettingsRepository = repository,
            userProviderKeyRepository = userProviderKeyRepository,
            featureFlags = featureFlags,
            toolCatalog = toolCatalog(
                ToolCategory.FILES to fakeTool("ListFiles"),
                ToolCategory.BROWSER to fakeTool("OpenBrowser"),
                ToolCategory.TELEGRAM to fakeTool("SendTelegramMessage"),
            ),
            localModelAvailability = localModelAvailability,
        )

    private fun toolCatalog(vararg tools: Pair<ToolCategory, LLMToolSetup>): AgentToolCatalog =
        object : AgentToolCatalog {
            override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
                tools.groupBy(keySelector = { it.first }, valueTransform = { it.second })
                    .mapValues { (_, setups) -> setups.associateBy { it.fn.name } }
        }

    private fun fakeTool(name: String): LLMToolSetup =
        object : LLMToolSetup {
            override val fn: LLMRequest.Function = LLMRequest.Function(
                name = name,
                description = "test",
                parameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
            )

            override suspend fun invoke(functionCall: ru.souz.llms.LLMResponse.FunctionCall) =
                error("not used in tests")
        }

    private fun unavailableLocalModels(): LocalModelAvailability =
        object : LocalModelAvailability {
            override fun availableGigaModels(): List<LLMModel> = emptyList()

            override fun defaultGigaModel(): LLMModel? = null

            override fun isProviderAvailable(): Boolean = false
        }

    private fun localModels(
        available: List<LLMModel>,
        default: LLMModel,
    ): LocalModelAvailability =
        object : LocalModelAvailability {
            override fun availableGigaModels(): List<LLMModel> = available

            override fun defaultGigaModel(): LLMModel = default

            override fun isProviderAvailable(): Boolean = true
        }
}
