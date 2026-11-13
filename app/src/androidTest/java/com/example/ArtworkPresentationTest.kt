package com.example

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.ImageRequest
import coil.request.Options
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogAlbum
import com.example.data.repository.CatalogDestination
import com.example.data.repository.CatalogPlaylist
import com.example.performance.BackgroundWorkCoordinator
import com.example.playback.PlayerUiState
import com.example.ui.components.PlayerCoverArtwork
import com.example.ui.screens.CatalogDetailScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.util.*
import com.example.ui.viewmodel.CatalogDetailState
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ArtworkPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var previous: ImageLoader
    private lateinit var loader: ImageLoader
    private val fetches = AtomicInteger()
    private val failures = AtomicInteger()
    private val square = "https://lh3.googleusercontent.com/song-album=w600-h600-l90-rj"
    private val rectangle = "https://lh3.googleusercontent.com/shelf-video=w640-h360-l90-rj"
    private val track = MusicTrack("yt_squaretest1", "Song", "Singer", "Album", 180_000, rectangle, "audio")

    @Before fun setUp() {
        BackgroundWorkCoordinator.foreground(true)
        BackgroundWorkCoordinator.firstFrameRendered()
        previous = Coil.imageLoader(context)
        loader = ImageLoader.Builder(context).components {
            add(coil.intercept.Interceptor { chain ->
                chain.proceed(chain.request).also { if (it is coil.request.ErrorResult) failures.incrementAndGet() }
            })
            add(ArtworkResolutionInterceptor())
            add(object : Fetcher.Factory<Any> {
                override fun create(data: Any, options: Options, imageLoader: ImageLoader) = Fetcher {
                    fetches.incrementAndGet()
                    val landscape = data.toString().contains("shelf-video")
                    val image = Bitmap.createBitmap(160, if (landscape) 90 else 160, Bitmap.Config.ARGB_8888)
                    Canvas(image).apply {
                        drawColor(android.graphics.Color.GREEN)
                        drawRect(0f, 0f, 160f, image.height * .20f, Paint().apply { color = android.graphics.Color.RED })
                        drawRect(0f, image.height * .80f, 160f, image.height.toFloat(), Paint().apply { color = android.graphics.Color.BLUE })
                    }
                    DrawableResult(BitmapDrawable(context.resources, image), false, DataSource.MEMORY_CACHE)
                }
            })
        }.networkObserverEnabled(false).build()
        Coil.setImageLoader(loader)
    }

    @After fun tearDown() {
        Coil.setImageLoader(previous)
        loader.shutdown()
        BackgroundWorkCoordinator.foreground(false)
    }

    @Test fun albumHeaderKeepsSquareArtAcrossWidthsAndScreenHeights() = verifyHeader(playlist = false)
    @Test fun playlistHeaderKeepsSquareArtAcrossWidthsAndScreenHeights() = verifyHeader(playlist = true)

    private fun verifyHeader(playlist: Boolean) {
        var width by mutableStateOf(260.dp)
        var screenHeight by mutableIntStateOf(600)
        val tag = if (playlist) "playlist_profile_artwork" else "album_profile_artwork"
        val destination = if (playlist) CatalogDestination.Playlist(CatalogPlaylist("test", "Test playlist", square))
            else CatalogDestination.Album(CatalogAlbum("test", "Test album", emptyList(), square, "2026"))
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
                screenHeightDp = screenHeight
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MyApplicationTheme {
                    Box(Modifier.width(width)) {
                        CatalogDetailScreen(CatalogDetailState(destination), PlayerUiState(), {}, {}, { _, _ -> }, {}, { _, _ -> }, handleBack = false)
                    }
                }
            }
        }
        fun verify() {
            compose.waitUntil(30_000) { fetches.get() > 0 }
            val node = compose.onNodeWithTag(tag)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertEquals("Art must be square independent of the screen's height", bounds.width, bounds.height, 1f)
            compose.waitUntil(30_000) {
                val pixels = node.captureToImage().toPixelMap()
                pixels[pixels.width / 2, pixels.height / 2].green > .9f
            }
            val pixels = node.captureToImage().toPixelMap()
            assertTrue("The top of the cover must remain visible", pixels[pixels.width / 2, (pixels.height * .15f).toInt()].red > .9f)
            assertTrue("The bottom of the cover must remain visible", pixels[pixels.width / 2, (pixels.height * .85f).toInt()].blue > .9f)
        }
        verify()
        compose.runOnIdle { width = 300.dp; screenHeight = 1000 }
        verify()
    }

    @Test fun visiblePlayerResolvesSquareSongArtWithoutChangingTheShelfOrPlayback() {
        var active by mutableStateOf(false)
        val calls = AtomicInteger()
        val request = AtomicReference<ImageRequest>()
        compose.setContent {
            val artwork = rememberPlayerArtworkRequest(track, active, resolveCover = { calls.incrementAndGet(); square })
            SideEffect { request.set(artwork) }
        }
        compose.runOnIdle { assertEquals(0, calls.get()); active = true }
        compose.waitUntil(30_000) { request.get().data.toString().contains("song-album") }
        val metadata = request.get().parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!
        assertTrue(metadata.requireSquareSongArtwork)
        assertFalse(metadata.allowVideoFallback)
        assertEquals(1, calls.get())
        assertEquals(rectangle, track.coverUrl)
        assertEquals("audio", track.audioUrl)
        compose.runOnIdle { active = false }
        compose.waitForIdle()
        compose.runOnIdle { active = true }
        compose.waitForIdle()
        assertEquals("Reopening reuses the verified song cover", 1, calls.get())
    }

    @Test fun aRectangularCandidateCannotReplaceThePlayersSongArtwork() {
        val request = AtomicReference<ImageRequest>()
        compose.setContent {
            val artwork = rememberPlayerArtworkRequest(track.copy(id = "yt_invalidart1"), true,
                resolveCover = { rectangle.replace("shelf-video", "shelf-video-rejected") })
            SideEffect { request.set(artwork) }
        }
        compose.waitUntil(30_000) { failures.get() > 0 }
        assertFalse(request.get().data.toString().contains("rejected"))
    }

    @Test fun aLateCoverForThePreviousTrackCannotReplaceTheNewTrack() {
        val release = CompletableDeferred<String>()
        val entered = AtomicInteger()
        var song by mutableStateOf(track.copy(id = "yt_oldcover001"))
        val request = AtomicReference<ImageRequest>()
        compose.setContent {
            val artwork = rememberPlayerArtworkRequest(song, true, resolveCover = {
                entered.incrementAndGet()
                if (it.id == "yt_oldcover001") release.await() else null
            })
            SideEffect { request.set(artwork) }
        }
        compose.waitUntil(30_000) { entered.get() == 1 }
        compose.runOnIdle { song = song.copy(id = "yt_newcover001", coverUrl = rectangle.replace("shelf-video", "new-video")) }
        compose.waitUntil(30_000) { entered.get() == 2 }
        release.complete(square)
        compose.runOnIdle { assertTrue(request.get().data.toString().contains("new-video")) }
    }

    @Test fun rectangularFallbackFillsTheSquareWithoutLetterboxing() {
        var ready by mutableStateOf(false)
        val request = ImageRequest.Builder(context).data(rectangle).crossfade(false).build()
        compose.setContent {
            Box(Modifier.size(200.dp).background(Color.Magenta).testTag("player_square")) {
                PlayerCoverArtwork(request, Modifier.fillMaxSize(), onReady = { ready = true })
            }
        }
        compose.waitUntil(30_000) { ready }
        val pixels = compose.onNodeWithTag("player_square").captureToImage().toPixelMap()
        assertTrue(pixels[pixels.width / 2, 1].red > .9f)
        assertTrue(pixels[pixels.width / 2, pixels.height - 2].blue > .9f)
    }

    @Test fun bitmapSamplingAvoidsWhiteAndIsIndependentOfColorMode() {
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            Canvas(bitmap).drawRect(40f, 40f, 80f, 80f, Paint().apply { color = 0xff1838bc.toInt() })
            val dark = extractPlayerArtworkColors(bitmap, true)!!
            assertEquals(dark, extractPlayerArtworkColors(bitmap, false))
            assertEquals(Color(0xff1838bc), dark.dominantColor)
            assertEquals(dark.dominantColor, dark.topEdgeColor)
            assertEquals(dark.dominantColor, dark.bottomEdgeColor)
        } finally { bitmap.recycle() }
    }
}
