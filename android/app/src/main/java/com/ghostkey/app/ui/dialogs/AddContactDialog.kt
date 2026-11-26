package com.ghostkey.app.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.data.Contact
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.theme.GhostTheme
import com.ghostkey.app.util.GhostKeyUtils

@Composable
fun AddDirectChatDialog(
    onDismiss: () -> Unit,
    onAddContact: (Contact) -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    val context = LocalContext.current
    var armoredKey by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = strings.newDirectChat,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = palette.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Paste the peer's OpenPGP Public Key. Drop ID and fingerprint are verified locally.",
                    fontSize = 12.sp,
                    color = palette.textSecondary,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(strings.displayNameLabel) },
                    placeholder = { Text("Alice, Bob", color = palette.textTertiary) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = armoredKey,
                    onValueChange = {
                        armoredKey = it
                        errorText = null
                    },
                    label = { Text("PGP Public Key Block") },
                    placeholder = { Text("-----BEGIN PGP PUBLIC KEY BLOCK-----...", color = palette.textTertiary) },
                    minLines = 4,
                    maxLines = 6,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = {
                        GhostKeyUtils.readClipboardText(context)?.takeIf { it.isNotBlank() }?.let {
                            armoredKey = it
                            errorText = null
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(AppIcons.ContentPaste, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Paste From Clipboard", fontSize = 13.sp)
                }

                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        color = palette.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val trimmed = armoredKey.trim()
                        if (trimmed.isBlank()) {
                            errorText = "Please paste a public key"
                            return@Button
                        }
                        val info = StandalonePgpEngine.parsePeerKey(trimmed)
                        val dropId = info.dropId ?: throw IllegalArgumentException("Key has no Drop-ID")
                        val cleanAlias = GhostKeyUtils.sanitizeAlias(alias)
                        val finalAlias = cleanAlias.ifBlank { info.alias ?: "Peer ${info.fingerprint.take(6)}" }
                        onAddContact(
                            Contact(
                                id = dropId,
                                alias = finalAlias,
                                dropId = dropId,
                                publicKeyArmored = trimmed,
                                fingerprint = info.fingerprint
                            )
                        )
                    } catch (e: Exception) {
                        errorText = e.message ?: "Invalid OpenPGP public key"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText)
            ) {
                Text(strings.addContactBtn, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancelBtn, color = palette.textSecondary)
            }
        }
    )
}
