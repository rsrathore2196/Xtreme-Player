package com.example.performance

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Coordinates optional work; never drives frames or controls the audio engine. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
object BackgroundWorkCoordinator {
    internal const val BETWEEN_JOBS_MS = 750L
    // One shared execution budget for Home transforms, artwork matching and Coil
    // bitmap decoding. Separate limitedParallelism views would multiply this budget.
    // Audio decoding, stream extraction and network waits remain independent.
    val optionalCpuDispatcher = AppDispatchers.CpuBounded
    private val traceCookies = AtomicInteger()

    fun trace(name: String): AutoCloseable {
        val cookie = traceCookies.incrementAndGet()
        androidx.tracing.Trace.beginAsyncSection("Xtreme/$name", cookie)
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true))
                androidx.tracing.Trace.endAsyncSection("Xtreme/$name", cookie)
        }
    }

    /** Async sections survive dispatcher switches; no frame ticker or UI state. */
    suspend fun <T> traceWork(name: String, block: suspend () -> T): T {
        val section = trace(name)
        try { return block() }
        finally { section.close() }
    }
    private val foregroundDrawPending = AtomicBoolean(false)
    private val firstFrame = kotlinx.coroutines.CompletableDeferred<Unit>()
    fun expectForegroundDraw() {
        if (!foregroundDrawPending.getAndSet(true))
            androidx.tracing.Trace.beginAsyncSection("Xtreme/Foreground first draw", 0)
    }
    private fun finishForegroundDrawTrace() {
        if (foregroundDrawPending.getAndSet(false))
            androidx.tracing.Trace.endAsyncSection("Xtreme/Foreground first draw", 0)
    }
    fun firstFrameRendered() {
        finishForegroundDrawTrace()
        firstFrame.complete(Unit)
    }
    suspend fun awaitFirstFrame() { firstFrame.await() }

    private val _interactions = MutableStateFlow(0L)
    val interactions = _interactions.asStateFlow()
    private val touching = AtomicBoolean(false)
    private val focused = AtomicBoolean(true)
    fun windowFocus(active: Boolean) {
        focused.set(active)
        if (!active) touching.set(false)
        interaction()
    }
    fun touch(active: Boolean) { touching.set(active); interaction() }

    /** Measured from an actual Home draw, never process age or active audio time. */
    suspend fun awaitContinuousForegroundIdle(homeDrawAt: Long, quietMillis: Long = 10_000L) {
        var idleSince = homeDrawAt
        while (true) {
            currentCoroutineContext().ensureActive()
            val now = SystemClock.elapsedRealtime()
            val eligible = foreground.get() && focused.get() && isInteractionIdle(awaitFrameQuiet = false)
            if (!eligible) idleSince = now
            val remaining = quietMillis - (now - maxOf(idleSince, lastInteraction.get()))
            if (eligible && remaining <= 0L) return
            delay(if (eligible) remaining.coerceIn(1L, 250L) else 250L)
        }
    }

    /** WorkManager can wake a headless process or coincide with a warm reopen.
     * Check BEFORE acquiring cache/database locks; workers do not own a busy lease.
     * Do not await Home milestones when there is no foreground Activity. */
    suspend fun awaitMaintenanceIdle() {
        while (true) {
            currentCoroutineContext().ensureActive()
            // A paused Activity may retain a navigation/optional-work lease.
            // Headless workers must not wait for a UI that will not draw.
            if (!foreground.get()) return
            val now = SystemClock.elapsedRealtime()
            val uiReady = firstFrame.isCompleted && optionalInteractionIdle(now)
            if (uiReady && jobs.get() == 0 && isInteractionIdle()) return
            delay(250L)
        }
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

    private fun optionalCanRun(requireForeground: Boolean, urgent: () -> Boolean): Boolean {
        if (urgent()) return true
        if (com.example.recommendation.DiscoveryRequestCoordinator.playbackResolving) return false
        if (requireForeground && !foreground.get()) return false
        if (!foreground.get()) return true
        return isInteractionIdle() && optionalInteractionIdle(SystemClock.elapsedRealtime())
    }

    internal fun optionalPlaybackWorkMayProceed(): Boolean = optionalCanRun(false) { false }

    private suspend fun optionalContext(requireForeground: Boolean, urgent: () -> Boolean = { false }) =
        NonEssentialWork(currentCoroutineContext()[Job], { optionalCanRun(requireForeground, urgent) }) {
            while (!optionalCanRun(requireForeground, urgent)) {
                currentCoroutineContext().ensureActive()
                delay(50L)
            }
        }

    /**
     * Admission is not permission to keep consuming CPU through a later gesture.
     * Retain required UI work and its lane, but park CPU continuations during
     * interactive frames. Unlike optional enrichment this uses the short interaction
     * fence: waiting for the optional lane here could block local tab data
     * behind the very restoration that owns the essential lane.
     */
    private suspend fun uiContinuationContext(urgent: () -> Boolean): NonEssentialWork {
        fun canRun() = urgent() || !foreground.get() ||
            (isInteractionIdle() && !com.example.recommendation.DiscoveryRequestCoordinator.playbackResolving)
        return NonEssentialWork(currentCoroutineContext()[Job], ::canRun) {
            while (!canRun()) {
                currentCoroutineContext().ensureActive()
                delay(50L)
            }
        }
    }

    /** Local snapshots use the existing short interaction fence without taking a startup/network lease. */
    internal suspend fun <T> localDataPreparation(urgent: () -> Boolean = { false }, block: suspend () -> T): T =
        withContext(uiContinuationContext(urgent)) { block() }

    /** WorkManager also participates in dock preemption, without requiring a headless Home. */
    internal suspend fun <T> maintenance(block: suspend () -> T): T =
        optionalProcessing(block = block)

    /** Shared flights use their own live urgency, not a single consumer's cancellation. */
    internal suspend fun <T> optionalProcessing(urgent: () -> Boolean = { false }, block: suspend () -> T): T =
        withContext(optionalContext(requireForeground = false, urgent = urgent)) { block() }

    /** Use only between batches, after releasing cache/database/network resources. */
    internal suspend fun awaitOptionalBatch() {
        yield()
        currentCoroutineContext()[NonEssentialWork]?.awaitAdmission?.invoke()
    }

    /** Finish a bounded response/transaction before yielding, never idle with its permit held. */
    internal suspend fun <T> finishBoundedBatch(block: suspend () -> T): T {
        val optional = currentCoroutineContext()[NonEssentialWork] ?: return block()
        return withContext(NonEssentialWork(optional.owner, canRun = { true }, awaitAdmission = { })) { block() }
    }

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
        if (requireForeground || foreground.get()) awaitFirstFrame()
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
                    val interactionQuiet = !foreground.get() || optionalInteractionIdle(now)
                    val breathingGapMet = lastOptionalCompleted.get() == 0L ||
                        now - lastOptionalCompleted.get() >= BETWEEN_JOBS_MS
                    val quiet = jobs.get() == 0 && (!foreground.get() ||
                        !interacting && now - lastSlowFrame.get() >= 250L) && interactionQuiet && breathingGapMet
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
            val effectivePriority = if (admittedEssential) StartupPriority.ESSENTIAL_UI
                else priority
            val admission = Admission(effectivePriority, cardWork)
            val context = when {
                admittedEssential -> admission + uiContinuationContext(urgent)
                else -> admission + optionalContext(requireForeground, urgent)
            }
            return withContext(context) {
                work { traceWork(label, block) }
            }
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
        if (!bypass()) currentCoroutineContext()[NonEssentialWork]?.awaitAdmission?.invoke()
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
        return (!requireForeground || foreground.get()) && !foregroundDrawPending.get() && !touching.get() &&
            !scrolling.get() && navigationJobs.get() == 0 && now >= navigationUntil.get() &&
            now - lastInteraction.get() >= 250L && (!awaitFrameQuiet || now - lastSlowFrame.get() >= 250L)
    }
    private fun optionalInteractionIdle(now: Long): Boolean =
        isInteractionIdle() && now - lastInteraction.get() >= 250L

    private val startedAt = SystemClock.elapsedRealtime()
    private val foreground = AtomicBoolean(false)
    private val _uiForeground = MutableStateFlow(false)
    val uiForeground = _uiForeground.asStateFlow()
    private val lastInteraction = AtomicLong(startedAt)
    private val lastSlowFrame = AtomicLong(startedAt)
    private val lastWork = AtomicLong(startedAt)
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
        cancelNonEssentialWork()
    }

    /**
     * Immediately revoke optional runnable admission. Existing bounded batches yield at
     * their next suspension/checkpoint; new CPU continuations park until genuine idle.
     * IO completes its current transaction/bounded file batch before yielding, so
     * a paused CPU consumer never parks a database transaction on an IO worker.
     * Pausing instead of destroying caller jobs preserves in-flight cache writes, parsed
     * history and verified matches. Critical playback/user jobs are never cancelled.
     */
    fun cancelNonEssentialWork() {
        interaction()
        navigationUntil.set(SystemClock.elapsedRealtime() + 350L)
    }

    /** A lifecycle-owned lease for first layout and full-screen transitions. */
    fun navigationBusy(): AutoCloseable {
        interaction()
        navigationJobs.incrementAndGet()
        val work = busy()
        val section = trace("Navigation transition")
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                navigationJobs.decrementAndGet()
                interaction()
                section.close()
                work.close()
            }
        }
    }

    /**
     * Unlike awaitIdle, this ignores background jobs and loading shimmer frames.
     * It is safe inside an admitted refresh: that refresh cannot wait on itself.
     * Playback and essential user searches do not use this optional-work gate.
     */
    internal fun navigationIsIdle(): Boolean = !foregroundDrawPending.get() &&
        navigationJobs.get() == 0 && SystemClock.elapsedRealtime() >= navigationUntil.get()

    suspend fun awaitNavigationIdle(requireForeground: Boolean = false,
        bypass: () -> Boolean = { false }) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (bypass() || (!requireForeground || foreground.get()) &&
                navigationIsIdle()) return
            delay(50L)
        }
    }

    fun foreground(visible: Boolean) {
        if (foreground.getAndSet(visible) == visible) return
        if (visible) {
            interaction()
            com.example.data.cache.HomeCardCache.retryPendingWrites()
        } else {
            touching.set(false)
            interaction()
            finishForegroundDrawTrace()
        }
        _uiForeground.value = visible
    }
    fun interaction() { lastInteraction.set(SystemClock.elapsedRealtime()); _interactions.update { it + 1 } }
    fun frame(durationNanos: Long, budgetNanos: Long) {
        if (durationNanos > budgetNanos) {
            lastSlowFrame.set(SystemClock.elapsedRealtime())
        }
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
    /** Wait for observed interaction/frame quiet; no process-wide initialization prerequisite. */
    suspend fun awaitIdle(quietMillis: Long = 750L, requireForeground: Boolean = true,
        minimumUptimeMillis: Long = 0L, userRequested: () -> Boolean = { false }) {
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
