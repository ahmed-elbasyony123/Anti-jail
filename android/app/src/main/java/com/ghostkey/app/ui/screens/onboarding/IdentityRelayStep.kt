package com.ghostkey.app.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.RelayTestState
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.components.PrimaryActionButton
import com.ghostkey.app.ui.theme.GhostTheme
import com.ghostkey.app.util.GhostKeyUtils

@Composable
internal fun IdentityRelayStep(
    alias: String,
    onAliasChange: (String) -> Unit,
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    ephemeralMode: Boolean,
    onEphemeralChange: (Boolean) -> Unit,
    relayTestState: RelayTestState,
    onTestRelay: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = strings.backBtn,
                tint = palette.textPrimary
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(palette.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = strings.identityTitle,
                color = palette.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = strings.identitySubtitle,
                color = palette.textSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = alias,
                onValueChange = onAliasChange,
                label = { Text(strings.displayNameLabel) },
                placeholder = { Text(strings.displayNamePlaceholder, color = palette.textTertiary) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.divider,
                    focusedContainerColor = palette.surface,
                    unfocusedContainerColor = palette.surface,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = relayUrl,
                onValueChange = { onRelayUrlChange(it.trim()) },
                label = { Text(strings.relayUrlLabel) },
                placeholder = { Text(strings.relayUrlPlaceholder, color = palette.textTertiary) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                trailingIcon = {
                    IconButton(onClick = {
                        GhostKeyUtils.readClipboardText(context)?.trim()?.takeIf { it.isNotBlank() }?.let {
                            onRelayUrlChange(it)
                        }
                    }) {
                        Icon(AppIcons.ContentPaste, contentDescription = "Paste", tint = palette.accent)
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.divider,
                    focusedContainerColor = palette.surface,
                    unfocusedContainerColor = palette.surface,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onTestRelay,
                    enabled = relayUrl.isNotBlank() && relayTestState !is RelayTestState.Testing,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (relayTestState is RelayTestState.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = palette.accent
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.testingConnection, fontSize = 13.sp)
                    } else {
                        Text(strings.testConnection, fontSize = 13.sp)
                    }
                }

                when (relayTestState) {
                    is RelayTestState.Success -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = palette.connected, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${strings.connectionOkPrefix} (${relayTestState.latencyMs} ms)",
                                color = palette.connected,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    is RelayTestState.Failed -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = palette.error, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${strings.connectionFailedPrefix}: ${relayTestState.reason}",
                                color = palette.error,
                                fontSize = 12.sp,
                                maxLines = 2
                            )
                        }
                    }
                    else -> Unit
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = palette.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.ephemeralToggleTitle,
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = strings.ephemeralToggleSubtitle,
                            color = palette.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = ephemeralMode,
                        onCheckedChange = onEphemeralChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = palette.accent,
                            uncheckedThumbColor = palette.textSecondary,
                            uncheckedTrackColor = palette.surfaceVariant
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        PrimaryActionButton(
            text = strings.continueBtn,
            enabled = alias.isNotBlank(),
            onClick = onContinue
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}
