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
import ru.souz.backend.toBackendSettingsConfig
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.testutil.repository.MemoryUserProviderKeyRepository
import ru.souz.backend.testutil.repository.MemoryUserSettingsRepository
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmProvider
import ru.souz.llms.codex.CodexOAuthCredentialStore
import ru.souz.llms.codex.CodexOAuthCredentials
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
                    enabledTools = setOf("Calculator"),
                    showToolEvents = false,
                    streamingMessages = true,
                    interfaceLanguage = "en",
                    requestTimeoutMillis = 45_000L,
                    useFewShotExamples = false,
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
        assertEquals(setOf("Calculator"), effective.enabledTools)
        assertFalse(effective.showToolEvents)
        assertTrue(effective.streamingMessages)
        assertEquals("en", effective.interfaceLanguage)
        assertEquals(45_000L, effective.requestTimeoutMillis)
        assertFalse(effective.useFewShotExamples)
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

        val effective = resolver(
            settingsProvider = settingsProvider,
            repository = repository,
        ).resolve("user-a")

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
    fun `resolver keeps Codex model with server managed OAuth access`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            regionProfile = "en"
            codexAccessToken = "server-codex-access-token"
            codexRefreshToken = "server-codex-refresh-token"
            codexAccountId = "server-codex-account-id"
            codexExpiresAt = 1_800_000_000L
            openaiKey = null
            anthropicKey = null
            qwenChatKey = null
        }
        val repository = MemoryUserSettingsRepository()
        repository.save(
            UserSettings(
                userId = "user-a",
                defaultModel = LLMModel.CodexGpt55,
            )
        )

        val effective = resolver(
            settingsProvider = settingsProvider,
            repository = repository,
            codexOAuthCredentialStore = completeCodexStore(),
        ).resolve("user-a")

        assertEquals(LLMModel.CodexGpt55, effective.defaultModel)
    }

    @Test
    fun `resolver rejects Codex model with incomplete server managed OAuth access`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            regionProfile = "en"
            codexAccessToken = "server-codex-access-token"
            openaiKey = "server-openai-key"
            anthropicKey = null
            qwenChatKey = null
        }
        val repository = MemoryUserSettingsRepository()
        repository.save(
            UserSettings(
                userId = "user-a",
                defaultModel = LLMModel.CodexGpt55,
            )
        )

        val effective = resolver(settingsProvider = settingsProvider, repository = repository).resolve("user-a")

        assertEquals(LLMModel.OpenAIGpt5Nano, effective.defaultModel)
    }

    @Test
    fun `resolver uses configured OpenAI-compatible model as backend default`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            regionProfile = "en"
            gigaModel = LLMModel.OpenAIGpt5Nano
            openaiKey = "server-openai-key"
            openaiModel = "provider-chat-model"
            codexAccessToken = null
        }

        val effective = resolver(settingsProvider = settingsProvider).resolve("user-a")

        assertEquals(LLMModel.OpenAICompatibleCustom, effective.defaultModel)
    }

    @Test
    fun `resolver maps persisted OpenAI default to configured OpenAI-compatible model`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            regionProfile = "en"
            openaiKey = "server-openai-key"
            openaiModel = "provider-chat-model"
        }
        val repository = MemoryUserSettingsRepository()
        repository.save(
            UserSettings(
                userId = "user-a",
                defaultModel = LLMModel.OpenAIGpt52,
            )
        )

        val effective = resolver(settingsProvider = settingsProvider, repository = repository).resolve("user-a")

        assertEquals(LLMModel.OpenAICompatibleCustom, effective.defaultModel)
    }

    @Test
    fun `resolver rejects OpenAI-compatible custom model when provider model is missing`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            regionProfile = "en"
            openaiKey = "server-openai-key"
            openaiModel = null
        }
        val repository = MemoryUserSettingsRepository()
        repository.save(
            UserSettings(
                userId = "user-a",
                defaultModel = LLMModel.OpenAICompatibleCustom,
            )
        )

        val effective = resolver(settingsProvider = settingsProvider, repository = repository).resolve("user-a")

        assertEquals(LLMModel.OpenAIGpt5Nano, effective.defaultModel)
    }

    @Test
    fun `resolver rejects selected local model`() = runTest {
        val repository = MemoryUserSettingsRepository()
        repository.save(
            UserSettings(
                userId = "user-a",
                defaultModel = LLMModel.LocalGemma4_E2B_It,
            )
        )

        val effective = resolver(repository = repository).resolve("user-a")

        assertEquals(LLMModel.Max, effective.defaultModel)
    }

    @Test
    fun `resolver never falls back to a local model when remote providers are inaccessible`() = runTest {
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

        val effective = resolver(settingsProvider = settingsProvider, repository = repository).resolve("user-a")

        assertEquals(LLMModel.CodexGpt54, effective.defaultModel)
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
    fun `new settings resolve via request overrides then persisted values then backend defaults`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            gigaChatKey = "giga-key"
            regionProfile = "en"
            requestTimeoutMillis = 40_000L
            useFewShotExamples = false
        }
        val repository = MemoryUserSettingsRepository()
        repository.save(
            UserSettings(
                userId = "user-a",
                locale = Locale.forLanguageTag("ru-RU"),
                timeZone = ZoneId.of("Europe/Moscow"),
                interfaceLanguage = "ru",
                requestTimeoutMillis = 45_000L,
                useFewShotExamples = false,
            )
        )

        val effective = resolver(
            settingsProvider = settingsProvider,
            repository = repository,
        ).resolve(
            userId = "user-a",
            requestOverrides = UserSettingsOverrides(
                interfaceLanguage = "en",
                requestTimeoutMillis = 50_000L,
                useFewShotExamples = true,
            ),
        )

        assertEquals(Locale.forLanguageTag("ru-RU"), effective.locale)
        assertEquals(ZoneId.of("Europe/Moscow"), effective.timeZone)
        assertEquals("en", effective.interfaceLanguage)
        assertEquals(50_000L, effective.requestTimeoutMillis)
        assertTrue(effective.useFewShotExamples)
    }

    @Test
    fun `unsupported enabled tools are filtered out`() = runTest {
        val repository = MemoryUserSettingsRepository()
        repository.save(
                UserSettings(
                    userId = "user-a",
                    enabledTools = setOf("Calculator", "OpenBrowser", "SendTelegramMessage"),
                )
        )

        val effective = resolver(repository = repository).resolve("user-a")

        assertEquals(setOf("Calculator"), effective.enabledTools)
    }

    @Test
    fun `missing locale and time zone fall back to stable defaults`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            regionProfile = "en"
            requestTimeoutMillis = 41_000L
            useFewShotExamples = false
        }

        val effective = resolver(settingsProvider = settingsProvider).resolve("user-a")

        assertEquals(Locale.forLanguageTag("en-US"), effective.locale)
        assertEquals(ZoneId.systemDefault(), effective.timeZone)
        assertEquals("en", effective.interfaceLanguage)
        assertEquals(41_000L, effective.requestTimeoutMillis)
        assertTrue(effective.useFewShotExamples)
    }

    private fun resolver(
        settingsProvider: TestSettingsProvider = TestSettingsProvider().apply { gigaChatKey = "giga-key" },
        repository: MemoryUserSettingsRepository = MemoryUserSettingsRepository(),
        userProviderKeyRepository: MemoryUserProviderKeyRepository = MemoryUserProviderKeyRepository(),
        featureFlags: BackendFeatureFlags = BackendFeatureFlags(
            streamingMessages = true,
            toolEvents = true,
        ),
        codexOAuthCredentialStore: CodexOAuthCredentialStore? = null,
    ): EffectiveSettingsResolver =
        EffectiveSettingsResolver(
            baseSettings = settingsProvider.toBackendSettingsConfig(),
            userSettingsRepository = repository,
            userProviderKeyRepository = userProviderKeyRepository,
            featureFlags = featureFlags,
            toolCatalog = toolCatalog(
                ToolCategory.CALCULATOR to fakeTool("Calculator"),
                ToolCategory.BROWSER to fakeTool("OpenBrowser"),
                ToolCategory.TELEGRAM to fakeTool("SendTelegramMessage"),
            ),
            codexOAuthCredentialStore = codexOAuthCredentialStore,
        )

    private fun completeCodexStore(): CodexOAuthCredentialStore = object : CodexOAuthCredentialStore {
        override suspend fun load() = CodexOAuthCredentials(
            accessToken = "server-codex-access-token",
            refreshToken = "server-codex-refresh-token",
            accountId = "server-codex-account-id",
            expiresAtEpochSeconds = 1_800_000_000L,
            version = 0L,
        )

        override suspend fun compareAndSet(
            expectedVersion: Long?,
            credentials: CodexOAuthCredentials,
        ): Boolean = error("not used")
    }

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

}
