package com.ghostkey.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.ghostkey.app.ui.GhostKeyTheme
import com.ghostkey.app.ui.screens.LockedScreen
import com.ghostkey.app.ui.screens.MainModernApp
import com.ghostkey.app.ui.screens.WelcomeOnboardingScreen
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    companion object {
        var isExternalPickerActive = false
    }

    private val unlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                viewModel.onAppUnlocked()
            }
        }

    private val recordAudioLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                viewModel.startRecordingAudio()
            } else {
                Toast.makeText(this, "Microphone permission required for voice notes", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screenshots, screen recording, and app thumbnail preview in recents
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // Defer notification permission prompt if the user is still in onboarding (which has a dedicated step)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !viewModel.uiState.value.isOnboarding) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }

        setContent {
            val state by viewModel.uiState.collectAsState()
            GhostKeyTheme {
                when {
                    state.isOnboarding -> {
                        WelcomeOnboardingScreen(
                            onCompleteOnboarding = { alias, appPin, duressPin, isEphemeral ->
                                viewModel.completeOnboarding(alias, appPin, duressPin, isEphemeral)
                            }
                        )
                    }
                    state.isLocked -> {
                        LockedScreen(
                            onUnlock = { viewModel.onAppUnlocked() },
                            onDeviceUnlock = { requestDeviceUnlock() },
                            onVerifyPin = { pin ->
                                if (viewModel.isDuressPin(pin)) {
                                    triggerEmergencyPanicWipe()
                                    false
                                } else {
                                    viewModel.verifyPin(pin)
                                }
                            }
                        )
                    }
                    else -> {
                        MainModernApp(
                            viewModel = viewModel,
                            onMicClick = { requestMicAndRecord() },
                            onEmergencyPanic = { triggerEmergencyPanicWipe() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForeground()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPauseActivity()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onStopActivity()
    }

    private fun requestMicAndRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startRecordingAudio()
        } else {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestDeviceUnlock() {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent("GhostKey", "Unlock to open your saved identity")
        if (intent == null) {
            viewModel.onDeviceLockUnavailable()
        } else {
            unlockLauncher.launch(intent)
        }
    }

    private fun triggerEmergencyPanicWipe() {
        viewModel.executePanicWipe()
        finishAffinity()
        exitProcess(0)
    }
}
