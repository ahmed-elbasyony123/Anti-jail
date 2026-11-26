package com.ghostkey.app.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.AppLanguage
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.components.PrimaryActionButton
import com.ghostkey.app.ui.icons.ExtraIcons
import com.ghostkey.app.ui.theme.GhostTheme

@Composable
internal fun WelcomeStep(
    language: AppLanguage,
    onToggleLanguage: (AppLanguage) -> Unit,
    onStart: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Language toggle in top-right corner
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.surface)
                    .border(1.dp, palette.divider, RoundedCornerShape(20.dp))
                    .clickable {
                        val next = if (language == AppLanguage.EN) AppLanguage.AR else AppLanguage.EN
                        onToggleLanguage(next)
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = ExtraIcons.Language,
                    contentDescription = "Language",
                    tint = palette.accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (language == AppLanguage.EN) "عربي" else "EN",
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.Shield,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = strings.appName,
            color = palette.textPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = strings.welcomeSubtitle,
            color = palette.textSecondary,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.weight(1.2f))

        PrimaryActionButton(
            text = strings.welcomeStart,
            onClick = onStart
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}
