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
import com.ghostkey.app.ui.*

@Composable
fun SafetyCodeDialog(
    safetyNumber: String,
    recipientFingerprint: String,
    isPeerVerified: Boolean,
    onVerificationChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AnthropicSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.VerifiedUser, contentDescription = null, tint = AnthropicSage)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "SAFETY CODE VERIFICATION",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = AnthropicTerracotta,
                    fontSize = 14.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Compare these 30 security digits with your peer (by voice call or in person). If they match, the key you saved is very likely your peer's real key and was not swapped on the way. A mismatch means do not trust the key:",
                    color = AnthropicTextMid,
                    fontSize = 11.sp
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(AnthropicDarkBg)
                        .border(1.dp, AnthropicSage.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = safetyNumber,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AnthropicCream,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp,
                        lineHeight = 20.sp
                    )
                }
                if (recipientFingerprint.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onVerificationChanged(!isPeerVerified) }
                    ) {
                        Checkbox(
                            checked = isPeerVerified,
                            onCheckedChange = onVerificationChanged,
                            colors = CheckboxDefaults.colors(checkedColor = AnthropicSage)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Mark Peer as Verified",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isPeerVerified) AnthropicSage else AnthropicCream,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AnthropicTerracotta, contentColor = AnthropicDarkBg)
            ) {
                Text("DONE", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun NewIdentityConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AnthropicSurface,
        title = {
            Text(
                "NEW IDENTITY?",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = AnthropicTerracotta,
                fontSize = 15.sp
            )
        },
        text = {
            Text(
                "This replaces your keys and Drop-ID. Your peer needs your new public key to keep chatting with you.",
                color = AnthropicTextMid,
                fontSize = 12.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = AnthropicTerracotta, contentColor = AnthropicDarkBg)
            ) { Text("CREATE", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = AnthropicTextMuted) }
        }
    )
}
