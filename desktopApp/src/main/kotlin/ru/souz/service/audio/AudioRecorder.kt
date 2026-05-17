package ru.souz.service.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.ui.host.UiAudioRecorder
import ru.souz.ui.host.UiAudioRecordingState

class InMemoryAudioRecorder(
    private val recorder: ActiveSoundRecorder = ActiveSoundRecorderImpl(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    warmupOnInit: Boolean = false,
) : UiAudioRecorder {
    private val l = LoggerFactory.getLogger(InMemoryAudioRecorder::class.java)
    private val _audioFlow = MutableSharedFlow<ByteArray>()

    private val _recordingState = MutableStateFlow<UiAudioRecordingState>(UiAudioRecordingState.Idle)
    override val recordingState = _recordingState.asStateFlow()

    override val audioFlow: Flow<ByteArray> = _audioFlow

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

    override fun stop() {
        coroutineScope.launch {
            try {
                _recordingState.value = UiAudioRecordingState.Stopping
                val bytes = recorder.stopRecording()
                _audioFlow.emit(bytes)
                _recordingState.value = UiAudioRecordingState.Idle
            } catch (e: Exception) {
                _recordingState.value = UiAudioRecordingState.Error(e.message ?: "Failed to stop recording")
            }
        }
    }
}
