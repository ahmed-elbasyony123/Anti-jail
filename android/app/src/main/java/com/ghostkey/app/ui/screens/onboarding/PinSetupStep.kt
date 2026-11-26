package com.ghostkey.app.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.ui.components.InAppNumericKeypad
import com.ghostkey.app.ui.components.PinCodeBoxes
import com.ghostkey.app.ui.theme.GhostTheme

private const val ONBOARDING_PIN_LENGTH = 6

@Composable
internal fun PinSetupStep(
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onPinConfirmed: (String) -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings

    var firstPin by remember { mutableStateOf<String?>(null) }
    var currentInput by remember { mutableStateOf("") }
    var isMismatchError by remember { mutableStateOf(false) }

    val isConfirming = firstPin != null

    fun handleDigit(digit: String) {
        if (currentInput.length >= ONBOARDING_PIN_LENGTH) return
        isMismatchError = false
        val next = currentInput + digit
        currentInput = next
        if (next.length == ONBOARDING_PIN_LENGTH) {
            val chosen = firstPin
            if (chosen == null) {
                firstPin = next
                currentInput = ""
            } else if (chosen == next) {
                onPinConfirmed(next)
            } else {
                isMismatchError = true
                firstPin = null
                currentInput = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (isConfirming) {
                    firstPin = null
                    currentInput = ""
                    isMismatchError = false
                } else {
                    onBack()
                }
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = strings.backBtn,
                    tint = palette.textPrimary
                )
            }

            TextButton(onClick = onSkip) {
                Text(
                    text = strings.skipBtn,
                    color = palette.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .size(56.dp)
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
            text = if (isConfirming) strings.pinConfirmTitle else strings.pinTitle,
            color = palette.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = if (isConfirming) strings.pinConfirmSubtitle else strings.pinSubtitle,
            color = palette.textSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        PinCodeBoxes(
            pinLength = ONBOARDING_PIN_LENGTH,
            enteredLength = currentInput.length,
            isError = isMismatchError
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (isMismatchError) {
            Text(
                text = strings.pinMismatchError,
                color = palette.error,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        } else {
            Text(
                text = strings.pinSecurityNote,
                color = palette.textTertiary,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        InAppNumericKeypad(
            onDigit = ::handleDigit,
            onBackspace = {
                if (currentInput.isNotEmpty()) {
                    currentInput = currentInput.dropLast(1)
                    isMismatchError = false
                }
            },
            onClear = {
                currentInput = ""
                isMismatchError = false
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}
