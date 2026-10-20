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
    private fun seed(browser: CatalogBrowserState) {
        CatalogBrowserState::class.java.getDeclaredField("catalogCandidates").apply { isAccessible = true }
            .set(browser, listOf(jio, youtube))
        val state = CatalogBrowserState::class.java.getDeclaredField("_search").apply { isAccessible = true }
            .get(browser) as MutableStateFlow<CatalogSearchState>
        state.value = CatalogSearchState(query = "Song", type = "Tracks", requestId = 1,
            page = CatalogPage(tracks = listOf(jio, youtube)))
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
}
