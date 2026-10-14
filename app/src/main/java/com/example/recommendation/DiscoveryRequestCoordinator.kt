package com.example.recommendation

import com.example.data.model.MusicTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.Normalizer
import java.util.Locale

/** Shared discovery work, with one optional slot and two slots reserved for radio. */
internal object DiscoveryRequestCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Flight(val urgent: MutableStateFlow<Boolean>, val result: Deferred<List<MusicTrack>>, var readers: Int = 0)
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

    suspend fun <T> network(essential: Boolean = false, block: suspend () -> T): T =
        permit(MutableStateFlow(essential)) {
            withTimeoutOrNull(12_000L) { Result.success(block()) }?.getOrThrow()
                ?: throw java.io.IOException("Discovery request timed out")
        }

    suspend fun <T> playback(block: suspend () -> T): T {
        resolving.update { it + 1 }
        try { return block() } finally { resolving.update { (it - 1).coerceAtLeast(0) } }
    }

    suspend fun shared(key: String, essential: Boolean, network: Boolean = true,
        block: suspend () -> List<MusicTrack>): List<MusicTrack> {
        val flight = synchronized(flights) {
            flights.getOrPut(key) {
                val urgent = MutableStateFlow(essential)
                Flight(urgent, scope.async(start = CoroutineStart.LAZY) {
                    if (network) permit(urgent) { withTimeoutOrNull(12_000L) { block() }.orEmpty() }
                    else block()
                })
            }.also { it.readers++; if (essential) it.urgent.value = true }
        }
        try { flight.result.start(); return flight.result.await() }
        finally {
            synchronized(flights) {
                if (--flight.readers == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    if (!flight.result.isCompleted) flight.result.cancel()
                }
            }
        }
    }

    private suspend fun <T> permit(urgent: StateFlow<Boolean>, block: suspend () -> T): T {
        var acquired = false
        var optional = false
        try {
            combine(changed, urgent, scrolling, resolving) { _, _, _, _ -> Unit }.first {
                synchronized(lock) {
                    if (running < 3 && (urgent.value || optionalRunning == 0 && !scrolling.value && resolving.value == 0)) {
                        optional = !urgent.value
                        running++
                        if (optional) optionalRunning++
                        acquired = true
                        true
                    } else false
                }
            }
            currentCoroutineContext().ensureActive()
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
