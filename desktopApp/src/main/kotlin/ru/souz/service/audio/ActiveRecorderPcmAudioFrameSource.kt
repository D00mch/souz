package ru.souz.service.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.souz.ambient.PcmAudioFrameSource
import kotlin.coroutines.cancellation.CancellationException

class ActiveRecorderPcmAudioFrameSource(
    private val recorder: ActiveSoundRecorder,
    private val chunkMillis: Int = 200,
) : PcmAudioFrameSource {
    override val sampleRateHz: Int
        get() = recorder.sampleRateHz
    override val channels: Int
        get() = recorder.channels
    override val bitsPerSample: Int
        get() = recorder.bitsPerSample

    override fun frames(): Flow<ByteArray> = flow {
        val chunkSize = ambientChunkSizeBytes()
        var chunk = ByteArray(chunkSize)
        var filled = 0

        recorder.frames().collect { frame ->
            var offset = 0
            while (offset < frame.size) {
                val copySize = minOf(chunkSize - filled, frame.size - offset)
                frame.copyInto(
                    destination = chunk,
                    destinationOffset = filled,
                    startIndex = offset,
                    endIndex = offset + copySize,
                )
                filled += copySize
                offset += copySize

                if (filled == chunkSize) {
                    emit(chunk)
                    chunk = ByteArray(chunkSize)
                    filled = 0
                }
            }
        }

        if (filled > 0) {
            emit(chunk.copyOf(filled))
        }
    }

    override suspend fun start(): Boolean = try {
        recorder.startRecording()
        true
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        false
    }

    override suspend fun stop() {
        recorder.stopRecording()
    }

    private fun ambientChunkSizeBytes(): Int {
        val bytesPerSample = bitsPerSample / 8
        val bytesPerSecond = sampleRateHz * channels * bytesPerSample
        val rawChunkSize = bytesPerSecond * chunkMillis / 1_000
        val blockAlign = channels * bytesPerSample
        return rawChunkSize - (rawChunkSize % blockAlign)
    }
}
