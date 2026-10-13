package com.example.playback

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.MainThread
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single monotonic timer owned by playback, independent of any screen or dialog. */
object SleepTimerManager {
    private val handler = Handler(Looper.getMainLooper())
    private val _deadline = MutableStateFlow(0L)
    val deadline = _deadline.asStateFlow()
    private var preferences: SharedPreferences? = null
    private var bootCount = -1
    private var player: Player? = null
    private val expirationListeners = linkedSetOf<() -> Unit>()

    @MainThread
    fun addExpirationListener(listener: () -> Unit) { expirationListeners.add(listener) }

    @MainThread
    fun removeExpirationListener(listener: () -> Unit) { expirationListeners.remove(listener) }
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && _deadline.value > 0 && SystemClock.elapsedRealtime() >= _deadline.value) expire()
        }
    }
    private val expiration = object : Runnable {
        override fun run() {
            if (_deadline.value <= 0L) return
            val remaining = _deadline.value - SystemClock.elapsedRealtime()
            if (remaining > 0L) handler.postDelayed(this, remaining) else expire()
        }
    }

    @MainThread
    fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences("playback_sleep_timer", Context.MODE_PRIVATE)
        preferences = prefs
        bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val wallDeadline = prefs.getLong("wall_deadline", 0L)
        if (wallDeadline > 0L) {
            val elapsed = prefs.getLong("elapsed_deadline", 0L)
            _deadline.value = if (bootCount >= 0 && prefs.getInt("boot_count", -2) == bootCount && elapsed > 0L) elapsed
                else (SystemClock.elapsedRealtime() + wallDeadline - System.currentTimeMillis()).coerceAtLeast(1L)
        }
    }

    @MainThread
    fun attach(context: Context, activePlayer: Player) {
        initialize(context)
        player?.removeListener(listener)
        player = activePlayer
        activePlayer.addListener(listener)
        schedule()
    }

    @MainThread
    fun detach(activePlayer: Player) {
        if (player !== activePlayer) return
        activePlayer.removeListener(listener)
        handler.removeCallbacks(expiration)
        player = null // Persist the deadline so a restarted playback service can recover it.
    }

    @MainThread
    fun start(context: Context, durationMs: Long) {
        require(durationMs in 1_000L..(12L * 60L * 60_000L))
        initialize(context)
        val elapsed = SystemClock.elapsedRealtime() + durationMs
        preferences?.edit()?.putLong("elapsed_deadline", elapsed)
            ?.putLong("wall_deadline", System.currentTimeMillis() + durationMs)
            ?.putInt("boot_count", bootCount)?.apply()
        _deadline.value = elapsed
        schedule()
    }

    @MainThread
    fun cancel() {
        handler.removeCallbacks(expiration)
        preferences?.edit()?.clear()?.apply()
        _deadline.value = 0L
    }

    private fun schedule() {
        handler.removeCallbacks(expiration)
        if (_deadline.value <= 0L || player == null) return
        val remaining = _deadline.value - SystemClock.elapsedRealtime()
        if (remaining <= 0L) expire() else handler.postDelayed(expiration, remaining)
    }

    private fun expire() {
        cancel()
        expirationListeners.toList().forEach { it() }
        player?.pause()
    }
}
