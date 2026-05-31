package ru.souz.service.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import ru.souz.llms.giga.GigaVoiceAPI
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.llms.tunnel.AiTunnelVoiceAPI
import ru.souz.llms.openai.OpenAIVoiceAPI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val LOCAL_MACOS_PCM_SAMPLE_RATE_HZ = 16_000
private const val LOCAL_MACOS_PCM_BYTES_PER_SAMPLE = 2
private const val LOCAL_MACOS_PCM_CHANNELS = 1
private const val MAX_LOCAL_MACOS_STT_AUDIO_SECONDS = 45

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
    private val isMacOsProvider: () -> Boolean = LocalMacOsSpeechHost::isCurrentHost,
    private val liveSpeechProvider: LiveSpeechTranscriptionProvider? = null,
) : SpeechRecognitionProvider {
    override val enabled: Boolean
        get() = isMacOsProvider()

    override val hasRequiredKey: Boolean
        get() = enabled

    override suspend fun recognize(audio: ByteArray): String = withContext(Dispatchers.IO) {
        if (!enabled) {
            throw LocalMacOsSpeechUnavailableException("Local macOS speech recognition is unavailable on this host.")
        }

        val locale = localeFor(settingsProvider.regionProfile)
        ensureSpeechUsageDescription(locale)
        ensureAuthorization(locale)

        val liveProvider = currentLiveSpeechProvider()
        if (isLiveSupportedForPushToTalk(liveProvider, locale)) {
            try {
                return@withContext recognizeWithLiveProvider(liveProvider, locale, audio).trim()
            } catch (error: LocalMacOsLiveSpeechUnsupportedException) {
                // Use the legacy local batch backend when the live backend is unavailable before transcription.
            } catch (error: LocalMacOsLiveSpeechModelUnavailableException) {
                // Use the legacy local batch backend when the live model is unavailable before transcription.
            } catch (error: LocalMacOsLiveSpeechPermissionDeniedException) {
                throw LocalMacOsSpeechPermissionDeniedException()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw mapLiveRecognitionError(error, locale)
            }
        }

        recognizeWithLegacyBatch(locale, audio).trim()
    }

    suspend fun supportsAmbientLiveTranscription(locale: String): Boolean = withContext(Dispatchers.IO) {
        enabled && currentLiveSpeechProvider().isSupported(locale)
    }

    private fun currentLiveSpeechProvider(): LiveSpeechTranscriptionProvider =
        liveSpeechProvider ?: MacOsSpeechAnalyzerLiveTranscriptionProvider(bridge, isMacOsProvider)

    private suspend fun isLiveSupportedForPushToTalk(
        liveProvider: LiveSpeechTranscriptionProvider,
        locale: String,
    ): Boolean = try {
        liveProvider.isSupported(locale)
    } catch (error: LocalMacOsLiveSpeechPermissionDeniedException) {
        throw LocalMacOsSpeechPermissionDeniedException()
    } catch (error: LocalMacOsLiveSpeechException) {
        false
    } catch (error: CancellationException) {
        throw error
    }

    private suspend fun recognizeWithLiveProvider(
        liveProvider: LiveSpeechTranscriptionProvider,
        locale: String,
        audio: ByteArray,
    ): String {
        var session: LiveSpeechTranscriptionSession? = null
        var finalized = false
        val events = mutableListOf<LiveSpeechTranscriptEvent>()
        try {
            session = liveProvider.start(locale)
            try {
                for (chunk in audio.pcmLiveChunks()) {
                    session.acceptPcm(chunk)
                    events += session.pollEvents()
                }
                events += session.finalizeAndFinish()
                finalized = true
            } catch (error: LocalMacOsLiveSpeechUnsupportedException) {
                throw LocalMacOsLiveSpeechUnavailableException(
                    error.message ?: "Local macOS live speech transcription failed."
                )
            } catch (error: LocalMacOsLiveSpeechModelUnavailableException) {
                throw LocalMacOsLiveSpeechUnavailableException(
                    error.message ?: "Local macOS live speech transcription model became unavailable."
                )
            }
        } finally {
            if (!finalized) {
                runCatching { session?.cancel() }
            }
        }

        val finalEvents = events.filter { it.isFinal }
        return if (finalEvents.isNotEmpty()) {
            finalEvents.joinToString(separator = "") { it.text }
        } else {
            events.lastOrNull { !it.isFinal }?.text.orEmpty()
        }
    }

    private fun ByteArray.pcmLiveChunks(): Sequence<ByteArray> = sequence {
        val bytesPerSecond = LOCAL_MACOS_PCM_SAMPLE_RATE_HZ *
            LOCAL_MACOS_PCM_CHANNELS *
            LOCAL_MACOS_PCM_BYTES_PER_SAMPLE
        val chunkSize = (bytesPerSecond / 5).let { size ->
            size - (size % LOCAL_MACOS_PCM_BYTES_PER_SAMPLE)
        }

        var offset = 0
        while (offset < size) {
            val end = minOf(size, offset + chunkSize)
            yield(copyOfRange(offset, end))
            offset = end
        }
    }

    private suspend fun recognizeWithLegacyBatch(locale: String, audio: ByteArray): String {
        val maxBytes = LOCAL_MACOS_PCM_SAMPLE_RATE_HZ *
            LOCAL_MACOS_PCM_BYTES_PER_SAMPLE *
            LOCAL_MACOS_PCM_CHANNELS *
            MAX_LOCAL_MACOS_STT_AUDIO_SECONDS
        if (audio.size > maxBytes) {
            throw LocalMacOsSpeechAudioTooLongException()
        }
        val wavPath = MacOsSpeechWavWriter.writePcmToTempWav(audio)
        try {
            return recognizeWavCancellable(wavPath, locale)
        } catch (error: Throwable) {
            throw if (error is CancellationException) error else mapBridgeError(error, locale)
        } finally {
            Files.deleteIfExists(wavPath)
        }
    }

    private suspend fun recognizeWavCancellable(wavPath: Path, locale: String): String = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val recognitionJob = launch(Dispatchers.IO) {
                try {
                    val recognized = bridge.recognizeWav(wavPath.toString(), locale).trim()
                    if (continuation.isActive) {
                        runCatching { continuation.resume(recognized) }
                    }
                } catch (error: Throwable) {
                    val mappedError = if (error is CancellationException) error else mapBridgeError(error, locale)
                    if (continuation.isActive) {
                        runCatching { continuation.resumeWithException(mappedError) }
                    }
                }
            }

            continuation.invokeOnCancellation {
                runCatching { bridge.cancelRecognition() }
                recognitionJob.cancel()
            }
        }
    }

    private fun ensureSpeechUsageDescription(locale: String) {
        val hasUsageDescription = try {
            bridge.hasSpeechRecognitionUsageDescription()
        } catch (error: Throwable) {
            throw mapBridgeError(error, locale)
        }

        if (!hasUsageDescription) {
            throw LocalMacOsSpeechAppBundleMissingUsageDescriptionException()
        }
    }

    private fun ensureAuthorization(locale: String) {
        val status = try {
            bridge.authorizationStatus()
        } catch (error: Throwable) {
            throw mapBridgeError(error, locale)
        }
        if (status == MacOsSpeechAuthorizationStatus.NOT_DETERMINED) {
            try {
                bridge.requestAuthorizationIfNeeded()
            } catch (error: Throwable) {
                throw mapBridgeError(error, locale)
            }
        }

        val resolvedStatus = try {
            bridge.authorizationStatus()
        } catch (error: Throwable) {
            throw mapBridgeError(error, locale)
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
        if (error is CancellationException) return error

        val message = error.message.orEmpty()
        return when {
            message.startsWith(MacOsSpeechBridgeError.PERMISSION_DENIED.prefix) ->
                LocalMacOsSpeechPermissionDeniedException()

            message.startsWith(MacOsSpeechBridgeError.RESTRICTED.prefix) ->
                LocalMacOsSpeechUnavailableException("macOS speech recognition is restricted on this device.")

            message.startsWith(MacOsSpeechBridgeError.CANCELLED.prefix) ->
                CancellationException(
                    message
                        .removePrefix(MacOsSpeechBridgeError.CANCELLED.prefix)
                        .removePrefix(":")
                        .ifBlank { "Local macOS speech recognition was cancelled." }
                )

            message.startsWith(MacOsSpeechBridgeError.UNSUPPORTED_LOCALE.prefix) ->
                LocalMacOsSpeechLocaleUnsupportedException(locale)

            message.startsWith(MacOsSpeechBridgeError.ON_DEVICE_UNSUPPORTED.prefix) ->
                LocalMacOsSpeechOnDeviceUnsupportedException(
                    message
                        .removePrefix(MacOsSpeechBridgeError.ON_DEVICE_UNSUPPORTED.prefix)
                        .removePrefix(":")
                        .ifBlank { "Local macOS speech recognition requires on-device recognition support." }
                )

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

    private fun mapLiveRecognitionError(error: Throwable, locale: String): Throwable {
        if (error is CancellationException) return error
        return when (val mappedError = mapLiveBridgeError(error)) {
            is LocalMacOsLiveSpeechPermissionDeniedException -> LocalMacOsSpeechPermissionDeniedException()
            is LocalMacOsLiveSpeechUnsupportedException -> mappedError
            is LocalMacOsLiveSpeechModelUnavailableException -> mappedError
            is LocalMacOsLiveSpeechUnavailableException ->
                LocalMacOsSpeechUnavailableException(
                    mappedError.message ?: "Local macOS live speech transcription is unavailable."
                )

            else -> mapBridgeError(error, locale)
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

class LocalMacOsSpeechAudioTooLongException(
    message: String = "Local macOS speech recognition supports up to $MAX_LOCAL_MACOS_STT_AUDIO_SECONDS seconds per request."
) : LocalMacOsSpeechRecognitionException(message)

class LocalMacOsSpeechOnDeviceUnsupportedException(message: String) :
    LocalMacOsSpeechRecognitionException(message)

class LocalMacOsSpeechLocaleUnsupportedException(locale: String) :
    LocalMacOsSpeechRecognitionException("Local macOS speech recognition locale is unsupported: $locale")

private enum class MacOsSpeechBridgeError(val prefix: String) {
    PERMISSION_DENIED("LOCAL_MACOS_STT:PERMISSION_DENIED"),
    RESTRICTED("LOCAL_MACOS_STT:RESTRICTED"),
    CANCELLED("LOCAL_MACOS_STT:CANCELLED"),
    UNAVAILABLE("LOCAL_MACOS_STT:UNAVAILABLE"),
    UNSUPPORTED_LOCALE("LOCAL_MACOS_STT:UNSUPPORTED_LOCALE"),
    ON_DEVICE_UNSUPPORTED("LOCAL_MACOS_STT:ON_DEVICE_UNSUPPORTED"),
}
