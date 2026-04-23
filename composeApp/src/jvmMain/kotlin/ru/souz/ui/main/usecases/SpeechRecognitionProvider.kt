package ru.souz.ui.main.usecases

import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import ru.souz.llms.giga.GigaVoiceAPI
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.llms.tunnel.AiTunnelVoiceAPI
import ru.souz.llms.openai.OpenAIVoiceAPI
import java.nio.file.Files

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

class OpenAISpeechRecognitionProvider(
    private val openAIVoiceAPI: OpenAIVoiceAPI,
    private val settingsProvider: SettingsProvider,
) : SpeechRecognitionProvider {
    override val enabled: Boolean
        get() = settingsProvider.regionProfile == REGION_EN
    override val hasRequiredKey: Boolean
        get() = enabled && !settingsProvider.openaiKey.isNullOrBlank()

    override suspend fun recognize(audio: ByteArray): String = openAIVoiceAPI.recognize(audio).trim()
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

class MacOsSpeechRecognitionProvider(
    private val settingsProvider: SettingsProvider,
    private val bridge: MacOsSpeechBridgeApi,
    private val isMacOsProvider: () -> Boolean = { isCurrentMacOsSpeechHost() },
) : SpeechRecognitionProvider {
    override val enabled: Boolean
        get() = isMacOsProvider()

    override val hasRequiredKey: Boolean
        get() = enabled

    override suspend fun recognize(audio: ByteArray): String {
        if (!enabled) {
            throw LocalMacOsSpeechUnavailableException("Local macOS speech recognition is unavailable on this host.")
        }

        ensureSpeechUsageDescription()
        ensureAuthorization()
        val locale = localeFor(settingsProvider.regionProfile)
        val wavPath = writePcmToTempWav(audio)
        return try {
            bridge.recognizeWav(wavPath.toString(), locale).trim()
        } catch (error: Throwable) {
            throw mapBridgeError(error, locale)
        } finally {
            Files.deleteIfExists(wavPath)
        }
    }

    private fun ensureSpeechUsageDescription() {
        val hasUsageDescription = try {
            bridge.hasSpeechRecognitionUsageDescription()
        } catch (error: Throwable) {
            throw mapBridgeError(error, localeFor(settingsProvider.regionProfile))
        }

        if (!hasUsageDescription) {
            throw LocalMacOsSpeechAppBundleMissingUsageDescriptionException()
        }
    }

    private fun ensureAuthorization() {
        val status = try {
            bridge.authorizationStatus()
        } catch (error: Throwable) {
            throw mapBridgeError(error, localeFor(settingsProvider.regionProfile))
        }
        if (status == MacOsSpeechAuthorizationStatus.NOT_DETERMINED) {
            try {
                bridge.requestAuthorizationIfNeeded()
            } catch (error: Throwable) {
                throw mapBridgeError(error, localeFor(settingsProvider.regionProfile))
            }
        }

        val resolvedStatus = try {
            bridge.authorizationStatus()
        } catch (error: Throwable) {
            throw mapBridgeError(error, localeFor(settingsProvider.regionProfile))
        }

        when (resolvedStatus) {
            MacOsSpeechAuthorizationStatus.AUTHORIZED -> Unit
            MacOsSpeechAuthorizationStatus.DENIED ->
                throw LocalMacOsSpeechPermissionDeniedException()

            MacOsSpeechAuthorizationStatus.RESTRICTED ->
                throw LocalMacOsSpeechUnavailableException("macOS speech recognition is restricted on this device.")

            MacOsSpeechAuthorizationStatus.UNSUPPORTED ->
                throw LocalMacOsSpeechUnavailableException("macOS speech recognition is unavailable on this device.")

            MacOsSpeechAuthorizationStatus.NOT_DETERMINED ->
                throw LocalMacOsSpeechUnavailableException("macOS speech recognition authorization did not complete.")
        }
    }

    private fun localeFor(regionProfile: String): String = when (regionProfile) {
        REGION_EN -> "en-US"
        else -> "ru-RU"
    }

    private fun mapBridgeError(error: Throwable, locale: String): Throwable {
        val message = error.message.orEmpty()
        return when {
            message.startsWith(MacOsSpeechBridgeError.PERMISSION_DENIED.prefix) ->
                LocalMacOsSpeechPermissionDeniedException()

            message.startsWith(MacOsSpeechBridgeError.RESTRICTED.prefix) ->
                LocalMacOsSpeechUnavailableException("macOS speech recognition is restricted on this device.")

            message.startsWith(MacOsSpeechBridgeError.UNSUPPORTED_LOCALE.prefix) ->
                LocalMacOsSpeechLocaleUnsupportedException(locale)

            message.startsWith(MacOsSpeechBridgeError.UNAVAILABLE.prefix) ->
                LocalMacOsSpeechUnavailableException(
                    message
                        .removePrefix(MacOsSpeechBridgeError.UNAVAILABLE.prefix)
                        .removePrefix(":")
                        .ifBlank { "Local macOS speech recognition is unavailable." }
                )

            error is LocalMacOsSpeechRecognitionException -> error
            else -> LocalMacOsSpeechUnavailableException(
                message.ifBlank { "Local macOS speech recognition is unavailable." }
            )
        }
    }
}

class ModelAwareSpeechRecognitionProvider(
    private val settingsProvider: SettingsProvider,
    private val saluteSpeechProvider: SaluteSpeechRecognitionProvider,
    private val openAiSpeechProvider: OpenAISpeechRecognitionProvider,
    private val aiTunnelSpeechProvider: AiTunnelSpeechRecognitionProvider,
    private val macOsSpeechProvider: MacOsSpeechRecognitionProvider,
) : SpeechRecognitionProvider {
    override val enabled: Boolean
        get() = resolveProvider()?.enabled ?: false

    override val hasRequiredKey: Boolean
        get() = resolveProvider()?.hasRequiredKey ?: false

    override suspend fun recognize(audio: ByteArray): String {
        val provider = resolveProvider() ?: throw VoiceRecognitionUnavailableException()
        return provider.recognize(audio)
    }

    private fun resolveProvider(): SpeechRecognitionProvider? {
        return when (settingsProvider.voiceRecognitionModel.provider) {
            VoiceRecognitionProvider.LOCAL_MACOS -> macOsSpeechProvider.takeIf { it.enabled }
            else -> {
                val selectedProvider = providerFor(settingsProvider.voiceRecognitionModel.provider)
                val preferred = buildList {
                    selectedProvider?.let(::add)
                    add(openAiSpeechProvider)
                    add(aiTunnelSpeechProvider)
                    add(saluteSpeechProvider)
                }.distinct().filter { it.enabled }
                preferred.firstOrNull { it.hasRequiredKey } ?: preferred.firstOrNull()
            }
        }
    }

    private fun providerFor(provider: VoiceRecognitionProvider): SpeechRecognitionProvider? = when (provider) {
        VoiceRecognitionProvider.SALUTE_SPEECH -> saluteSpeechProvider
        VoiceRecognitionProvider.AI_TUNNEL -> aiTunnelSpeechProvider
        VoiceRecognitionProvider.OPENAI -> openAiSpeechProvider
        VoiceRecognitionProvider.LOCAL_MACOS -> macOsSpeechProvider
    }
}

class VoiceRecognitionUnavailableException : IllegalStateException("Voice recognition is not configured for this build")

sealed class LocalMacOsSpeechRecognitionException(message: String) : IllegalStateException(message)

class LocalMacOsSpeechPermissionDeniedException :
    LocalMacOsSpeechRecognitionException("Speech recognition permission denied.")

class LocalMacOsSpeechAppBundleMissingUsageDescriptionException :
    LocalMacOsSpeechRecognitionException(
        "Local macOS speech recognition requires a macOS app bundle with NSSpeechRecognitionUsageDescription."
    )

class LocalMacOsSpeechUnavailableException(message: String) :
    LocalMacOsSpeechRecognitionException(message)

class LocalMacOsSpeechLocaleUnsupportedException(locale: String) :
    LocalMacOsSpeechRecognitionException("Local macOS speech recognition locale is unsupported: $locale")

private enum class MacOsSpeechBridgeError(val prefix: String) {
    PERMISSION_DENIED("LOCAL_MACOS_STT:PERMISSION_DENIED"),
    RESTRICTED("LOCAL_MACOS_STT:RESTRICTED"),
    UNAVAILABLE("LOCAL_MACOS_STT:UNAVAILABLE"),
    UNSUPPORTED_LOCALE("LOCAL_MACOS_STT:UNSUPPORTED_LOCALE"),
}
