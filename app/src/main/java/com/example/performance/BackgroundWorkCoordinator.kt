package com.example.performance

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Coordinates optional work; never drives frames or controls the audio engine. */
object BackgroundWorkCoordinator {
    private val firstFrame = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val homeReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val cachedHomeReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val playbackReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val recommendationsReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val maintenanceReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    fun firstFrameRendered() { firstFrame.complete(Unit) }
    fun homeContentReady() { homeReady.complete(Unit) }
    fun cachedHomeRestored() { cachedHomeReady.complete(Unit) }
    fun playbackSessionRestored() { playbackReady.complete(Unit) }
    fun optionalRecommendationsReady() { recommendationsReady.complete(Unit) }
    suspend fun awaitFirstFrame() { firstFrame.await() }
    suspend fun awaitHomeContent() { homeReady.await() }
    suspend fun awaitPlaybackSession() { playbackReady.await() }
    suspend fun awaitOptionalRecommendations() { recommendationsReady.await() }
    suspend fun awaitMaintenanceReady() { maintenanceReady.await() }
    /** Readiness fences, followed by actual quiet; no fixed startup wake-up timer. */
    suspend fun awaitStartupIdle() {
        awaitFirstFrame()
        awaitHomeContent()
        cachedHomeReady.await()
        awaitIdle(quietMillis = 750L, minimumUptimeMillis = 0L)
        awaitNavigationIdle(requireForeground = true)
    }

    enum class StartupPriority { CRITICAL, ESSENTIAL_UI, DEFERRED, MAINTENANCE }
    private class Admission : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Admission>
    }
    private class Ticket(val priority: StartupPriority, val foregroundOnly: Boolean) {
        val queuedAt = SystemClock.elapsedRealtime()
    }
    private val admissionLock = Any()
    private val waiting = mutableListOf<Ticket>()
    private var essentialActive = false
    private var optionalActive = false

    /**
     * Caller-owned, cancellable admission, rather than a broadcast idle latch. There
     * is one heavy optional worker and one essential lane. Playback/user work never
     * queues behind either lane. Network/image consumers retain their own limits.
     */
    suspend fun <T> startup(priority: StartupPriority, requireForeground: Boolean = true,
        urgent: () -> Boolean = { false },
        block: suspend () -> T): T {
        if (currentCoroutineContext()[Admission] != null) return block()
        if (priority == StartupPriority.CRITICAL || urgent()) return withContext(Admission()) { work { block() } }
        awaitFirstFrame()
        val ticket = Ticket(priority, requireForeground)
        synchronized(admissionLock) { waiting.add(ticket) }
        var admitted = false
        try {
            while (!admitted) {
                currentCoroutineContext().ensureActive()
                if (urgent()) {
                    synchronized(admissionLock) { waiting.remove(ticket) }
                    return withContext(Admission()) { work { block() } }
                }
                admitted = synchronized(admissionLock) {
                    val now = SystemClock.elapsedRealtime()
                    val essential = priority == StartupPriority.ESSENTIAL_UI
                    val eligible = waiting.filter { next ->
                        (!next.foregroundOnly || foreground.get()) &&
                            (next.priority == StartupPriority.ESSENTIAL_UI ||
                                homeReady.isCompleted && cachedHomeReady.isCompleted)
                    }
                    // Aging only resolves competition between optional tasks; it
                    // never promotes maintenance ahead of essential UI/playback.
                    val next = eligible.minByOrNull {
                        if (it.priority == StartupPriority.MAINTENANCE && now - it.queuedAt >= 30_000L)
                            StartupPriority.DEFERRED.ordinal else it.priority.ordinal
                    }
                    val quiet = jobs.get() == 0 && navigationJobs.get() == 0 && now >= navigationUntil.get() &&
                        now - maxOf(lastInteraction.get(), lastSlowFrame.get(), lastWork.get()) >= 750L
                    if (next === ticket && (if (essential) !essentialActive else !optionalActive && !essentialActive && quiet)) {
                        waiting.remove(ticket)
                        if (essential) essentialActive = true else optionalActive = true
                        true
                    } else false
                }
                if (!admitted) delay(50L)
            }
            if (priority == StartupPriority.MAINTENANCE) maintenanceReady.complete(Unit)
            return withContext(Admission()) { work { block() } }
        } finally {
            synchronized(admissionLock) {
                waiting.remove(ticket)
                if (admitted) {
                    if (priority == StartupPriority.ESSENTIAL_UI) essentialActive = false else optionalActive = false
                }
            }
        }
    }

    /** Pause a long maintenance/batch loop for interaction, without waiting on its own lease. */
    suspend fun yieldToInteraction(requireForeground: Boolean = false) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val now = SystemClock.elapsedRealtime()
            if ((!requireForeground || foreground.get()) && navigationJobs.get() == 0 &&
                now >= navigationUntil.get() && now - maxOf(lastInteraction.get(), lastSlowFrame.get()) >= 250L) return
            delay(50L)
        }
    }
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
    suspend fun awaitNavigationIdle(requireForeground: Boolean = false,
        bypass: () -> Boolean = { false }) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (bypass() || (!requireForeground || foreground.get()) &&
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
        // An admitted task owns a busy lease. Waiting for jobs == 0 here would
        // wait for itself (including nested cache/repository calls).
        if (currentCoroutineContext()[Admission] != null) {
            awaitNavigationIdle(requireForeground, userRequested)
            return
        }
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
