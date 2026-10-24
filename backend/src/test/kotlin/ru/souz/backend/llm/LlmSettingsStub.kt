package ru.souz.backend.llm

import ru.souz.agent.AgentId
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel

internal class LlmSettingsStub : SettingsProvider {
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
    override var regionProfile: String = "ru"
    override var activeAgentId: AgentId = AgentId.default
    override var gigaModel: LLMModel = LLMModel.Max
    override var useFewShotExamples: Boolean = false
    override var useStreaming: Boolean = false
    override var requestTimeoutMillis: Long = 30_000
    override var contextSize: Int = 16_000
    override var temperature: Float = 0.7f
    override var forbiddenFolders: List<String> = emptyList()
    override var embeddingsModel: EmbeddingsModel = EmbeddingsModel.GigaEmbeddings

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
