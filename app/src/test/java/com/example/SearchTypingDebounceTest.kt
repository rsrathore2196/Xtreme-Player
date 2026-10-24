package com.example

import android.app.Application
import com.example.data.local.PlaybackIntegrationSettings
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogPage
import com.example.data.repository.CatalogRepository
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSourcePolicy
import com.example.ui.viewmodel.CatalogBrowserState
import com.example.ui.viewmodel.SEARCH_TYPING_DEBOUNCE_MS
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SearchTypingDebounceTest {
    private fun page(query: String) = CatalogPage(tracks = listOf(
        MusicTrack("yt_$query", query, "Artist", "Album", 180000, "", "")))

    @Test fun continuousTypingStartsOnlyTheFinalQueryAfterTheLastKeystrokeSettles() = runTest {
        val calls = mutableListOf<String>()
        val browser = CatalogBrowserState(CatalogRepository(), backgroundScope,
            catalogSearch = { query, _, _, _ -> calls.add(query); page(query) },
            preparationDispatcher = StandardTestDispatcher(testScheduler))
        browser.queryChanged("S")
        runCurrent()
        advanceTimeBy(400)
        browser.queryChanged("So")
        runCurrent()
        advanceTimeBy(400)
        browser.queryChanged("Song")
        runCurrent()
        advanceTimeBy(SEARCH_TYPING_DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(browser.search.value.page.tracks.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("Song"), calls)
        assertEquals("Song", browser.search.value.page.tracks.single().title)
    }

    @Test fun clearingTheQueryCancelsThePendingSearchAndItsResults() = runTest {
        val calls = mutableListOf<String>()
        val browser = CatalogBrowserState(CatalogRepository(), backgroundScope,
            catalogSearch = { query, _, _, _ -> calls.add(query); page(query) },
            preparationDispatcher = StandardTestDispatcher(testScheduler))
        browser.queryChanged("Song")
        runCurrent()
        advanceTimeBy(SEARCH_TYPING_DEBOUNCE_MS - 1)
        browser.queryChanged("")
        advanceTimeBy(SEARCH_TYPING_DEBOUNCE_MS)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals("", browser.search.value.query)
        assertTrue(browser.search.value.page.tracks.isEmpty())
        assertFalse(browser.search.value.loading)
    }

    @Test fun resultsFromAnObsoleteUncooperativeRequestCannotReplaceTheNewQuery() = runTest {
        val releaseOld = CompletableDeferred<Unit>()
        val oldStarted = CompletableDeferred<Unit>()
        val browser = CatalogBrowserState(CatalogRepository(), backgroundScope,
            catalogSearch = { query, _, _, partial ->
                if (query == "Old") {
                    oldStarted.complete(Unit)
                    withContext(NonCancellable) { releaseOld.await(); partial(page(query)) }
                }
                page(query)
            }, preparationDispatcher = StandardTestDispatcher(testScheduler))
        try {
            browser.queryChanged("Old")
            runCurrent()
            advanceTimeBy(SEARCH_TYPING_DEBOUNCE_MS)
            runCurrent()
            assertTrue(oldStarted.isCompleted)
            browser.queryChanged("Current")
            runCurrent()
            assertTrue(browser.search.value.page.tracks.isEmpty())
            advanceTimeBy(SEARCH_TYPING_DEBOUNCE_MS)
            runCurrent()
            releaseOld.complete(Unit)
            runCurrent()
            assertEquals("Current", browser.search.value.query)
            assertEquals("Current", browser.search.value.page.tracks.single().title)
        } finally { releaseOld.complete(Unit) }
    }

    @Test fun sourceAndAddonChangesCannotBypassTheTypingPause() = runTest {
        val browser = CatalogBrowserState(CatalogRepository(), backgroundScope,
            addonSearch = { _, _ -> emptyList() }, catalogSearch = { query, _, _, _ -> page(query) },
            preparationDispatcher = StandardTestDispatcher(testScheduler))
        browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX))
        browser.queryChanged("Song")
        browser.setStreamingAddon(PlaybackIntegrationSettings(addonEnabled = true, addonUrl = "https://addons.example"))
        browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX, enhancer = true))
        runCurrent()
        val worker = CatalogBrowserState::class.java.getDeclaredField("addonSearchJob").apply { isAccessible = true }
        assertNull(worker.get(browser))
        advanceTimeBy(SEARCH_TYPING_DEBOUNCE_MS)
        runCurrent()
        assertNotNull(worker.get(browser))
        assertEquals("Song", browser.search.value.page.tracks.single().title)
    }

    @Test fun explicitRetryDoesNotWaitForTheTypingDebounce() = runTest {
        val calls = mutableListOf<String>()
        val browser = CatalogBrowserState(CatalogRepository(), backgroundScope,
            catalogSearch = { query, _, _, _ -> calls.add(query); page(query) },
            preparationDispatcher = StandardTestDispatcher(testScheduler))
        browser.queryChanged("Song")
        browser.retrySearch()
        runCurrent()
        assertEquals(listOf("Song"), calls)
        assertFalse(browser.search.value.loading)
    }
}
