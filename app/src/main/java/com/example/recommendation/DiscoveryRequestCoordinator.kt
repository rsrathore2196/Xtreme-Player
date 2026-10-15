package com.example.recommendation

import com.example.data.model.MusicTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Shared discovery work: one optional slot, or two during an explicit Home refresh. */
internal object DiscoveryRequestCoordinator {
    /** A pull can promote its existing refresh without launching another pass. */
    class HomeRefresh(requested: Boolean) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<HomeRefresh>
        val requested = MutableStateFlow(requested)
        fun promote() { requested.value = true }

        /** Start the deadline on a pull, including promotion of a deferred silent pass. */
        suspend fun <T> withBudget(milliseconds: Long, block: suspend () -> T): T? = coroutineScope {
            val work = async { block() }
            val deadline = async { requested.first { it }; delay(milliseconds) }
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Flight(val urgent: MutableStateFlow<Boolean>, val requested: MutableStateFlow<Boolean>,
        val result: Deferred<List<MusicTrack>>, var readers: Int = 0)
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
        return permit(MutableStateFlow(essential), requested) {
            withTimeoutOrNull(12_000L) { Result.success(block()) }?.getOrThrow()
                ?: throw java.io.IOException("Discovery request timed out")
        }
    }

    suspend fun <T> playback(block: suspend () -> T): T {
        resolving.update { it + 1 }
        try { return block() } finally { resolving.update { (it - 1).coerceAtLeast(0) } }
    }

    suspend fun shared(key: String, essential: Boolean, network: Boolean = true,
        block: suspend () -> List<MusicTrack>): List<MusicTrack> {
        val priority = currentCoroutineContext()[HomeRefresh]
        val flight = synchronized(flights) {
            flights.getOrPut(key) {
                val urgent = MutableStateFlow(essential)
                val requested = MutableStateFlow(priority?.requested?.value == true)
                Flight(urgent, requested, scope.async(priority ?: EmptyCoroutineContext, start = CoroutineStart.LAZY) {
                    if (network) permit(urgent, requested) { withTimeoutOrNull(12_000L) { block() }.orEmpty() }
                    else block()
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
        var acquired = false
        var optional = false
        try {
            // Do not admit optional discovery in the same frames as a tab/player transition.
            // No request is cancelled or restarted, and playback's urgent lane stays open.
            if (!urgent.value) com.example.performance.BackgroundWorkCoordinator.awaitNavigationIdle(bypass = { urgent.value })
            combine(changed, urgent, requested, scrolling, resolving) { _, _, _, _, _ -> Unit }.first {
                synchronized(lock) {
                    // Explicit refresh can use two slots and start during the pull's
                    // settling scroll. A third slot remains available to radio;
                    // selected-track resolution still takes priority over Home.
                    val homeAllowed = resolving.value == 0 && if (requested.value)
                        optionalRunning < 2 else optionalRunning == 0 && !scrolling.value
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
            // A tab can change while this request is queued for a network slot.
            if (!urgent.value) com.example.performance.BackgroundWorkCoordinator.awaitNavigationIdle(bypass = { urgent.value })
            return block()
        } finally {
            if (acquired) synchronized(lock) {
                running--
                if (optional) optionalRunning--
                changed.update { it + 1 }
            }
        }
    }
}
