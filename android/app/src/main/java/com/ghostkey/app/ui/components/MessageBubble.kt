package com.ghostkey.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.audio.PlaybackState
import com.ghostkey.app.crypto.SignatureStatus
import com.ghostkey.app.data.ChatMessage
import com.ghostkey.app.data.MessageContent
import com.ghostkey.app.ui.*
import com.ghostkey.app.util.GhostKeyUtils

/**
 * Asymmetric message bubble with Anthropic warm cream/terracotta aesthetics.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    playbackState: PlaybackState = PlaybackState(),
    nowMs: Long = System.currentTimeMillis(),
    onManualDecrypt: () -> Unit,
    onAudioPlayPause: (ChatMessage) -> Unit = {},
    onAudioSeek: (ChatMessage, Float) -> Unit = { _, _ -> },
    onAcceptIntroduction: (alias: String, dropId: String, armoredKey: String, safetyCode: String?) -> Unit = { _, _, _, _ -> }
) {
    val isOut = message.isOutgoing
    val align = if (isOut) Alignment.End else Alignment.Start
    val bubbleColor = if (isOut) AnthropicBubbleOutgoing else AnthropicBubbleIncoming
    val borderColor = if (isOut) AnthropicTerracotta.copy(alpha = 0.3f) else AnthropicBorder

    val bubbleShape = if (isOut) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

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
                                AppIcons.VerifiedUser,
                                contentDescription = "Verified PGP Signature",
                                tint = AnthropicSage,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }

                when (val content = message.content) {
                    is MessageContent.AudioNote -> {
                        AudioBubble(
                            message = message,
                            playbackState = playbackState,
                            onPlayPause = { onAudioPlayPause(message) },
                            onSeek = { fraction -> onAudioSeek(message, fraction) }
                        )
                    }
                    is MessageContent.ImageMedia -> {
                        val imageBitmap = remember(message.id, content.imageBytes.size) {
                            runCatching {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(content.imageBytes, 0, content.imageBytes.size, bounds)
                                val opts = BitmapFactory.Options().apply {
                                    inSampleSize = GhostKeyUtils.calculateInSampleSize(bounds.outWidth, bounds.outHeight, 1024)
                                }
                                BitmapFactory.decodeByteArray(content.imageBytes, 0, content.imageBytes.size, opts)?.asImageBitmap()
                            }.getOrNull()
                        }
                        if (imageBitmap != null) {
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = content.filename,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            FileAttachmentFallback(content.filename, content.mimeType)
                        }
                    }
                    is MessageContent.FileMedia -> {
                        FileAttachmentFallback(content.filename, content.mimeType)
                    }
                    is MessageContent.ContactIntro -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AnthropicSurfaceVariant)
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.Handshake,
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
                                    "You introduced ${content.alias}"
                                } else {
                                    "${message.senderAlias ?: "Peer"} wants to introduce ${content.alias}"
                                },
                                color = AnthropicCream,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (content.dropId.isNotBlank()) {
                                Text(
                                    text = "Drop ID: ${content.dropId.take(12)}...",
                                    color = AnthropicTextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            if (!content.safetyCode.isNullOrBlank()) {
                                Text(
                                    text = "Safety Code: ${content.safetyCode}",
                                    color = AnthropicSage,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (!isOut && content.armoredKey.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        onAcceptIntroduction(
                                            content.alias,
                                            content.dropId,
                                            content.armoredKey,
                                            content.safetyCode
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
                    }
                    MessageContent.Encrypted -> {
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
                    else -> {
                        Text(
                            text = message.previewText(""),
                            color = AnthropicCream,
                            fontSize = 14.5.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.expiresAt != null) {
                        val rem = message.remainingSeconds(nowMs)
                        val remText = if (rem != null) {
                            when {
                                rem >= 3600 -> "${rem / 3600}h"
                                rem >= 60 -> "${rem / 60}m"
                                else -> "${rem}s"
                            }
                        } else ""
                        Icon(
                            AppIcons.Hourglass,
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
                        text = GhostKeyUtils.formatClockTime(message.timestamp),
                        fontSize = 10.sp,
                        color = AnthropicTextMuted
                    )
                    if (isOut) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Done,
                            contentDescription = "Sent to Relay",
                            tint = AnthropicTextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileAttachmentFallback(filename: String, mimeType: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AnthropicSurfaceVariant)
            .padding(8.dp)
    ) {
        Icon(AppIcons.AttachFile, contentDescription = null, tint = AnthropicTerracotta)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = filename.ifEmpty { "Attachment" },
                color = AnthropicCream,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = mimeType.ifEmpty { "application/octet-stream" },
                color = AnthropicTextMuted,
                fontSize = 10.sp
            )
        }
    }
}
