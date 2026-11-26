package com.ghostkey.app.ui.dialogs

import android.Manifest
import android.content.ClipData
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ghostkey.app.MainActivity
import com.ghostkey.app.crypto.ContactCard
import com.ghostkey.app.crypto.InPersonPairing
import com.ghostkey.app.data.Contact
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.theme.GhostTheme
import com.ghostkey.app.util.GhostKeyUtils
import java.util.concurrent.Executors

@Composable
fun MyQrCodeDialog(
    myAlias: String,
    myDropId: String,
    myFingerprint: String,
    myPublicKeyArmored: String,
    onDismiss: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
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
        runCatching { InPersonPairing.generateQrBitmap(contactCardJson, 400).asImageBitmap() }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(AppIcons.QrCode, contentDescription = null, tint = palette.accent)
                Text(strings.myQrButton, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                    "Scan this QR code in person to exchange keys and verify safety codes instantly.",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Image(bitmap = qrBitmap, contentDescription = "Contact QR", modifier = Modifier.size(220.dp))
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(myAlias.ifBlank { "Anonymous" }, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("ID: ${myDropId.take(16)}...", color = palette.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("FP: ${myFingerprint.take(20)}...", color = palette.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(
                    onClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("GhostKey Contact Card", contactCardJson))
                        Toast.makeText(context, "Contact Card copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(AppIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy Contact Card", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = palette.accent, fontWeight = FontWeight.Bold)
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
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    val context = LocalContext.current
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
        if (!granted) Toast.makeText(context, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    val text = bitmap?.let { InPersonPairing.decodeQrFromBitmap(it) }
                    if (text != null) {
                        val card = InPersonPairing.parseContactCard(text)
                        if (card != null) {
                            parsedCard = card
                            customAlias = card.alias
                            errorText = null
                        } else {
                            errorText = "Unrecognized QR format"
                        }
                    } else {
                        errorText = "No QR code found in image"
                    }
                }
            }.onFailure { errorText = it.message }
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
        containerColor = palette.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(AppIcons.Shield, contentDescription = null, tint = palette.accent)
                Text(
                    if (parsedCard == null) strings.scanQrPair else strings.safetyCodeAction,
                    color = palette.textPrimary,
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
                    if (hasCameraPermission) {
                        val lifecycleOwner = LocalLifecycleOwner.current
                        val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
                        var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

                        DisposableEffect(Unit) {
                            onDispose {
                                runCatching { boundProvider?.unbindAll() }
                                analysisExecutor.shutdownNow()
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    val previewView = PreviewView(ctx).apply {
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                    val future = ProcessCameraProvider.getInstance(ctx)
                                    future.addListener({
                                        val cameraProvider = future.get()
                                        boundProvider = cameraProvider
                                        val preview = Preview.Builder().build().also {
                                            it.setSurfaceProvider(previewView.surfaceProvider)
                                        }
                                        val analysis = ImageAnalysis.Builder()
                                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                            .build()
                                        analysis.setAnalyzer(analysisExecutor) { proxy ->
                                            val scanned = InPersonPairing.decodeQrFromImageProxy(proxy)
                                            proxy.close()
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
                                        runCatching {
                                            cameraProvider.unbindAll()
                                            cameraProvider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                analysis
                                            )
                                        }
                                    }, ContextCompat.getMainExecutor(ctx))
                                    previewView
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .size(170.dp)
                                    .border(2.dp, palette.connected, RoundedCornerShape(16.dp))
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(AppIcons.CameraAlt, contentDescription = null, tint = palette.accent, modifier = Modifier.size(34.dp))
                            Text("Camera Permission Needed", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                            Button(
                                onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText)
                            ) { Text("Grant Camera") }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            MainActivity.isExternalPickerActive = true
                            imagePickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(AppIcons.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Scan QR from Image", fontSize = 13.sp)
                    }

                    if (errorText != null) {
                        Text(errorText!!, color = palette.error, fontSize = 12.sp)
                    }
                } else {
                    val card = parsedCard!!
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = palette.connected, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Peer Detected", color = palette.connected, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        OutlinedTextField(
                            value = customAlias,
                            onValueChange = { customAlias = it },
                            label = { Text(strings.displayNameLabel) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text("Drop ID: ${card.dropId}", color = palette.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (sasCode != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.background, RoundedCornerShape(10.dp))
                                .border(1.dp, palette.connected.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Safety Code (Compare both screens)", color = palette.connected, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = sasCode.replace(" ", "  "),
                                color = palette.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
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
                        onContactVerified(
                            Contact(
                                id = card.dropId,
                                alias = customAlias.ifBlank { card.alias },
                                dropId = card.dropId,
                                publicKeyArmored = card.publicKeyArmored,
                                fingerprint = card.fingerprint,
                                isVerified = true
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.connected, contentColor = Color.White)
                ) { Text("Trust & Add", fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (parsedCard != null) parsedCard = null else onDismiss()
            }) {
                Text(if (parsedCard != null) strings.backBtn else strings.cancelBtn, color = palette.textSecondary)
            }
        }
    )
}
