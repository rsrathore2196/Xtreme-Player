package com.example

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.model.MusicTrack
import com.example.ui.screens.ShelfTrackCard
import com.example.ui.util.*
import kotlinx.coroutines.*
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrendingArtworkFallbackUiTest {
    @get:Rule val compose = createComposeRule()
    private val id = NativeFeedFixtures.id(1)
    private val video = "https://i.ytimg.com/vi/$id/hq720.jpg"
    private val square = "https://lh3.googleusercontent.com/album=w600-h600-l90-rj"
    private fun track(cover: String = video) = MusicTrack("yt_$id", "Song", "Singer", "Album", 180_000, cover, "audio")
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(16)
        condition()
    }
    private class Images(fails: (String) -> Boolean = { false }) : AutoCloseable {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val calls = CopyOnWriteArrayList<String>()
        val successes = CopyOnWriteArrayList<SuccessResult>()
        private val previous = Coil.imageLoader(app)
        private fun png(width: Int, height: Int) = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.GREEN)
                compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
            }; output.toByteArray()
        }
        private val videoBytes = png(640, 360)
        private val squareBytes = png(600, 600)
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val cached = request.cacheControl.onlyIfCached
            if (!cached) calls.add(url)
            val failed = cached || fails(url)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (failed) 404 else 200)
                .message("Fixture").body((if (failed) byteArrayOf() else if (url.contains("album")) squareBytes else videoBytes)
                    .toResponseBody("image/png".toMediaType())).build()
        }.build()
        val loader = ImageLoader.Builder(app).diskCache(null).okHttpClient(client)
            .components { add(ArtworkResolutionInterceptor()) }.networkObserverEnabled(false).respectCacheHeaders(false)
            .eventListenerFactory { object : EventListener {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) { successes.add(result) }
            } }.build()
        init { Coil.setImageLoader(loader) }
        override fun close() { Coil.setImageLoader(previous); loader.shutdown() }
    }

    @Test fun videoAppearsBeforeLookupCompletesAndDecodedSquareUpgradesTheSameSquareCard() {
        Images().use { images ->
            val release = CompletableDeferred<String?>()
            val lookups = AtomicInteger()
            val current = AtomicReference<ImageRequest>()
            val song = track()
            compose.setContent {
                val request = rememberNativeSongArtworkRequest(song, "", "account-a", resolve = { _, _ ->
                    lookups.incrementAndGet(); release.await()
                }, cached = { null })
                SideEffect { current.set(request) }
                ShelfTrackCard(song, false, cardBg = Color.DarkGray, artworkRequest = request) {}
            }
            waitFor { images.successes.any { it.request.data.toString().contains("ytimg") } }
            assertFalse(release.isCompleted)
            assertEquals(ContentScale.Fit, nativeSongArtworkContentScale(current.get()))
            compose.onNodeWithContentDescription(song.title, useUnmergedTree = true)
                .assertWidthIsEqualTo(163.2.dp).assertHeightIsEqualTo(163.2.dp)
            release.complete(square)
            waitFor { current.get()?.data.toString().contains("album") && images.successes.any { it.request.data.toString().contains("album") } }
            assertEquals(ContentScale.Crop, nativeSongArtworkContentScale(current.get()))
            assertEquals(1, lookups.get())
            assertEquals("yt_$id", song.id)
            assertEquals(video, song.coverUrl)
        }
    }

    @Test fun failedNativeVideoUsesTrackCoverAndLookupFailureDoesNotBlankItOrRetryOnRecomposition() {
        Images { it.contains("broken") }.use { images ->
            val song = track("https://images.example/same-track.jpg")
            val current = AtomicReference<ImageRequest>()
            val lookups = AtomicInteger()
            var unrelated by mutableIntStateOf(0)
            compose.setContent {
                unrelated
                val request = rememberNativeSongArtworkRequest(song, "", "account-a", resolve = { _, _ ->
                    lookups.incrementAndGet(); throw java.io.IOException("Square lookup timed out")
                }, cached = { null }, landscapeThumbnail = "https://images.example/broken.jpg")
                SideEffect { current.set(request) }
                ShelfTrackCard(song, false, artworkRequest = request) {}
            }
            waitFor { images.successes.any { it.request.data.toString().contains("same-track.jpg") } }
            assertEquals(ContentScale.Fit, nativeSongArtworkContentScale(current.get()))
            assertTrue(images.calls.first().contains("broken"))
            assertEquals(1, lookups.get())
            val count = images.calls.size
            compose.runOnIdle { unrelated++ }
            compose.waitForIdle()
            assertEquals(count, images.calls.size)
            assertEquals(1, lookups.get())
        }
    }

    @Test fun unavailableSourcesTerminateAtTheExistingPlaceholderWithoutRetryLoops() {
        Images { true }.use { images ->
            val current = AtomicReference<ImageRequest>()
            val lookups = AtomicInteger()
            var unrelated by mutableIntStateOf(0)
            compose.setContent {
                unrelated
                val request = rememberNativeSongArtworkRequest(track(), "", "account-a", resolve = { _, _ ->
                    lookups.incrementAndGet(); null
                }, cached = { null })
                SideEffect { current.set(request) }
                ShelfTrackCard(track(), false, artworkRequest = request) {}
            }
            waitFor { current.get()?.data == "" }
            assertTrue(images.successes.isEmpty())
            assertTrue(images.calls.size in 1..12)
            assertEquals("Failed source URLs are not reissued under different fallback slots", images.calls.distinct().size, images.calls.size)
            val count = images.calls.size
            compose.runOnIdle { unrelated++ }
            compose.waitForIdle()
            assertEquals(count, images.calls.size)
            assertEquals(1, lookups.get())
        }
    }

    @Test fun cachedSquareSkipsVideoAndSearchWhileRejectedSquareReturnsToVideo() {
        Images().use { images ->
            val current = AtomicReference<ImageRequest>()
            val lookups = AtomicInteger()
            var badCache by mutableStateOf(false)
            compose.setContent {
                val request = rememberNativeSongArtworkRequest(track(), "", if (badCache) "account-b" else "account-a",
                    resolve = { _, _ -> lookups.incrementAndGet(); null },
                    cached = { if (badCache) "https://lh3.googleusercontent.com/video=w640-h360-l90-rj" else square })
                SideEffect { current.set(request) }
                ShelfTrackCard(track(), false, artworkRequest = request) {}
            }
            waitFor { images.successes.any { it.request.data.toString().contains("album") } }
            assertEquals(0, lookups.get())
            assertFalse(images.calls.any { it.contains("ytimg") })
            compose.runOnIdle { badCache = true }
            waitFor { images.successes.any { it.request.data.toString().contains("ytimg") } }
            assertEquals(ContentScale.Fit, nativeSongArtworkContentScale(current.get()))
        }
    }

    @Test fun accountAndTrackChangesCannotPublishAnObsoleteSquareCover() {
        Images().use { images ->
            val old = CompletableDeferred<String?>()
            val current = AtomicReference<ImageRequest>()
            val lookups = AtomicInteger()
            var account by mutableStateOf("old-account")
            var song by mutableStateOf(track())
            compose.setContent {
                val request = rememberNativeSongArtworkRequest(song, "", account, resolve = { _, _ ->
                    lookups.incrementAndGet()
                    if (account == "old-account") withContext(NonCancellable) { old.await() } else null
                }, cached = { null })
                SideEffect { current.set(request) }
                ShelfTrackCard(song, false, artworkRequest = request) {}
            }
            waitFor { lookups.get() == 1 && images.successes.isNotEmpty() }
            compose.runOnIdle {
                account = "new-account"
                song = track().copy(id = "yt_${NativeFeedFixtures.id(2)}", title = "Second Song",
                    coverUrl = "https://i.ytimg.com/vi/${NativeFeedFixtures.id(2)}/hq720.jpg")
            }
            old.complete(square)
            waitFor { lookups.get() == 2 }
            compose.waitForIdle()
            assertFalse(current.get().data.toString().contains("album"))
            assertTrue(current.get().data.toString().contains("/vi/${NativeFeedFixtures.id(2)}/"))
            assertEquals(ContentScale.Fit, nativeSongArtworkContentScale(current.get()))
        }
    }

    @Test fun failedSquareDownloadKeepsTheVideoFallbackAndNeverMarksItAsAnAlbumCover() {
        Images { it.contains("broken-album") }.use { images ->
            val current = AtomicReference<ImageRequest>()
            val lookups = AtomicInteger()
            compose.setContent {
                val request = rememberNativeSongArtworkRequest(track(), "", "account-a", resolve = { _, _ ->
                    lookups.incrementAndGet(); "https://lh3.googleusercontent.com/broken-album=w600-h600-l90-rj"
                }, cached = { null })
                SideEffect { current.set(request) }
                ShelfTrackCard(track(), false, artworkRequest = request) {}
            }
            waitFor { images.calls.any { it.contains("broken-album") } && images.successes.any { it.request.data.toString().contains("ytimg") } }
            compose.waitForIdle()
            assertEquals(ContentScale.Fit, nativeSongArtworkContentScale(current.get()))
            assertFalse(current.get().parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.requireSquareSongArtwork)
            assertEquals(1, lookups.get())
        }
    }
}
