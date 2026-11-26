package com.ghostkey.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.theme.avatarColorFor
import java.util.Base64

/**
 * Shared Telegram-style avatar rendering an optional stripped Base64 bitmap (decoded once via remember)
 * or a deterministic hash-colored letter/group circle.
 */
@Composable
fun AvatarView(
    title: String,
    seed: String,
    avatarBase64: String? = null,
    isGroup: Boolean = false,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    val decodedBitmap = remember(avatarBase64) {
        if (avatarBase64.isNullOrBlank()) null
        else runCatching {
            val bytes = Base64.getDecoder().decode(avatarBase64)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    val letter = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "G"
    val bgColor = remember(seed, isGroup) { avatarColorFor(seed.ifBlank { title }) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        when {
            decodedBitmap != null -> {
                Image(
                    bitmap = decodedBitmap,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            isGroup -> {
                Icon(
                    imageVector = AppIcons.Groups,
                    contentDescription = title,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.52f)
                )
            }
            else -> {
                Text(
                    text = letter,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.40f).sp
                )
            }
        }
    }
}
