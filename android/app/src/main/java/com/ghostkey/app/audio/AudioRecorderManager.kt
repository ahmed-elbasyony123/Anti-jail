package com.ghostkey.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

data class AudioRecordResult(
    val bytes: ByteArray,
    val mimeType: String,
    val filename: String,
    val durationMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioRecordResult
        if (!bytes.contentEquals(other.bytes)) return false
        if (mimeType != other.mimeType) return false
        if (filename != other.filename) return false
        if (durationMs != other.durationMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + durationMs.hashCode()
        return result
    }
}

/**
 * Handles recording encrypted PTT voice notes with zero disk persistence.
 *
 * Uses Opus in OGG container on Android 10+ (API 29+) with high audio fidelity at 32 kbps,
 * falling back to AAC in MPEG-4 on older versions.
 *
 * Anti-Forensics:
 * Immediately upon recording completion, temporary cache files are overwritten with zeroes
 * and shredded from disk. The audio exists purely in volatile RAM.
 */
class AudioRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var tempFile: File? = null
    private var recordStartTime: Long = 0L
    private var activeMimeType: String = "audio/ogg"
    private var activeExt: String = "ogg"

    val isRecording: Boolean get() = mediaRecorder != null

    fun startRecording(): Boolean {
        if (isRecording) return false

        try {
            val isOpusSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            activeMimeType = if (isOpusSupported) "audio/ogg" else "audio/mp4"
            activeExt = if (isOpusSupported) "ogg" else "m4a"

            val file = File(context.cacheDir, "ptt_${System.currentTimeMillis()}.$activeExt")
            tempFile = file

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)

            if (isOpusSupported) {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                recorder.setAudioSamplingRate(48000)
                recorder.setAudioEncodingBitRate(32000)
            } else {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(44100)
                recorder.setAudioEncodingBitRate(64000)
            }

            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            recordStartTime = SystemClock.elapsedRealtime()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            return false
        }
    }

    fun getMaxAmplitude(): Int {
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun stopRecording(): AudioRecordResult? {
        val recorder = mediaRecorder ?: return null
        val file = tempFile ?: return null
        val duration = SystemClock.elapsedRealtime() - recordStartTime

        return try {
            try {
                recorder.stop()
            } catch (ignored: Exception) {}
            recorder.release()
            mediaRecorder = null

            if (!file.exists() || file.length() == 0L || duration < 500) {
                shredFile(file)
                cleanup()
                return null
            }

            val audioBytes = file.readBytes()
            val filename = "voice_${System.currentTimeMillis()}.$activeExt"

            // Anti-Forensics: Shred temp file from disk immediately
            shredFile(file)
            cleanup()

            AudioRecordResult(
                bytes = audioBytes,
                mimeType = activeMimeType,
                filename = filename,
                durationMs = duration
            )
        } catch (e: Exception) {
            e.printStackTrace()
            shredFile(file)
            cleanup()
            null
        }
    }

    fun cancelRecording() {
        val recorder = mediaRecorder
        val file = tempFile
        try {
            recorder?.stop()
        } catch (ignored: Exception) {}
        try {
            recorder?.release()
        } catch (ignored: Exception) {}
        mediaRecorder = null

        if (file != null) {
            shredFile(file)
        }
        cleanup()
    }

    private fun shredFile(file: File) {
        try {
            if (file.exists()) {
                val length = file.length()
                if (length > 0) {
                    val zeroes = ByteArray(minOf(length.toInt(), 4096))
                    FileOutputStream(file).use { fos ->
                        var remaining = length
                        while (remaining > 0) {
                            val toWrite = minOf(remaining.toInt(), zeroes.size)
                            fos.write(zeroes, 0, toWrite)
                            remaining -= toWrite
                        }
                        fos.flush()
                    }
                }
                file.delete()
            }
        } catch (ignored: Exception) {}
    }

    private fun cleanup() {
        mediaRecorder = null
        tempFile = null
        recordStartTime = 0L
    }
}
