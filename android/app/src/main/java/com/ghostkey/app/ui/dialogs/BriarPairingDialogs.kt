package com.ghostkey.app.ui.dialogs

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.crypto.BriarPairing
import com.ghostkey.app.crypto.ContactCard
import com.ghostkey.app.data.Contact
import com.ghostkey.app.ui.*

@Composable
fun MyQrCodeDialog(
    myAlias: String,
    myDropId: String,
    myFingerprint: String,
    myPublicKeyArmored: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val contactCardJson = remember(myAlias, myDropId, myFingerprint, myPublicKeyArmored) {
        BriarPairing.encodeContactCard(
            alias = myAlias.ifBlank { "GhostKey User" },
            dropId = myDropId,
            fingerprint = myFingerprint,
            publicKeyArmored = myPublicKeyArmored
        )
    }

    val qrBitmap = remember(contactCardJson) {
        try {
            BriarPairing.generateQrBitmap(contactCardJson, 400).asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AnthropicSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.QrCode, contentDescription = null, tint = AnthropicTerracotta)
                Text(
                    "My Contact QR Code",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = AnthropicCream
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Have your peer scan this QR code or import it to pair in-person with end-to-end cryptographic verification.",
                    fontSize = 12.sp,
                    color = AnthropicTextMuted,
                    textAlign = TextAlign.Center
                )

                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "Contact QR Code",
                            modifier = Modifier.size(220.dp)
                        )
                    }
                }

                // Details Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AnthropicSurfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Alias: ${myAlias.ifBlank { "Anonymous" }}",
                        color = AnthropicCream,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Drop ID: ${myDropId.take(16)}...",
                        color = AnthropicTextMid,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "Fingerprint: ${myFingerprint.take(20)}...",
                        color = AnthropicTextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                OutlinedButton(
                    onClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(android.content.ClipData.newPlainText("GhostKey Contact Card", contactCardJson))
                        Toast.makeText(context, "Contact Card JSON copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AnthropicTerracotta)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy Contact Card Data", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AnthropicTerracotta, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun PairContactDialog(
    myFingerprint: String,
    onDismiss: () -> Unit,
    onContactVerified: (Contact) -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var parsedCard by remember { mutableStateOf<ContactCard?>(null) }
    var customAlias by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    val text = BriarPairing.decodeQrFromBitmap(bitmap)
                    if (text != null) {
                        inputText = text
                        val card = BriarPairing.parseContactCard(text)
                        if (card != null) {
                            parsedCard = card
                            customAlias = card.alias
                            errorText = null
                        } else {
                            errorText = "QR decoded but not a recognized GhostKey contact format."
                        }
                    } else {
                        errorText = "No QR code could be detected in this image."
                    }
                }
            } catch (e: Exception) {
                errorText = "Failed to read image: ${e.message}"
            }
        }
    }

    val sasCode = remember(parsedCard, myFingerprint) {
        val card = parsedCard
        if (card != null && card.fingerprint.isNotBlank() && myFingerprint.isNotBlank()) {
            BriarPairing.calculateSas(myFingerprint, card.fingerprint)
        } else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AnthropicSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Security, contentDescription = null, tint = AnthropicTerracotta)
                Text(
                    if (parsedCard == null) "Pair Contact (Briar-Style)" else "Verify Safety Code (SAS)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = AnthropicCream
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (parsedCard == null) {
                    Text(
                        "Scan or import your peer's QR code or paste their Contact Card to verify identity and establish anti-MITM protection.",
                        fontSize = 12.sp,
                        color = AnthropicTextMuted,
                        lineHeight = 16.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AnthropicSlate)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan Image", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val text = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    if (text.isNotBlank()) {
                                        inputText = text
                                        val card = BriarPairing.parseContactCard(text)
                                        if (card != null) {
                                            parsedCard = card
                                            customAlias = card.alias
                                            errorText = null
                                        } else {
                                            errorText = "Clipboard data is not a valid contact card or PGP key."
                                        }
                                    }
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AnthropicTerracotta)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Paste Clip", fontSize = 11.sp)
                        }
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = {
                            inputText = it
                            errorText = null
                            val card = BriarPairing.parseContactCard(it)
                            if (card != null) {
                                parsedCard = card
                                customAlias = card.alias
                            }
                        },
                        label = { Text("Contact Card JSON or PGP Key") },
                        placeholder = { Text("{\"v\":1,\"type\":\"ghostkey_contact\"...}") },
                        minLines = 3,
                        maxLines = 5,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AnthropicTerracotta,
                            unfocusedBorderColor = AnthropicBorder,
                            focusedTextColor = AnthropicCream,
                            unfocusedTextColor = AnthropicCream
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorText != null) {
                        Text(errorText!!, color = AnthropicCrimson, fontSize = 11.sp)
                    }
                } else {
                    // SAS Verification Screen
                    val card = parsedCard!!

                    OutlinedTextField(
                        value = customAlias,
                        onValueChange = { customAlias = it },
                        label = { Text("Peer Nickname") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AnthropicTerracotta,
                            unfocusedBorderColor = AnthropicBorder,
                            focusedTextColor = AnthropicCream,
                            unfocusedTextColor = AnthropicCream
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // SAS Display Box
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AnthropicSurfaceVariant, RoundedCornerShape(10.dp))
                            .border(1.dp, AnthropicSage.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "IN-PERSON SAFETY CODE (SAS)",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = AnthropicSage
                        )

                        Text(
                            sasCode ?: "------",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            letterSpacing = 1.sp,
                            color = AnthropicCream,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            "Compare this 30-digit safety code on both screens. If it matches identically on both devices, your connection is authenticated against any Man-in-the-Middle (MITM) eavesdropping.",
                            fontSize = 11.sp,
                            color = AnthropicTextMuted,
                            textAlign = TextAlign.Center,
                            lineHeight = 15.sp
                        )
                    }

                    // Peer details
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AnthropicSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            "Drop ID: ${card.dropId}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AnthropicTextMid
                        )
                        Text(
                            "Fingerprint: ${card.fingerprint}",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AnthropicTextMuted
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (parsedCard != null) {
                Button(
                    onClick = {
                        val card = parsedCard!!
                        val contact = Contact(
                            id = card.dropId,
                            alias = customAlias.ifBlank { card.alias },
                            dropId = card.dropId,
                            publicKeyArmored = card.publicKeyArmored,
                            fingerprint = card.fingerprint,
                            isVerified = true,
                            safetyCode = sasCode
                        )
                        onContactVerified(contact)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AnthropicSage)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Confirm & Verify", color = AnthropicCream, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = AnthropicTextMuted)
                }
            }
        },
        dismissButton = {
            if (parsedCard != null) {
                TextButton(
                    onClick = {
                        val card = parsedCard!!
                        val contact = Contact(
                            id = card.dropId,
                            alias = customAlias.ifBlank { card.alias },
                            dropId = card.dropId,
                            publicKeyArmored = card.publicKeyArmored,
                            fingerprint = card.fingerprint,
                            isVerified = false,
                            safetyCode = null
                        )
                        onContactVerified(contact)
                    }
                ) {
                    Text("Save Unverified", color = AnthropicTextMuted, fontSize = 12.sp)
                }
            }
        }
    )
}
