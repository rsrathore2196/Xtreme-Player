package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.SuccessResult
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.util.ArtworkResolutionInterceptor
import com.example.ui.util.ArtworkVisibility
import com.example.ui.util.buildPlaylistCoverRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Config(sdk = [34], application = Application::class)
class PlaylistArtworkPersistenceTest {
    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun realCoilRequestsReuseMemoryThenPersistentDiskAcrossLoaderRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        BackgroundWorkCoordinator.foreground(false)
        BackgroundWorkCoordinator.firstFrameRendered()
        (BackgroundWorkCoordinator::class.java.getDeclaredField("navigationUntil").apply { isAccessible = true }
            .get(null) as java.util.concurrent.atomic.AtomicLong).set(0L)
        BackgroundWorkCoordinator.interaction()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300))
        val downloads = AtomicInteger()
        val bytes = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.GREEN)
                compress(Bitmap.CompressFormat.PNG, 100, output)
                recycle()
            }
            output.toByteArray()
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val cacheOnly = chain.request().cacheControl.onlyIfCached
            if (!cacheOnly) downloads.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (cacheOnly) 504 else 200).message(if (cacheOnly) "Unsatisfiable cache-only request" else "OK")
                .header("Cache-Control", "public, max-age=86400")
                .body((if (cacheOnly) byteArrayOf() else bytes).toResponseBody("image/png".toMediaType())).build()
        }.build()
        val directory = File(context.cacheDir, "playlist-persistence-check").apply { deleteRecursively(); mkdirs() }
        fun loader() = ImageLoader.Builder(context).components {
            add(ArtworkResolutionInterceptor())
        }.okHttpClient(client).networkObserverEnabled(false).respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(context).maxSizeBytes(4 * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(directory).maxSizeBytes(4L * 1024 * 1024).build() }.build()
        val url = "https://covers.example/playlist.png"
        fun request(dimension: Int) = buildPlaylistCoverRequest(context, url, dimension, ArtworkVisibility(true))
        val first = loader()
        try {
            assertEquals(DataSource.NETWORK, (first.execute(request(600)) as SuccessResult).dataSource)
            assertEquals(DataSource.MEMORY_CACHE, (first.execute(request(600)) as SuccessResult).dataSource)
            assertEquals(1, downloads.get())
        } finally { first.shutdown() }
        val reopened = loader()
        try {
            assertEquals(DataSource.DISK, (reopened.execute(request(600)) as SuccessResult).dataSource)
            // A detail-size decode still shares the same downloaded source file.
            assertEquals(DataSource.DISK, (reopened.execute(request(200)) as SuccessResult).dataSource)
            assertEquals("Reopening/changing decode size cannot redownload a retained playlist source", 1, downloads.get())
        } finally { reopened.shutdown(); directory.deleteRecursively() }
    }
}
