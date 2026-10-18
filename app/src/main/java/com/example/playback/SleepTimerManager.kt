package com.example.playback

import com.example.performance.AppDispatchers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.MainThread
import androidx.media3.common.Player
import com.example.data.local.ReactiveSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Player commands/deadlines belong to Main. Boot detection, restore and persistence do not. */
object SleepTimerManager {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private val loading = Mutex()
    private data class Saved(val deadline: Long, val boot: Int)
    @Volatile private var saved: Saved? = null
    private var restored = false
    private var revision = 0L
    private var appContext: Context? = null
    private val writes = Channel<Pair<Long, Long>>(Channel.UNLIMITED)
    init { scope.launch {
        for ((elapsed, wall) in writes) {
            val context = appContext ?: continue
            preload(context)
            val prefs = ReactiveSettingsRepository.preferences(context, "playback_sleep_timer")
            val editor = prefs.edit()
            if (elapsed == 0L) editor.clear() else editor.putLong("elapsed_deadline", elapsed)
                .putLong("wall_deadline", wall).putInt("boot_count", saved?.boot ?: -1)
            editor.commit()
        }
    } }
    private val _deadline = MutableStateFlow(0L)
    val deadline = _deadline.asStateFlow()
    private var player: Player? = null
    private val expirationListeners = linkedSetOf<() -> Unit>()

    suspend fun preload(context: Context) = withContext(AppDispatchers.Io) {
        loading.withLock {
            if (saved != null) return@withLock
            val prefs = ReactiveSettingsRepository.preferences(context, "playback_sleep_timer")
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            val wall = prefs.getLong("wall_deadline", 0L)
            val elapsed = prefs.getLong("elapsed_deadline", 0L)
            val deadline = if (wall <= 0L) 0L else if (boot >= 0 && prefs.getInt("boot_count", -2) == boot && elapsed > 0L) elapsed
                else (SystemClock.elapsedRealtime() + wall - System.currentTimeMillis()).coerceAtLeast(1L)
            saved = Saved(deadline, boot)
        }
    }

    @MainThread fun addExpirationListener(listener: () -> Unit) { expirationListeners.add(listener) }
    @MainThread fun removeExpirationListener(listener: () -> Unit) { expirationListeners.remove(listener) }
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

    @MainThread fun initialize(context: Context) {
        appContext = context.applicationContext
        if (restored) return
        val generation = revision
        fun restore() {
            if (!restored && revision == generation) {
                restored = true
                _deadline.value = saved?.deadline ?: 0L
                schedule()
            }
        }
        if (saved != null) restore() else scope.launch {
            preload(context.applicationContext)
            withContext(AppDispatchers.Main) { restore() }
        }
    }

    @MainThread fun attach(context: Context, activePlayer: Player) {
        initialize(context)
        player?.removeListener(listener)
        player = activePlayer
        activePlayer.addListener(listener)
        schedule()
    }
    @MainThread fun detach(activePlayer: Player) {
        if (player !== activePlayer) return
        activePlayer.removeListener(listener)
        handler.removeCallbacks(expiration)
        player = null
    }
    @MainThread fun start(context: Context, durationMs: Long) {
        require(durationMs in 1_000L..(12L * 60L * 60_000L))
        initialize(context)
        revision++
        restored = true
        val elapsed = SystemClock.elapsedRealtime() + durationMs
        _deadline.value = elapsed
        writes.trySend(elapsed to System.currentTimeMillis() + durationMs)
        schedule()
    }
    @MainThread fun cancel() {
        revision++
        restored = true
        handler.removeCallbacks(expiration)
        _deadline.value = 0L
        writes.trySend(0L to 0L)
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
