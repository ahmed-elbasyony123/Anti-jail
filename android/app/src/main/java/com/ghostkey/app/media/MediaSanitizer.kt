package com.ghostkey.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import com.ghostkey.app.audio.MemoryAudioDataSource
import com.ghostkey.app.util.GhostKeyUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.max
import kotlin.math.min

data class SanitizedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int
)

data class SanitizedAvatar(
    val bytes: ByteArray,
    val base64: String,
    val sha256Hex: String
)

/**
 * Strips EXIF/GPS/camera metadata from images and compresses media to save Tor/Mesh bandwidth and RAM.
 */
object MediaSanitizer {

    private const val DEFAULT_MAX_EDGE_PX = 1280
    private const val DEFAULT_IMAGE_QUALITY = 80
    private const val AVATAR_SIZE_PX = 192
    private const val MAX_DECODE_INPUT_BYTES = 20 * 1024 * 1024
    const val MAX_AVATAR_BYTES = 25 * 1024

    fun sanitizeImage(
        rawBytes: ByteArray,
        maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
        quality: Int = DEFAULT_IMAGE_QUALITY
    ): SanitizedImage? {
        if (rawBytes.isEmpty() || rawBytes.size > MAX_DECODE_INPUT_BYTES) return null
        val orientation = readExifOrientation(rawBytes)
        val decoded = decodeSampledAndOriented(rawBytes, maxEdgePx, orientation) ?: return null
        return try {
            val scaled = scaleDownIfNeeded(decoded, maxEdgePx)
            try {
                val compressed = compressWebp(scaled, quality)
                SanitizedImage(
                    bytes = compressed,
                    mimeType = "image/webp",
                    width = scaled.width,
                    height = scaled.height
                )
            } finally {
                if (scaled !== decoded) scaled.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    fun sanitizeAvatar(
        rawBytes: ByteArray,
        targetSizePx: Int = AVATAR_SIZE_PX,
        maxBytes: Int = MAX_AVATAR_BYTES
    ): SanitizedAvatar? {
        if (rawBytes.isEmpty()) return null
        val orientation = readExifOrientation(rawBytes)
        val decoded = decodeSampledAndOriented(rawBytes, targetSizePx * 2, orientation) ?: return null
        return try {
            val cropEdge = min(decoded.width, decoded.height)
            val cropX = (decoded.width - cropEdge) / 2
            val cropY = (decoded.height - cropEdge) / 2
            val square = Bitmap.createBitmap(decoded, cropX, cropY, cropEdge, cropEdge)
            var edge = min(targetSizePx, cropEdge)
            var currentBitmap = if (square.width != edge || square.height != edge) {
                Bitmap.createScaledBitmap(square, edge, edge, true).also { square.recycle() }
            } else {
                square
            }
            try {
                var encoded = compressFittingBudget(currentBitmap, maxBytes)
                if (encoded.size > maxBytes && edge > 128) {
                    val smaller = Bitmap.createScaledBitmap(currentBitmap, 128, 128, true)
                    currentBitmap.recycle()
                    currentBitmap = smaller
                    encoded = compressFittingBudget(currentBitmap, maxBytes)
                }
                val b64 = Base64.getEncoder().encodeToString(encoded)
                SanitizedAvatar(
                    bytes = encoded,
                    base64 = b64,
                    sha256Hex = GhostKeyUtils.sha256Hex(b64)
                )
            } finally {
                currentBitmap.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    fun extractAudioDurationMs(bytes: ByteArray): Long {
        if (bytes.isEmpty()) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(MemoryAudioDataSource(bytes))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun readExifOrientation(bytes: ByteArray): Int = runCatching {
        ByteArrayInputStream(bytes).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun decodeSampledAndOriented(
        rawBytes: ByteArray,
        maxEdgePx: Int,
        orientation: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = GhostKeyUtils.calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts) ?: return null
        val matrix = orientationMatrix(orientation) ?: return decoded
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } finally {
            decoded.recycle()
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix? {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return null
        }
        return matrix
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxEdgePx: Int): Bitmap {
        val maxDim = max(bitmap.width, bitmap.height)
        if (maxDim <= maxEdgePx || maxDim <= 0) return bitmap
        val ratio = maxEdgePx.toFloat() / maxDim.toFloat()
        val w = max(1, (bitmap.width * ratio).toInt())
        val h = max(1, (bitmap.height * ratio).toInt())
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun compressFittingBudget(bitmap: Bitmap, maxBytes: Int): ByteArray {
        val qualities = intArrayOf(75, 60, 45, 32)
        var best = ByteArray(0)
        for (q in qualities) {
            best = compressWebp(bitmap, q)
            if (best.size <= maxBytes) return best
        }
        return best
    }

    @Suppress("DEPRECATION")
    private fun compressWebp(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(32 * 1024)
        val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(fmt, quality.coerceIn(10, 100), out)
        return out.toByteArray()
    }
}
