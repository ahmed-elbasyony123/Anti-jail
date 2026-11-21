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
        } catch (t: Throwable) {
            android.util.Log.e("GhostKey-Arti", "Could not load libarti_mobile.so: ${t.message}", t)
            isLibraryLoaded = false
        }
    }

    val isAvailable: Boolean get() = isLibraryLoaded

    @Synchronized
    fun start(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)): Boolean {
        if (!isLibraryLoaded) {
            _state.value = ArtiTorState.Error("Arti native library (libarti_mobile.so) not present")
            return false
        }

        if (activePort > 0) {
            val currentState = _state.value
            if (currentState is ArtiTorState.Connected || currentState is ArtiTorState.Starting) {
                return true
            }
        }

        val stateDir = File(context.filesDir, "arti_state").apply { mkdirs() }.absolutePath
        val cacheDir = File(context.cacheDir, "arti_cache").apply { mkdirs() }.absolutePath

        val port = startNative(stateDir, cacheDir)
        if (port <= 0) {
            _state.value = ArtiTorState.Error("Failed to bind Arti local SOCKS5 proxy")
            return false
        }

        activePort = port
        _state.value = ArtiTorState.Starting(0, socksPort = port)

        // Poll bootstrap progress until 100% or timeout
        progressJob?.cancel()
        progressJob = scope.launch {
            var elapsedMs = 0L
            while (isActive) {
                val progress = getBootstrapProgressNative()
                if (progress >= 100) {
                    _state.value = ArtiTorState.Connected(port)
                    break
                } else if (progress < 0) {
                    _state.value = ArtiTorState.Error("Arti Tor circuit bootstrap failed in native engine")
                    break
                } else {
                    val current = _state.value
                    if (current !is ArtiTorState.Connected) {
                        _state.value = ArtiTorState.Starting(progress, socksPort = port)
                    }
                }
                delay(300)
                elapsedMs += 300
                // After 60 seconds: if partial bootstrap succeeded (>= 40%), allow connection; otherwise report error
                if (elapsedMs >= 60_000L) {
                    if (progress >= 40) {
                        android.util.Log.i("GhostKey-Arti", "Proceeding with partially bootstrapped Tor ($progress%)")
                        _state.value = ArtiTorState.Connected(port)
                        break
                    } else {
                        android.util.Log.e("GhostKey-Arti", "Tor bootstrap timed out at $progress% after 60s")
                        _state.value = ArtiTorState.Error("Tor circuit bootstrap timed out ($progress%)")
                        break
                    }
                }
            }
        }

        return true
    }

    @Synchronized
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
