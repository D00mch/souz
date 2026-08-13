package ru.souz.db

import ru.souz.agent.AgentId
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfileSettings
import ru.souz.llms.LlmProvider
import ru.souz.llms.ProviderSettings
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.VoiceRecognitionProvider

const val REGION_RU = "ru"
const val REGION_EN = "en"
const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 400_000L

interface SettingsProvider : AgentSettingsProvider, LlmBuildProfileSettings, ProviderSettings {
    var gigaChatKey: String?
    var qwenChatKey: String?
    var aiTunnelKey: String?
    var anthropicKey: String?
    var openaiKey: String?
    override var openaiBaseUrl: String?
    override var openaiModel: String?
    var codexAccessToken: String?
    var codexRefreshToken: String?
    var codexAccountId: String?
    var codexExpiresAt: Long?
    var saluteSpeechKey: String?
    var supportEmail: String?
    override var defaultCalendar: String?
    override var regionProfile: String
    override var activeAgentId: AgentId
    override var gigaModel: LLMModel
    var ambientAnalysisModel: LLMModel
    var useFewShotExamples: Boolean
    override var useStreaming: Boolean
    var notificationSoundEnabled: Boolean
    var voiceInputReviewEnabled: Boolean
    var safeModeEnabled: Boolean
    var needsOnboarding: Boolean
    var onboardingCompleted: Boolean
    override var requestTimeoutMillis: Long
    override var contextSize: Int
    var initialWindowWidthDp: Int
    var initialWindowHeightDp: Int
    override var temperature: Float
    var forbiddenFolders: List<String>
    override var embeddingsModel: EmbeddingsModel
    var voiceRecognitionModel: VoiceRecognitionModel
    var mcpServersJson: String?
    var mcpServersFile: String?

    fun hasKey(provider: LlmProvider): Boolean = when (provider) {
        LlmProvider.GIGA -> !gigaChatKey.isNullOrBlank()
        LlmProvider.QWEN -> !qwenChatKey.isNullOrBlank()
        LlmProvider.AI_TUNNEL -> !aiTunnelKey.isNullOrBlank()
        LlmProvider.ANTHROPIC -> !anthropicKey.isNullOrBlank()
        LlmProvider.OPENAI -> !openaiKey.isNullOrBlank()
        LlmProvider.LOCAL -> true
        LlmProvider.CODEX -> !codexAccessToken.isNullOrBlank()
    }

    fun hasKey(provider: VoiceRecognitionProvider): Boolean = when (provider) {
        VoiceRecognitionProvider.SALUTE_SPEECH -> !saluteSpeechKey.isNullOrBlank()
        VoiceRecognitionProvider.AI_TUNNEL -> !aiTunnelKey.isNullOrBlank()
        VoiceRecognitionProvider.OPENAI -> !openaiKey.isNullOrBlank()
        VoiceRecognitionProvider.LOCAL_MACOS -> true
    }
}
