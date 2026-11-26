package com.ghostkey.app.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.data.Contact
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.theme.GhostTheme

@Composable
fun IntroduceContactDialog(
    currentContactAlias: String,
    availableContacts: List<Contact>,
    onDismiss: () -> Unit,
    onIntroduce: (introducedContact: Contact) -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    var selectedContact by remember { mutableStateOf<Contact?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        icon = { Icon(AppIcons.Handshake, contentDescription = null, tint = palette.accent, modifier = Modifier.size(30.dp)) },
        title = { Text(strings.introduceContactAction, color = palette.textPrimary, fontSize = 18.sp) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Select a contact to introduce to $currentContactAlias.",
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                if (availableContacts.isEmpty()) {
                    Text("No other contacts available.", color = palette.textSecondary, fontSize = 13.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        items(availableContacts, key = { it.id }) { contact ->
                            val isSelected = selectedContact?.id == contact.id
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) palette.selectionHighlight else palette.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { selectedContact = contact }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = if (isSelected) palette.accent else palette.textSecondary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(contact.alias, color = palette.textPrimary, fontSize = 14.sp)
                                        Text("FP: ${contact.fingerprint.take(12)}...", color = palette.textSecondary, fontSize = 11.sp)
                                    }
                                    if (contact.isVerified) {
                                        Icon(AppIcons.Shield, contentDescription = null, tint = palette.connected, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedContact?.let(onIntroduce) },
                enabled = selectedContact != null,
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText)
            ) { Text("Introduce") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancelBtn, color = palette.textSecondary) }
        }
    )
}
