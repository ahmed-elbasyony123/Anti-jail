package com.ghostkey.app.net

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed class ArtiTorState {
    object Idle : ArtiTorState()
    data class Starting(val progress: Int, val socksPort: Int = 0) : ArtiTorState()
    data class Connected(val socksPort: Int) : ArtiTorState()
    data class Error(val message: String) : ArtiTorState()
}

/**
 * Manages the embedded Arti Tor client (The Tor Project official Rust engine).
 * Runs completely in-process; eliminates dependency on external apps like Orbot.
 */
object ArtiTorManager {

    private val _state = MutableStateFlow<ArtiTorState>(ArtiTorState.Idle)
    val state: StateFlow<ArtiTorState> = _state.asStateFlow()

    private var activePort: Int = 0
    private var progressJob: Job? = null
    private var isLibraryLoaded = false

    init {
        try {
            System.loadLibrary("arti_mobile")
            isLibraryLoaded = true
        } catch (_: UnsatisfiedLinkError) {
            // Graceful fallback to external Orbot / Tor proxy if native binary is absent
            isLibraryLoaded = false
        }
    }

    val isAvailable: Boolean get() = isLibraryLoaded

    fun start(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)): Boolean {
        if (!isLibraryLoaded) {
            _state.value = ArtiTorState.Error("Arti native library (libarti_mobile.so) not present")
            return false
        }

        if (activePort > 0) {
            _state.value = ArtiTorState.Connected(activePort)
            return true
        }

        val port = startNative(stateDir, cacheDir)
        if (port <= 0) {
            _state.value = ArtiTorState.Error("Failed to bind Arti local SOCKS5 proxy")
            return false
        }

        activePort = port
        _state.value = ArtiTorState.Starting(0, socksPort = port)

        // Poll bootstrap progress until 100%
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val progress = getBootstrapProgressNative()
                if (progress >= 100) {
                    _state.value = ArtiTorState.Connected(port)
                    break
                } else {
                    _state.value = ArtiTorState.Starting(progress, socksPort = port)
                }
                delay(300)
            }
        }

        return true
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        if (isLibraryLoaded) {
            stopNative()
        }
        activePort = 0
        _state.value = ArtiTorState.Idle
    }

    // Native JNI functions implemented in Rust (rust/arti-mobile/src/lib.rs)
    private external fun startNative(stateDir: String, cacheDir: String): Int
    private external fun getBootstrapProgressNative(): Int
    private external fun stopNative()
}
