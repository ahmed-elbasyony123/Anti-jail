package com.ghostkey.app.audio

import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val activeMessageId: String? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L
) {
    val progress: Float
        get() = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * In-memory MediaDataSource to stream audio bytes directly from RAM into MediaPlayer.
 * Zero bytes are written to disk, achieving maximum anti-forensics.
 */
class MemoryAudioDataSource(private var data: ByteArray) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val remaining = data.size - position.toInt()
        val toRead = minOf(size, remaining)
        System.arraycopy(data, position.toInt(), buffer, offset, toRead)
        return toRead
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {
        // No-op
    }

    fun zeroOut() {
        data.fill(0)
    }
}

/**
 * Manages audio playback for encrypted voice notes in RAM.
 */
class AudioPlayerManager {

    private var mediaPlayer: MediaPlayer? = null
    private var currentDataSource: MemoryAudioDataSource? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val progressUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null && player.isPlaying) {
                val current = player.currentPosition.toLong()
                val duration = player.duration.toLong()
                _playbackState.value = _playbackState.value.copy(
                    currentPositionMs = current,
                    durationMs = duration
                )
                handler.postDelayed(this, 100)
            }
        }
    }

    fun play(messageId: String, audioBytes: ByteArray) {
        if (audioBytes.isEmpty()) return

        // If toggling the currently playing message:
        if (_playbackState.value.activeMessageId == messageId) {
            val player = mediaPlayer
            if (player != null) {
                if (player.isPlaying) {
                    player.pause()
                    handler.removeCallbacks(progressUpdater)
                    _playbackState.value = _playbackState.value.copy(isPlaying = false)
                    return
                } else {
                    player.start()
                    handler.post(progressUpdater)
                    _playbackState.value = _playbackState.value.copy(isPlaying = true)
                    return
                }
            }
        }

        // Otherwise stop previous playback and start new
        stop()

        try {
            val dataSource = MemoryAudioDataSource(audioBytes)
            currentDataSource = dataSource

            val player = MediaPlayer()
            player.setDataSource(dataSource)
            player.prepare()

            val duration = player.duration.toLong()
            player.setOnCompletionListener {
                stop()
            }
            player.setOnErrorListener { _, _, _ ->
                stop()
                true
            }

            player.start()
            mediaPlayer = player
            handler.post(progressUpdater)

            _playbackState.value = PlaybackState(
                activeMessageId = messageId,
                isPlaying = true,
                currentPositionMs = 0L,
                durationMs = duration
            )
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayerManager", "Playback failed: ${e.message}", e)
            stop()
        }
    }

    fun seekTo(messageId: String, fraction: Float) {
        if (_playbackState.value.activeMessageId != messageId) return
        val player = mediaPlayer ?: return
        val targetMs = (fraction * player.duration).toInt().coerceIn(0, player.duration)
        player.seekTo(targetMs)
        _playbackState.value = _playbackState.value.copy(currentPositionMs = targetMs.toLong())
    }

    fun stop() {
        handler.removeCallbacks(progressUpdater)
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        try {
            currentDataSource?.close()
        } catch (_: Exception) {}
        mediaPlayer = null
        currentDataSource = null
        _playbackState.value = PlaybackState()
    }

    /**
     * Emergency Panic Wipe: Purges all active audio buffers and zeroizes memory.
     */
    fun wipe() {
        currentDataSource?.zeroOut()
        currentDataSource = null
        stop()
    }
}
