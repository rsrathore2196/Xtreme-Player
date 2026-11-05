package com.example

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.ui.screens.VisibleItemArtwork
import com.example.ui.util.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeSongArtworkUiTest {
    @get:Rule val compose = createComposeRule()

    private fun waitForCover(condition: () -> Boolean) = compose.waitUntil(10_000) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(16)
        condition()
    }

    @Test fun cachedCoverUsesOnlyTheDisplayedStrictSquareRequest() = assertOneDisplayedCover(cachedCover = true)
    @Test fun nativeCoverUsesOnlyTheDisplayedStrictSquareRequest() = assertOneDisplayedCover(cachedCover = false)

    private fun assertOneDisplayedCover(cachedCover: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cover = "https://lh3.googleusercontent.com/cached-album=w600-h600-l90-rj"
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val lookups = java.util.concurrent.atomic.AtomicInteger()
        val ready = java.util.concurrent.atomic.AtomicBoolean()
        val bytes = ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply {
                compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
            }; out.toByteArray()
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(bytes.toResponseBody("image/png".toMediaType())).build()
        }.build()
        val loader = ImageLoader.Builder(app).components { add(ArtworkResolutionInterceptor()) }.okHttpClient(client)
            .networkObserverEnabled(false).eventListener(object : coil.EventListener {
                override fun onStart(request: coil.request.ImageRequest) { requests.incrementAndGet() }
            }).build()
        val previous = Coil.imageLoader(app); Coil.setImageLoader(loader)
        var update by mutableIntStateOf(0)
        val track = MusicTrack("yt_abcdefghijk", "Song", "Singer", "Album", 180_000, "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", "")
        try {
            compose.setContent {
                update
                val request = rememberNativeSongArtworkRequest(track, if (cachedCover) "" else cover, "cached-fixture", cached = { cover.takeIf { cachedCover } },
                    resolve = { _, _ -> lookups.incrementAndGet(); cover })
                assertTrue(request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.requireSquareSongArtwork)
                AsyncImage(request, "Cached cover", Modifier.size(224.dp, 126.dp), onSuccess = { ready.set(true) })
            }
            waitForCover { ready.get() }
            compose.runOnIdle { update++ }; compose.waitForIdle()
            assertEquals("No separate preload/validation request for an already cached candidate", 1, requests.get())
            assertEquals(if (cachedCover) 0 else 1, lookups.get())
        } finally { Coil.setImageLoader(previous); loader.shutdown() }
    }

    @Test fun onlyVisibleCardsResolveSquareCoversAndRetainThemThroughScrollingAndRecomposition() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val bytes = ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.GREEN); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
            }; out.toByteArray()
        }
        val urls = CopyOnWriteArrayList<String>()
        val decoded = ConcurrentHashMap.newKeySet<String>()
        val lookups = CopyOnWriteArrayList<String>()
        val covers = ConcurrentHashMap<String, String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val cacheOnly = chain.request().cacheControl.onlyIfCached
            if (!cacheOnly) urls.add(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (cacheOnly) 504 else 200)
                .message("fixture").body((if (cacheOnly) byteArrayOf() else bytes).toResponseBody("image/png".toMediaType())).build()
        }.build()
        val loader = ImageLoader.Builder(app).components { add(ArtworkResolutionInterceptor()) }
            .okHttpClient(client).networkObserverEnabled(false).respectCacheHeaders(false).build()
        val previous = Coil.imageLoader(app)
        Coil.setImageLoader(loader)
        var active by mutableStateOf(false)
        var unrelated by mutableIntStateOf(0)
        val tracks = (1..12).map { MusicTrack("yt_${NativeFeedFixtures.id(it)}", "Song $it", "Singer $it", "Album", 180_000,
            "https://i.ytimg.com/vi/${NativeFeedFixtures.id(it)}/hq720.jpg", "audio") }
        try {
            compose.setContent {
                unrelated
                CompositionLocalProvider(LocalArtworkRequestsActive provides active) {
                    val state = rememberLazyListState()
                    LazyRow(state = state, modifier = Modifier.width(350.dp).testTag("songs")) {
                        items(tracks, key = { it.id }) { track ->
                            VisibleItemArtwork(state, track.id) {
                                val request = rememberNativeSongArtworkRequest(track, "", "fixture", resolve = { item, _ ->
                                    lookups.add(item.id)
                                    "https://lh3.googleusercontent.com/album-${item.id}=w600-h600-l90-rj".also { covers[item.id] = it }
                                }, cached = { covers[it.id] })
                                AsyncImage(request, track.title, modifier = Modifier.size(163.2.dp), contentScale = nativeSongArtworkContentScale(request),
                                    onSuccess = { result ->
                                        if (result.result.request.data.toString().contains("album-")) decoded.add(track.id)
                                    })
                            }
                        }
                    }
                }
            }
            compose.runOnIdle { assertTrue(lookups.isEmpty()); assertTrue(urls.isEmpty()); active = true }
            waitForCover { decoded.size >= 2 }
            assertTrue("Unseen cards must not start metadata discovery", lookups.distinct().size <= 3)
            assertTrue(urls.all { url -> url.startsWith("https://lh3.googleusercontent.com/album-") ||
                tracks.any { url.contains("/vi/${it.id.removePrefix("yt_")}/") } })
            compose.onNodeWithContentDescription("Song 1").assertWidthIsEqualTo(163.2.dp).assertHeightIsEqualTo(163.2.dp)
            compose.runOnIdle { unrelated++ }
            compose.waitForIdle()
            assertEquals("Recomposition does not repeat metadata discovery", lookups.size, lookups.distinct().size)
            compose.onNodeWithTag("songs").performScrollToIndex(6)
            waitForCover { decoded.contains(tracks[6].id) }
            assertTrue("A whole shelf is never prefetched", lookups.distinct().size < tracks.size)
            val firstLookups = lookups.count { it == tracks.first().id }
            compose.onNodeWithTag("songs").performScrollToIndex(0)
            compose.onNodeWithContentDescription("Song 1").assertIsDisplayed()
            compose.waitForIdle()
            assertEquals(firstLookups, lookups.count { it == tracks.first().id })
            println("Visible song cover evidence: lookups=${lookups.distinct().size}/${tracks.size}, downloads=${urls.size}, decoded=${decoded.size}")
        } finally { Coil.setImageLoader(previous); loader.shutdown() }
    }
}
