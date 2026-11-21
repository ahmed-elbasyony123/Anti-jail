package com.ghostkey.app.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.data.Contact
import com.ghostkey.app.ui.GhostKeyColors

/**
 * Contact Introduction Dialog:
 *
 * Allows a mutual friend to introduce two contacts to each other over encrypted channels,
 * sharing their public keys and drop IDs without needing an external directory.
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
                imageVector = Icons.Default.Handshake,
                contentDescription = null,
                tint = GhostKeyColors.AccentGold,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Introduce a Contact",
                style = MaterialTheme.typography.titleMedium,
                color = GhostKeyColors.TextPrimary
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Select a contact to introduce to $currentContactAlias. Both parties will receive each other's verified public keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GhostKeyColors.TextSecondary,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (availableContacts.isEmpty()) {
                    Text(
                        text = "No other contacts available to introduce.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GhostKeyColors.TextSecondary
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
                                color = if (isSelected) GhostKeyColors.SurfaceSecondary else GhostKeyColors.SurfacePrimary,
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
                                        tint = if (isSelected) GhostKeyColors.AccentGold else GhostKeyColors.TextSecondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = contact.alias,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = GhostKeyColors.TextPrimary
                                        )
                                        Text(
                                            text = "FP: ${contact.fingerprint.take(12)}...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = GhostKeyColors.TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                    if (contact.isVerified) {
                                        Icon(
                                            imageVector = Icons.Default.Shield,
                                            contentDescription = "Verified",
                                            tint = GhostKeyColors.StatusGreen,
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
                onClick = {
                    selectedContact?.let { onIntroduce(it) }
                },
                enabled = selectedContact != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GhostKeyColors.AccentGold,
                    contentColor = GhostKeyColors.SurfacePrimary
                )
            ) {
                Text("Introduce")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = GhostKeyColors.TextSecondary)
            }
        },
        containerColor = GhostKeyColors.SurfacePrimary
    )
}
