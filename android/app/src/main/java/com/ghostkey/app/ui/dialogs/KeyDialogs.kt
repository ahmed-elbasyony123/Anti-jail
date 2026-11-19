package com.ghostkey.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.ui.*

@Composable
fun SheetAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyBottomSheet(
    recipientFingerprint: String,
    onDismiss: () -> Unit,
    onCopyMyKey: () -> Unit,
    onEditPeerKey: () -> Unit,
    onOpenSafetyCode: () -> Unit,
    onNewIdentity: () -> Unit,
    onBurnIdentity: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = StealthSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                "KEYS & IDENTITY",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = NeonEmerald,
                fontSize = 13.sp
            )
            Text(
                "PEER: ${recipientFingerprint.take(12)}...",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = StealthTextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            SheetAction(Icons.Default.ContentCopy, "COPY MY KEY (includes Drop-ID)", NeonEmerald) {
                onCopyMyKey()
            }
            SheetAction(Icons.Default.Edit, "PASTE / EDIT PEER KEY", NeonEmerald) {
                onEditPeerKey()
            }
            SheetAction(Icons.Default.VerifiedUser, "SAFETY CODE", NeonEmerald) {
                onOpenSafetyCode()
            }
            SheetAction(Icons.Default.Refresh, "NEW IDENTITY", WarningAmber) {
                onNewIdentity()
            }
            SheetAction(Icons.Default.DeleteForever, "BURN IDENTITY", AlertCrimson) {
                onBurnIdentity()
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun MyKeyDialog(
    myPgpKeys: PgpKeyPairArmored,
    onDismiss: () -> Unit,
    onCopyKey: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StealthSurface,
        title = {
            Text(
                "MY OPENPGP PUBLIC KEY",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = NeonEmerald,
                fontSize = 15.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Fingerprint:\n${myPgpKeys.fingerprint}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = StealthTextSecondary
                )
                OutlinedTextField(
                    value = myPgpKeys.publicKeyArmored,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onCopyKey,
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
            ) {
                Text("COPY KEY", color = Color(0xFF002914), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = StealthTextSecondary)
            }
        }
    )
}

@Composable
fun PeerKeyDialog(
    initialKeyText: String,
    onDismiss: () -> Unit,
    onSaveKey: (String) -> Unit
) {
    var tempKeyText by remember { mutableStateOf(initialKeyText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StealthSurface,
        title = {
            Text(
                "RECIPIENT PUBLIC KEY",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = NeonEmerald,
                fontSize = 15.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste your peer's armored OpenPGP public key (starts with -----BEGIN PGP PUBLIC KEY BLOCK-----):",
                    color = StealthTextSecondary,
                    fontSize = 11.sp
                )
                OutlinedTextField(
                    value = tempKeyText,
                    onValueChange = { tempKeyText = it },
                    placeholder = { Text("-----BEGIN PGP PUBLIC KEY BLOCK-----\n...", fontSize = 10.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveKey(tempKeyText) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
            ) {
                Text("SAVE KEY", color = Color(0xFF002914), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = StealthTextSecondary)
            }
        }
    )
}

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
        containerColor = StealthSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = NeonEmerald)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "SAFETY CODE VERIFICATION",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = NeonEmerald,
                    fontSize = 14.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Compare these 30 security digits with your peer (by voice call or in person). If they match, the key you saved is very likely your peer's real key and was not swapped on the way. A mismatch means do not trust the key:",
                    color = StealthTextSecondary,
                    fontSize = 11.sp
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(StealthBackground)
                        .border(1.dp, NeonEmerald.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = safetyNumber,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = NeonEmerald,
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
                            colors = CheckboxDefaults.colors(checkedColor = NeonEmerald)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Mark Peer as Verified",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isPeerVerified) NeonEmerald else StealthTextPrimary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
            ) {
                Text("DONE", color = Color(0xFF002914), fontWeight = FontWeight.Bold)
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
        containerColor = StealthSurface,
        title = {
            Text("NEW IDENTITY?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = WarningAmber, fontSize = 15.sp)
        },
        text = {
            Text(
                "This replaces your keys and Drop-ID. Your peer needs your new public key to keep chatting with you.",
                color = StealthTextSecondary,
                fontSize = 12.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
            ) { Text("CREATE", color = Color(0xFF002914), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = StealthTextSecondary) }
        }
    )
}

@Composable
fun BurnConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StealthSurface,
        title = {
            Text("BURN IDENTITY?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = AlertCrimson, fontSize = 15.sp)
        },
        text = {
            Text(
                "Deletes the saved identity and its encryption key, clears all settings and closes the app. This cannot be undone.",
                color = StealthTextSecondary,
                fontSize = 12.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = AlertCrimson)
            ) { Text("BURN", color = Color.White, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = StealthTextSecondary) }
        }
    )
}
