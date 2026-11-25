package ru.souz.agent.scenarios

import io.ktor.http.Url
import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LlmProvider
import ru.souz.llms.http.createOpenAiProviderHttpClient
import ru.souz.llms.openai.OpenAICompatibleChatAPI

internal class ScenarioLiveModel(
    val modelId: String,
    val reasoningEffort: String?,
    val providerHost: String,
    val wire: ScenarioWire,
    val api: LLMChatAPI,
)

internal suspend fun withScenarioModel(run: suspend (ScenarioLiveModel) -> Unit) {
    val optIn = "SOUZ_AGENT_INTEGRATION_TESTS_ON"
    assumeTrue(System.getenv(optIn).equals("true", true), "Set $optIn=true to evaluate a live model.")
    val provider = System.getenv("SOUZ_AGENT_INTEGRATION_TEST_PROVIDER") ?: "openai"
    require(provider.equals("openai", true)) { "Scenario evaluation requires an OpenAI-compatible provider." }
    val id = scenarioEnv("OPENAI_MODEL")
    val endpoint = scenarioEnv("OPENAI_BASE_URL")
    val key = scenarioEnv("OPENAI_API_KEY")
    val effort = System.getenv("SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT")?.trim()?.takeIf(String::isNotEmpty)
    val wire = ScenarioWire()
    createOpenAiProviderHttpClient().use { base ->
        base.config { install(wire.plugin) }.use { client ->
            val api = OpenAICompatibleChatAPI(LlmProvider.OPENAI, scenarioSettings(id), client, apiKey = key, baseUrl = endpoint)
            run(ScenarioLiveModel(id, effort, Url(endpoint).host, wire, api))
        }
    }
}

private fun scenarioEnv(name: String) = requireNotNull(System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)) {
    "$name is required to run the scenario evaluation."
}
