package com.ghostkey.app.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.ui.NeonEmerald
import com.ghostkey.app.ui.StealthSurface
import com.ghostkey.app.ui.StealthTextPrimary
import com.ghostkey.app.ui.StealthTextSecondary

@Composable
fun SettingsDialog(
    initialRelayUrl: String,
    initialUseTorSocks: Boolean,
    initialKeepAlive: Boolean,
    initialEphemeralMode: Boolean,
    initialLockEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (relayUrl: String, useTorSocks: Boolean, keepAlive: Boolean, ephemeralMode: Boolean, lockEnabled: Boolean) -> Unit
) {
    var relayUrl by remember { mutableStateOf(initialRelayUrl) }
    var useTorSocks by remember { mutableStateOf(initialUseTorSocks) }
    var keepAlive by remember { mutableStateOf(initialKeepAlive) }
    var ephemeralMode by remember { mutableStateOf(initialEphemeralMode) }
    var lockEnabled by remember { mutableStateOf(initialLockEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StealthSurface,
        title = {
            Text(
                "SECURITY & TOR ROUTING",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = NeonEmerald,
                fontSize = 16.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = relayUrl,
                    onValueChange = {
                        relayUrl = it.trim()
                        if (relayUrl.contains(".onion", ignoreCase = true)) {
                            useTorSocks = true
                        }
                    },
                    label = { Text("Relay (.onion or IP:Port)") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = useTorSocks,
                        onCheckedChange = { useTorSocks = it },
                        colors = CheckboxDefaults.colors(checkedColor = NeonEmerald)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Route via Tor (Built-in Arti Rust engine; Orbot is optional)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = StealthTextPrimary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = keepAlive,
                        onCheckedChange = { keepAlive = it },
                        colors = CheckboxDefaults.colors(checkedColor = NeonEmerald)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Keep connection alive in background (shows a notification)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = StealthTextPrimary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = ephemeralMode,
                        onCheckedChange = { ephemeralMode = it },
                        colors = CheckboxDefaults.colors(checkedColor = NeonEmerald)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Ephemeral mode (RAM only): the identity is not saved and a new one is created at every launch",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = StealthTextPrimary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = lockEnabled,
                        onCheckedChange = { lockEnabled = it },
                        colors = CheckboxDefaults.colors(checkedColor = NeonEmerald)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Require the device screen lock (PIN, pattern, password) to open the app",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = StealthTextPrimary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(relayUrl, useTorSocks, keepAlive, ephemeralMode, lockEnabled)
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
            ) {
                Text("SAVE CONFIG", color = Color(0xFF002914), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = StealthTextSecondary)
            }
        }
    )
}
