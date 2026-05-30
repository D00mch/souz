package ru.souz.backend

import ru.souz.agent.AgentId
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.VoiceRecognitionModel

internal class TestSettingsProvider : SettingsProvider {
    private val promptOverrides = HashMap<Pair<AgentId, LLMModel>, String>()

    override var gigaChatKey: String? = null
    override var qwenChatKey: String? = null
    override var aiTunnelKey: String? = null
    override var anthropicKey: String? = null
    override var openaiKey: String? = null
    override var saluteSpeechKey: String? = null
    override var supportEmail: String? = null
    override var defaultCalendar: String? = null
    override var regionProfile: String = "ru"
    override var activeAgentId: AgentId = AgentId.default
    override var gigaModel: LLMModel = LLMModel.Max
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
