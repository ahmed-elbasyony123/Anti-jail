package com.ghostkey.app.ui.dialogs

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.ghostkey.app.QuickPairUiState
import com.ghostkey.app.crypto.InPersonPairing
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.theme.GhostTheme
import com.ghostkey.app.util.GhostKeyUtils
import kotlinx.coroutines.delay

@Composable
internal fun QuickPairDialog(
    state: QuickPairUiState,
    myFingerprint: String,
    onSelectTab: (Int) -> Unit,
    onGenerateInvite: () -> Unit,
    onRedeemInput: (String) -> Unit,
    onConfirmPreview: (accepted: Boolean, customAlias: String) -> Unit,
    onCopyText: (label: String, value: String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    val context = LocalContext.current

    var redeemInput by remember { mutableStateOf("") }
    var customAlias by remember(state.previewContact?.dropId) {
        mutableStateOf(state.previewContact?.alias.orEmpty())
    }
    var remainingSec by remember(state.expiresAtMs) { mutableLongStateOf(0L) }

    LaunchedEffect(state.expiresAtMs) {
        val exp = state.expiresAtMs ?: return@LaunchedEffect
        while (true) {
            val left = ((exp - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            remainingSec = left
            if (left <= 0L) break
            delay(1_000L)
        }
    }

    val preview = state.previewContact
    val sasCode = remember(preview?.fingerprint, myFingerprint) {
        if (preview != null && preview.fingerprint.isNotBlank() && myFingerprint.isNotBlank()) {
            InPersonPairing.calculateSas(myFingerprint, preview.fingerprint)
        } else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (preview != null) AppIcons.VerifiedUser else AppIcons.Handshake,
                    contentDescription = null,
                    tint = palette.accent
                )
                Text(
                    text = if (preview != null) strings.quickPairPreviewTitle else strings.quickPairTitle,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (preview != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.surfaceVariant)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = palette.connected, modifier = Modifier.size(18.dp))
                            Text(strings.quickPairPreviewTitle, color = palette.connected, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        OutlinedTextField(
                            value = customAlias,
                            onValueChange = { customAlias = it },
                            label = { Text(strings.displayNameLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "FP: ${preview.fingerprint.chunked(4).take(5).joinToString(" ")}...",
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Drop ID: ${preview.dropId.take(16)}...",
                            color = palette.textSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (sasCode != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.background)
                                .border(1.dp, palette.connected.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(strings.safetyCodeAction, color = palette.connected, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = sasCode,
                                color = palette.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.activeTab == 0,
                            onClick = { onSelectTab(0) },
                            label = { Text(strings.quickPairTabCreate, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = state.activeTab == 1,
                            onClick = { onSelectTab(1) },
                            label = { Text(strings.quickPairTabRedeem, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (state.activeTab == 0) {
                        Button(
                            onClick = onGenerateInvite,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (state.isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(strings.quickPairGenerateBtn, fontWeight = FontWeight.SemiBold)
                        }

                        if (!state.generatedCode.isNullOrBlank()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.background)
                                    .border(1.dp, palette.accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = state.generatedCode,
                                    color = palette.textPrimary,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                val mins = remainingSec / 60L
                                val secs = remainingSec % 60L
                                Text(
                                    text = if (remainingSec > 0) String.format("%d:%02d · 1x", mins, secs) else "Expired",
                                    color = if (remainingSec > 0) palette.connected else palette.error,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                val shareLink = state.generatedWebLink ?: state.generatedAppLink.orEmpty()
                                Text(
                                    text = shareLink,
                                    color = palette.textSecondary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { onCopyText("Invite Link", shareLink) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(AppIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(strings.quickPairCopyLink, fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { onCopyText("Invite Code", state.generatedCode) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Text(strings.quickPairCopyCode, fontSize = 11.sp)
                                    }
                                }
                                Button(
                                    onClick = {
                                        val appUrl = state.generatedAppLink.orEmpty()
                                        val msg = buildString {
                                            append("GhostKey 5-min Invite:\n")
                                            if (shareLink.isNotBlank() && shareLink != appUrl) append("$shareLink\n")
                                            if (appUrl.isNotBlank()) append("App Link: $appUrl\n")
                                            append("Code: ${state.generatedCode}")
                                        }
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, msg)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, strings.quickPairShareBtn))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(strings.quickPairShareBtn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = redeemInput,
                                onValueChange = { redeemInput = it },
                                label = { Text("XXXXX-XXXXX / Link") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    val pasted = GhostKeyUtils.readClipboardText(context)?.trim().orEmpty()
                                    if (pasted.isNotBlank()) {
                                        redeemInput = pasted
                                    } else {
                                        Toast.makeText(context, "Clipboard empty", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.surfaceVariant)
                            ) {
                                Icon(AppIcons.ContentPaste, contentDescription = "Paste", tint = palette.accent)
                            }
                        }
                        Button(
                            onClick = { onRedeemInput(redeemInput) },
                            enabled = !state.isBusy && redeemInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (state.isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(strings.quickPairRedeemBtn, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (!state.error.isNullOrBlank()) {
                    Text(
                        text = state.error,
                        color = palette.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            if (preview != null) {
                Button(
                    onClick = { onConfirmPreview(true, customAlias) },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.connected, contentColor = Color.White)
                ) {
                    Text(strings.quickPairAcceptBtn, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (preview != null) {
                TextButton(onClick = { onConfirmPreview(false, "") }) {
                    Text(strings.quickPairDeclineBtn, color = palette.error)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(strings.cancelBtn, color = palette.textSecondary)
                }
            }
        }
    )
}
