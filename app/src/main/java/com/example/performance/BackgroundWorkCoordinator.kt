package com.example.performance

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Coordinates optional work; never drives frames or controls the audio engine. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
object BackgroundWorkCoordinator {
    internal const val OPTIONAL_IDLE_MS = 15_000L
    internal const val BETWEEN_JOBS_MS = 750L
    // One shared execution budget for Home transforms, artwork matching and Coil
    // bitmap decoding. Separate limitedParallelism views would multiply this budget.
    // Audio decoding, stream extraction and network waits remain independent.
    val optionalCpuDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2)
    private val traceCookies = AtomicInteger()

    /** Async sections survive dispatcher switches; no frame ticker or UI state. */
    suspend fun <T> traceWork(name: String, block: suspend () -> T): T {
        val cookie = traceCookies.incrementAndGet()
        androidx.core.os.TraceCompat.beginAsyncSection("Xtreme/$name", cookie)
        try { return block() }
        finally { androidx.core.os.TraceCompat.endAsyncSection("Xtreme/$name", cookie) }
    }
    private val foregroundDrawPending = AtomicBoolean(false)
    private val firstFrame = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val homeReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val cachedHomeReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val playbackReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val recommendationsReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val maintenanceReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    fun expectForegroundDraw() {
        if (!foregroundDrawPending.getAndSet(true))
            androidx.core.os.TraceCompat.beginAsyncSection("Xtreme/Foreground first draw", 0)
    }
    private fun finishForegroundDrawTrace() {
        if (foregroundDrawPending.getAndSet(false))
            androidx.core.os.TraceCompat.endAsyncSection("Xtreme/Foreground first draw", 0)
    }
    fun firstFrameRendered() {
        finishForegroundDrawTrace()
        firstFrame.complete(Unit)
    }
    fun homeContentReady() { homeReady.complete(Unit) }
    fun cachedHomeRestored() { cachedHomeReady.complete(Unit) }
    /** A restored non-Home destination has no feed/cache prerequisite for optional app work. */
    fun nonHomeContentReady() {
        homeReady.complete(Unit)
        cachedHomeReady.complete(Unit)
    }
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
    private class Admission(val priority: StartupPriority, val cardWork: Boolean = false) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Admission>
    }
    private class Ticket(val priority: StartupPriority, val foregroundOnly: Boolean,
        val cardWork: Boolean, val urgent: () -> Boolean) {
        val queuedAt = SystemClock.elapsedRealtime()
        fun essential() = priority == StartupPriority.ESSENTIAL_UI || cardWork &&
            (priority == StartupPriority.CRITICAL || urgent())
    }
    private val admissionLock = Any()
    private val waiting = mutableListOf<Ticket>()
    private var essentialActive = false
    private var optionalActive = false
    private var cardActive = false
    private var lastCardCompleted = 0L

    /**
     * Caller-owned, cancellable admission, rather than a broadcast idle latch. There
     * is one heavy optional worker and one essential lane. Playback/user work never
     * queues behind either lane. Network/image consumers retain their own limits.
     */
    suspend fun <T> startup(priority: StartupPriority, requireForeground: Boolean = true,
        urgent: () -> Boolean = { false },
        cardWork: Boolean = false,
        label: String = priority.name,
        block: suspend () -> T): T {
        if (currentCoroutineContext()[Admission] != null) return traceWork(label, block)
        if (!cardWork && (priority == StartupPriority.CRITICAL || urgent()))
            return withContext(Admission(StartupPriority.CRITICAL)) { work { traceWork(label, block) } }
        awaitFirstFrame()
        val ticket = Ticket(priority, requireForeground, cardWork, urgent)
        synchronized(admissionLock) { waiting.add(ticket) }
        var admitted = false
        var admittedEssential = false
        try {
            while (!admitted) {
                currentCoroutineContext().ensureActive()
                if (!cardWork && urgent()) {
                    synchronized(admissionLock) { waiting.remove(ticket) }
                    return withContext(Admission(StartupPriority.CRITICAL)) { work { traceWork(label, block) } }
                }
                admitted = synchronized(admissionLock) {
                    val now = SystemClock.elapsedRealtime()
                    val essential = ticket.essential()
                    val eligible = waiting.filter { next ->
                        (!next.foregroundOnly || foreground.get()) &&
                            (next.essential() || homeReady.isCompleted && cachedHomeReady.isCompleted) &&
                            (!next.cardWork || !cardActive && (lastCardCompleted == 0L ||
                                now - lastCardCompleted >= BETWEEN_JOBS_MS))
                    }
                    // Aging is fairness between optional jobs, never permission to
                    // bypass essential content, gestures or the foreground idle gate.
                    val next = eligible.minByOrNull {
                        if (it.essential()) StartupPriority.ESSENTIAL_UI.ordinal
                        else if (it.priority == StartupPriority.MAINTENANCE && now - it.queuedAt >= 30_000L)
                            StartupPriority.DEFERRED.ordinal else it.priority.ordinal
                    }
                    val interacting = foregroundDrawPending.get() || scrolling.get() ||
                        navigationJobs.get() != 0 || now < navigationUntil.get()
                    val idle15sMet = optionalInteractionIdle(now)
                    val breathingGapMet = lastOptionalCompleted.get() == 0L ||
                        now - lastOptionalCompleted.get() >= BETWEEN_JOBS_MS
                    val quiet = jobs.get() == 0 && !interacting &&
                        now - lastSlowFrame.get() >= 250L && idle15sMet && breathingGapMet
                    if (next === ticket && (if (essential) !essentialActive && !interacting
                        else !optionalActive && !essentialActive && quiet)) {
                        waiting.remove(ticket)
                        admittedEssential = essential
                        if (essential) essentialActive = true else optionalActive = true
                        if (cardWork) cardActive = true
                        true
                    } else false
                }
                if (!admitted) delay(50L)
            }
            if (priority == StartupPriority.MAINTENANCE) maintenanceReady.complete(Unit)
            return withContext(Admission(if (admittedEssential) StartupPriority.ESSENTIAL_UI else priority, cardWork)) { work { traceWork(label, block) } }
        } finally {
            synchronized(admissionLock) {
                waiting.remove(ticket)
                if (admitted) {
                    if (cardWork) {
                        cardActive = false
                        lastCardCompleted = SystemClock.elapsedRealtime()
                    }
                    if (admittedEssential) {
                        essentialActive = false
                    } else {
                        optionalActive = false
                        lastOptionalCompleted.set(SystemClock.elapsedRealtime())
                    }
                }
            }
        }
    }

    /** Pause a long maintenance/batch loop for interaction, without waiting on its own lease. */
    suspend fun yieldToInteraction(requireForeground: Boolean = false, awaitFrameQuiet: Boolean = true,
        bypass: () -> Boolean = { false }) {
        // A fast batch must also give other coroutines a turn, even when no gesture
        // is active. This gate never waits for jobs == 0 (the caller may own a lease).
        yield()
        val admission = currentCoroutineContext()[Admission]
        while (true) {
            currentCoroutineContext().ensureActive()
            val essentialWaiting = if (admission?.priority == StartupPriority.DEFERRED ||
                admission?.priority == StartupPriority.MAINTENANCE) synchronized(admissionLock) {
                // A second card needs this card's slot. Yielding *to that
                // waiter* would wait for our own completion indefinitely.
                essentialActive || waiting.any { it.essential() && (!admission.cardWork || !it.cardWork) &&
                    (!it.foregroundOnly || foreground.get()) }
            } else false
            val optional = admission?.priority == StartupPriority.DEFERRED ||
                admission?.priority == StartupPriority.MAINTENANCE
            val frameQuiet = awaitFrameQuiet && admission?.priority != StartupPriority.ESSENTIAL_UI
            if (bypass() || !essentialWaiting && isInteractionIdle(requireForeground, frameQuiet) &&
                (!optional || optionalInteractionIdle(SystemClock.elapsedRealtime()))) return
            delay(50L)
        }
    }

    /** Recheck admission after a resource wait, without holding that resource while idling. */
    internal fun isInteractionIdle(requireForeground: Boolean = false, awaitFrameQuiet: Boolean = true): Boolean {
        val now = SystemClock.elapsedRealtime()
        return (!requireForeground || foreground.get()) && !foregroundDrawPending.get() &&
            !scrolling.get() && navigationJobs.get() == 0 && now >= navigationUntil.get() &&
            now - lastInteraction.get() >= 250L && (!awaitFrameQuiet || now - lastSlowFrame.get() >= 250L)
    }
    private fun optionalInteractionIdle(now: Long): Boolean =
        now - lastInteraction.get() >= OPTIONAL_IDLE_MS && now - lastForegroundResume.get() >= OPTIONAL_IDLE_MS

    private val startedAt = SystemClock.elapsedRealtime()
    private val foreground = AtomicBoolean(false)
    private val _uiForeground = MutableStateFlow(false)
    val uiForeground = _uiForeground.asStateFlow()
    private val lastInteraction = AtomicLong(startedAt)
    private val lastSlowFrame = AtomicLong(startedAt)
    private val lastWork = AtomicLong(startedAt)
    private val lastForegroundResume = AtomicLong(startedAt)
    private val lastOptionalCompleted = AtomicLong(0L)
    private val jobs = AtomicInteger(0)
    private val navigationJobs = AtomicInteger(0)
    private val navigationUntil = AtomicLong(0L)
    private val scrolling = AtomicBoolean(false)
    private val scrollOwners = mutableSetOf<Any>()
    private val legacyScrollOwner = Any()

    fun scrolling(active: Boolean) = scrolling(legacyScrollOwner, active)

    /** Independent screen/row owners cannot clear another active fling. */
    fun scrolling(owner: Any, active: Boolean) {
        val changed = synchronized(scrollOwners) {
            val changed = if (active) scrollOwners.add(owner) else scrollOwners.remove(owner)
            scrolling.set(scrollOwners.isNotEmpty())
            changed
        }
        if (changed) interaction()
    }

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
                !foregroundDrawPending.get() &&
                navigationJobs.get() == 0 &&
                SystemClock.elapsedRealtime() >= navigationUntil.get()) return
            delay(50L)
        }
    }

    fun foreground(visible: Boolean) {
        if (foreground.getAndSet(visible) == visible) return
        if (visible) {
            interaction()
            lastForegroundResume.set(SystemClock.elapsedRealtime())
        } else {
            finishForegroundDrawTrace()
        }
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
    /** Wait for actual quiet (defaulting to 15s after reopen/interaction), not just a quick pulse. */
    suspend fun awaitIdle(quietMillis: Long = 15_000L, requireForeground: Boolean = true,
        minimumUptimeMillis: Long = 15_000L, userRequested: () -> Boolean = { false }) {
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
            val quiet = !foregroundDrawPending.get() && jobs.get() == 0 &&
                now - startedAt >= minimumUptimeMillis && now - last >= quietMillis
            if ((!requireForeground || foreground.get()) && (userRequested() || quiet)) return
            delay(250L)
        }
    }
}
