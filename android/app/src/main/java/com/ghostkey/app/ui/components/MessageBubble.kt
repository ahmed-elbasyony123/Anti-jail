package com.ghostkey.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.audio.PlaybackState
import com.ghostkey.app.crypto.SignatureStatus
import com.ghostkey.app.data.ChatMessage
import com.ghostkey.app.data.DeliveryState
import com.ghostkey.app.data.MessageContent
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.icons.ExtraIcons
import com.ghostkey.app.ui.theme.GhostTheme
import com.ghostkey.app.util.GhostKeyUtils

/**
 * Minimal chat bubble: black/white outgoing, light gray incoming, with embedded reply quote support.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    replyTargetMessage: ChatMessage? = null,
    playbackState: PlaybackState = PlaybackState(),
    nowMs: Long = System.currentTimeMillis(),
    onManualDecrypt: () -> Unit,
    onAudioPlayPause: (ChatMessage) -> Unit = {},
    onAudioSeek: (ChatMessage, Float) -> Unit = { _, _ -> },
    onReplyToMessage: (ChatMessage) -> Unit = {},
    onAcceptIntroduction: (alias: String, dropId: String, armoredKey: String, safetyCode: String?) -> Unit = { _, _, _, _ -> }
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    val isOut = message.isOutgoing
    val align = if (isOut) Alignment.End else Alignment.Start
    val bubbleBg = if (isOut) palette.bubbleOutgoing else palette.bubbleIncoming
    val textColor = if (isOut) palette.bubbleOutgoingText else palette.bubbleIncomingText
    val subColor = if (isOut) palette.bubbleOutgoingSubtext else palette.bubbleIncomingSubtext

    val bubbleShape = if (isOut) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = align
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 84.dp, max = 304.dp)
                .clip(bubbleShape)
                .background(bubbleBg)
                .combinedClickable(
                    onClick = {
                        if (!message.isDecrypted) onManualDecrypt()
                    },
                    onLongClick = {
                        if (message.isDecrypted) onReplyToMessage(message)
                    }
                )
                .padding(horizontal = 14.dp, vertical = 9.dp)
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
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = palette.accent
                        )
                        if (message.signatureStatus == SignatureStatus.VERIFIED) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                AppIcons.VerifiedUser,
                                contentDescription = null,
                                tint = palette.connected,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }

                if (replyTargetMessage != null) {
                    ReplyQuotePreview(
                        target = replyTargetMessage,
                        isOutgoing = isOut
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                when (val content = message.content) {
                    is MessageContent.AudioNote -> {
                        AudioBubble(
                            message = message,
                            playbackState = playbackState,
                            isOutgoing = isOut,
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
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                        } else {
                            FileAttachmentChip(content.filename, content.mimeType, isOut)
                        }
                    }
                    is MessageContent.FileMedia -> {
                        FileAttachmentChip(content.filename, content.mimeType, isOut)
                    }
                    is MessageContent.ContactIntro -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(textColor.copy(alpha = 0.08f))
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.Handshake,
                                    contentDescription = null,
                                    tint = palette.accent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = strings.contactIntroTitle,
                                    color = palette.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = content.alias,
                                color = textColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (content.dropId.isNotBlank()) {
                                Text(
                                    text = "ID: ${content.dropId.take(12)}...",
                                    color = subColor,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (!content.safetyCode.isNullOrBlank()) {
                                Text(
                                    text = "SAS: ${content.safetyCode}",
                                    color = palette.connected,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (!isOut && content.armoredKey.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        onAcceptIntroduction(content.alias, content.dropId, content.armoredKey, content.safetyCode)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.accent,
                                        contentColor = palette.accentText
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(strings.addIntroducedContact, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    MessageContent.Encrypted -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = strings.encryptedPlaceholderTitle,
                                    color = textColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = strings.encryptedPlaceholderSubtitle,
                                    color = subColor,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = message.previewText(""),
                            color = textColor,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            style = TextStyle(textDirection = TextDirection.Content)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.expiresAt != null) {
                        val rem = message.remainingSeconds(nowMs)
                        val remText = rem?.let {
                            when {
                                it >= 3600 -> "${it / 3600}h"
                                it >= 60 -> "${it / 60}m"
                                else -> "${it}s"
                            }
                        }.orEmpty()
                        Icon(
                            AppIcons.Hourglass,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(11.dp)
                        )
                        if (remText.isNotBlank()) {
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = remText,
                                fontSize = 10.sp,
                                color = palette.accent,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = GhostKeyUtils.formatClockTime(message.timestamp),
                        fontSize = 10.sp,
                        color = subColor
                    )
                    if (isOut) {
                        Spacer(modifier = Modifier.width(4.dp))
                        DeliveryStateIcon(state = message.deliveryState, tint = subColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyQuotePreview(
    target: ChatMessage,
    isOutgoing: Boolean
) {
    val palette = GhostTheme.colors
    val textColor = if (isOutgoing) palette.bubbleOutgoingText else palette.bubbleIncomingText
    val subColor = if (isOutgoing) palette.bubbleOutgoingSubtext else palette.bubbleIncomingSubtext
    val senderLabel = if (target.isOutgoing) "You" else (target.senderAlias ?: "Peer")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(textColor.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.accent)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = senderLabel,
                color = palette.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = target.previewText("Message"),
                color = subColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DeliveryStateIcon(state: DeliveryState, tint: androidx.compose.ui.graphics.Color) {
    val palette = GhostTheme.colors
    when (state) {
        DeliveryState.FAILED -> Icon(Icons.Default.Warning, contentDescription = null, tint = palette.error, modifier = Modifier.size(13.dp))
        DeliveryState.QUEUED -> Icon(AppIcons.Hourglass, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        DeliveryState.DELIVERED_LAN,
        DeliveryState.RELAYED_BLE_MESH -> Icon(ExtraIcons.DoneAll, contentDescription = null, tint = palette.accent, modifier = Modifier.size(14.dp))
        DeliveryState.SENT_RELAY,
        DeliveryState.NONE -> Icon(Icons.Default.Done, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun FileAttachmentChip(filename: String, mimeType: String, isOutgoing: Boolean) {
    val palette = GhostTheme.colors
    val textColor = if (isOutgoing) palette.bubbleOutgoingText else palette.bubbleIncomingText
    val subColor = if (isOutgoing) palette.bubbleOutgoingSubtext else palette.bubbleIncomingSubtext
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(textColor.copy(alpha = 0.08f))
            .padding(10.dp)
    ) {
        Icon(AppIcons.AttachFile, contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = filename.ifEmpty { "Attachment" },
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = mimeType.ifEmpty { "application/octet-stream" },
                color = subColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
