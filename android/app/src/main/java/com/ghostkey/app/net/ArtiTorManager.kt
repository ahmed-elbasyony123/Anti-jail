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
    private val artiScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    fun start(context: Context, scope: CoroutineScope = artiScope): Boolean {
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

        val stateDirFile = File(context.filesDir, "arti_state").apply { mkdirs() }
        val cacheDirFile = File(context.cacheDir, "arti_cache").apply { mkdirs() }

        // Clean any stale locks left behind from previous crashes or force stops
        try {
            File(stateDirFile, "state.lock").delete()
            File(cacheDirFile, "cache.lock").delete()
        } catch (_: Throwable) {}

        val stateDir = stateDirFile.absolutePath
        val cacheDir = cacheDirFile.absolutePath

        _state.value = ArtiTorState.Starting(0, socksPort = 0)

        // Run startNative asynchronously on background IO dispatcher to avoid UI ANR
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.IO) {
            val port = try {
                startNative(stateDir, cacheDir)
            } catch (t: Throwable) {
                android.util.Log.e("GhostKey-Arti", "startNative threw exception: ${t.message}", t)
                -1
            }

            if (port <= 0) {
                val nativeErr = try { getLastErrorNative() } catch (_: Throwable) { null }
                val detail = if (!nativeErr.isNullOrBlank()) ": $nativeErr" else ""
                _state.value = ArtiTorState.Error("Failed to bind Arti local SOCKS5 proxy$detail")
                activePort = 0
                return@launch
            }

            activePort = port
            _state.value = ArtiTorState.Starting(0, socksPort = port)

            // Poll bootstrap progress until 100% or timeout. Do NOT declare connected at 40%.
            var elapsedMs = 0L
            while (isActive) {
                val progress = try {
                    getBootstrapProgressNative()
                } catch (_: Throwable) {
                    -1
                }

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
                // Strict 60s timeout: Require 100% bootstrap; do not declare connected on partial bootstrap
                if (elapsedMs >= 60_000L) {
                    android.util.Log.e("GhostKey-Arti", "Tor bootstrap timed out at $progress% after 60s")
                    _state.value = ArtiTorState.Error("Tor circuit bootstrap timed out ($progress%)")
                    break
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
            // Run stopNative asynchronously on background IO dispatcher to avoid blocking UI thread / ANR
            artiScope.launch(Dispatchers.IO) {
                try {
                    stopNative()
                } catch (t: Throwable) {
                    android.util.Log.e("GhostKey-Arti", "Error stopping native Arti engine: ${t.message}", t)
                }
            }
        }
        activePort = 0
        _state.value = ArtiTorState.Idle
    }

    /**
     * Panic Wipe / Zero-Disk compliance:
     * Completely stops Arti Tor and deletes arti_state and arti_cache directories from storage.
     */
    @Synchronized
    fun wipe(context: Context) {
        progressJob?.cancel()
        progressJob = null
        if (isLibraryLoaded) {
            try {
                stopNative()
            } catch (t: Throwable) {
                android.util.Log.e("GhostKey-Arti", "Error stopping native Arti during wipe: ${t.message}", t)
            }
        }
        activePort = 0
        _state.value = ArtiTorState.Idle

        // Recursively delete state and cache directories
        try {
            val stateDirFile = File(context.filesDir, "arti_state")
            if (stateDirFile.exists()) {
                stateDirFile.deleteRecursively()
            }
        } catch (t: Throwable) {
            android.util.Log.e("GhostKey-Arti", "Failed to wipe arti_state: ${t.message}", t)
        }

        try {
            val cacheDirFile = File(context.cacheDir, "arti_cache")
            if (cacheDirFile.exists()) {
                cacheDirFile.deleteRecursively()
            }
        } catch (t: Throwable) {
            android.util.Log.e("GhostKey-Arti", "Failed to wipe arti_cache: ${t.message}", t)
        }
    }

    // Native JNI functions implemented in Rust (rust/arti-mobile/src/lib.rs)
    private external fun startNative(stateDir: String, cacheDir: String): Int
    private external fun getBootstrapProgressNative(): Int
    private external fun getLastErrorNative(): String?
    private external fun stopNative()
}
