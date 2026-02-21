package ru.souz.ui.main.usecases

import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaVoiceAPI
import ru.souz.giga.LlmProvider
import ru.souz.llms.OpenAIVoiceAPI
import ru.souz.llms.QwenVoiceAPI

/** Provide locality specific Voice recognition, e.g. SaluteSpeech for Ru. */
interface SpeechRecognitionProvider {
    val enabled: Boolean
    val hasRequiredKey: Boolean

    suspend fun recognize(audio: ByteArray): String
}

class SaluteSpeechRecognitionProvider(
    private val gigaVoiceAPI: GigaVoiceAPI,
    private val settingsProvider: SettingsProvider,
) : SpeechRecognitionProvider {
    override val enabled: Boolean = true
    override val hasRequiredKey: Boolean
        get() = !settingsProvider.saluteSpeechKey.isNullOrBlank()

    override suspend fun recognize(audio: ByteArray): String =
        gigaVoiceAPI.recognize(audio).result.joinToString("\n").trim()
}

class OpenAISpeechRecognitionProvider(
    private val openAIVoiceAPI: OpenAIVoiceAPI,
    private val settingsProvider: SettingsProvider,
) : SpeechRecognitionProvider {
    override val enabled: Boolean = true
    override val hasRequiredKey: Boolean
        get() = !settingsProvider.openaiKey.isNullOrBlank()

    override suspend fun recognize(audio: ByteArray): String = openAIVoiceAPI.recognize(audio).trim()
}

class QwenSpeechRecognitionProvider(
    private val qwenVoiceAPI: QwenVoiceAPI,
    private val settingsProvider: SettingsProvider,
) : SpeechRecognitionProvider {
    override val enabled: Boolean = true
    override val hasRequiredKey: Boolean
        get() = !settingsProvider.qwenChatKey.isNullOrBlank()

    override suspend fun recognize(audio: ByteArray): String = qwenVoiceAPI.recognize(audio).trim()
}

class ModelAwareSpeechRecognitionProvider(
    private val settingsProvider: SettingsProvider,
    private val saluteSpeechProvider: SaluteSpeechRecognitionProvider,
    private val openAiSpeechProvider: OpenAISpeechRecognitionProvider,
    private val qwenSpeechProvider: QwenSpeechRecognitionProvider,
) : SpeechRecognitionProvider {
    override val enabled: Boolean = true
    override val hasRequiredKey: Boolean
        get() = resolveProvider().hasRequiredKey

    override suspend fun recognize(audio: ByteArray): String = resolveProvider().recognize(audio)

    private fun resolveProvider(): SpeechRecognitionProvider {
        val preferred = when (settingsProvider.gigaModel.provider) {
            LlmProvider.GIGA -> listOf(saluteSpeechProvider, qwenSpeechProvider, openAiSpeechProvider)
            LlmProvider.OPENAI -> listOf(openAiSpeechProvider, qwenSpeechProvider, saluteSpeechProvider)
            LlmProvider.QWEN -> listOf(qwenSpeechProvider, openAiSpeechProvider, saluteSpeechProvider)
            LlmProvider.AI_TUNNEL, LlmProvider.ANTHROPIC -> listOf(
                qwenSpeechProvider, openAiSpeechProvider, saluteSpeechProvider
            )
        }
        return preferred.firstOrNull { it.hasRequiredKey } ?: preferred.first()
    }
}

class VoiceRecognitionUnavailableException : IllegalStateException("Voice recognition is not configured for this build")
