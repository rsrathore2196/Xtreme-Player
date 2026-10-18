package com.example

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
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
        compose.runOnIdle { track = track.copy(title = "Updated title", coverUrl = "https://covers.example/new.jpg") }
        compose.runOnIdle {
            assertNotSame(first, request)
            val metadata = request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!
            assertEquals("Updated title", metadata.item!!.rawTitle)
            assertEquals("https://covers.example/new.jpg", metadata.providerUrl)
        }
        val updated = request
        compose.runOnIdle { dimensions = IntSize(600, 400) }
        compose.runOnIdle { assertNotSame(updated, request) }
        assertEquals(Size(600, 400), runBlocking { request.sizeResolver.size() })
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
    }
}
