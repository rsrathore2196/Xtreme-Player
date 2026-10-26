package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.imageLoader
import coil.memory.MemoryCache
import org.junit.After
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import coil.request.ImageRequest
import com.example.data.model.MusicTrack
import com.example.ui.util.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerArtworkSourceTest {
    @get:Rule val compose = createComposeRule()
    private var previousLoader: ImageLoader? = null
    private var loader: ImageLoader? = null
    @After fun cleanup() { previousLoader?.let(Coil::setImageLoader); loader?.shutdown() }
    private val original = "https://lh3.googleusercontent.com/recording=w120-h120-l90-rj"
    private val track = MusicTrack("yt_abcdefghijk", "Title", "Artist", "Album", 180_000,
        original, "signed-audio", source = "YOUTUBE_MUSIC")

    @Test fun originalProviderCoverIsReadyAtFirstCompositionWithoutLookupOrNavigationWait() {
        lateinit var request: ImageRequest
        compose.setContent { val prepared = rememberPlayerArtworkRequest(track, true); SideEffect { request = prepared } }
        compose.runOnIdle {
            assertEquals(providerArtworkUrl(original, 2000), request.data)
            assertEquals(request.data, request.placeholderMemoryCacheKey?.key)
            assertTrue(request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.currentTrack)
        }
    }

    @Test fun actualAudioProviderAndSignedUrlChangesDoNotRestartCatalogArtwork() {
        var song by mutableStateOf(track)
        lateinit var request: ImageRequest
        compose.setContent { val prepared = rememberPlayerArtworkRequest(song, true); SideEffect { request = prepared } }
        compose.waitForIdle()
        val first = request
        compose.runOnIdle { song = song.copy(audioUrl = "new-stream", source = "JIOSAAVN", bitrateKbps = 320) }
        compose.runOnIdle { assertSame(first, request) }
    }

    @Test fun switchingTracksImmediatelyUsesTheNewCatalogCover() {
        var song by mutableStateOf(track)
        lateinit var request: ImageRequest
        compose.setContent { val prepared = rememberPlayerArtworkRequest(song, true); SideEffect { request = prepared } }
        compose.runOnIdle { song = song.copy(id = "yt_zyxwvutsrqp", coverUrl = original.replace("recording", "new-recording")) }
        compose.runOnIdle { assertTrue(request.data.toString().contains("new-recording")) }
    }

    @Test fun absentThumbnailUsesOnlyTheSameVideosKnownIdentity() {
        lateinit var request: ImageRequest
        compose.setContent { val prepared = rememberPlayerArtworkRequest(track.copy(coverUrl = ""), true); SideEffect { request = prepared } }
        compose.runOnIdle { assertEquals("https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg", request.data) }
    }
    @Test fun playerCanImmediatelyShowTheSameCoverAlreadyCachedByHome() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        previousLoader = context.imageLoader
        loader = ImageLoader.Builder(context).memoryCache { MemoryCache.Builder(context).maxSizeBytes(4 * 1024 * 1024).build() }
            .build().also(Coil::setImageLoader)
        val homeUrl = providerArtworkUrl(original, 600)
        loader!!.memoryCache!![MemoryCache.Key(homeUrl)] = MemoryCache.Value(Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888))
        lateinit var request: ImageRequest
        compose.setContent { val prepared = rememberPlayerArtworkRequest(track, true); SideEffect { request = prepared } }
        compose.runOnIdle {
            assertEquals(providerArtworkUrl(original, 2000), request.data)
            assertEquals("Use the already visible provider image while the larger one loads", homeUrl, request.placeholderMemoryCacheKey?.key)
        }
    }

}
