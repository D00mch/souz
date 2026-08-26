package ru.souz.android.voice

import android.content.Context
import android.provider.Settings

/**
 * VAD thresholds, overridable at runtime with `adb shell settings put global <key> <value>`
 * so they can be tuned against the real remote without rebuilding.
 */
data class VadParams(
    val silenceRms: Int,
    val leadInMs: Long,
    val trailingSilenceMs: Long,
    val maxUtteranceMs: Long,
    val minSpeechMs: Long,
    val recognizeWithoutSpeech: Boolean,
    val dumpAudio: Boolean,
) {
    companion object {
        const val KEY_SILENCE_RMS = "souz_vad_silence_rms"
        const val KEY_LEAD_IN_MS = "souz_vad_lead_in_ms"
        const val KEY_TRAILING_SILENCE_MS = "souz_vad_trailing_silence_ms"
        const val KEY_MAX_MS = "souz_vad_max_ms"
        const val KEY_MIN_SPEECH_MS = "souz_vad_min_speech_ms"
        const val KEY_RECOGNIZE_WITHOUT_SPEECH = "souz_vad_recognize_without_speech"
        const val KEY_DUMP_AUDIO = "souz_vad_dump_audio"

        // Measured on the BLE remote: noise peaks at ~1980 RMS per 20 ms chunk, speech at ~6600.
        val DEFAULT = VadParams(
            silenceRms = 2_000,
            leadInMs = 5_000,
            trailingSilenceMs = 1_000,
            maxUtteranceMs = 20_000,
            minSpeechMs = 400,
            recognizeWithoutSpeech = false,
            dumpAudio = false,
        )

        fun read(context: Context): VadParams {
            val resolver = context.contentResolver
            fun int(key: String, fallback: Int) = Settings.Global.getInt(resolver, key, fallback)
            return VadParams(
                silenceRms = int(KEY_SILENCE_RMS, DEFAULT.silenceRms),
                leadInMs = int(KEY_LEAD_IN_MS, DEFAULT.leadInMs.toInt()).toLong(),
                trailingSilenceMs = int(KEY_TRAILING_SILENCE_MS, DEFAULT.trailingSilenceMs.toInt()).toLong(),
                maxUtteranceMs = int(KEY_MAX_MS, DEFAULT.maxUtteranceMs.toInt()).toLong(),
                minSpeechMs = int(KEY_MIN_SPEECH_MS, DEFAULT.minSpeechMs.toInt()).toLong(),
                recognizeWithoutSpeech = int(KEY_RECOGNIZE_WITHOUT_SPEECH, 0) == 1,
                dumpAudio = int(KEY_DUMP_AUDIO, 0) == 1,
            )
        }
    }
}

enum class CaptureOutcome {
    SPEECH,
    NO_SPEECH,
    TOO_SHORT,
    ERROR,
}

data class CaptureStats(
    val outcome: CaptureOutcome,
    val speechMs: Long = 0,
    val elapsedMs: Long = 0,
    val peakRms: Int = 0,
    val error: String? = null,
)

data class CapturedAudio(
    val pcm: ByteArray,
    val stats: CaptureStats,
)
