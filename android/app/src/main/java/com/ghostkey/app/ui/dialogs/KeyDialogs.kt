package com.ghostkey.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.theme.GhostTheme

@Composable
fun SafetyCodeDialog(
    safetyNumber: String,
    recipientFingerprint: String,
    isPeerVerified: Boolean,
    onVerificationChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.VerifiedUser, contentDescription = null, tint = palette.connected)
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.safetyCodeAction, fontWeight = FontWeight.Bold, color = palette.textPrimary, fontSize = 17.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Compare these 30 digits with your peer out-of-band. If they match, your keys have not been intercepted:",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.background)
                        .border(1.dp, palette.connected.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = safetyNumber,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp,
                        lineHeight = 22.sp
                    )
                }
                if (recipientFingerprint.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onVerificationChanged(!isPeerVerified) }
                    ) {
                        Checkbox(
                            checked = isPeerVerified,
                            onCheckedChange = onVerificationChanged,
                            colors = CheckboxDefaults.colors(checkedColor = palette.connected)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Mark Peer as Verified",
                            fontWeight = FontWeight.SemiBold,
                            color = if (isPeerVerified) palette.connected else palette.textPrimary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText)
            ) { Text("Done", fontWeight = FontWeight.SemiBold) }
        }
    )
}

@Composable
fun NewIdentityConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(strings.generateNewIdentityTitle, fontWeight = FontWeight.Bold, color = palette.error, fontSize = 17.sp)
        },
        text = {
            Text(
                "This replaces your OpenPGP keypair and Drop ID. Peers will need your new public key to message you.",
                color = palette.textSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = palette.error, contentColor = androidx.compose.ui.graphics.Color.White)
            ) { Text("Replace Identity", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancelBtn, color = palette.textSecondary) }
        }
    )
}
