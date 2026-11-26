package com.ghostkey.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.crypto.GroupCrypto
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.data.Contact
import com.ghostkey.app.data.GroupChat
import com.ghostkey.app.data.GroupMember
import com.ghostkey.app.ui.theme.GhostTheme
import java.util.UUID

@Composable
fun CreateGroupDialog(
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onCreateGroup: (GroupChat) -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    var groupName by remember { mutableStateOf("") }
    val selectedDropIds = remember { mutableStateListOf<String>() }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = strings.newEncryptedGroup,
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
                    "Group messages use a random AES-256 session key shared over Tor.",
                    fontSize = 12.sp,
                    color = palette.textSecondary,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it; errorText = null },
                    label = { Text("Group Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (contacts.isNotEmpty()) {
                    Text(
                        "Members",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )

                    contacts.forEach { contact ->
                        val isSelected = selectedDropIds.contains(contact.dropId)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) palette.selectionHighlight else palette.surfaceVariant)
                                .clickable {
                                    if (isSelected) selectedDropIds.remove(contact.dropId)
                                    else selectedDropIds.add(contact.dropId)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    if (checked) selectedDropIds.add(contact.dropId)
                                    else selectedDropIds.remove(contact.dropId)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = palette.accent)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(contact.alias, color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "ID: ${contact.dropId.take(10)}...",
                                    color = palette.textSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                } else {
                    Text("No contacts available yet.", fontSize = 12.sp, color = palette.textSecondary)
                }

                if (errorText != null) {
                    Text(errorText!!, color = palette.error, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
                    val groupReadToken = StandalonePgpEngine.randomHex32()
                    val groupDropId = StandalonePgpEngine.deriveDropId(groupReadToken)
                    val groupKey = GroupCrypto.generateGroupKey()
                    val members = contacts.filter { selectedDropIds.contains(it.dropId) }.map {
                        GroupMember(it.dropId, it.alias, it.publicKeyArmored, it.fingerprint)
                    }
                    onCreateGroup(
                        GroupChat(
                            id = groupId,
                            name = groupName.trim(),
                            groupDropId = groupDropId,
                            groupReadToken = groupReadToken,
                            sharedKeyBase64 = groupKey,
                            members = members,
                            isAdmin = true
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText)
            ) {
                Text("Create", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancelBtn, color = palette.textSecondary)
            }
        }
    )
}
