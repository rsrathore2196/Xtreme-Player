package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.ui.viewmodel.visibleCatalogFavoriteIds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class VisibleCatalogFavoritesTest {
    @Test fun hiddenAndBackgroundCatalogStopsObservingAndCatchesUpOnReturn() = runBlocking {
        val visible = MutableStateFlow(true)
        val foreground = MutableStateFlow(false)
        val active = AtomicInteger()
        val starts = AtomicInteger()
        val rows = MutableStateFlow(listOf(MusicTrack("one", "Song", "Artist", "Album", 10, "cover", "audio")))
        val presented = MutableStateFlow<Set<String>>(emptySet())
        val job = launch {
            visibleCatalogFavoriteIds(visible, foreground, {
                rows.onStart { active.incrementAndGet(); starts.incrementAndGet() }
                    .onCompletion { active.decrementAndGet() }
            }, awaitNavigation = {}).collect { presented.value = it }
        }
        suspend fun awaitActive(expected: Int) = withTimeout(5_000) { while (active.get() != expected) delay(10) }
        try {
            yield()
            assertEquals(0, starts.get())
            foreground.value = true
            withTimeout(5_000) { presented.first { it == setOf("one") } }
            assertEquals(1, active.get())
            foreground.value = false
            awaitActive(0)
            rows.value = listOf(rows.value.single().copy(id = "two"))
            assertEquals(setOf("one"), presented.value)
            foreground.value = true
            withTimeout(5_000) { presented.first { it == setOf("two") } }
            assertEquals(2, starts.get())
            visible.value = false
            awaitActive(0)
            foreground.value = false
            foreground.value = true
            assertEquals(2, starts.get())
        } finally { job.cancelAndJoin() }
    }

    @Test fun leavingDuringNavigationCancelsAdmissionBeforeOpeningRoom() = runBlocking {
        val visible = MutableStateFlow(true)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val starts = AtomicInteger()
        val job = launch {
            visibleCatalogFavoriteIds(visible, MutableStateFlow(true), {
                starts.incrementAndGet(); flowOf(emptyList())
            }, awaitNavigation = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }).collect()
        }
        try {
            withTimeout(5_000) { entered.await() }
            visible.value = false
            withTimeout(5_000) { cancelled.await() }
            assertEquals(0, starts.get())
        } finally { job.cancelAndJoin() }
    }
}
