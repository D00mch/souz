package ru.gigadesk.ui.main.usecases

import ru.gigadesk.giga.GigaVoiceAPI

/** Provide locality specific Voice recognition, e.g. SaluteSpeech for Ru. */
interface SpeechRecognitionProvider {
    val enabled: Boolean
    val requiresVoiceKey: Boolean

    suspend fun recognize(audio: ByteArray): String
}

class SaluteSpeechRecognitionProvider(
    private val gigaVoiceAPI: GigaVoiceAPI,
) : SpeechRecognitionProvider {
    override val enabled: Boolean = true
    override val requiresVoiceKey: Boolean = true

    override suspend fun recognize(audio: ByteArray): String =
        gigaVoiceAPI.recognize(audio).result.joinToString("\n").trim()
}

class VoiceRecognitionUnavailableException : IllegalStateException("Voice recognition is not configured for this build")

object DisabledSpeechRecognitionProvider : SpeechRecognitionProvider {
    override val enabled: Boolean = false
    override val requiresVoiceKey: Boolean = false

    override suspend fun recognize(audio: ByteArray): String = throw VoiceRecognitionUnavailableException()
}
