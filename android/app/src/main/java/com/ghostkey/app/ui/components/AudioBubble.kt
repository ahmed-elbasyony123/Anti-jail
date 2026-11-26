package com.ghostkey.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.audio.PlaybackState
import com.ghostkey.app.data.ChatMessage
import com.ghostkey.app.data.MessageContent
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.theme.GhostTheme
import com.ghostkey.app.util.GhostKeyUtils
import kotlin.math.abs

/**
 * Quiet waveform voice note bubble inspired by minimal Messenger UI (Play button + vertical waveform + timer).
 */
@Composable
fun AudioBubble(
    message: ChatMessage,
    playbackState: PlaybackState,
    isOutgoing: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    val isThisActive = playbackState.activeMessageId == message.id
    val isThisPlaying = isThisActive && playbackState.isPlaying

    val noteContent = message.content as? MessageContent.AudioNote
    val noteDurationMs = noteContent?.durationMs ?: 0L
    val currentMs = if (isThisActive) playbackState.currentPositionMs else 0L
    val totalMs = if (isThisActive && playbackState.durationMs > 0) playbackState.durationMs else noteDurationMs
    val progress = if (isThisActive) playbackState.progress.coerceIn(0f, 1f) else 0f

    val fgColor = if (isOutgoing) palette.bubbleOutgoingText else palette.bubbleIncomingText
    val subColor = if (isOutgoing) palette.bubbleOutgoingSubtext else palette.bubbleIncomingSubtext
    val inactiveBarColor = fgColor.copy(alpha = 0.28f)

    // Deterministic waveform heights derived from audio bytes / message ID so recomposition never flickers
    val bars = remember(message.id, noteContent?.audioBytes?.size) {
        val bytes = noteContent?.audioBytes
        List(28) { idx ->
            val raw = if (bytes != null && bytes.isNotEmpty()) {
                val sampleIdx = (idx * bytes.size / 28).coerceIn(0, bytes.lastIndex)
                abs(bytes[sampleIdx].toInt()) % 100
            } else {
                abs((message.id.hashCode() * (idx + 7)) % 100)
            }
            0.25f + (raw / 100f) * 0.75f
        }
    }

    Row(
        modifier = Modifier
            .widthIn(min = 210.dp, max = 260.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(fgColor)
        ) {
            Icon(
                imageVector = if (isThisPlaying) AppIcons.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isThisPlaying) "Pause" else "Play",
                tint = if (isOutgoing) palette.bubbleOutgoing else palette.bubbleIncoming,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerInput(message.id) {
                        detectTapGestures { offset ->
                            if (size.width > 0) {
                                onSeek((offset.x / size.width).coerceIn(0f, 1f))
                            }
                        }
                    }
            ) {
                val count = bars.size
                val slotWidth = size.width / count
                val barWidth = (slotWidth * 0.56f).coerceAtLeast(2.dp.toPx())
                val activeCount = (progress * count).toInt()
                bars.forEachIndexed { idx, amp ->
                    val barHeight = (size.height * amp).coerceAtLeast(4.dp.toPx())
                    val x = idx * slotWidth + (slotWidth - barWidth) / 2f
                    val y = (size.height - barHeight) / 2f
                    drawRoundRect(
                        color = if (idx <= activeCount && isThisActive) fgColor else inactiveBarColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = GhostKeyUtils.formatDurationMmSs(if (isThisActive) currentMs else 0L),
                    color = subColor,
                    fontSize = 10.sp
                )
                Text(
                    text = "${strings.voiceNoteLabel} · ${GhostKeyUtils.formatDurationMmSs(totalMs)}",
                    color = subColor,
                    fontSize = 10.sp
                )
            }
        }
    }
}
