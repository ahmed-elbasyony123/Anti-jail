package com.ghostkey.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.ui.*

@Composable
fun KeyBar(
    myPgpKeys: PgpKeyPairArmored?,
    recipientArmoredKey: String,
    recipientFingerprint: String,
    onOpenMyKey: () -> Unit,
    onCopyMyKey: () -> Unit,
    onNewKey: () -> Unit,
    onHideKeyBar: () -> Unit,
    onOpenPeerKey: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StealthSurfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // My Key Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onOpenMyKey() }
            ) {
                Icon(Icons.Default.VpnKey, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (myPgpKeys != null) "MY KEY: ${myPgpKeys.fingerprint.take(12)}..." else "NO KEY IN RAM",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (myPgpKeys != null) NeonEmerald else AlertCrimson
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onCopyMyKey,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("COPY KEY", fontSize = 10.sp, color = NeonEmerald, fontFamily = FontFamily.Monospace)
                }
                TextButton(
                    onClick = onNewKey,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("NEW KEY", fontSize = 10.sp, color = StealthTextSecondary, fontFamily = FontFamily.Monospace)
                }
                if (recipientArmoredKey.isNotBlank()) {
                    TextButton(
                        onClick = onHideKeyBar,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("HIDE", fontSize = 10.sp, color = StealthTextSecondary, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Recipient Key Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (recipientArmoredKey.isBlank()) AlertCrimson.copy(alpha = 0.15f) else NeonEmerald.copy(alpha = 0.1f))
                .border(
                    1.dp,
                    if (recipientArmoredKey.isBlank()) AlertCrimson.copy(alpha = 0.4f) else NeonEmerald.copy(alpha = 0.4f),
                    RoundedCornerShape(4.dp)
                )
                .clickable { onOpenPeerKey() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (recipientArmoredKey.isBlank()) Icons.Default.AddLink else Icons.Default.Person,
                    contentDescription = null,
                    tint = if (recipientArmoredKey.isBlank()) AlertCrimson else NeonEmerald,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (recipientArmoredKey.isNotBlank()) "PEER: ${recipientFingerprint.take(12)}..." else "PASTE PEER'S PUBLIC KEY TO CHAT",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (recipientArmoredKey.isNotBlank()) NeonEmerald else AlertCrimson
                )
            }

            Text(
                text = if (recipientArmoredKey.isNotBlank()) "EDIT" else "PASTE",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (recipientArmoredKey.isNotBlank()) NeonEmerald else AlertCrimson
            )
        }
    }
}
