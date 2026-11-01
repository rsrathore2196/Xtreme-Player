package com.example.ui.util

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.performance.BackgroundWorkCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One measured viewport, owned by the existing tab-preparation deadline. */
internal class ViewportArtworkPreparation(private val maximumRequests: Int = 12, private val initial: Boolean = true) {
    private val lock = Any()
    private val tickets = linkedMapOf<String, Ticket>()
    private val pending = MutableStateFlow(0)
    private val slots = Semaphore(2)
    private var closed = false

    fun register(key: String): Ticket? = synchronized(lock) {
        if (closed || key in tickets || tickets.size >= maximumRequests) return null
        Ticket().also { tickets[key] = it; pending.update { it + 1 } }
    }

    val complete: Boolean get() = pending.value == 0
    suspend fun awaitCompletion() { pending.first { it == 0 } }

    fun close() = synchronized(lock) {
        closed = true
        tickets.values.forEach { it.allowed.value = false; it.finish() }
    }

    inner class Ticket internal constructor() {
        val allowed = MutableStateFlow(true)
        private val finished = AtomicBoolean()

        fun finish() {
            if (finished.compareAndSet(false, true)) pending.update { it - 1 }
        }

        suspend fun <T> load(visible: () -> Boolean, block: suspend () -> T): T {
            suspend fun bounded() = slots.withPermit {
                if (!allowed.value && !visible()) awaitCancellation()
                BackgroundWorkCoordinator.traceWork("Tab viewport artwork", block)
            }
            return if (initial) bounded() else BackgroundWorkCoordinator.optionalProcessing(
                urgent = { visible() && BackgroundWorkCoordinator.isInteractionIdle(awaitFrameQuiet = false) }) { bounded() }
        }
    }
}

internal val LocalViewportArtworkPreparation = staticCompositionLocalOf<ViewportArtworkPreparation?> { null }
