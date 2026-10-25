package com.example

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import coil.request.ImageRequest
import coil.transition.Transition
import com.example.data.local.ArtworkCacheDao
import com.example.data.local.ArtworkCacheEntity
import com.example.data.model.MusicTrack
import com.example.data.remote.iTunesApiService
import com.example.data.remote.iTunesSearchResponse
import com.example.data.repository.iTunesArtworkRepository
import com.example.ui.util.*
import kotlinx.coroutines.*
import org.junit.Before
import com.example.performance.BackgroundWorkCoordinator
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerArtworkSourceTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val searches = AtomicInteger()
    private val reads = AtomicInteger()
    private val lookupKeys = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val apple = "https://is1-ssl.mzstatic.com/image/thumb/Music/album/100x100bb.jpg"
    private val track = MusicTrack("yt_recording", "Title", "Artist", "Album", 180_000,
        "https://i.ytimg.com/vi/recording/hqdefault.jpg", "signed-audio", source = "YOUTUBE_MUSIC")

    @Before fun foreground() {
        BackgroundWorkCoordinator.foreground(true)
        BackgroundWorkCoordinator.firstFrameRendered()
        (BackgroundWorkCoordinator::class.java.getDeclaredField("navigationUntil")
            .apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicLong).set(0)
    }
    @After fun stop() { scope.cancel(); BackgroundWorkCoordinator.foreground(false) }
    private fun repository(read: suspend (String) -> String?) = iTunesArtworkRepository(object : iTunesApiService {
        override suspend fun searchSong(term: String, media: String, limit: Int): iTunesSearchResponse {
            searches.incrementAndGet()
            return iTunesSearchResponse()
        }
    }, object : ArtworkCacheDao {
        override suspend fun getUrl(key: String): String? { reads.incrementAndGet(); lookupKeys.add(key); return read(key) }
        override suspend fun getNewest(limit: Int) = emptyList<ArtworkCacheEntity>()
        override suspend fun insert(entity: ArtworkCacheEntity) = Unit
        override suspend fun deleteExpired(expiryTime: Long) = Unit
        override suspend fun getCacheCount() = 0
    }, scope)

    @Test fun coverUsesOneHighQualityItunesUrlAndSurvivesAudioSourceChanges() {
        val repo = repository { apple }
        var song by mutableStateOf(track)
        lateinit var request: ImageRequest
        compose.setContent {
            val prepared = rememberPlayerArtworkRequest(song, true) { repo }
            SideEffect { request = prepared }
        }
        compose.waitUntil(3_000) { request.data is String }
        val first = request
        assertEquals(iTunesArtworkRepository.formatArtworkUrl(apple, 2000), request.data)
        assertEquals(request.data, request.placeholderMemoryCacheKey?.key)
        assertNull(request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER))
        assertSame(Transition.Factory.NONE, request.transitionFactory)
        compose.runOnIdle { song = song.copy(audioUrl = "another-provider", source = "JIOSAAVN", bitrateKbps = 320) }
        compose.runOnIdle { assertSame(first, request) }
        assertEquals(1, reads.get())
        assertEquals(0, searches.get())
    }

    @Test fun unavailableOrLegacyProviderArtworkNeverBecomesAPlayerFallback() {
        val repo = repository { track.coverUrl }
        lateinit var request: ImageRequest
        compose.setContent {
            val prepared = rememberPlayerArtworkRequest(track, true) { repo }
            SideEffect { request = prepared }
        }
        compose.waitUntil(3_000) { reads.get() > 0 }
        compose.runOnIdle {
            assertFalse(request.data is String)
            assertNull(request.placeholderMemoryCacheKey)
        }
        assertEquals(0, searches.get())
    }

    @Test fun latePreviousTrackLookupCannotReplaceTheNewCover() {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val newer = apple.replace("/album/", "/new-album/")
        val repo = repository { key ->
            if (key.startsWith("title_")) {
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                apple
            } else newer
        }
        var song by mutableStateOf(track)
        lateinit var request: ImageRequest
        compose.setContent {
            val prepared = rememberPlayerArtworkRequest(song, true) { repo }
            SideEffect { request = prepared }
        }
        try {
            compose.waitUntil(3_000) { entered.isCompleted }
            compose.runOnIdle { song = song.copy(id = "new-song", title = "New recording") }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.waitUntil(3_000) { request.data.toString().contains("/new-album/") }
            gate.complete(Unit)
            compose.waitForIdle()
            assertEquals(iTunesArtworkRepository.formatArtworkUrl(newer, 2000), request.data)
        } finally { gate.complete(Unit) }
    }
}
