package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.remote.InternetArchiveApiService
import com.example.data.repository.CatalogPage
import com.example.data.repository.CatalogRepository
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSourcePolicy
import com.example.ui.viewmodel.CatalogBrowserState
import com.example.ui.viewmodel.CatalogSearchState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SearchSourcePolicyTest {
    private val jio = MusicTrack("online_song", "Song", "Artist", "Album", 180_000, "cover", "https://aac.saavncdn.com/song_320.mp4")
    private val youtube = jio.copy(id = "yt_abcdefghijk", source = "YouTube Music", audioUrl = "")
    private val archive = jio.copy(id = "ia_album:song.flac", source = "INTERNET_ARCHIVE", isLossless = true,
        audioUrl = "https://archive.org/download/album/song.flac")

    @Suppress("UNCHECKED_CAST")
    private fun seed(browser: CatalogBrowserState, tracks: List<MusicTrack> = listOf(jio, youtube), type: String = "Tracks") {
        CatalogBrowserState::class.java.getDeclaredField("searchGeneration").apply { isAccessible = true }.setInt(browser, 1)
        CatalogBrowserState::class.java.getDeclaredField("catalogCandidates").apply { isAccessible = true }
            .set(browser, tracks)
        val state = CatalogBrowserState::class.java.getDeclaredField("_search").apply { isAccessible = true }
            .get(browser) as MutableStateFlow<CatalogSearchState>
        state.value = CatalogSearchState(query = "Song", type = type, requestId = 1,
            page = CatalogPage(tracks = tracks))
    }
    private suspend fun awaitCondition(test: () -> Boolean) = withTimeout(3_000) { while (!test()) delay(10) }

    @Test fun lowerQualityImmediatelyRemovesArchiveAndUnverifiedYoutubeWithoutClearingQueryOrJio() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val verify = CompletableDeferred<Boolean>()
        val browser = CatalogBrowserState(CatalogRepository(), scope,
            verifyPlayback = { _, _ -> verify.await() },
            archiveSearch = { _, _, _, _ -> InternetArchiveApiService.LosslessSearchPage(listOf(archive)) })
        try {
            seed(browser)
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX))
            awaitCondition { browser.search.value.page.tracks.any { it.id == archive.id } }
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.HIGH))
            assertEquals("Song", browser.search.value.query)
            assertEquals(listOf(jio.id), browser.search.value.page.tracks.map { it.id })
            verify.complete(true)
            awaitCondition { browser.search.value.page.tracks.any { it.id == youtube.id } }
            assertFalse(browser.search.value.page.tracks.any { it.id == archive.id })
        } finally { scope.cancel() }
    }

    @Test fun lateArchiveResponseCannotReappearAfterMaximumIsDisabled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val browser = CatalogBrowserState(CatalogRepository(), scope, verifyPlayback = { _, _ -> false },
            archiveSearch = { _, _, _, _ ->
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                finished.complete(Unit)
                InternetArchiveApiService.LosslessSearchPage(listOf(archive))
            })
        try {
            seed(browser)
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX))
            withTimeout(3_000) { started.await() }
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.NORMAL))
            release.complete(Unit)
            withTimeout(3_000) { finished.await() }
            awaitCondition { browser.search.value.page.tracks == listOf(jio) }
            assertEquals("Song", browser.search.value.query)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun enhancerChangesRecheckYoutubeEligibilityWhileKeepingJioVisible() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val browser = CatalogBrowserState(CatalogRepository(), scope, verifyPlayback = { _, policy -> policy.preferOpus })
        try {
            seed(browser)
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.DATA_SAVER, enhancer = true))
            awaitCondition { browser.search.value.page.tracks.any { it.id == youtube.id } }
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.DATA_SAVER, enhancer = false))
            assertEquals(listOf(jio.id), browser.search.value.page.tracks.map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun failedYoutubeRowsCannotStarveLaterRowsAndVerificationStaysBounded() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val checked = java.util.concurrent.atomic.AtomicInteger()
        val tracks = (0..15).map { youtube.copy(id = "yt_aaaaaaaaa${it.toString().padStart(2, '0')}", title = "Song $it") }
        val browser = CatalogBrowserState(CatalogRepository(), scope, verifyPlayback = { track, _ ->
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                delay(10)
                checked.incrementAndGet()
                val index = track.id.takeLast(2).toInt()
                if (index == 0) throw java.io.IOException("Transient lookup failure")
                index >= 14
            } finally { active.decrementAndGet() }
        }, archiveSearch = { _, _, _, _ -> error("Lower-quality search must not query Archive") })
        try {
            seed(browser, listOf(jio) + tracks)
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.HIGH, enhancer = true))
            awaitCondition { checked.get() == 16 && browser.search.value.page.tracks.size == 3 }
            assertEquals(setOf(jio.id, tracks[14].id, tracks[15].id), browser.search.value.page.tracks.map { it.id }.toSet())
            assertTrue(peak.get() in 1..2)
        } finally { scope.cancel() }
    }

    @Test fun cachedArchiveRowsReturnWithoutAnotherLookupAndKeepTheirOwnMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val browser = CatalogBrowserState(CatalogRepository(), scope, verifyPlayback = { _, _ -> false },
            archiveSearch = { _, _, _, _ -> calls.incrementAndGet()
                InternetArchiveApiService.LosslessSearchPage(listOf(archive), nextPage = 2) })
        try {
            seed(browser)
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX))
            awaitCondition { browser.search.value.page.tracks.any { it.id == archive.id } }
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.DATA_SAVER))
            assertEquals(listOf(jio.id), browser.search.value.page.tracks.map { it.id })
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX))
            awaitCondition { browser.search.value.page.tracks.any { it.id == archive.id } }
            assertEquals(archive, browser.search.value.page.tracks.single { it.id == archive.id })
            assertEquals(1, calls.get())
            assertEquals(2, browser.search.value.page.nextPage)
            assertEquals("Song", browser.search.value.query)
        } finally { scope.cancel() }
    }

    @Test fun archiveOnlyPaginationDoesNotRestartOrdinaryCatalogSearch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pages = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val second = archive.copy(id = "ia_album:second.flac", audioUrl = "https://archive.org/download/album/second.flac")
        val browser = CatalogBrowserState(CatalogRepository(), scope, verifyPlayback = { _, _ -> true },
            archiveSearch = { _, page, _, _ -> pages.add(page)
                InternetArchiveApiService.LosslessSearchPage(listOf(if (page == 1) archive else second),
                    nextPage = if (page == 1) 2 else null) })
        try {
            seed(browser)
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX))
            awaitCondition { browser.search.value.page.tracks.any { it.id == archive.id } }
            // The page is already visible, but its previous worker still owns the
            // finalization phase. The next click must wait rather than get lost.
            val finishingWorker = Job(scope.coroutineContext[Job])
            CatalogBrowserState::class.java.getDeclaredField("archiveSearchJob").apply { isAccessible = true }
                .set(browser, finishingWorker)
            browser.loadMoreSearch()
            browser.loadMoreSearch()
            assertTrue(browser.search.value.loadingMore)
            assertEquals(listOf(1), pages.toList())
            finishingWorker.complete()
            awaitCondition { browser.search.value.page.tracks.any { it.id == second.id } && !browser.search.value.loadingMore }
            assertEquals(listOf(1, 2), pages.toList())
            assertNull(browser.search.value.page.nextPage)
            assertTrue(browser.search.value.page.tracks.any { it.id == jio.id })
            assertEquals("Song", browser.search.value.query)
        } finally { scope.cancel() }
    }

    @Test fun downgradeCancelsArchivePaginationAndClearsItsLoadingState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val browser = CatalogBrowserState(CatalogRepository(), scope, verifyPlayback = { _, _ -> false },
            archiveSearch = { _, page, _, _ ->
                if (page == 2) { started.complete(Unit); withContext(NonCancellable) { released.await() }; finished.complete(Unit) }
                InternetArchiveApiService.LosslessSearchPage(listOf(archive), nextPage = 2)
            })
        try {
            seed(browser)
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX))
            awaitCondition { browser.search.value.page.tracks.any { it.id == archive.id } }
            browser.loadMoreSearch()
            withTimeout(3_000) { started.await() }
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.NORMAL))
            assertFalse(browser.search.value.loadingMore)
            assertEquals(listOf(jio.id), browser.search.value.page.tracks.map { it.id })
            assertNull(browser.search.value.page.nextPage)
            released.complete(Unit)
            withTimeout(3_000) { finished.await() }
            awaitCondition { browser.search.value.page.tracks == listOf(jio) }
        } finally { released.complete(Unit); scope.cancel() }
    }

    @Test fun lateArchiveDataCannotLeakIntoANewQuery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val browser = CatalogBrowserState(CatalogRepository(), scope, archiveSearch = { _, _, _, _ ->
            started.complete(Unit)
            withContext(NonCancellable) { released.await() }
            finished.complete(Unit)
            InternetArchiveApiService.LosslessSearchPage(listOf(archive))
        })
        try {
            seed(browser)
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX))
            withTimeout(3_000) { started.await() }
            browser.queryChanged("")
            released.complete(Unit)
            withTimeout(3_000) { finished.await() }
            assertEquals("", browser.search.value.query)
            assertTrue(browser.search.value.page.tracks.isEmpty())
        } finally { released.complete(Unit); scope.cancel() }
    }

    @Test fun lateOpusVerificationCannotMakeAnUnmatchedRowPlayableAfterEnhancerIsDisabled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val browser = CatalogBrowserState(CatalogRepository(), scope, verifyPlayback = { _, policy ->
            if (policy.preferOpus) {
                started.complete(Unit)
                withContext(NonCancellable) { released.await() }
                finished.complete(Unit)
                true
            } else false
        })
        try {
            seed(browser)
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.HIGH, enhancer = true))
            withTimeout(3_000) { started.await() }
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.HIGH, enhancer = false))
            released.complete(Unit)
            withTimeout(3_000) { finished.await() }
            awaitCondition { browser.search.value.page.tracks == listOf(jio) }
            assertEquals("Song", browser.search.value.query)
        } finally { released.complete(Unit); scope.cancel() }
    }

    @Test fun archiveResultsUseTheExistingRelevanceRankingWithoutLosingTheirProvider() {
        val ordinary = (0..10).map { jio.copy(id = "online_$it", title = "Song related $it") }
        val router = com.example.data.remote.AudioRoutingService
        assertFalse(router.rankTracks("Song", ordinary + archive).any { it.id == archive.id })
        val ranked = router.rankTracks("Song", ordinary + archive, includeArchive = true)
        assertEquals(archive, ranked.first())
        assertEquals(archive.audioUrl, ranked.first().audioUrl)
        assertEquals("INTERNET_ARCHIVE", ranked.first().source)
    }
}
