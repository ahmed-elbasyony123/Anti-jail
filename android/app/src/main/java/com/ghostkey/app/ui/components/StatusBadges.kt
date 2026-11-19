package com.ghostkey.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.ui.*

@Composable
fun StatusBadge(label: String, isActive: Boolean) {
    val color = if (isActive) NeonEmerald else AlertCrimson
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = color
        )
    }
}

@Composable
fun RelayStatusBanner(status: RelayStatus) {
    val (text, color) = when (status) {
        RelayStatus.ORBOT_NOT_RUNNING ->
            "Orbot is not running. Start Orbot and connect it to Tor, then return here." to AlertCrimson
        RelayStatus.BUILDING_CIRCUIT ->
            "Connecting through Tor. This can take a minute." to StealthTextSecondary
        RelayStatus.TIMED_OUT ->
            "Connection timed out. Retrying automatically." to AlertCrimson
        RelayStatus.AUTH_FAILED ->
            "The relay rejected this mailbox's access token and will not retry. Create a new identity or check the relay." to AlertCrimson
        RelayStatus.DISCONNECTED ->
            "Not connected to the relay. Retrying automatically." to StealthTextSecondary
        RelayStatus.CONNECTED -> return
    }
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(StealthSurface)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
fun SignatureWarning(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(AlertCrimson.copy(alpha = 0.22f))
            .border(1.dp, AlertCrimson.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = AlertCrimson
        )
    }
}
