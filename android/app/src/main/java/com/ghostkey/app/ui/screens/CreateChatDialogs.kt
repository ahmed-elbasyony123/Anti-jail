package com.ghostkey.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.crypto.GroupCrypto
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.data.Contact
import com.ghostkey.app.data.GroupChat
import com.ghostkey.app.data.GroupMember
import com.ghostkey.app.ui.*
import java.util.UUID

@Composable
fun AddDirectChatDialog(
    onDismiss: () -> Unit,
    onAddContact: (Contact) -> Unit
) {
    val context = LocalContext.current
    var armoredKey by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun pasteFromClipboard() {
        try {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (text.isNotBlank()) armoredKey = text
        } catch (_: Exception) {}
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AnthropicSurface,
        title = {
            Text(
                "New Direct E2EE Chat",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = AnthropicCream
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
                    "Paste the peer's OpenPGP Public Key. The Drop ID and fingerprint are cryptographically verified automatically.",
                    fontSize = 12.sp,
                    color = AnthropicTextMuted,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Peer Nickname (Optional)") },
                    placeholder = { Text("e.g. Alice, Bob") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AnthropicTerracotta,
                        unfocusedBorderColor = AnthropicBorder,
                        focusedTextColor = AnthropicCream,
                        unfocusedTextColor = AnthropicCream
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = armoredKey,
                    onValueChange = {
                        armoredKey = it
                        errorText = null
                    },
                    label = { Text("PGP Public Key Block") },
                    placeholder = { Text("-----BEGIN PGP PUBLIC KEY BLOCK-----...") },
                    minLines = 4,
                    maxLines = 6,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AnthropicTerracotta,
                        unfocusedBorderColor = AnthropicBorder,
                        focusedTextColor = AnthropicCream,
                        unfocusedTextColor = AnthropicCream
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { pasteFromClipboard() },
                    colors = ButtonDefaults.buttonColors(containerColor = AnthropicSurfaceVariant),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, tint = AnthropicTerracotta, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Paste From Clipboard", color = AnthropicCream, fontSize = 12.sp)
                }

                if (errorText != null) {
                    Text(
                        text = errorText ?: "",
                        color = AnthropicCrimson,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
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
                        val finalAlias = alias.ifBlank { info.alias ?: "Peer ${info.fingerprint.take(6)}" }
                        val contact = Contact(
                            id = dropId,
                            alias = finalAlias,
                            dropId = dropId,
                            publicKeyArmored = trimmed,
                            fingerprint = info.fingerprint
                        )
                        onAddContact(contact)
                    } catch (e: Exception) {
                        errorText = e.message ?: "Invalid OpenPGP public key"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AnthropicTerracotta)
            ) {
                Text("Start Chat", color = AnthropicCream, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AnthropicTextMuted)
            }
        }
    )
}

@Composable
fun CreateGroupDialog(
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onCreateGroup: (GroupChat) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    val selectedDropIds = remember { mutableStateListOf<String>() }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AnthropicSurface,
        title = {
            Text(
                "Create Encrypted Group",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = AnthropicCream
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
                    "Messages in the group are encrypted with a random AES-256 session key and broadcasted over a dedicated group mailbox.",
                    fontSize = 12.sp,
                    color = AnthropicTextMuted,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Group Name") },
                    placeholder = { Text("e.g. Project Anonymous, Friends") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AnthropicTerracotta,
                        unfocusedBorderColor = AnthropicBorder,
                        focusedTextColor = AnthropicCream,
                        unfocusedTextColor = AnthropicCream
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (contacts.isNotEmpty()) {
                    Text(
                        "Select Members:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AnthropicCream
                    )

                    contacts.forEach { contact ->
                        val isSelected = selectedDropIds.contains(contact.dropId)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) AnthropicTerracotta.copy(alpha = 0.15f) else AnthropicSurfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    if (checked) selectedDropIds.add(contact.dropId)
                                    else selectedDropIds.remove(contact.dropId)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = AnthropicTerracotta)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(contact.alias, color = AnthropicCream, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("Drop ID: ${contact.dropId.take(10)}...", color = AnthropicTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                } else {
                    Text(
                        "No contacts available yet. You can invite members later.",
                        fontSize = 12.sp,
                        color = AnthropicTextMuted
                    )
                }

                if (errorText != null) {
                    Text(
                        text = errorText ?: "",
                        color = AnthropicCrimson,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (groupName.isBlank()) {
                        errorText = "Please enter a group name"
                        return@Button
                    }
                    val groupId = UUID.randomUUID().toString()
                    val groupDropKeypair = StandalonePgpEngine.generateKeyPair()
                    val groupKey = GroupCrypto.generateGroupKey()

                    val members = contacts.filter { selectedDropIds.contains(it.dropId) }.map {
                        GroupMember(
                            dropId = it.dropId,
                            alias = it.alias,
                            publicKeyArmored = it.publicKeyArmored,
                            fingerprint = it.fingerprint
                        )
                    }

                    val group = GroupChat(
                        id = groupId,
                        name = groupName.trim(),
                        groupDropId = groupDropKeypair.dropId,
                        groupReadToken = groupDropKeypair.readToken,
                        sharedKeyBase64 = groupKey,
                        members = members,
                        isAdmin = true
                    )
                    onCreateGroup(group)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AnthropicTerracotta)
            ) {
                Text("Create Group", color = AnthropicCream, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AnthropicTextMuted)
            }
        }
    )
}
