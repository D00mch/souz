package ru.souz.backend.settings.service

import io.mockk.coEvery
import io.mockk.mockk
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import ru.souz.backend.http.BackendV1Exception
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
import ru.souz.llms.LLMModel.OpenAICompatibleCustom
import ru.souz.llms.LLMModel.OpenAIGpt5Mini
import ru.souz.llms.LLMModel.QwenMax
import ru.souz.llms.LlmProvider
import ru.souz.llms.LlmProvider.AI_TUNNEL
import ru.souz.llms.LlmProvider.ANTHROPIC
import ru.souz.llms.LlmProvider.OPENAI
import ru.souz.llms.LlmProvider.QWEN

class EffectiveSettingsResolverTest {
    @Test
    fun `selects the default model with at most one stored key listing`() = runTest {
        val named = "test-chat"
        val cases = listOf(
            Case(null, selected = named, saved = named, listings = 1, newUser = true, named = true),
            Case(QwenMax.alias, selected = named, listings = 1, named = true),
            Case(null, selected = named, saved = named, listings = 1, newUser = true, named = true, region = "en"),
            Case(named, selected = named, listings = 0, named = true),
            Case(QwenMax.alias, requestModel = named, selected = named, listings = 0, named = true),
            Case(QwenMax.alias, selected = QwenMax.alias, listings = 1, region = "en", customOpenAi = true, keys = setOf(QWEN)),
            Case(QwenMax.alias, requestModel = AnthropicHaiku45.alias, selected = AnthropicHaiku45.alias, listings = 1, region = "en", keys = setOf(ANTHROPIC, QWEN)),
            Case(QwenMax.alias, selected = QwenMax.alias, listings = 0, region = "en", customOpenAi = true, serverKeys = setOf(QWEN)),
            Case(OpenAICompatibleCustom.alias, selected = OpenAICompatibleCustom.alias, listings = 0, region = "en", customOpenAi = true, serverKeys = setOf(OPENAI)),
            Case(CodexGpt54.alias, selected = CodexGpt54.alias, listings = 0, region = "en", customOpenAi = true, codex = true),
            Case(LLMModel.Max.alias, selected = LLMModel.Max.alias, listings = 0),
            Case(OpenAIGpt5Mini.alias, selected = OpenAICompatibleCustom.alias, listings = 1, region = "en", customOpenAi = true, keys = setOf(OPENAI)),
            Case(QwenMax.alias, selected = CodexGpt54.alias, listings = 1, region = "en", customOpenAi = true),
            Case(QwenMax.alias, selected = AiTunnelClaudeHaiku.alias, listings = 1, keys = setOf(AI_TUNNEL, ANTHROPIC)),
            Case(QwenMax.alias, selected = AnthropicHaiku45.alias, listings = 1, region = "en", keys = setOf(AI_TUNNEL, ANTHROPIC)),
            Case(OpenAICompatibleCustom.alias, selected = QwenMax.alias, listings = 1, region = "en", customOpenAi = true, keys = setOf(QWEN)),
            Case(OpenAICompatibleCustom.alias, selected = OpenAIGpt5Mini.alias, listings = 1, region = "en", keys = setOf(OPENAI)),
            Case(null, selected = OpenAICompatibleCustom.alias, listings = 1, region = "en", customOpenAi = true, keys = setOf(OPENAI)),
            Case(
                null, selected = OpenAICompatibleCustom.alias, listings = 1, region = "en", customOpenAi = true,
                keys = setOf(OPENAI, ANTHROPIC), named = true, fallbacks = listOf("custom-model", AnthropicHaiku45.alias, named),
            ),
            Case(QwenMax.alias, selected = QwenMax.alias, listings = 0, supplied = setOf(QWEN)),
            Case(AiTunnelClaudeHaiku.alias, selected = QwenMax.alias, listings = 0, keys = setOf(AI_TUNNEL), supplied = emptySet()),
            Case(
                null, selected = QwenMax.alias, saved = QwenMax.alias, listings = 1,
                region = "en", customOpenAi = true, keys = setOf(QWEN), newUser = true,
            ),
            Case(
                null, selected = AnthropicHaiku45.alias, saved = AnthropicHaiku45.alias, listings = 1,
                keys = setOf(ANTHROPIC, QWEN), serverDefault = AnthropicHaiku45, newUser = true,
            ),
        )
        assertEquals(cases, cases.map { resolve(it) })
        assertFailsWith<BackendV1Exception> {
            resolve(Case("removed-model", selected = named, listings = 0))
        }
    }

    private suspend fun resolve(case: Case): Case {
        var listings = 0
        var saved: String? = null
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
            namedModelNames = if (case.named) setOf("test-chat") else emptySet(),
            baseSettingsProvider = settings,
            userSettingsRepository = settingsRepository,
            userProviderKeyRepository = keyRepository,
            featureFlags = BackendFeatureFlags(),
            toolCatalog = TestToolCatalog(),
        )
        val resolved = resolver.resolve(USER, UserSettingsOverrides(defaultModel = case.requestModel), userManagedProviders = case.supplied)
        val selection = case.copy(selected = resolved.defaultModel, saved = saved, listings = listings)
        return selection.copy(fallbacks = if (case.fallbacks == null) null else resolver.fallbackModels(USER, Locale.forLanguageTag(case.region)))
    }
}

/** Resolution inputs with the expected selected model, new-user saved default, stored key listings and fallback chain. */
private data class Case(
    val model: String?,
    val requestModel: String? = null,
    val selected: String,
    val listings: Int,
    val saved: String? = null,
    val region: String = "ru",
    val keys: Set<LlmProvider> = emptySet(),
    val supplied: Set<LlmProvider>? = null,
    val serverKeys: Set<LlmProvider> = emptySet(),
    val customOpenAi: Boolean = false,
    val codex: Boolean = false,
    val newUser: Boolean = false,
    val named: Boolean = false,
    val serverDefault: LLMModel = LLMModel.Max,
    val fallbacks: List<String>? = null,
)

private const val USER = "user-1"
private const val SERVER_KEY = "server-key"
