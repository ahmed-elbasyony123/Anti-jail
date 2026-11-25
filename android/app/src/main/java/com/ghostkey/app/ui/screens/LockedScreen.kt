package com.ghostkey.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.ui.*

/**
 * Lock Screen supporting In-App PIN, silent Duress PIN wipe, and Device Credential fallback.
 */
@Composable
fun LockedScreen(
    onUnlock: () -> Unit,
    onDeviceUnlock: () -> Unit = onUnlock,
    onVerifyPin: (String) -> Boolean = { true }
) {
    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    fun handleDigit(digit: String) {
        if (enteredPin.length < 8) {
            isError = false
            enteredPin += digit
        }
    }

    fun handleBackspace() {
        if (enteredPin.isNotEmpty()) {
            enteredPin = enteredPin.dropLast(1)
            isError = false
        }
    }

    fun submitPin() {
        if (enteredPin.length >= 4) {
            if (onVerifyPin(enteredPin)) {
                onUnlock()
            } else {
                isError = true
                enteredPin = ""
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AnthropicDarkBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(AnthropicSurfaceVariant)
                    .border(1.dp, AnthropicBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = AnthropicTerracotta,
                    modifier = Modifier.size(28.dp)
                )
            }

            Text(
                "GHOSTKEY ENCRYPTED",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = AnthropicCream,
                fontSize = 15.sp,
                letterSpacing = 2.sp
            )

            Text(
                "Enter PIN to unlock your encrypted identity",
                color = AnthropicTextMuted,
                fontSize = 12.sp
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                val dotCount = maxOf(4, enteredPin.length)
                for (i in 0 until dotCount) {
                    val isFilled = i < enteredPin.length
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isError -> AnthropicCrimson
                                    isFilled -> AnthropicTerracotta
                                    else -> AnthropicSurfaceVariant
                                }
                            )
                            .border(
                                1.dp,
                                if (isError) AnthropicCrimson else AnthropicBorder,
                                CircleShape
                            )
                    )
                }
            }

            if (isError) {
                Text(
                    "Invalid PIN. Try again.",
                    color = AnthropicCrimson,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(8.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val keys = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("C", "0", "DEL")
                )

                for (row in keys) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (key in row) {
                            Box(
                                modifier = Modifier
                                    .size(62.dp)
                                    .clip(CircleShape)
                                    .background(AnthropicSurface)
                                    .border(1.dp, AnthropicBorderSubtle, CircleShape)
                                    .clickable {
                                        when (key) {
                                            "C" -> {
                                                enteredPin = ""
                                                isError = false
                                            }
                                            "DEL" -> handleBackspace()
                                            else -> handleDigit(key)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (key == "DEL") {
                                    Icon(
                                        AppIcons.Backspace,
                                        contentDescription = "Backspace",
                                        tint = AnthropicTextMid,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Text(
                                        key,
                                        color = AnthropicCream,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (enteredPin.length >= 4) {
                    Button(
                        onClick = { submitPin() },
                        colors = ButtonDefaults.buttonColors(containerColor = AnthropicTerracotta)
                    ) {
                        Text("UNLOCK", fontWeight = FontWeight.Bold, color = AnthropicCream)
                    }
                }

                OutlinedButton(
                    onClick = onDeviceUnlock,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AnthropicSlate)
                ) {
                    Icon(AppIcons.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Device Lock", fontSize = 12.sp)
                }
            }
        }
    }
}
