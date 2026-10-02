package ru.souz.backend.settings.service

import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.llm.LlmSettingsStub
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.testutil.TestToolCatalog
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMModel.AiTunnelClaudeHaiku
import ru.souz.llms.LLMModel.AnthropicHaiku45
import ru.souz.llms.LLMModel.CodexGpt54
import ru.souz.llms.LLMModel.LocalQwen3_4B_Instruct_2507
import ru.souz.llms.LLMModel.OpenAICompatibleCustom
import ru.souz.llms.LLMModel.OpenAIGpt5Mini
import ru.souz.llms.LLMModel.QwenMax
import ru.souz.llms.LlmProvider
import ru.souz.llms.LlmProvider.AI_TUNNEL
import ru.souz.llms.LlmProvider.ANTHROPIC
import ru.souz.llms.LlmProvider.OPENAI
import ru.souz.llms.LlmProvider.QWEN
import ru.souz.llms.LocalModelAvailability

class EffectiveSettingsResolverTest {
    @Test
    fun `selects the default model with at most one stored key listing`() = runTest {
        val cases = listOf(
            Case(QwenMax, selected = QwenMax, listings = 1, region = "en", customOpenAi = true, keys = setOf(QWEN)),
            Case(QwenMax, requestModel = AnthropicHaiku45, selected = AnthropicHaiku45, listings = 1, region = "en", keys = setOf(ANTHROPIC, QWEN)),
            Case(QwenMax, selected = QwenMax, listings = 0, region = "en", customOpenAi = true, serverKeys = setOf(QWEN)),
            Case(OpenAICompatibleCustom, selected = OpenAICompatibleCustom, listings = 0, region = "en", customOpenAi = true, serverKeys = setOf(OPENAI)),
            Case(CodexGpt54, selected = CodexGpt54, listings = 0, region = "en", customOpenAi = true, codex = true),
            Case(LLMModel.Max, selected = LLMModel.Max, listings = 0),
            Case(LocalQwen3_4B_Instruct_2507, selected = LocalQwen3_4B_Instruct_2507, listings = 0, local = true),
            Case(OpenAIGpt5Mini, selected = OpenAICompatibleCustom, listings = 1, region = "en", customOpenAi = true, keys = setOf(OPENAI)),
            Case(QwenMax, selected = CodexGpt54, listings = 1, region = "en", customOpenAi = true),
            Case(QwenMax, selected = AiTunnelClaudeHaiku, listings = 1, keys = setOf(AI_TUNNEL, ANTHROPIC)),
            Case(QwenMax, selected = AnthropicHaiku45, listings = 1, region = "en", keys = setOf(AI_TUNNEL, ANTHROPIC)),
            Case(QwenMax, selected = LocalQwen3_4B_Instruct_2507, listings = 1, local = true),
            Case(LocalQwen3_4B_Instruct_2507, selected = QwenMax, listings = 1, keys = setOf(QWEN)),
            Case(LocalQwen3_4B_Instruct_2507, selected = AiTunnelClaudeHaiku, listings = 0, serverKeys = setOf(AI_TUNNEL)),
            Case(OpenAICompatibleCustom, selected = QwenMax, listings = 1, region = "en", customOpenAi = true, keys = setOf(QWEN)),
            Case(OpenAICompatibleCustom, selected = OpenAIGpt5Mini, listings = 1, region = "en", keys = setOf(OPENAI)),
            Case(null, selected = OpenAICompatibleCustom, listings = 1, region = "en", customOpenAi = true, keys = setOf(OPENAI)),
            Case(QwenMax, selected = QwenMax, listings = 0, supplied = setOf(QWEN)),
            Case(AiTunnelClaudeHaiku, selected = QwenMax, listings = 0, keys = setOf(AI_TUNNEL), supplied = emptySet()),
            Case(
                null, selected = QwenMax, saved = QwenMax, listings = 1,
                region = "en", customOpenAi = true, keys = setOf(QWEN), newUser = true,
            ),
            Case(
                null, selected = AnthropicHaiku45, saved = AnthropicHaiku45, listings = 1,
                keys = setOf(ANTHROPIC, QWEN), serverDefault = AnthropicHaiku45, newUser = true,
            ),
        )
        assertEquals(cases, cases.map { resolve(it) })
    }

    private suspend fun resolve(case: Case): Case {
        var listings = 0
        var saved: LLMModel? = null
        val settings = LlmSettingsStub().apply {
            regionProfile = case.region
            gigaModel = case.serverDefault
            qwenChatKey = SERVER_KEY.takeIf { QWEN in case.serverKeys }
            aiTunnelKey = SERVER_KEY.takeIf { AI_TUNNEL in case.serverKeys }
            anthropicKey = SERVER_KEY.takeIf { ANTHROPIC in case.serverKeys }
            openaiKey = SERVER_KEY.takeIf { OPENAI in case.serverKeys }
            openaiModel = "custom-model".takeIf { case.customOpenAi }
            if (case.codex) {
                codexAccessToken = "access"
                codexRefreshToken = "refresh"
                codexAccountId = "account"
                codexExpiresAt = Long.MAX_VALUE
            }
        }
        val settingsRepository = mockk<UserSettingsRepository>()
        coEvery { settingsRepository.get(USER) } returns
            if (case.newUser) null else UserSettings(USER, defaultModel = case.model)
        coEvery { settingsRepository.save(any()) } answers { firstArg<UserSettings>().also { saved = it.defaultModel } }
        val keyRepository = mockk<UserProviderKeyRepository> {
            coEvery { list(USER) } answers {
                listings++
                case.keys.map { UserProviderKey(userId = USER, provider = it, encryptedApiKey = "encrypted", keyHint = "...key") }
            }
        }
        val resolver = EffectiveSettingsResolver(
            baseSettingsProvider = settings,
            userSettingsRepository = settingsRepository,
            userProviderKeyRepository = keyRepository,
            featureFlags = BackendFeatureFlags(),
            toolCatalog = TestToolCatalog(),
            localModelAvailability = object : LocalModelAvailability {
                override fun availableGigaModels() = listOfNotNull(LocalQwen3_4B_Instruct_2507.takeIf { case.local })
                override fun defaultGigaModel() = availableGigaModels().firstOrNull()
                override fun isProviderAvailable() = case.local
            },
        )
        val resolved = resolver.resolve(USER, UserSettingsOverrides(defaultModel = case.requestModel), userManagedProviders = case.supplied)
        return case.copy(selected = resolved.defaultModel, saved = saved, listings = listings)
    }
}

/** Resolution inputs with the expected selected model, new-user saved default, and stored key listings. */
private data class Case(
    val model: LLMModel?,
    val requestModel: LLMModel? = null,
    val selected: LLMModel,
    val listings: Int,
    val saved: LLMModel? = null,
    val region: String = "ru",
    val keys: Set<LlmProvider> = emptySet(),
    val supplied: Set<LlmProvider>? = null,
    val serverKeys: Set<LlmProvider> = emptySet(),
    val customOpenAi: Boolean = false,
    val codex: Boolean = false,
    val local: Boolean = false,
    val newUser: Boolean = false,
    val serverDefault: LLMModel = LLMModel.Max,
)

private const val USER = "user-1"
private const val SERVER_KEY = "server-key"
