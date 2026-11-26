package com.ghostkey.app.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.crypto.InPersonPairing
import com.ghostkey.app.net.mesh.MeshPermissions
import com.ghostkey.app.net.share.OfflineShareState
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.theme.GhostTheme

/**
 * Briar-style Offline APK Share dialog: spins up an HTTP download server over local Wi-Fi / Wi-Fi Direct
 * and displays a scannable QR code so nearby devices can install GhostKey without internet.
 */
@Composable
fun OfflineShareDialog(
    state: OfflineShareState,
    onStartSharing: (createWifiDirectGroup: Boolean) -> Unit,
    onStopSharing: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    val context = LocalContext.current
    var useWifiDirectGroup by remember { mutableStateOf(true) }

    val wifiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        onStartSharing(useWifiDirectGroup && grants.values.all { it })
    }

    val qrBitmap = remember(state.shareUrl) {
        state.shareUrl?.let { url ->
            runCatching { InPersonPairing.generateQrBitmap(url, 380).asImageBitmap() }.getOrNull()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Wifi, contentDescription = null, tint = palette.accent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.offlineShareTitle, color = palette.textPrimary, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.offlineShareSubtitle,
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )

                if (state.isSharing && qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Image(bitmap = qrBitmap, contentDescription = "APK Download QR", modifier = Modifier.size(200.dp))
                    }
                    state.shareUrl?.let { url ->
                        Text(
                            text = url,
                            color = palette.accent,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                        OutlinedButton(
                            onClick = {
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clip.setPrimaryClip(ClipData.newPlainText("GhostKey Share URL", url))
                                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(AppIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Link")
                        }
                    }
                    val sizeMb = String.format("%.1f MB", state.apkSizeBytes / (1024.0 * 1024.0))
                    Text(
                        text = "APK Size: $sizeMb · Downloads: ${state.downloadsServed}",
                        color = palette.textSecondary,
                        fontSize = 12.sp
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = useWifiDirectGroup,
                            onCheckedChange = { useWifiDirectGroup = it },
                            colors = CheckboxDefaults.colors(checkedColor = palette.accent)
                        )
                        Text(
                            text = "Create Wi-Fi Direct hotspot if offline",
                            color = palette.textPrimary,
                            fontSize = 13.sp
                        )
                    }
                    if (!state.error.isNullOrBlank()) {
                        Text(text = state.error, color = palette.error, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (state.isSharing) {
                Button(
                    onClick = onStopSharing,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.error, contentColor = Color.White)
                ) { Text("Stop Sharing") }
            } else {
                Button(
                    onClick = {
                        if (useWifiDirectGroup && !MeshPermissions.hasWifiDirectPermissions(context)) {
                            wifiPermLauncher.launch(MeshPermissions.requiredWifiDirectPermissions().toTypedArray())
                        } else {
                            onStartSharing(useWifiDirectGroup)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText)
                ) { Text("Start Server") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancelBtn, color = palette.textSecondary)
            }
        }
    )
}
