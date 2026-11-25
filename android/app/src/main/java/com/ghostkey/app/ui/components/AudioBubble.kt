package com.ghostkey.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.audio.PlaybackState
import com.ghostkey.app.data.ChatMessage
import com.ghostkey.app.ui.*
import com.ghostkey.app.util.GhostKeyUtils

@Composable
fun AudioBubble(
    message: ChatMessage,
    playbackState: PlaybackState,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit
) {
    val isThisPlaying = playbackState.activeMessageId == message.id && playbackState.isPlaying
    val isThisActive = playbackState.activeMessageId == message.id

    val currentMs = if (isThisActive) playbackState.currentPositionMs else 0L
    val noteDurationMs = (message.content as? com.ghostkey.app.data.MessageContent.AudioNote)?.durationMs ?: 0L
    val totalMs = if (isThisActive && playbackState.durationMs > 0) playbackState.durationMs else noteDurationMs
    val progress = if (isThisActive) playbackState.progress else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AnthropicSurfaceVariant)
            .border(1.dp, AnthropicBorder, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(AnthropicTerracotta)
            ) {
                Icon(
                    imageVector = if (isThisPlaying) AppIcons.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isThisPlaying) "Pause voice note" else "Play voice note",
                    tint = AnthropicCream,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Slider(
                    value = progress,
                    onValueChange = onSeek,
                    colors = SliderDefaults.colors(
                        thumbColor = AnthropicTerracotta,
                        activeTrackColor = AnthropicTerracotta,
                        inactiveTrackColor = AnthropicBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = GhostKeyUtils.formatDurationMmSs(if (isThisActive) currentMs else 0L),
                        color = AnthropicTextMuted,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "Voice note · " + GhostKeyUtils.formatDurationMmSs(totalMs),
                        color = AnthropicTextMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
