package ru.souz.db

import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfileSettings
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.VoiceRecognitionProvider

const val REGION_RU = "ru"
const val REGION_EN = "en"
const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 400_000L

interface SettingsProvider : AgentSettingsProvider, LlmBuildProfileSettings {
    val subagentModels: Map<String, LlmProvider> get() = emptyMap()

    override fun executionModelId(model: LLMModel): String =
        if (model == LLMModel.OpenAICompatibleCustom) {
            openaiModel?.trim()?.takeIf(String::isNotEmpty) ?: model.alias
        } else model.alias

    var gigaChatKey: String?
    var qwenChatKey: String?
    var aiTunnelKey: String?
    var anthropicKey: String?
    var openaiKey: String?
    var openaiBaseUrl: String?
    var openaiModel: String?
    val openaiSummarizationApiKey: String? get() = null
    val openaiSummarizationBaseUrl: String? get() = null
    val openaiSummarizationModel: String? get() = null
    val openaiSummarizationParameters: String? get() = null
    var codexAccessToken: String?
    var codexRefreshToken: String?
    var codexAccountId: String?
    var codexExpiresAt: Long?
    var saluteSpeechKey: String?
    var supportEmail: String?
    override var regionProfile: String
    var ambientAnalysisModel: LLMModel
    var useFewShotExamples: Boolean
    var notificationSoundEnabled: Boolean
    var voiceInputReviewEnabled: Boolean
    var safeModeEnabled: Boolean
    var needsOnboarding: Boolean
    var onboardingCompleted: Boolean
    var requestTimeoutMillis: Long
    var initialWindowWidthDp: Int
    var initialWindowHeightDp: Int
    var forbiddenFolders: List<String>
    var embeddingsModel: EmbeddingsModel
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
