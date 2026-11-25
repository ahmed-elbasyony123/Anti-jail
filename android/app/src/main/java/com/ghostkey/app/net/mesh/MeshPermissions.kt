package com.ghostkey.app.net.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object MeshPermissions {

    fun requiredBlePermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        else -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    fun requiredWifiDirectPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
        else -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    fun requiredAllMeshPermissions(): List<String> =
        (requiredBlePermissions() + requiredWifiDirectPermissions()).distinct()

    fun hasBlePermissions(context: Context): Boolean =
        requiredBlePermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun hasWifiDirectPermissions(context: Context): Boolean =
        requiredWifiDirectPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
