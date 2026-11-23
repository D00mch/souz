package ru.souz.agent.scenarios

import io.ktor.client.HttpClient
import io.ktor.http.Url
import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LlmProvider
import ru.souz.llms.http.createOpenAiProviderHttpClient
import ru.souz.llms.openai.OpenAICompatibleChatAPI

/** Credentials remain inside the provider; only safe model metadata is exposed. */
internal class ScenarioLiveModel private constructor(
    val modelId: String,
    val reasoningEffort: String?,
    val provider: LlmProvider,
    val api: LLMChatAPI,
    private val client: HttpClient,
    private val baseClient: HttpClient,
    val wire: ScenarioWireCapture,
    val providerHost: String,
) : AutoCloseable {
    override fun close() {
        client.close()
        baseClient.close()
    }

    companion object {
        fun fromEnvironment(): ScenarioLiveModel {
            assumeTrue(System.getenv(SOUZ_AGENT_INTEGRATION_TESTS_ON).equals("true", ignoreCase = true),
                "Set $SOUZ_AGENT_INTEGRATION_TESTS_ON=true to evaluate a live model.")
            fun required(name: String): String = requireNotNull(System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }) {
                "$name is required when $SOUZ_AGENT_INTEGRATION_TESTS_ON=true."
            }
            val model = required("OPENAI_MODEL")
            val configuredProvider = System.getenv(SOUZ_AGENT_INTEGRATION_TEST_PROVIDER) ?: "openai"
            require(configuredProvider.equals("openai", ignoreCase = true)) {
                "$SOUZ_AGENT_INTEGRATION_TEST_PROVIDER must be openai for scenario evaluation."
            }
            val settings = scenarioSettings(model)
            val provider = LlmProvider.OPENAI
            val baseUrl = required("OPENAI_BASE_URL")
            val providerHost = Url(baseUrl).host
            val key = required("OPENAI_API_KEY")
            val effort = System.getenv(SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT)?.trim()?.takeIf { it.isNotEmpty() }
            val baseClient = createOpenAiProviderHttpClient()
            val wire = ScenarioWireCapture()
            val client = baseClient.config { install(wire.plugin) }
            val api = OpenAICompatibleChatAPI(provider, settings, client, apiKey = key, baseUrl = baseUrl)
            return ScenarioLiveModel(model, effort, provider, api, client, baseClient, wire, providerHost)
        }
    }
}

const val SOUZ_AGENT_INTEGRATION_TESTS_ON = "SOUZ_AGENT_INTEGRATION_TESTS_ON"
const val SOUZ_AGENT_INTEGRATION_TEST_PROVIDER = "SOUZ_AGENT_INTEGRATION_TEST_PROVIDER"
const val SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT = "SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT"
