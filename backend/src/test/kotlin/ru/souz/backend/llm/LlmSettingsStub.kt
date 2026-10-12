package ru.souz.backend.llm

import ru.souz.agent.AgentId
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.VoiceRecognitionModel

internal class LlmSettingsStub : SettingsProvider {
    override var namedLlmModels = ru.souz.db.NamedLlmModels.EMPTY
    private val promptOverrides = HashMap<Pair<AgentId, LLMModel>, String>()

    override var gigaChatKey: String? = null
    override var qwenChatKey: String? = null
    override var aiTunnelKey: String? = null
    override var anthropicKey: String? = null
    override var openaiKey: String? = null
    override var openaiBaseUrl: String? = null
    override var openaiModel: String? = null
    override var openaiSummarizationApiKey: String? = null
    override var openaiSummarizationBaseUrl: String? = null
    override var openaiSummarizationModel: String? = null
    override var openaiSummarizationParameters: String? = null
    override var codexAccessToken: String? = null
    override var codexRefreshToken: String? = null
    override var codexAccountId: String? = null
    override var codexExpiresAt: Long? = null
    override var saluteSpeechKey: String? = null
    override var supportEmail: String? = null
    override var defaultCalendar: String? = null
    override var regionProfile: String = "ru"
    override var activeAgentId: AgentId = AgentId.default
    override var gigaModel: LLMModel = LLMModel.Max
    override var ambientAnalysisModel: LLMModel = LLMModel.LocalQwen3_4B_Instruct_2507
    override var useFewShotExamples: Boolean = false
    override var useStreaming: Boolean = false
    override var notificationSoundEnabled: Boolean = true
    override var voiceInputReviewEnabled: Boolean = false
    override var safeModeEnabled: Boolean = true
    override var needsOnboarding: Boolean = false
    override var onboardingCompleted: Boolean = false
    override var requestTimeoutMillis: Long = 30_000
    override var contextSize: Int = 16_000
    override var initialWindowWidthDp: Int = 580
    override var initialWindowHeightDp: Int = 780
    override var temperature: Float = 0.7f
    override var forbiddenFolders: List<String> = emptyList()
    override var embeddingsModel: EmbeddingsModel = EmbeddingsModel.GigaEmbeddings
    override var voiceRecognitionModel: VoiceRecognitionModel = VoiceRecognitionModel.SaluteSpeech
    override var mcpServersJson: String? = null
    override var mcpServersFile: String? = null

    override fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String? =
        promptOverrides[agentId to model]

    override fun setSystemPromptForAgentModel(agentId: AgentId, model: LLMModel, prompt: String?) {
        val key = agentId to model
        if (prompt.isNullOrBlank()) {
            promptOverrides.remove(key)
        } else {
            promptOverrides[key] = prompt
        }
    }
}

/** Shared file-backed configuration for configuration, HTTP routing and production-wired tests. */
internal fun withNamedLlmConfig(
    json: String = NAMED_LLM_CONFIG,
    block: (ru.souz.backend.config.BackendConfigSource) -> Unit,
) {
    val path = java.nio.file.Files.createTempFile("souz-llm-", ".json")
    try {
        java.nio.file.Files.writeString(path, json)
        block(object : ru.souz.backend.config.BackendConfigSource {
            override fun env(key: String): String? = when (key) {
                "SOUZ_LLM_CONFIG_FILE" -> path.toString()
                "ROUTER_KEY" -> "router-secret"
                "EXPERIMENT_KEY" -> "experiment-secret"
                else -> null
            }
            override fun property(key: String): String? = null
        })
    } finally {
        java.nio.file.Files.deleteIfExists(path)
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
  "summarization": {"model":"tv-agent", "contextSize":32000}
}"""
