package com.ghostkey.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.theme.GhostTheme

/**
 * Telegram-style segmented PIN digit boxes: active slot shows accent border, filled slots show dot.
 */
@Composable
fun PinCodeBoxes(
    pinLength: Int,
    enteredLength: Int,
    isError: Boolean = false,
    modifier: Modifier = Modifier
) {
    val palette = GhostTheme.colors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until pinLength) {
            val isFilled = i < enteredLength
            val isActive = i == enteredLength && !isError
            val borderColor by animateColorAsState(
                targetValue = when {
                    isError -> palette.error
                    isActive -> palette.accent
                    isFilled -> palette.textPrimary
                    else -> palette.divider
                },
                label = "PinBoxBorder"
            )
            Box(
                modifier = Modifier
                    .size(width = 42.dp, height = 50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.surface)
                    .border(if (isActive || isError) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isFilled) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (isError) palette.error else palette.textPrimary)
                    )
                }
            }
        }
    }
}

/**
 * In-app numeric keypad keeping PIN digits off third-party system keyboards.
 */
@Composable
fun InAppNumericKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    keySize: Dp = 68.dp,
    modifier: Modifier = Modifier
) {
    val palette = GhostTheme.colors
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("C", "0", "DEL")
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (row in rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (key in row) {
                    val isUtility = key == "C" || key == "DEL"
                    Box(
                        modifier = Modifier
                            .size(keySize)
                            .clip(CircleShape)
                            .background(if (isUtility) Color.Transparent else palette.surface)
                            .clickable {
                                when (key) {
                                    "C" -> onClear()
                                    "DEL" -> onBackspace()
                                    else -> onDigit(key)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (key == "DEL") {
                            Icon(
                                imageVector = AppIcons.Backspace,
                                contentDescription = "Backspace",
                                tint = palette.textSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Text(
                                text = key,
                                color = if (key == "C") palette.textSecondary else palette.textPrimary,
                                fontSize = if (key == "C") 18.sp else 24.sp,
                                fontWeight = if (key == "C") FontWeight.Medium else FontWeight.SemiBold,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }
                }
            }
        }
    }
}
