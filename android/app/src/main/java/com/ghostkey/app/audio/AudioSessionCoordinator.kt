package com.ghostkey.app.audio

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AudioRecordingState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L
)

/**
 * Coordinates in-RAM voice-note recording, elapsed timer updates, and zero-disk audio playback.
 */
internal class AudioSessionCoordinator(
    appContext: Context,
    private val scope: CoroutineScope
) {
    private val recorder = AudioRecorderManager(appContext)
    private val player = AudioPlayerManager()
    private var timerJob: Job? = null

    private val _recordingState = MutableStateFlow(AudioRecordingState())
    val recordingState: StateFlow<AudioRecordingState> = _recordingState.asStateFlow()
    val playbackState: StateFlow<PlaybackState> = player.playbackState

    fun startRecording(): Boolean {
        if (_recordingState.value.isRecording) return true
        if (!recorder.startRecording()) return false
        _recordingState.value = AudioRecordingState(isRecording = true, elapsedMs = 0L)
        timerJob?.cancel()
        val startElapsed = SystemClock.elapsedRealtime()
        timerJob = scope.launch {
            while (_recordingState.value.isRecording) {
                delay(100)
                _recordingState.value = AudioRecordingState(
                    isRecording = true,
                    elapsedMs = SystemClock.elapsedRealtime() - startElapsed
                )
            }
        }
        return true
    }

    fun stopRecording(): AudioRecordResult? {
        resetRecordingState()
        return recorder.stopRecording()
    }

    fun cancelRecording() {
        resetRecordingState()
        recorder.cancelRecording()
    }

    private fun resetRecordingState() {
        timerJob?.cancel()
        timerJob = null
        _recordingState.value = AudioRecordingState()
    }

    fun togglePlayback(messageId: String, audioBytes: ByteArray) {
        player.play(messageId, audioBytes)
    }

    fun seekTo(messageId: String, fraction: Float) {
        player.seekTo(messageId, fraction)
    }

    fun close() {
        resetRecordingState()
        recorder.cancelRecording()
        player.stop()
    }

    fun wipe() {
        resetRecordingState()
        player.wipe()
        recorder.wipe()
    }
}
