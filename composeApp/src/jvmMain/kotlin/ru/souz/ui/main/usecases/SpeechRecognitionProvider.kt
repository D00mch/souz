package ru.souz.ui.main.usecases

import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaVoiceAPI
import ru.souz.giga.LlmProvider
import ru.souz.llms.OpenAIVoiceAPI

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

class ModelAwareSpeechRecognitionProvider(
    private val settingsProvider: SettingsProvider,
    private val saluteSpeechProvider: SaluteSpeechRecognitionProvider,
    private val openAiSpeechProvider: OpenAISpeechRecognitionProvider,
) : SpeechRecognitionProvider {
    override val enabled: Boolean = true
    override val hasRequiredKey: Boolean
        get() = resolveProvider().hasRequiredKey

    override suspend fun recognize(audio: ByteArray): String = resolveProvider().recognize(audio)

    private fun resolveProvider(): SpeechRecognitionProvider {
        val preferred = when (settingsProvider.gigaModel.provider) {
            LlmProvider.GIGA -> listOf(saluteSpeechProvider, openAiSpeechProvider)
            LlmProvider.OPENAI -> listOf(openAiSpeechProvider, saluteSpeechProvider)
            LlmProvider.QWEN, LlmProvider.AI_TUNNEL, LlmProvider.ANTHROPIC -> listOf(
                openAiSpeechProvider, saluteSpeechProvider
            )
        }
        return preferred.firstOrNull { it.hasRequiredKey } ?: preferred.first()
    }
}

class VoiceRecognitionUnavailableException : IllegalStateException("Voice recognition is not configured for this build")
