package com.ghostkey.app.net

import kotlin.random.Random

/**
 * Reconnect delays: 4s -> 8s -> 16s -> 32s -> 60s (cap), each with +/-20% jitter so that
 * several clients do not hammer the Tor circuit in lock-step. The result never exceeds the cap.
 */
object Backoff {
    const val BASE_MS = 4_000L
    const val MAX_MS = 60_000L
    private const val JITTER = 0.2

    fun delayMs(attempt: Int, random: Random = Random.Default): Long {
        val exp = minOf(attempt.coerceAtLeast(0), 10)
        val base = minOf(BASE_MS shl exp, MAX_MS)
        val factor = 1.0 - JITTER + random.nextDouble() * (2 * JITTER)
        return minOf((base * factor).toLong(), MAX_MS)
    }
}
