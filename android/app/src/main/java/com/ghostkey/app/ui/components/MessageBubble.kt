package com.ghostkey.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Verified
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

/**
 * Modern Telegram-style asymmetric message bubble with Anthropic warm cream/terracotta aesthetics.
 *
 * Outgoing: Curved top-left, top-right, bottom-left (18dp) with small tail on bottom-right (4dp).
 * Incoming: Curved top-left, top-right, bottom-right (18dp) with small tail on bottom-left (4dp).
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    playbackState: PlaybackState = PlaybackState(),
    onToggleArmor: () -> Unit,
    onManualDecrypt: () -> Unit,
    onAudioPlayPause: (ChatMessage) -> Unit = {},
    onAudioSeek: (ChatMessage, Float) -> Unit = { _, _ -> },
    onAcceptIntroduction: (alias: String, dropId: String, armoredKey: String, safetyCode: String?) -> Unit = { _, _, _, _ -> }
) {
    val isOut = message.isOutgoing
    val align = if (isOut) Alignment.End else Alignment.Start
    val bubbleColor = if (isOut) AnthropicBubbleOutgoing else AnthropicBubbleIncoming
    val borderColor = if (isOut) AnthropicTerracotta.copy(alpha = 0.3f) else AnthropicBorder

    // Telegram asymmetric bubble corners
    val bubbleShape = if (isOut) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = align
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 90.dp, max = 310.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .border(1.dp, borderColor, bubbleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                // Group / Sender Header for Incoming Messages
                if (!isOut && (message.isGroup || !message.senderAlias.isNullOrBlank())) {
                    val displayName = message.senderAlias ?: message.signerFingerprint?.take(8) ?: "Member"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Text(
                            text = displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = AnthropicTerracotta
                        )
                        if (message.signatureStatus == SignatureStatus.VERIFIED) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Verified,
                                contentDescription = "Verified PGP Signature",
                                tint = AnthropicSage,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }

                // Message Payload Area
                if (message.isAudio && message.isDecrypted && message.audioBytes != null) {
                    AudioBubble(
                        message = message,
                        playbackState = playbackState,
                        onPlayPause = { onAudioPlayPause(message) },
                        onSeek = { fraction -> onAudioSeek(message, fraction) }
                    )
                } else if (message.isMedia && message.isDecrypted) {
                    if (message.mediaBitmap != null) {
                        Image(
                            bitmap = message.mediaBitmap,
                            contentDescription = message.filename,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AnthropicSurfaceVariant)
                                .padding(8.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, tint = AnthropicTerracotta)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = message.filename.ifEmpty { "Attachment" },
                                    color = AnthropicCream,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = message.mimeType.ifEmpty { "application/octet-stream" },
                                    color = AnthropicTextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                } else if (message.isIntroduction && message.isDecrypted) {
                    // Briar-style Contact Introduction Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AnthropicSurfaceVariant)
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Handshake,
                                contentDescription = null,
                                tint = AnthropicTerracotta,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Contact Introduction",
                                color = AnthropicTerracotta,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isOut) {
                                "You introduced ${message.introAlias ?: "a contact"}"
                            } else {
                                "${message.senderAlias ?: "Peer"} wants to introduce ${message.introAlias ?: "a contact"}"
                            },
                            color = AnthropicCream,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (!message.introDropId.isNullOrBlank()) {
                            Text(
                                text = "Drop ID: ${message.introDropId.take(12)}...",
                                color = AnthropicTextMuted,
                                fontSize = 11.sp
                            )
                        }
                        if (!message.introSafetyCode.isNullOrBlank()) {
                            Text(
                                text = "Safety Code: ${message.introSafetyCode}",
                                color = AnthropicSage,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (!isOut && !message.introArmoredKey.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    onAcceptIntroduction(
                                        message.introAlias ?: "Peer",
                                        message.introDropId ?: "",
                                        message.introArmoredKey,
                                        message.introSafetyCode
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AnthropicTerracotta,
                                    contentColor = AnthropicDarkBg
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp)
                            ) {
                                Text("Add Contact", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else if (message.isDecrypted) {
                    Text(
                        text = message.plaintext ?: "",
                        color = AnthropicCream,
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Normal
                    )
                } else {
                    // Locked / Encrypted State with Tap to Decrypt
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onManualDecrypt() }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Encrypted",
                            tint = AnthropicTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Encrypted OpenPGP Message",
                            color = AnthropicCream,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Tap to decrypt with your private key",
                            color = AnthropicTextMuted,
                            fontSize = 10.sp
                        )
                    }
                }

                // Telegram Inline Timestamp & Double Checkmarks
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.expiresAt != null) {
                        val rem = message.remainingSeconds()
                        val remText = if (rem != null) {
                            if (rem >= 3600) "${rem / 3600}h"
                            else if (rem >= 60) "${rem / 60}m"
                            else "${rem}s"
                        } else ""
                        Icon(
                            Icons.Default.HourglassBottom,
                            contentDescription = "Disappearing message",
                            tint = AnthropicTerracotta,
                            modifier = Modifier.size(11.dp)
                        )
                        if (remText.isNotBlank()) {
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = remText,
                                fontSize = 9.sp,
                                color = AnthropicTerracotta,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = timeFormat.format(Date(message.timestamp)),
                        fontSize = 10.sp,
                        color = AnthropicTextMuted
                    )
                    if (isOut) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (message.isDecrypted) Icons.Default.DoneAll else Icons.Default.Done,
                            contentDescription = if (message.isDecrypted) "Delivered & Decrypted" else "Sent to Relay",
                            tint = if (message.isDecrypted) AnthropicSage else AnthropicTextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}
