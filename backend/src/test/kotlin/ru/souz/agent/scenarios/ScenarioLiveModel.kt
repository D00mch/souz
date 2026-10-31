package ru.souz.agent.scenarios

import io.ktor.client.HttpClient
import io.mockk.every
import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LlmProvider
import ru.souz.llms.codex.CodexChatAPI
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.createOpenAiProviderHttpClient
import ru.souz.llms.http.createStandardProviderHttpClient
import ru.souz.llms.openai.OpenAICompatibleChatAPI

/** Credentials remain inside the provider; only safe model metadata is exposed. */
class ScenarioLiveModel private constructor(
    val modelId: String,
    val reasoningEffort: String?,
    val provider: LlmProvider,
    val api: LLMChatAPI,
    private val client: HttpClient,
) : AutoCloseable {
    override fun close() = client.close()

    companion object {
        fun fromEnvironment(): ScenarioLiveModel {
            assumeTrue(System.getenv(SOUZ_AGENT_INTEGRATION_TESTS_ON).equals("true", ignoreCase = true),
                "Set $SOUZ_AGENT_INTEGRATION_TESTS_ON=true to evaluate a live model.")
            fun required(name: String): String = requireNotNull(System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }) {
                "$name is required when $SOUZ_AGENT_INTEGRATION_TESTS_ON=true."
            }
            val model = required("OPENAI_MODEL")
            val provider = when (System.getenv(SOUZ_AGENT_INTEGRATION_TEST_PROVIDER)?.lowercase()) {
                null, "openai" -> LlmProvider.OPENAI
                "codex" -> LlmProvider.CODEX
                else -> error("$SOUZ_AGENT_INTEGRATION_TEST_PROVIDER must be openai or codex.")
            }
            val settings = scenarioSettings(model, provider)
            val baseUrl = if (provider == LlmProvider.OPENAI) required("OPENAI_BASE_URL") else null
            val key = if (provider == LlmProvider.OPENAI) required("OPENAI_API_KEY") else null
            if (provider == LlmProvider.CODEX) {
                val accessToken = required("CODEX_ACCESS_TOKEN")
                val accountId = required("CODEX_ACCOUNT_ID")
                every { settings.codexAccessToken } returns accessToken
                every { settings.codexAccountId } returns accountId
                // Borrow an existing access token; its owning Codex session manages refresh.
                every { settings.codexExpiresAt } returns null
            }
            val effort = System.getenv(SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT)?.trim()?.takeIf { it.isNotEmpty() }
            val client = if (provider == LlmProvider.CODEX) createStandardProviderHttpClient() else createOpenAiProviderHttpClient()
            val api = if (provider == LlmProvider.CODEX) {
                CodexChatAPI(settings, CodexOAuthService(settings, client), client)
            } else OpenAICompatibleChatAPI(provider, settings, client, apiKey = key, baseUrl = baseUrl)
            return ScenarioLiveModel(model, effort, provider, api, client)
        }
    }
}

const val SOUZ_AGENT_INTEGRATION_TESTS_ON = "SOUZ_AGENT_INTEGRATION_TESTS_ON"
const val SOUZ_AGENT_INTEGRATION_TEST_PROVIDER = "SOUZ_AGENT_INTEGRATION_TEST_PROVIDER"
const val SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT = "SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT"
