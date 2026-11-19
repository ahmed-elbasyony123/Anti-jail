package com.ghostkey.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.audio.PlaybackState
import com.ghostkey.app.crypto.SignatureStatus
import com.ghostkey.app.data.ChatMessage
import com.ghostkey.app.ui.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MessageBubble(
    message: ChatMessage,
    playbackState: PlaybackState = PlaybackState(),
    onToggleArmor: () -> Unit,
    onManualDecrypt: () -> Unit,
    onAudioPlayPause: (ChatMessage) -> Unit = {},
    onAudioSeek: (ChatMessage, Float) -> Unit = { _, _ -> }
) {
    val align = if (message.isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (message.isOutgoing) StealthSurfaceVariant else StealthSurface
    val borderColor = if (message.isOutgoing) NeonEmerald.copy(alpha = 0.4f) else Color.DarkGray
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bubbleColor)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (message.isOutgoing) "OUTGOING (E2EE)" else "INCOMING",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = if (message.isOutgoing) NeonEmerald else WarningAmber
                    )
                    Text(
                        text = timeFormat.format(Date(message.timestamp)),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = StealthTextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (!message.isOutgoing && message.isDecrypted) {
                    when (message.signatureStatus) {
                        SignatureStatus.VERIFIED -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = NeonEmerald,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "VERIFIED SIGNATURE" + (message.signerFingerprint?.let { " · ${it.take(8)}" } ?: ""),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    color = NeonEmerald
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        SignatureStatus.UNSIGNED -> {
                            SignatureWarning("UNSIGNED MESSAGE")
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        SignatureStatus.INVALID_SIGNATURE -> {
                            SignatureWarning("INVALID SIGNATURE — UNVERIFIED SENDER")
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        null -> {}
                    }
                }

                if (message.isDecrypted) {
                    if (message.isAudio && message.audioBytes != null) {
                        AudioBubble(
                            message = message,
                            playbackState = playbackState,
                            onPlayPause = { onAudioPlayPause(message) },
                            onSeek = { fraction -> onAudioSeek(message, fraction) }
                        )
                    } else if (message.isMedia) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (message.mediaBitmap != null) {
                                Image(
                                    bitmap = message.mediaBitmap,
                                    contentDescription = message.filename,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(StealthBackground)
                                        .padding(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                ) {
                                    Icon(Icons.Default.AttachFile, contentDescription = null, tint = NeonEmerald)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column {
                                        Text(message.filename, color = StealthTextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        Text(message.mimeType, color = StealthTextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                            Text("📎 ${message.filename}", fontSize = 10.sp, color = NeonEmerald, fontFamily = FontFamily.Monospace)
                        }
                    } else {
                        Text(
                            text = message.plaintext ?: "",
                            color = StealthTextPrimary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Encrypted Payload (In-RAM)",
                            color = WarningAmber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                    TextButton(
                        onClick = onManualDecrypt,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("RETRY DECRYPT", color = NeonEmerald, fontSize = 10.sp)
                    }
                }

                if (message.showRawArmor) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(4.dp)
                    ) {
                        Text(
                            text = message.ciphertext,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            color = StealthTextSecondary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = if (message.showRawArmor) "HIDE ARMOR" else "VIEW ARMOR",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = StealthTextSecondary,
                        modifier = Modifier.clickable { onToggleArmor() }
                    )
                }
            }
        }
    }
}
