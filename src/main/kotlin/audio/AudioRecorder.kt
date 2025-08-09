package com.dumch.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InMemoryAudioRecorder(
    private val sampleRate: Float = 44_100f,
    private val channels: Int = 1,
    private val sampleSizeBits: Int = 16,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _audioFlow = MutableSharedFlow<ByteArray>()

    private val _recordingState = MutableStateFlow<State>(State.Idle)
    val recordingState = _recordingState.asStateFlow()

    val audioFlow: Flow<ByteArray> = _audioFlow

    init {
        // Warm up the microphone so the first spoken words are captured
        InMemoryOpusRecorder.prepare(
            scope = coroutineScope,
            sampleRate = sampleRate,
            channels = channels,
            sampleSizeBits = sampleSizeBits
        )
    }

    fun start() {
        if (_recordingState.value == State.Recording || _recordingState.value == State.Starting) {
            throw IllegalStateException("Recording is already in progress")
        }

        _recordingState.value = State.Starting
        try {
            InMemoryOpusRecorder.startRecording()
            _recordingState.value = State.Recording
        } catch (e: Exception) {
            _recordingState.value = State.Error(e.message ?: "Error during audio recording")
        }
    }

    fun stop() {
        coroutineScope.launch {
            try {
                _recordingState.value = State.Stopping
                val wavBytes = InMemoryOpusRecorder.stopRecording()
                val oggBytes = InMemoryOpusRecorder.wavToOpusOgg(wavBytes)
                _audioFlow.emit(oggBytes)
                _recordingState.value = State.Idle
            } catch (e: Exception) {
                _recordingState.value = State.Error(e.message ?: "Failed to stop recording")
            }
        }
    }

    sealed class State {
        object Starting : State()
        object Recording : State()
        object Stopping : State()
        object Idle : State()
        data class Error(val message: String) : State()
    }
}