package com.example.recommendation

import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Shared discovery work: two bounded optional slots and a reserved urgent lane. */
internal object DiscoveryRequestCoordinator {
    /** A pull can promote its existing refresh without launching another pass. */
    class HomeRefresh(requested: Boolean, val initialShelf: Boolean = false,
        val essentialShelves: Set<UnifiedRecommendationEngine.Shelf> = emptySet(),
        val initialPresentation: Boolean = false) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<HomeRefresh>
        val requested = MutableStateFlow(requested)
        val waitingShelf = MutableStateFlow<UnifiedRecommendationEngine.Shelf?>(null)
        val activeShelf = MutableStateFlow<UnifiedRecommendationEngine.Shelf?>(null)
        fun promote() { requested.value = true }

        /** Start the deadline on a pull, including promotion of a deferred silent pass. */
        suspend fun <T> withBudget(milliseconds: Long, block: suspend () -> T): T? = coroutineScope {
            val work = async { block() }
            val deadline = async { if (!initialPresentation) requested.first { it }; delay(milliseconds) }
            try {
                select {
                    work.onAwait { it }
                    deadline.onAwait { null }
                }
            } finally {
                work.cancel()
                deadline.cancel()
            }
        }
    }
    class HomeCard(val essential: Boolean) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<HomeCard>
    }
    suspend fun essentialHomeWork(): Boolean = currentCoroutineContext()[HomeRefresh]?.initialShelf == true ||
        currentCoroutineContext()[HomeCard]?.essential == true
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private class Flight(val urgent: MutableStateFlow<Boolean>, val requested: MutableStateFlow<Boolean>,
        val result: Deferred<List<MusicTrack>>, var readers: Int = 0)
    private class SharedPriority(val urgent: MutableStateFlow<Boolean>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<SharedPriority>
    }
    private val flights = mutableMapOf<String, Flight>()
    private val lock = Any()
    private val changed = MutableStateFlow(0L)
    private val scrolling = MutableStateFlow(false)
    private val resolving = MutableStateFlow(0)
    private var running = 0
    private var optionalRunning = 0
    private val spaces = Regex("\\s+")

    fun normalize(query: String): String = Normalizer.normalize(query, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(spaces, " ")

    fun homeScrolling(active: Boolean) { scrolling.value = active }

    suspend fun <T> network(essential: Boolean = false, block: suspend () -> T): T {
        val requested = currentCoroutineContext()[HomeRefresh]?.requested ?: MutableStateFlow(false)
        val urgent = currentCoroutineContext()[SharedPriority]?.urgent ?: MutableStateFlow(essential)
        if (essential) urgent.value = true
        return permit(urgent, requested) {
            // Finish this bounded transport/parse batch and release its permit.
            // Heavy ranking after the response remains interaction-preemptible.
            BackgroundWorkCoordinator.finishBoundedBatch {
                withTimeoutOrNull(12_000L) { Result.success(block()) }?.getOrThrow()
                    ?: throw java.io.IOException("Discovery request timed out")
            }
        }
    }

    suspend fun <T> playback(block: suspend () -> T): T {
        resolving.update { it + 1 }
        try { return block() } finally { resolving.update { (it - 1).coerceAtLeast(0) } }
    }

    suspend fun shared(key: String, essential: Boolean, network: Boolean = true,
        block: suspend () -> List<MusicTrack>): List<MusicTrack> {
        val priority = currentCoroutineContext()[HomeRefresh]
        val card = currentCoroutineContext()[HomeCard]
        val flight = synchronized(flights) {
            flights.getOrPut(key) {
                val urgent = MutableStateFlow(essential)
                val requested = MutableStateFlow(priority?.requested?.value == true)
                Flight(urgent, requested, scope.async((priority ?: EmptyCoroutineContext) +
                    (card ?: EmptyCoroutineContext) + SharedPriority(urgent), start = CoroutineStart.LAZY) {
                    BackgroundWorkCoordinator.optionalProcessing(urgent = {
                        urgent.value || requested.value || card?.essential == true || priority?.initialShelf == true
                    }) {
                        if (network) permit(urgent, requested) {
                            BackgroundWorkCoordinator.finishBoundedBatch { withTimeoutOrNull(12_000L) { block() }.orEmpty() }
                        }
                        else block()
                    }
                })
            }.also {
                it.readers++
                if (essential) it.urgent.value = true
                if (priority?.requested?.value == true) it.requested.value = true
            }
        }
        try {
            return coroutineScope {
                val promotion = priority?.let { launch(start = CoroutineStart.UNDISPATCHED) {
                    it.requested.first { requested -> requested }
                    flight.requested.value = true
                } }
                try { flight.result.start(); flight.result.await() }
                finally { promotion?.cancel() }
            }
        }
        finally {
            synchronized(flights) {
                if (--flight.readers == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    if (!flight.result.isCompleted) flight.result.cancel()
                }
            }
        }
    }

    private suspend fun <T> permit(urgent: StateFlow<Boolean>, requested: StateFlow<Boolean>, block: suspend () -> T): T {
        val awaitFrameQuiet = !essentialHomeWork()
        var acquired = false
        var optional = false
        fun release() {
            if (acquired) synchronized(lock) {
                running--
                if (optional) optionalRunning--
                acquired = false
                changed.update { it + 1 }
            }
        }
        try {
            // Do not admit optional discovery in the same frames as a tab/player transition.
            // No request is cancelled or restarted, and playback's urgent lane stays open.
            while (true) {
                if (!urgent.value) com.example.performance.BackgroundWorkCoordinator.yieldToInteraction(
                    awaitFrameQuiet = awaitFrameQuiet,
                    bypass = { urgent.value || requested.value })
                combine(changed, urgent, requested, scrolling, resolving) { _, _, _, _, _ -> Unit }.first {
                    synchronized(lock) {
                        // Explicit refresh can use two slots during the pull's
                        // settling scroll. A third slot remains available to radio;
                        // selected-track resolution still takes priority over Home.
                        val homeAllowed = resolving.value == 0 && if (requested.value)
                            optionalRunning < 2 else optionalRunning < 2 && !scrolling.value
                        if (running < 3 && (urgent.value || homeAllowed)) {
                            optional = !urgent.value
                            running++
                            if (optional) optionalRunning++
                            acquired = true
                            true
                        } else false
                    }
                }
                currentCoroutineContext().ensureActive()
                // A navigation gesture may begin while queued for a slot. Release
                // it before waiting again; paused Home work must not reserve IO
                // capacity needed by other consumers. No request has started yet.
                if (urgent.value || requested.value ||
                    com.example.performance.BackgroundWorkCoordinator.isInteractionIdle(awaitFrameQuiet = awaitFrameQuiet)) break
                release()
            }
            return block()
        } finally {
            release()
        }
    }
}
