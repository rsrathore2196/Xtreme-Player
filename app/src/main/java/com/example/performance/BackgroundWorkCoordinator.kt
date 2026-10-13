package com.example.performance

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Coordinates optional work; never drives frames or controls the audio engine. */
object BackgroundWorkCoordinator {
    private val startedAt = SystemClock.elapsedRealtime()
    private val foreground = AtomicBoolean(false)
    private val lastInteraction = AtomicLong(startedAt)
    private val lastSlowFrame = AtomicLong(startedAt)
    private val lastWork = AtomicLong(startedAt)
    private val jobs = AtomicInteger(0)

    fun foreground(visible: Boolean) {
        foreground.set(visible)
        if (visible) interaction()
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
    suspend fun awaitIdle(quietMillis: Long = 2_500L, requireForeground: Boolean = true) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val now = SystemClock.elapsedRealtime()
            val last = maxOf(lastInteraction.get(), lastSlowFrame.get(), lastWork.get())
            if ((!requireForeground || foreground.get()) && jobs.get() == 0 &&
                now - startedAt >= 6_000L && now - last >= quietMillis) return
            delay(250L)
        }
    }
}
