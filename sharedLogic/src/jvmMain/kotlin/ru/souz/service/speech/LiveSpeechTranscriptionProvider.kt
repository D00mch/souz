package ru.souz.service.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

data class LiveSpeechTranscriptEvent(
    val text: String,
    val isFinal: Boolean,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
)

interface LiveSpeechTranscriptionSession {
    suspend fun acceptPcm(audio: ByteArray)
    suspend fun pollEvents(): List<LiveSpeechTranscriptEvent>
    suspend fun finalizeAndFinish(): List<LiveSpeechTranscriptEvent>
    suspend fun cancel()
}

interface LiveSpeechTranscriptionProvider {
    val enabled: Boolean
    val hasRequiredKey: Boolean
    suspend fun isSupported(locale: String): Boolean
    suspend fun start(locale: String): LiveSpeechTranscriptionSession
}

class MacOsSpeechAnalyzerLiveTranscriptionProvider(
    private val bridge: MacOsSpeechBridgeApi,
    private val isMacOsProvider: () -> Boolean = LocalMacOsSpeechHost::isCurrentHost,
) : LiveSpeechTranscriptionProvider {
    override val enabled: Boolean
        get() = isMacOsProvider()

    override val hasRequiredKey: Boolean
        get() = enabled

    override suspend fun isSupported(locale: String): Boolean {
        if (!enabled) return false
        return try {
            withContext(Dispatchers.IO) {
                bridge.liveIsSupported(locale)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            when (val mappedError = mapLiveBridgeError(error)) {
                is LocalMacOsLiveSpeechPermissionDeniedException -> throw mappedError
                else -> false
            }
        }
    }

    override suspend fun start(locale: String): LiveSpeechTranscriptionSession {
        if (!enabled) {
            throw LocalMacOsLiveSpeechUnsupportedException("Local macOS live speech transcription is unavailable on this host.")
        }
        ensureSpeechUsageDescription()
        ensureAuthorization()
        prepareAssets(locale)
        val sessionId = try {
            withContext(Dispatchers.IO) {
                bridge.liveStart(locale)
            }
        } catch (error: Throwable) {
            throw mapLiveBridgeError(error)
        }
        return BridgeLiveSpeechTranscriptionSession(bridge, sessionId)
    }

    private fun ensureSpeechUsageDescription() {
        val hasUsageDescription = try {
            bridge.hasSpeechRecognitionUsageDescription()
        } catch (error: Throwable) {
            throw mapLiveBridgeError(error)
        }

        if (!hasUsageDescription) {
            throw LocalMacOsLiveSpeechUnavailableException(
                "Local macOS speech recognition requires a macOS app bundle with NSSpeechRecognitionUsageDescription."
            )
        }
    }

    private fun ensureAuthorization() {
        when (authorizationStatus()) {
            MacOsSpeechAuthorizationStatus.AUTHORIZED -> return
            MacOsSpeechAuthorizationStatus.NOT_DETERMINED -> {
                try {
                    bridge.requestAuthorizationIfNeeded()
                } catch (error: Throwable) {
                    throw mapLiveBridgeError(error)
                }
            }

            MacOsSpeechAuthorizationStatus.DENIED ->
                throw LocalMacOsLiveSpeechPermissionDeniedException()

            MacOsSpeechAuthorizationStatus.RESTRICTED ->
                throw LocalMacOsLiveSpeechUnavailableException("Speech recognition is restricted on this device.")

            MacOsSpeechAuthorizationStatus.UNSUPPORTED ->
                throw LocalMacOsLiveSpeechUnavailableException("Speech recognition is unavailable on this device.")
        }

        when (authorizationStatus()) {
            MacOsSpeechAuthorizationStatus.AUTHORIZED -> Unit
            MacOsSpeechAuthorizationStatus.DENIED ->
                throw LocalMacOsLiveSpeechPermissionDeniedException()

            MacOsSpeechAuthorizationStatus.RESTRICTED ->
                throw LocalMacOsLiveSpeechUnavailableException("Speech recognition is restricted on this device.")

            MacOsSpeechAuthorizationStatus.UNSUPPORTED ->
                throw LocalMacOsLiveSpeechUnavailableException("Speech recognition is unavailable on this device.")

            MacOsSpeechAuthorizationStatus.NOT_DETERMINED ->
                throw LocalMacOsLiveSpeechUnavailableException("Speech recognition authorization did not complete.")
        }
    }

    private fun authorizationStatus(): MacOsSpeechAuthorizationStatus = try {
        bridge.authorizationStatus()
    } catch (error: Throwable) {
        throw mapLiveBridgeError(error)
    }

    private suspend fun prepareAssets(locale: String) {
        try {
            withContext(Dispatchers.IO) {
                bridge.livePrepareAssets(locale)
            }
        } catch (error: Throwable) {
            throw mapLiveBridgeError(error)
        }
    }

    private class BridgeLiveSpeechTranscriptionSession(
        private val bridge: MacOsSpeechBridgeApi,
        private val sessionId: Long,
    ) : LiveSpeechTranscriptionSession {
        private var closed = false

        override suspend fun acceptPcm(audio: ByteArray) {
            ensureOpen()
            try {
                withContext(Dispatchers.IO) {
                    bridge.liveAcceptPcm(
                        sessionId = sessionId,
                        audio = audio,
                        sampleRateHz = 16_000,
                        channels = 1,
                        bitsPerSample = 16,
                    )
                }
            } catch (error: Throwable) {
                throw mapLiveBridgeError(error)
            }
        }

        override suspend fun pollEvents(): List<LiveSpeechTranscriptEvent> {
            ensureOpen()
            val raw = try {
                withContext(Dispatchers.IO) {
                    bridge.livePollEvents(sessionId)
                }
            } catch (error: Throwable) {
                throw mapLiveBridgeError(error)
            }
            return parseLiveSpeechTranscriptEvents(raw)
        }

        override suspend fun finalizeAndFinish(): List<LiveSpeechTranscriptEvent> {
            ensureOpen()
            val raw = try {
                withContext(Dispatchers.IO) {
                    bridge.liveFinalizeAndFinish(sessionId)
                }
            } catch (error: Throwable) {
                throw mapLiveBridgeError(error)
            }
            closed = true
            return parseLiveSpeechTranscriptEvents(raw)
        }

        override suspend fun cancel() {
            if (closed) return
            closed = true
            try {
                withContext(Dispatchers.IO) {
                    bridge.liveCancel(sessionId)
                }
            } catch (error: Throwable) {
                throw mapLiveBridgeError(error)
            }
        }

        private fun ensureOpen() {
            if (closed) {
                throw LocalMacOsLiveSpeechUnavailableException(
                    "Local macOS live speech transcription session is closed."
                )
            }
        }
    }
}

internal fun parseLiveSpeechTranscriptEvents(raw: String): List<LiveSpeechTranscriptEvent> {
    if (raw.isBlank()) return emptyList()

    return raw
        .lineSequence()
        .filter { it.isNotBlank() }
        .map { line -> parseLiveSpeechTranscriptEvent(line) }
        .toList()
}

private fun parseLiveSpeechTranscriptEvent(line: String): LiveSpeechTranscriptEvent {
    val fields = line.split('\t', limit = 4)
    if (fields.size != 4) {
        throw LocalMacOsLiveSpeechUnavailableException("Malformed local macOS live speech transcript event.")
    }

    val isFinal = when (fields[0]) {
        "1" -> true
        "0" -> false
        else -> throw LocalMacOsLiveSpeechUnavailableException(
            "Malformed local macOS live speech transcript event finality."
        )
    }
    val startedAtMs = fields[1].toNullableLong("start timestamp")
    val endedAtMs = fields[2].toNullableLong("end timestamp")
    val text = try {
        String(Base64.getDecoder().decode(fields[3]), Charsets.UTF_8)
    } catch (error: IllegalArgumentException) {
        throw LocalMacOsLiveSpeechUnavailableException(
            "Malformed local macOS live speech transcript event text."
        )
    }

    return LiveSpeechTranscriptEvent(
        text = text,
        isFinal = isFinal,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
    )
}

private fun String.toNullableLong(fieldName: String): Long? {
    if (isBlank()) return null
    return toLongOrNull()
        ?: throw LocalMacOsLiveSpeechUnavailableException(
            "Malformed local macOS live speech transcript event $fieldName."
        )
}

internal fun mapLiveBridgeError(error: Throwable): Throwable {
    if (error is CancellationException) return error
    if (error is LocalMacOsLiveSpeechException) return error
    if (error is UnsatisfiedLinkError) {
        return LocalMacOsLiveSpeechUnsupportedException("Local macOS live speech transcription symbols are missing.")
    }
    if (error is UnsupportedOperationException) {
        return LocalMacOsLiveSpeechUnsupportedException(
            error.message ?: "Local macOS live speech transcription is not supported."
        )
    }

    val message = error.message.orEmpty()
    return when {
        message.startsWith(MacOsLiveSpeechBridgeError.UNSUPPORTED_OS.prefix) ||
            message.startsWith(MacOsLiveSpeechBridgeError.UNSUPPORTED_LOCALE.prefix) ||
            message.startsWith(MacOsLiveSpeechBridgeError.UNSUPPORTED_CONFIGURATION.prefix) ->
            LocalMacOsLiveSpeechUnsupportedException(message.liveBridgeMessage())

        message.startsWith(MacOsLiveSpeechBridgeError.MODEL_UNAVAILABLE.prefix) ->
            LocalMacOsLiveSpeechModelUnavailableException(message.liveBridgeMessage())

        message.startsWith(MacOsLiveSpeechBridgeError.PERMISSION_DENIED.prefix) ->
            LocalMacOsLiveSpeechPermissionDeniedException()

        message.startsWith(MacOsLiveSpeechBridgeError.SESSION_NOT_FOUND.prefix) ||
            message.startsWith(MacOsLiveSpeechBridgeError.UNAVAILABLE.prefix) ->
            LocalMacOsLiveSpeechUnavailableException(message.liveBridgeMessage())

        else -> LocalMacOsLiveSpeechUnavailableException(
            message.ifBlank { "Local macOS live speech transcription is unavailable." }
        )
    }
}

private fun String.liveBridgeMessage(): String {
    val parts = split(':', limit = 3)
    val code = parts.getOrNull(1)?.ifBlank { null }
    val payload = parts.getOrNull(2)?.ifBlank { null }

    return when {
        code != null && payload != null -> "$code: $payload"
        payload != null -> payload
        code != null -> code
        else -> "Local macOS live speech transcription is unavailable."
    }
}

sealed class LocalMacOsLiveSpeechException(message: String) : IllegalStateException(message)

class LocalMacOsLiveSpeechUnsupportedException(message: String) :
    LocalMacOsLiveSpeechException(message)

class LocalMacOsLiveSpeechModelUnavailableException(message: String) :
    LocalMacOsLiveSpeechException(message)

class LocalMacOsLiveSpeechPermissionDeniedException(
    message: String = "Speech recognition permission denied.",
) : LocalMacOsLiveSpeechException(message)

class LocalMacOsLiveSpeechUnavailableException(message: String) :
    LocalMacOsLiveSpeechException(message)

private enum class MacOsLiveSpeechBridgeError(val prefix: String) {
    UNSUPPORTED_OS("LOCAL_MACOS_LIVE_STT:UNSUPPORTED_OS"),
    UNSUPPORTED_LOCALE("LOCAL_MACOS_LIVE_STT:UNSUPPORTED_LOCALE"),
    UNSUPPORTED_CONFIGURATION("LOCAL_MACOS_LIVE_STT:UNSUPPORTED_CONFIGURATION"),
    MODEL_UNAVAILABLE("LOCAL_MACOS_LIVE_STT:MODEL_UNAVAILABLE"),
    PERMISSION_DENIED("LOCAL_MACOS_LIVE_STT:PERMISSION_DENIED"),
    UNAVAILABLE("LOCAL_MACOS_LIVE_STT:UNAVAILABLE"),
    SESSION_NOT_FOUND("LOCAL_MACOS_LIVE_STT:SESSION_NOT_FOUND"),
}
