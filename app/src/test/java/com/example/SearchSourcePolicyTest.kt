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

    @Test fun lowerQualityRemovesArchiveWhileKeepingOriginalYoutubeAndJioRows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val browser = CatalogBrowserState(CatalogRepository(), scope,
            archiveSearch = { _, _, _, _ -> InternetArchiveApiService.LosslessSearchPage(listOf(archive)) })
        try {
            seed(browser)
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX))
            awaitCondition { browser.search.value.page.tracks.any { it.id == archive.id } }
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.HIGH))
            assertEquals("Song", browser.search.value.query)
            assertEquals(setOf(jio.id, youtube.id), browser.search.value.page.tracks.map { it.id }.toSet())
            assertFalse(browser.search.value.page.tracks.any { it.id == archive.id })
        } finally { scope.cancel() }
    }

    @Test fun lateArchiveResponseCannotReappearAfterMaximumIsDisabled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val browser = CatalogBrowserState(CatalogRepository(), scope, archiveSearch = { _, _, _, _ ->
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
            awaitCondition { browser.search.value.page.tracks.map { it.id }.toSet() == setOf(jio.id, youtube.id) }
            assertEquals("Song", browser.search.value.query)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun enhancerChangesDoNotHideOriginalYoutubeCatalogRows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val browser = CatalogBrowserState(CatalogRepository(), scope)
        try {
            seed(browser)
            for (enhancer in listOf(true, false, true, false)) {
                browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.DATA_SAVER, enhancer))
                assertEquals(setOf(jio.id, youtube.id), browser.search.value.page.tracks.map { it.id }.toSet())
            }
        } finally { scope.cancel() }
    }

    @Test fun youtubeRowsPublishTogetherWithoutAvailabilityWorkers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tracks = (0..15).map { youtube.copy(id = "yt_aaaaaaaaa${it.toString().padStart(2, '0')}", title = "Song $it") }
        val browser = CatalogBrowserState(CatalogRepository(), scope,
            archiveSearch = { _, _, _, _ -> error("Lower-quality search must not query Archive") })
        try {
            seed(browser, listOf(jio) + tracks)
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.HIGH, enhancer = true))
            assertEquals((listOf(jio) + tracks).map { it.id }.toSet(), browser.search.value.page.tracks.map { it.id }.toSet())
        } finally { scope.cancel() }
    }

    @Test fun cachedArchiveRowsReturnWithoutAnotherLookupAndKeepTheirOwnMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val browser = CatalogBrowserState(CatalogRepository(), scope, archiveSearch = { _, _, _, _ -> calls.incrementAndGet()
                InternetArchiveApiService.LosslessSearchPage(listOf(archive), nextPage = 2) })
        try {
            seed(browser)
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX))
            awaitCondition { browser.search.value.page.tracks.any { it.id == archive.id } }
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.DATA_SAVER))
            assertEquals(setOf(jio.id, youtube.id), browser.search.value.page.tracks.map { it.id }.toSet())
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
        val browser = CatalogBrowserState(CatalogRepository(), scope, archiveSearch = { _, page, _, _ -> pages.add(page)
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
        val browser = CatalogBrowserState(CatalogRepository(), scope, archiveSearch = { _, page, _, _ ->
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
            assertEquals(setOf(jio.id, youtube.id), browser.search.value.page.tracks.map { it.id }.toSet())
            assertNull(browser.search.value.page.nextPage)
            released.complete(Unit)
            withTimeout(3_000) { finished.await() }
            awaitCondition { browser.search.value.page.tracks.map { it.id }.toSet() == setOf(jio.id, youtube.id) }
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

    @Test fun rapidQualityAndEnhancerChangesPreserveYoutubeMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val browser = CatalogBrowserState(CatalogRepository(), scope)
        try {
            seed(browser)
            for (quality in AudioQualityCeiling.entries) for (enhancer in listOf(true, false))
                browser.setSourcePolicy(PlaybackSourcePolicy(quality, enhancer))
            assertEquals(youtube, browser.search.value.page.tracks.single { it.id == youtube.id })
            assertEquals("Song", browser.search.value.query)
        } finally { scope.cancel() }
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

    @Test fun streamingAddonRowsAreIndependentAndOtherSettingsDoNotRepeatTheirLookup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val custom = jio.copy(id = com.example.data.remote.StreamingAddons.catalogTrackId("https://personal.example", "song"), source = "Streaming Addon: Personal", audioUrl = "")
        val browser = CatalogBrowserState(CatalogRepository(), scope, addonSearch = { _, _ ->
            calls.incrementAndGet(); listOf(custom)
        })
        val settings = com.example.data.local.PlaybackIntegrationSettings(addonEnabled = true,
            addonUrl = "https://personal.example")
        try {
            seed(browser, listOf(jio))
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX))
            assertEquals(0, calls.get())
            browser.setStreamingAddon(settings)
            awaitCondition { browser.search.value.page.tracks.any { it.id == custom.id } }
            assertTrue(browser.search.value.page.tracks.any { it.id == jio.id })
            browser.setStreamingAddon(settings.copy(keepLyricsAwake = true))
            assertEquals(1, calls.get())
            browser.setStreamingAddon(settings.copy(addonEnabled = false))
            assertEquals(listOf(jio.id), browser.search.value.page.tracks.map { it.id })
            assertEquals("Song", browser.search.value.query)
        } finally { scope.cancel() }
    }

    @Test fun lateStreamingAddonSearchCannotReappearAfterItIsDisabled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        val browser = CatalogBrowserState(CatalogRepository(), scope, addonSearch = { _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { released.await() }
            finished.complete(Unit)
            listOf(jio.copy(id = com.example.data.remote.StreamingAddons.catalogTrackId("https://personal.example", "song"), source = "Streaming Addon: Personal"))
        })
        val settings = com.example.data.local.PlaybackIntegrationSettings(addonEnabled = true, addonUrl = "https://personal.example")
        try {
            seed(browser, listOf(jio))
            browser.setSourcePolicy(PlaybackSourcePolicy(AudioQualityCeiling.MAX))
            browser.setStreamingAddon(settings)
            withTimeout(3000) { entered.await() }
            browser.setStreamingAddon(settings.copy(addonEnabled = false))
            released.complete(Unit)
            withTimeout(3000) { finished.await() }
            awaitCondition { browser.search.value.page.tracks == listOf(jio) }
            assertEquals("Song", browser.search.value.query)
        } finally { released.complete(Unit); scope.cancel() }
    }
}
