package ru.souz.service.speech

import ru.souz.db.REGION_RU
import ru.souz.db.SettingsProvider
import ru.souz.llms.giga.GigaVoiceAPI
import ru.souz.llms.tunnel.AiTunnelVoiceAPI

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
    override val enabled: Boolean
        get() = settingsProvider.regionProfile == REGION_RU
    override val hasRequiredKey: Boolean
        get() = enabled && !settingsProvider.saluteSpeechKey.isNullOrBlank()

    override suspend fun recognize(audio: ByteArray): String =
        gigaVoiceAPI.recognize(audio).result.joinToString("\n").trim()
}

class AiTunnelSpeechRecognitionProvider(
    private val aiTunnelVoiceAPI: AiTunnelVoiceAPI,
    private val settingsProvider: SettingsProvider,
    private val isRuBuildProvider: () -> Boolean = { settingsProvider.regionProfile == REGION_RU },
) : SpeechRecognitionProvider {
    override val enabled: Boolean
        get() = isRuBuildProvider()

    override val hasRequiredKey: Boolean
        get() = enabled && !settingsProvider.aiTunnelKey.isNullOrBlank()

    override suspend fun recognize(audio: ByteArray): String {
        if (!enabled) throw VoiceRecognitionUnavailableException()
        return aiTunnelVoiceAPI.recognize(audio).trim()
    }
}

/** Produces WAV bytes for spoken output. */
interface SpeechSynthesisProvider {
    val hasRequiredKey: Boolean

    suspend fun synthesize(text: String): ByteArray
}

class AiTunnelSpeechSynthesisProvider(
    private val aiTunnelVoiceAPI: AiTunnelVoiceAPI,
    private val settingsProvider: SettingsProvider,
) : SpeechSynthesisProvider {
    override val hasRequiredKey: Boolean
        get() = !settingsProvider.aiTunnelKey.isNullOrBlank()

    override suspend fun synthesize(text: String): ByteArray = aiTunnelVoiceAPI.synthesize(text)
}

class SaluteSpeechSynthesisProvider(
    private val gigaVoiceAPI: GigaVoiceAPI,
    private val settingsProvider: SettingsProvider,
) : SpeechSynthesisProvider {
    override val hasRequiredKey: Boolean
        get() = !settingsProvider.saluteSpeechKey.isNullOrBlank()

    override suspend fun synthesize(text: String): ByteArray =
        gigaVoiceAPI.synthesize("<speak>${text.escapeSsml()}</speak>")
}

private fun String.escapeSsml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

class VoiceRecognitionUnavailableException : IllegalStateException("Voice recognition is not configured for this build")
