package com.example.playback

import android.os.SystemClock
import android.util.Log
import android.util.LruCache

/** Bounded diagnostics only: no polling, persistence, or UI invalidation. */
object LosslessDiagnostics {
    enum class Reason {
        NO_RESULTS, METADATA_INCOMPLETE, RECORDING_MISMATCH, NETWORK_TIMEOUT,
        PREBUFFER_FAILED, DECODER_UNSUPPORTED, NETWORK_POLICY_BLOCKED, ACTIVATION_FAILED
    }
    data class Failure(val reason: Reason, val stage: String, val atMs: Long)
    private val failures = LruCache<String, Failure>(128)

    fun record(id: String, reason: Reason, stage: String) {
        val now = SystemClock.elapsedRealtime()
        val previous = failures.get(id)
        failures.put(id, Failure(reason, stage, now))
        if (previous?.reason != reason || previous.stage != stage || now - previous.atMs > 5_000L)
            Log.d("LosslessUpgrade", "$reason at $stage (track=$id)")
    }
    fun last(id: String): Failure? = failures.get(id)
    fun clear(id: String) { failures.remove(id) }
}
