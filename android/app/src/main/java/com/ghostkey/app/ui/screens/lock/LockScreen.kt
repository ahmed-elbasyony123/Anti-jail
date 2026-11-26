package com.ghostkey.app.ui.screens.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.components.InAppNumericKeypad
import com.ghostkey.app.ui.components.PinCodeBoxes
import com.ghostkey.app.ui.components.PrimaryActionButton
import com.ghostkey.app.ui.theme.GhostTheme

/**
 * Clean Lock Screen using the shared in-app keypad (guards against 3rd-party keyboard logging
 * and handles silent Duress PIN panic wipe identically to normal unlock attempts).
 */
@Composable
fun LockScreen(
    onUnlock: () -> Unit,
    onDeviceUnlock: () -> Unit,
    onVerifyPin: (String) -> Boolean
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings

    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    fun submitPin(pin: String) {
        if (pin.length < 4) return
        if (onVerifyPin(pin)) {
            onUnlock()
        } else {
            isError = true
            enteredPin = ""
        }
    }

    fun handleDigit(digit: String) {
        if (enteredPin.length >= 8) return
        isError = false
        val next = enteredPin + digit
        enteredPin = next
        if (next.length == 6 && onVerifyPin(next)) {
            onUnlock()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.5f))

            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(palette.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = strings.lockScreenHeader,
                color = palette.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = strings.lockScreenSubtitle,
                color = palette.textSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(22.dp))

            PinCodeBoxes(
                pinLength = maxOf(6, enteredPin.length),
                enteredLength = enteredPin.length,
                isError = isError
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (isError) {
                Text(
                    text = strings.invalidPinError,
                    color = palette.error,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Spacer(modifier = Modifier.height(18.dp))
            }

            Spacer(modifier = Modifier.weight(0.5f))

            InAppNumericKeypad(
                onDigit = ::handleDigit,
                onBackspace = {
                    if (enteredPin.isNotEmpty()) {
                        enteredPin = enteredPin.dropLast(1)
                        isError = false
                    }
                },
                onClear = {
                    enteredPin = ""
                    isError = false
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (enteredPin.length >= 4) {
                PrimaryActionButton(
                    text = strings.continueBtn,
                    onClick = { submitPin(enteredPin) },
                    modifier = Modifier.widthIn(max = 260.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            TextButton(onClick = onDeviceUnlock) {
                Icon(AppIcons.Fingerprint, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.deviceLockFallback, color = palette.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
