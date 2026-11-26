package com.ghostkey.app.ui.screens.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.MainActivity
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.components.AvatarView
import com.ghostkey.app.ui.components.SettingsActionRow
import com.ghostkey.app.ui.components.SettingsDivider
import com.ghostkey.app.ui.components.SettingsSectionCard
import com.ghostkey.app.ui.theme.GhostTheme

/**
 * Third tab of the Navigation Island: Profile, Cryptographic Identity, QR Pairing, Offline Share & Panic Wipe.
 */
@Composable
fun ProfileScreen(
    userAlias: String,
    myAvatarBase64: String?,
    myPgpKeys: PgpKeyPairArmored?,
    onSaveAlias: (String) -> Unit,
    onPickAvatarUri: (Uri) -> Unit,
    onCopyDropId: () -> Unit,
    onCopyFingerprint: () -> Unit,
    onCopyPublicKey: () -> Unit,
    onShowMyQr: () -> Unit,
    onPairContactQr: () -> Unit,
    onOpenOfflineShare: () -> Unit,
    onEmergencyPanic: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings

    var editingAlias by remember(userAlias) { mutableStateOf(userAlias) }
    var isEditingName by remember { mutableStateOf(false) }
    var showPanicConfirm by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) onPickAvatarUri(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .padding(bottom = 104.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = strings.profileTitle,
                color = palette.textPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Box(
            modifier = Modifier.clickable {
                MainActivity.isExternalPickerActive = true
                avatarPicker.launch("image/*")
            }
        ) {
            AvatarView(
                title = userAlias.ifBlank { "G" },
                seed = myPgpKeys?.dropId ?: userAlias,
                avatarBase64 = myAvatarBase64,
                size = 90.dp
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(palette.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.CameraAlt,
                    contentDescription = strings.editAvatarHint,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = strings.editAvatarHint,
            color = palette.textSecondary,
            fontSize = 11.5.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (isEditingName) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                OutlinedTextField(
                    value = editingAlias,
                    onValueChange = { editingAlias = it },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        onSaveAlias(editingAlias)
                        isEditingName = false
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(palette.accent)
                ) {
                    Icon(Icons.Default.Check, contentDescription = strings.saveBtn, tint = Color.White)
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { isEditingName = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = userAlias.ifBlank { "Ghost" },
                    color = palette.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(Icons.Default.Edit, contentDescription = "Edit alias", tint = palette.textSecondary, modifier = Modifier.size(16.dp))
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        SettingsSectionCard(title = strings.fingerprintLabel.uppercase()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCopyDropId() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.dropIdLabel, color = palette.textSecondary, fontSize = 12.sp)
                    Text(
                        text = myPgpKeys?.dropId ?: "—",
                        color = palette.textPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
                Icon(AppIcons.ContentCopy, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
            }

            SettingsDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCopyFingerprint() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.fingerprintLabel, color = palette.textSecondary, fontSize = 12.sp)
                    Text(
                        text = myPgpKeys?.fingerprint?.chunked(4)?.joinToString(" ") ?: "—",
                        color = palette.textPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2
                    )
                }
                Icon(AppIcons.ContentCopy, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        SettingsSectionCard(title = strings.myQrButton.uppercase()) {
            SettingsActionRow(
                icon = AppIcons.QrCode,
                title = strings.myQrButton,
                onClick = onShowMyQr,
                iconTint = palette.accent
            )
            SettingsDivider()
            SettingsActionRow(
                icon = AppIcons.QrCodeScanner,
                title = strings.scanQrButton,
                onClick = onPairContactQr,
                iconTint = palette.connected
            )
            SettingsDivider()
            SettingsActionRow(
                icon = AppIcons.Key,
                title = strings.copyPublicKeyButton,
                onClick = onCopyPublicKey
            )
            SettingsDivider()
            SettingsActionRow(
                icon = AppIcons.Wifi,
                title = strings.offlineApkShareButton,
                subtitle = strings.offlineShareSubtitle,
                onClick = onOpenOfflineShare,
                iconTint = palette.accent
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        Button(
            onClick = { showPanicConfirm = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.error.copy(alpha = 0.14f),
                contentColor = palette.error
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(strings.panicWipeButton, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }

    if (showPanicConfirm) {
        AlertDialog(
            onDismissRequest = { showPanicConfirm = false },
            containerColor = palette.surface,
            title = { Text(strings.panicWipeConfirmTitle, color = palette.error, fontWeight = FontWeight.Bold) },
            text = { Text(strings.panicWipeConfirmBody, color = palette.textPrimary, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showPanicConfirm = false
                        onEmergencyPanic()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.error, contentColor = Color.White)
                ) {
                    Text(strings.panicWipeButton, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicConfirm = false }) {
                    Text(strings.cancelBtn, color = palette.textSecondary)
                }
            }
        )
    }
}
