package com.example

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntSize
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import coil.size.Precision
import coil.transition.Transition
import com.example.data.model.MusicTrack
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArtworkRequestIdentityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeRequestRetainsIdentityUntilArtworkMetadataOrMeasuredDimensionsChange() {
        var track by mutableStateOf(MusicTrack(id = "online_test", title = "Title", artist = "Artist",
            album = "Album", durationMs = 180_000, coverUrl = "https://covers.example/art.jpg", audioUrl = "https://audio.example/old"))
        var dimensions by mutableStateOf(IntSize(420, 320))
        lateinit var request: ImageRequest
        compose.setContent {
            val prepared = rememberOptimizedImageRequest(track, ImageConfig.RECOMMENDATION_CARD_SIZE,
                crossfadeEnabled = false, preferStableArtwork = true, decodeSize = dimensions)
            SideEffect { request = prepared }
        }
        compose.waitForIdle()
        val first = request
        assertSame(BackgroundWorkCoordinator.optionalCpuDispatcher, first.decoderDispatcher)
        assertEquals(Size(420, 320), runBlocking { first.sizeResolver.size() })
        assertSame(Transition.Factory.NONE, first.transitionFactory)
        assertEquals(CachePolicy.ENABLED, first.diskCachePolicy)
        assertEquals(CachePolicy.ENABLED, first.memoryCachePolicy)
        assertTrue(first.allowHardware)
        compose.runOnIdle { track = track.copy(audioUrl = "https://audio.example/refreshed", bitrateKbps = 320) }
        compose.runOnIdle { assertSame("Transport refresh must not reload artwork", first, request) }
        compose.runOnIdle { track = track.copy(source = "YOUTUBE_MUSIC", recommendationOnly = true) }
        compose.runOnIdle { assertSame("Actual playback provider and queue policy do not change a cover", first, request) }
        compose.runOnIdle { track = track.copy(title = "Updated title", coverUrl = "https://covers.example/new.jpg") }
        compose.runOnIdle {
            assertNotSame(first, request)
            val metadata = request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!
            assertEquals("https://covers.example/new.jpg", metadata.providerUrl)
        }
        val updated = request
        compose.runOnIdle { dimensions = IntSize(600, 400) }
        compose.runOnIdle { assertNotSame(updated, request) }
        assertEquals(Size(600, 400), runBlocking { request.sizeResolver.size() })
        val sized = request
        compose.runOnIdle { track = track.copy(durationMs = 190_000L) }
        compose.runOnIdle {
            assertNotSame("Corrected metadata keeps its provider image", sized, request)
            assertEquals("https://covers.example/new.jpg", request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.providerUrl)
        }
    }

    @Test fun playerArtworkUsesTheSameProcessWideDecodeBudgetAsHome() {
        lateinit var request: ImageRequest
        compose.setContent {
            val prepared = rememberOptimizedImageRequest("https://covers.example/player.jpg",
                targetSize = ImageConfig.PLAYER_SCREEN_SIZE, decodeSize = IntSize(1080, 1080))
            SideEffect { request = prepared }
        }
        compose.waitForIdle()
        assertSame(com.example.performance.AppDispatchers.CpuBounded, request.decoderDispatcher)
        assertEquals(Size(1080, 1080), runBlocking { request.sizeResolver.size() })
        assertEquals(Precision.INEXACT, request.precision)
    }

    @Test fun heroSourceFollowsPixelBoundsAndDecodesOnlyItsViewport() {
        var dimensions by mutableStateOf(IntSize(480, 240))
        lateinit var request: ImageRequest
        compose.setContent {
            val prepared = rememberOptimizedImageRequest("https://lh3.googleusercontent.com/hero=w60-h60-l90-rj",
                ImageConfig.HERO_BANNER_SIZE, decodeSize = dimensions)
            SideEffect { request = prepared }
        }
        compose.runOnIdle {
            assertTrue(request.data.toString().contains("=w600-h600"))
            assertEquals(Size(480, 240), runBlocking { request.sizeResolver.size() })
            dimensions = IntSize(1800, 720)
        }
        compose.runOnIdle {
            assertTrue(request.data.toString().contains("=w2000-h2000"))
            assertEquals(Size(1800, 720), runBlocking { request.sizeResolver.size() })
            dimensions = IntSize(2560, 960)
        }
        compose.runOnIdle {
            assertTrue(request.data.toString().contains("=w3000-h3000"))
            assertEquals(Precision.INEXACT, request.precision)
            assertEquals(Size(2560, 960), runBlocking { request.sizeResolver.size() })
        }
    }

    @Test @Config(qualifiers = "w1200dp-h800dp-land-xhdpi")
    fun renderedHeroRequestsEnoughSourcePixelsForLargeHighDensityWindows() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val previous = coil.Coil.imageLoader(context)
        val measured = java.util.concurrent.atomic.AtomicReference<Pair<ImageRequest, Size>>()
        val loader = coil.ImageLoader.Builder(context).components {
            add(coil.intercept.Interceptor { chain ->
                measured.set(chain.request to chain.size)
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(context.resources,
                    android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)),
                    chain.request, coil.decode.DataSource.MEMORY_CACHE)
            })
        }.build()
        coil.Coil.setImageLoader(loader)
        try {
            val song = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180000,
                "https://lh3.googleusercontent.com/hero=w60-h60-l90-rj", "")
            compose.setContent { com.example.ui.components.InfiniteRadioHeroCard(song, null, false, onPlayInfiniteRadio = {}) }
            compose.waitUntil(3000) { measured.get() != null }
            val bounds = compose.onNodeWithTag("infinite_radio_hero_card").fetchSemanticsNode().boundsInRoot
            val size = IntSize(bounds.width.toInt(), bounds.height.toInt())
            assertTrue("The fixture must exercise a real viewport wider than the old source hint", size.width > 1200)
            val (request, requestedSize) = checkNotNull(measured.get())
            assertEquals(Size(size.width, size.height), requestedSize)
            assertEquals(mapTargetSizeToArtworkDimension(ImageConfig.HERO_BANNER_SIZE, size),
                request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.sourceDimension)
            assertEquals(Precision.INEXACT, request.precision)
        } finally { coil.Coil.setImageLoader(previous); loader.shutdown() }
    }

    @Test fun retainedTabVisibilityDoesNotRecreateTheArtworkRequest() {
        var visible by mutableStateOf(true)
        lateinit var request: ImageRequest
        compose.setContent {
            CompositionLocalProvider(LocalArtworkRequestsActive provides visible) {
                val prepared = rememberOptimizedImageRequest("https://covers.example/retained.jpg")
                SideEffect { request = prepared }
            }
        }
        compose.waitForIdle()
        val first = request
        val visibility = first.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.visibility!!
        compose.runOnIdle { visible = false }
        compose.runOnIdle { assertSame(first, request); assertFalse(visibility.active.value) }
        compose.runOnIdle { visible = true }
        compose.runOnIdle { assertSame(first, request); assertTrue(visibility.active.value) }
    }
}
