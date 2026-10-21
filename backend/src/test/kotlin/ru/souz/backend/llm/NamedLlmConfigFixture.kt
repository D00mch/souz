package ru.souz.backend.llm

import java.nio.file.Files
import ru.souz.backend.config.BackendConfigSource
/** Shared file-backed configuration for configuration, HTTP routing and production-wired tests. */
internal fun withNamedLlmConfig(
    json: String = NAMED_LLM_CONFIG,
    environment: Map<String, String> = emptyMap(),
    block: (BackendConfigSource) -> Unit,
) {
    val path = Files.createTempFile("souz-llm-", ".json")
    try {
        Files.writeString(path, json)
        block(object : BackendConfigSource {
            override fun env(key: String): String? = when (key) {
                "SOUZ_LLM_CONFIG_FILE" -> path.toString()
                "ROUTER_KEY" -> "router-secret"
                "EXPERIMENT_KEY" -> "experiment-secret"
                else -> environment[key]
            }
            override fun property(key: String): String? = null
        })
    } finally {
        Files.deleteIfExists(path)
    }
}

internal const val NAMED_LLM_CONFIG = """{
  "openaiConnections": {
    "router": {"baseUrl":"https://router.test/v1", "apiKeyEnv":"ROUTER_KEY"},
    "experiment": {"baseUrl":"https://experiment.test/v1", "apiKeyEnv":"EXPERIMENT_KEY"}
  },
  "models": {
    "test-chat": {"connection":"experiment", "modelId":"shared-model"},
    "tv-agent": {"connection":"router", "modelId":"shared-model"}
  },
  "subagentModels": ["tv-agent"],
  "summarization": {"model":"tv-agent"}
}"""
