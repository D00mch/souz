package ru.souz.agent.scenarios

import io.ktor.client.HttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LlmProvider
import ru.souz.llms.http.createOpenAiProviderHttpClient
import ru.souz.llms.openai.OpenAICompatibleChatAPI

/** Credentials remain inside the provider; only safe model metadata is exposed. */
class ScenarioLiveModel private constructor(
    val modelId: String,
    val reasoningEffort: String?,
    val api: LLMChatAPI,
    private val client: HttpClient,
) : AutoCloseable {
    override fun close() = client.close()

    fun testSupport(): AgentScenarioTestSupport = AgentScenarioTestSupport(modelId, api, reasoningEffort)

    companion object {
        fun fromEnvironment(
            read: (String) -> String? = System::getenv,
            createClient: () -> HttpClient = ::createOpenAiProviderHttpClient,
        ): ScenarioLiveModel {
            assumeTrue(read(SOUZ_AGENT_INTEGRATION_TESTS_ON).equals("true", ignoreCase = true),
                "Set $SOUZ_AGENT_INTEGRATION_TESTS_ON=true to evaluate a live model.")
            fun required(name: String): String = requireNotNull(read(name)?.trim()?.takeIf { it.isNotEmpty() }) {
                "$name is required when $SOUZ_AGENT_INTEGRATION_TESTS_ON=true."
            }
            val model = required("OPENAI_MODEL")
            val baseUrl = required("OPENAI_BASE_URL")
            val key = required("OPENAI_API_KEY")
            val effort = read(SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT)?.trim()?.takeIf { it.isNotEmpty() }
            val client = createClient()
            return ScenarioLiveModel(model, effort, OpenAICompatibleChatAPI(
                LlmProvider.OPENAI, scenarioSettings(model), client, apiKey = key, baseUrl = baseUrl,
            ), client)
        }
    }
}

const val SOUZ_AGENT_INTEGRATION_TESTS_ON = "SOUZ_AGENT_INTEGRATION_TESTS_ON"
const val SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT = "SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT"
