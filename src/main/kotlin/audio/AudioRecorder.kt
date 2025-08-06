package com.dumch.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InMemoryAudioRecorder(
    private val recordingDurationSeconds: Int = 10,
    private val sampleRate: Float = 44_100f,
    private val channels: Int = 1,
    private val sampleSizeBits: Int = 16,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _audioFlow = kotlinx.coroutines.flow.MutableSharedFlow<ByteArray>()

    private var recordingJob: Job? = null
    private val _recordingState = MutableStateFlow<State>(State.Idle)
    val recordingState = _recordingState.asStateFlow()

    val audioFlow: Flow<ByteArray> = _audioFlow

    fun start() {
        if (recordingJob?.isActive == true) {
            throw IllegalStateException("Recording is already in progress")
        }

        _recordingState.value = State.Starting
        recordingJob = coroutineScope.launch {
            try {
                val audioData = InMemoryOpusRecorder.recordPcm(
                    seconds = recordingDurationSeconds,
                    sampleRate = sampleRate,
                    channels = channels,
                    sampleSizeBits = sampleSizeBits
                )
                _audioFlow.tryEmit(audioData)
            } catch (e: Exception) {
                _recordingState.value = State.Error(e.message ?: "Error during audio recording")
            }
        }
        _recordingState.value = State.Recording
    }

    fun stop() {
        coroutineScope.launch {
            try {
                _recordingState.value = State.Stopping
                // InMemoryOpusRecorder.stop() // TODO: Implement stop() method
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