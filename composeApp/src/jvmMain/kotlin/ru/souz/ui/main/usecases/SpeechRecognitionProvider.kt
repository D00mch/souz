package ru.souz.ui.main.usecases

import ru.souz.db.SettingsProvider
import ru.souz.edition.BuildEdition
import ru.souz.edition.BuildEditionConfig
import ru.souz.giga.GigaVoiceAPI
import ru.souz.giga.VoiceRecognitionProvider
import ru.souz.llms.AiTunnelVoiceAPI
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

class AiTunnelSpeechRecognitionProvider(
    private val aiTunnelVoiceAPI: AiTunnelVoiceAPI,
    private val settingsProvider: SettingsProvider,
    private val isRuEdition: Boolean = BuildEditionConfig.current == BuildEdition.RU,
) : SpeechRecognitionProvider {
    override val enabled: Boolean
        get() = isRuEdition

    override val hasRequiredKey: Boolean
        get() = enabled && !settingsProvider.aiTunnelKey.isNullOrBlank()

    override suspend fun recognize(audio: ByteArray): String {
        if (!enabled) throw VoiceRecognitionUnavailableException()
        return aiTunnelVoiceAPI.recognize(audio).trim()
    }
}

class ModelAwareSpeechRecognitionProvider(
    private val settingsProvider: SettingsProvider,
    private val saluteSpeechProvider: SaluteSpeechRecognitionProvider,
    private val openAiSpeechProvider: OpenAISpeechRecognitionProvider,
    private val aiTunnelSpeechProvider: AiTunnelSpeechRecognitionProvider,
) : SpeechRecognitionProvider {
    private val allProviders: List<SpeechRecognitionProvider> = listOf(
        saluteSpeechProvider,
        openAiSpeechProvider,
        aiTunnelSpeechProvider,
    )

    override val enabled: Boolean
        get() = allProviders.any { it.enabled }

    override val hasRequiredKey: Boolean
        get() = resolveProvider()?.hasRequiredKey ?: false

    override suspend fun recognize(audio: ByteArray): String {
        val provider = resolveProvider() ?: throw VoiceRecognitionUnavailableException()
        return provider.recognize(audio)
    }

    private fun resolveProvider(): SpeechRecognitionProvider? {
        val selectedProvider = providerFor(settingsProvider.voiceRecognitionModel.provider)
        val preferred = buildList {
            selectedProvider?.let(::add)
            add(openAiSpeechProvider)
            add(aiTunnelSpeechProvider)
            add(saluteSpeechProvider)
        }.distinct().filter { it.enabled }
        return preferred.firstOrNull { it.hasRequiredKey } ?: preferred.firstOrNull()
    }

    private fun providerFor(provider: VoiceRecognitionProvider): SpeechRecognitionProvider? = when (provider) {
        VoiceRecognitionProvider.SALUTE_SPEECH -> saluteSpeechProvider
        VoiceRecognitionProvider.AI_TUNNEL -> aiTunnelSpeechProvider
        VoiceRecognitionProvider.OPENAI -> openAiSpeechProvider
    }
}

class VoiceRecognitionUnavailableException : IllegalStateException("Voice recognition is not configured for this build")
