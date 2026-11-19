package com.ghostkey.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.ui.NeonEmerald
import com.ghostkey.app.ui.StealthBackground

@Composable
fun LockedScreen(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StealthBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = NeonEmerald,
                modifier = Modifier.size(40.dp)
            )
            Text(
                "LOCKED",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = NeonEmerald,
                fontSize = 16.sp
            )
            Button(
                onClick = onUnlock,
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
            ) {
                Text("UNLOCK", color = Color(0xFF002914), fontWeight = FontWeight.Bold)
            }
        }
    }
}
