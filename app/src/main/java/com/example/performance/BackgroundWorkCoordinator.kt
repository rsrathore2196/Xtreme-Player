package com.example.performance

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Coordinates optional work; never drives frames or controls the audio engine. */
object BackgroundWorkCoordinator {
    private val startedAt = SystemClock.elapsedRealtime()
    private val foreground = AtomicBoolean(false)
    private val _uiForeground = MutableStateFlow(false)
    val uiForeground = _uiForeground.asStateFlow()
    private val lastInteraction = AtomicLong(startedAt)
    private val lastSlowFrame = AtomicLong(startedAt)
    private val lastWork = AtomicLong(startedAt)
    private val jobs = AtomicInteger(0)
    private val navigationJobs = AtomicInteger(0)
    private val navigationUntil = AtomicLong(0L)

    /** Called before changing the selected tab, so worker admission closes immediately. */
    fun navigationInteraction() {
        interaction()
        navigationUntil.set(SystemClock.elapsedRealtime() + 350L)
    }

    /** A lifecycle-owned lease for first layout and full-screen transitions. */
    fun navigationBusy(): AutoCloseable {
        navigationJobs.incrementAndGet()
        val work = busy()
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                navigationJobs.decrementAndGet()
                work.close()
            }
        }
    }

    /**
     * Unlike awaitIdle, this ignores background jobs and loading shimmer frames.
     * It is safe inside an admitted refresh: that refresh cannot wait on itself.
     * Playback and essential user searches do not use this optional-work gate.
     */
    suspend fun awaitNavigationIdle(requireForeground: Boolean = false) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if ((!requireForeground || foreground.get()) &&
                navigationJobs.get() == 0 &&
                SystemClock.elapsedRealtime() >= navigationUntil.get()) return
            delay(50L)
        }
    }

    fun foreground(visible: Boolean) {
        if (visible) interaction()
        foreground.set(visible)
        _uiForeground.value = visible
    }
    fun interaction() { lastInteraction.set(SystemClock.elapsedRealtime()) }
    fun frame(durationNanos: Long, budgetNanos: Long) {
        if (durationNanos > budgetNanos) lastSlowFrame.set(SystemClock.elapsedRealtime())
    }
    fun busy(): AutoCloseable {
        jobs.incrementAndGet()
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                jobs.decrementAndGet()
                lastWork.set(SystemClock.elapsedRealtime())
            }
        }
    }
    suspend inline fun <T> work(block: () -> T): T {
        val lease = busy()
        try { return block() } finally { lease.close() }
    }
    /** Wait for actual quiet, not just a fixed delay after reopening. */
    suspend fun awaitIdle(quietMillis: Long = 2_500L, requireForeground: Boolean = true,
        minimumUptimeMillis: Long = 6_000L, userRequested: () -> Boolean = { false }) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val now = SystemClock.elapsedRealtime()
            val last = maxOf(lastInteraction.get(), lastSlowFrame.get(), lastWork.get())
            if ((!requireForeground || foreground.get()) && (userRequested() || jobs.get() == 0 &&
                now - startedAt >= minimumUptimeMillis && now - last >= quietMillis)) return
            delay(250L)
        }
    }
}
