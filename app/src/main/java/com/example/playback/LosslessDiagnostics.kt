package com.example.playback

import android.os.SystemClock
import android.util.Log
import android.util.LruCache

/** Bounded diagnostics only: no polling, persistence, or UI invalidation. */
object LosslessDiagnostics {
    enum class Reason {
        NO_RESULTS, METADATA_INCOMPLETE, RECORDING_MISMATCH, NETWORK_TIMEOUT,
        PREBUFFER_FAILED, DECODER_UNSUPPORTED, NETWORK_POLICY_BLOCKED, ACTIVATION_FAILED,
        LOOKUP_TIMEOUT, NO_MATCH, INVALID_FORMAT, SOURCE_VERIFICATION_FAILED, TRANSITION_FAILED,
        BUFFER_COVERAGE, UNAUTHORIZED, OPUS_UNAVAILABLE, POLICY_SOURCE_UNAVAILABLE
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
    fun prebufferRejected(id: String, prepared: Boolean) {
        if (prepared) record(id, Reason.BUFFER_COVERAGE, "prebuffer")
        else if (last(id)?.stage != "prebuffer") record(id, Reason.PREBUFFER_FAILED, "prebuffer")
    }
    fun rejectionTtlMs(reason: Reason?): Long = when (reason) {
        Reason.RECORDING_MISMATCH, Reason.DECODER_UNSUPPORTED, Reason.INVALID_FORMAT,
        Reason.SOURCE_VERIFICATION_FAILED, Reason.UNAUTHORIZED -> 300_000L
        Reason.ACTIVATION_FAILED, Reason.TRANSITION_FAILED -> 30_000L
        else -> 10_000L
    }
}
