package ru.souz.service.speech

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

enum class MacOsSpeechAuthorizationStatus(val code: Int) {
    NOT_DETERMINED(0),
    DENIED(1),
    RESTRICTED(2),
    AUTHORIZED(3),
    UNSUPPORTED(4),
    ;

    companion object {
        fun fromCode(code: Int): MacOsSpeechAuthorizationStatus =
            entries.firstOrNull { it.code == code } ?: UNSUPPORTED
    }
}

interface MacOsSpeechBridgeApi {
    fun hasSpeechRecognitionUsageDescription(): Boolean

    fun authorizationStatus(): MacOsSpeechAuthorizationStatus

    fun requestAuthorizationIfNeeded()

    fun recognizeWav(path: String, locale: String): String

    fun cancelRecognition()

    fun liveIsSupported(locale: String): Boolean = false

    fun livePrepareAssets(locale: String) = Unit

    fun liveStart(locale: String): Long =
        throw UnsupportedOperationException("Local macOS live speech transcription is not supported.")

    fun liveAcceptPcm(
        sessionId: Long,
        audio: ByteArray,
        sampleRateHz: Int,
        channels: Int,
        bitsPerSample: Int,
    ) {
        throw UnsupportedOperationException("Local macOS live speech transcription is not supported.")
    }

    fun livePollEvents(sessionId: Long): String =
        throw UnsupportedOperationException("Local macOS live speech transcription is not supported.")

    fun liveFinalizeAndFinish(sessionId: Long): String =
        throw UnsupportedOperationException("Local macOS live speech transcription is not supported.")

    fun liveCancel(sessionId: Long) {
        throw UnsupportedOperationException("Local macOS live speech transcription is not supported.")
    }
}

object LocalMacOsSpeechHost {
    fun isCurrentHost(): Boolean = currentResourceDirectory(
        osName = System.getProperty("os.name", ""),
        osArch = System.getProperty("os.arch", ""),
    ) != null

    fun currentResourceDirectory(osName: String, osArch: String): String? = when {
        osName.contains("Mac", ignoreCase = true) &&
            (osArch.contains("aarch64", ignoreCase = true) || osArch.contains("arm64", ignoreCase = true)) ->
            "darwin-arm64"

        osName.contains("Mac", ignoreCase = true) &&
            (osArch.contains("x86_64", ignoreCase = true) || osArch.contains("amd64", ignoreCase = true)) ->
            "darwin-x64"

        else -> null
    }
}

internal object MacOsSpeechWavWriter {
    fun writePcmToTempWav(
        rawPcm: ByteArray,
        sampleRateHz: Int = 16_000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): Path {
        val wavPath = Files.createTempFile("souz_local_macos_stt_", ".wav")
        Files.write(
            wavPath,
            pcm16MonoToWav(
                rawPcm = rawPcm,
                sampleRateHz = sampleRateHz,
                channels = channels,
                bitsPerSample = bitsPerSample,
            )
        )
        return wavPath
    }

    private fun pcm16MonoToWav(
        rawPcm: ByteArray,
        sampleRateHz: Int,
        channels: Int,
        bitsPerSample: Int,
    ): ByteArray {
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        return ByteArrayOutputStream(WAV_HEADER_SIZE + rawPcm.size).apply {
            writeAscii("RIFF")
            writeLeInt(36 + rawPcm.size)
            writeAscii("WAVE")
            writeAscii("fmt ")
            writeLeInt(16)
            writeLeShort(1)
            writeLeShort(channels)
            writeLeInt(sampleRateHz)
            writeLeInt(byteRate)
            writeLeShort(blockAlign)
            writeLeShort(bitsPerSample)
            writeAscii("data")
            writeLeInt(rawPcm.size)
            write(rawPcm)
        }.toByteArray()
    }
}

private const val WAV_HEADER_SIZE = 44

private fun ByteArrayOutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun ByteArrayOutputStream.writeLeShort(value: Int) {
    write(
        ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value.toShort())
            .array()
    )
}

private fun ByteArrayOutputStream.writeLeInt(value: Int) {
    write(
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()
    )
}
