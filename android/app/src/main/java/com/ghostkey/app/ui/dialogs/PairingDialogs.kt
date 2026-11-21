package com.ghostkey.app.ui.dialogs

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ghostkey.app.crypto.ContactCard
import com.ghostkey.app.crypto.InPersonPairing
import com.ghostkey.app.data.Contact
import com.ghostkey.app.ui.*
import java.util.concurrent.Executors

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
        InPersonPairing.encodeContactCard(
            alias = myAlias.ifBlank { "GhostKey User" },
            dropId = myDropId,
            fingerprint = myFingerprint,
            publicKeyArmored = myPublicKeyArmored
        )
    }

    val qrBitmap = remember(contactCardJson) {
        try {
            InPersonPairing.generateQrBitmap(contactCardJson, 400).asImageBitmap()
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
                    "My Contact QR Card",
                    color = AnthropicCream,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Ask your friend to scan this QR code with their camera to pair instantly and verify safety codes.",
                    color = AnthropicTextMid,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )

                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(12.dp))
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
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Live Camera, 1 = Manual/Image
    var inputText by remember { mutableStateOf("") }
    var parsedCard by remember { mutableStateOf<ContactCard?>(null) }
    var customAlias by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            Toast.makeText(context, "Camera permission is needed for live QR scanning", Toast.LENGTH_SHORT).show()
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    val text = InPersonPairing.decodeQrFromBitmap(bitmap)
                    if (text != null) {
                        inputText = text
                        val card = InPersonPairing.parseContactCard(text)
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
            InPersonPairing.calculateSas(myFingerprint, card.fingerprint)
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
                    if (parsedCard == null) "In-Person Contact Pairing" else "Verify Safety Code (SAS)",
                    color = AnthropicCream,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (parsedCard == null) {
                    // Mode Tabs: Live Camera vs Manual/Paste
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = AnthropicSurfaceVariant,
                        contentColor = AnthropicTerracotta,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Live Camera", fontSize = 12.sp, color = if (selectedTab == 0) AnthropicTerracotta else AnthropicTextMuted) },
                            icon = { Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Paste / Image", fontSize = 12.sp, color = if (selectedTab == 1) AnthropicTerracotta else AnthropicTextMuted) },
                            icon = { Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }

                    if (selectedTab == 0) {
                        // Live Camera Scanner View
                        if (hasCameraPermission) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black)
                                    .border(2.dp, AnthropicTerracotta.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                val lifecycleOwner = LocalLifecycleOwner.current
                                AndroidView(
                                    factory = { ctx ->
                                        val previewView = PreviewView(ctx).apply {
                                            layoutParams = ViewGroup.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        }
                                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                        cameraProviderFuture.addListener({
                                            val cameraProvider = cameraProviderFuture.get()
                                            val preview = Preview.Builder().build().also {
                                                it.setSurfaceProvider(previewView.surfaceProvider)
                                            }
                                            val imageAnalysis = ImageAnalysis.Builder()
                                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                                .build()

                                            val executor = Executors.newSingleThreadExecutor()
                                            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                                val scanned = InPersonPairing.decodeQrFromImageProxy(imageProxy)
                                                imageProxy.close()
                                                if (scanned != null && parsedCard == null) {
                                                    val card = InPersonPairing.parseContactCard(scanned)
                                                    if (card != null) {
                                                        ContextCompat.getMainExecutor(ctx).execute {
                                                            parsedCard = card
                                                            customAlias = card.alias
                                                            errorText = null
                                                        }
                                                    }
                                                }
                                            }

                                            try {
                                                cameraProvider.unbindAll()
                                                cameraProvider.bindToLifecycle(
                                                    lifecycleOwner,
                                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                                    preview,
                                                    imageAnalysis
                                                )
                                            } catch (_: Exception) {}
                                        }, ContextCompat.getMainExecutor(ctx))
                                        previewView
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Scanning Target Reticle Overlay
                                Box(
                                    modifier = Modifier
                                        .size(180.dp)
                                        .border(2.dp, AnthropicSage, RoundedCornerShape(16.dp))
                                )
                            }
                            Text(
                                "Point camera at your friend's GhostKey QR code",
                                color = AnthropicTextMid,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            // Permission Request Card
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AnthropicSurfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = AnthropicTerracotta, modifier = Modifier.size(36.dp))
                                Text(
                                    "Camera Access Required",
                                    color = AnthropicCream,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "To scan in-person contact QR codes directly, allow GhostKey to use your camera.",
                                    color = AnthropicTextMid,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                    colors = ButtonDefaults.buttonColors(containerColor = AnthropicTerracotta, contentColor = AnthropicDarkBg)
                                ) {
                                    Text("Grant Camera Permission", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        // Manual / Paste / Gallery Image
                        Text(
                            "Paste your friend's Contact Card JSON or OpenPGP Public Key Block:",
                            color = AnthropicTextMid,
                            fontSize = 12.sp
                        )

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = {
                                inputText = it
                                errorText = null
                                val card = InPersonPairing.parseContactCard(it)
                                if (card != null) {
                                    parsedCard = card
                                    customAlias = card.alias
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            placeholder = { Text("Paste contact JSON or PGP key here...", color = AnthropicTextMuted, fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AnthropicTerracotta,
                                unfocusedBorderColor = AnthropicBorder,
                                focusedTextColor = AnthropicCream,
                                unfocusedTextColor = AnthropicCream,
                                focusedContainerColor = AnthropicDarkNeutral,
                                unfocusedContainerColor = AnthropicDarkNeutral
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val item = clip.primaryClip?.getItemAt(0)?.text?.toString()
                                    if (!item.isNullOrBlank()) {
                                        inputText = item
                                        val card = InPersonPairing.parseContactCard(item)
                                        if (card != null) {
                                            parsedCard = card
                                            customAlias = card.alias
                                            errorText = null
                                        } else {
                                            errorText = "Clipboard data is not a valid contact card or PGP key."
                                        }
                                    } else {
                                        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AnthropicCream)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Paste", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AnthropicTerracotta)
                            ) {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("From Image", fontSize = 12.sp)
                            }
                        }
                    }

                    if (errorText != null) {
                        Text(
                            text = errorText ?: "",
                            color = AnthropicCrimson,
                            fontSize = 11.sp
                        )
                    }
                } else {
                    // Contact Parsed — Show Verification Card & Safety Code (SAS)
                    val card = parsedCard!!
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AnthropicSurfaceVariant, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AnthropicSage, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Peer Identity Detected", color = AnthropicSage, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        OutlinedTextField(
                            value = customAlias,
                            onValueChange = { customAlias = it },
                            label = { Text("Contact Nickname", color = AnthropicTextMid, fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AnthropicTerracotta,
                                unfocusedBorderColor = AnthropicBorder,
                                focusedTextColor = AnthropicCream,
                                unfocusedTextColor = AnthropicCream
                            )
                        )

                        Text(
                            "Drop ID: ${card.dropId}",
                            color = AnthropicTextMid,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        Text(
                            "Fingerprint: ${card.fingerprint}",
                            color = AnthropicTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // In-Person Safety Code (SAS)
                    if (sasCode != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AnthropicDarkBg, RoundedCornerShape(8.dp))
                                .border(1.dp, AnthropicSage.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "Safety Code (Compare on both screens):",
                                color = AnthropicSage,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            // Render 30 digits in groups of 5 for effortless human comparison
                            val chunks = sasCode.chunked(5).joinToString("  ")
                            Text(
                                text = chunks,
                                color = AnthropicCream,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                "If the 30 digits match on both phones, your link is cryptographically immune to man-in-the-middle attacks.",
                                color = AnthropicTextMuted,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center
                            )
                        }
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
                            isVerified = true
                        )
                        onContactVerified(contact)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AnthropicSage,
                        contentColor = AnthropicDarkBg
                    )
                ) {
                    Text("Trust & Add Contact", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (parsedCard != null) {
                    parsedCard = null
                } else {
                    onDismiss()
                }
            }) {
                Text(if (parsedCard != null) "Back" else "Cancel", color = AnthropicTextMuted)
            }
        }
    )
}
