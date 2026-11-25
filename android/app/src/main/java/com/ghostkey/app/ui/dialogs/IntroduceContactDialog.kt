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
import com.ghostkey.app.ui.*

/**
 * Contact Introduction Dialog:
 * Allows a mutual friend to introduce two contacts over encrypted channels.
 */
@Composable
fun IntroduceContactDialog(
    currentContactAlias: String,
    availableContacts: List<Contact>,
    onDismiss: () -> Unit,
    onIntroduce: (introducedContact: Contact) -> Unit
) {
    var selectedContact by remember { mutableStateOf<Contact?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = AppIcons.Handshake,
                contentDescription = null,
                tint = AnthropicTerracotta,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Introduce a Contact",
                style = MaterialTheme.typography.titleMedium,
                color = AnthropicCream
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Select a contact to introduce to $currentContactAlias. Both parties will receive each other's verified public keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AnthropicTextMuted,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (availableContacts.isEmpty()) {
                    Text(
                        text = "No other contacts available to introduce.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AnthropicTextMuted
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        items(availableContacts, key = { it.id }) { contact ->
                            val isSelected = selectedContact?.id == contact.id
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AnthropicSurfaceVariant else AnthropicSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { selectedContact = contact }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = if (isSelected) AnthropicTerracotta else AnthropicTextMuted,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = contact.alias,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AnthropicCream
                                        )
                                        Text(
                                            text = "FP: ${contact.fingerprint.take(12)}...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AnthropicTextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                    if (contact.isVerified) {
                                        Icon(
                                            imageVector = AppIcons.Shield,
                                            contentDescription = "Verified",
                                            tint = AnthropicSage,
                                            modifier = Modifier.size(16.dp)
                                        )
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
                onClick = { selectedContact?.let { onIntroduce(it) } },
                enabled = selectedContact != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AnthropicTerracotta,
                    contentColor = AnthropicSurface
                )
            ) {
                Text("Introduce")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AnthropicTextMuted)
            }
        },
        containerColor = AnthropicSurface
    )
}
