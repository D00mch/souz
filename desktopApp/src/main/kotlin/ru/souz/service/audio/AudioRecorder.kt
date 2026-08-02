package ru.souz.service.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import ru.souz.ui.host.UiAudioRecorder
import ru.souz.ui.host.UiAudioRecordingState
import kotlin.coroutines.cancellation.CancellationException

class InMemoryAudioRecorder(
    private val recorder: ActiveSoundRecorder = ActiveSoundRecorderImpl(),
    warmupOnInit: Boolean = false,
) : UiAudioRecorder {
    private val l = LoggerFactory.getLogger(InMemoryAudioRecorder::class.java)
    private val _recordingState = MutableStateFlow<UiAudioRecordingState>(UiAudioRecordingState.Idle)
    override val recordingState = _recordingState.asStateFlow()

    init {
        if (warmupOnInit) {
            recorder.prepare()
        }
    }

    override suspend fun logState(): Nothing {
        recordingState.collect { state ->
            when (state) {
                is UiAudioRecordingState.Starting -> l.info("Recording state: Starting audio recording...")
                is UiAudioRecordingState.Recording -> l.info("Recording state: Recording... (press Option + 2 to stop)")
                is UiAudioRecordingState.Stopping -> l.info("Recording state: Stopping recording...")
                is UiAudioRecordingState.Idle -> {
                    l.info("Recording state: Idle")
                }

                is UiAudioRecordingState.Error -> l.error("Recording state: Error: ${state.message}")
            }
        }
    }

    override fun start(): Boolean {
        if (_recordingState.value == UiAudioRecordingState.Recording ||
            _recordingState.value == UiAudioRecordingState.Starting
        ) {
            throw IllegalStateException("Recording is already in progress")
        }

        _recordingState.value = UiAudioRecordingState.Starting
        try {
            recorder.startRecording()
            _recordingState.value = UiAudioRecordingState.Recording
            return true
        } catch (e: Exception) {
            _recordingState.value = UiAudioRecordingState.Error(e.message ?: "Error during audio recording")
            return false
        }
    }

    override suspend fun stop(): ByteArray? {
        _recordingState.value = UiAudioRecordingState.Stopping
        return try {
            val bytes = recorder.stopRecording()
            _recordingState.value = UiAudioRecordingState.Idle
            bytes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _recordingState.value = UiAudioRecordingState.Error(e.message ?: "Failed to stop recording")
            null
        }
    }
}
