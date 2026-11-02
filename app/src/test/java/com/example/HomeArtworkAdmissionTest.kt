package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.*
import coil.size.Size
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@OptIn(coil.annotation.ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeArtworkAdmissionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** A disk decode fills memory exactly as Coil does; probes expose concurrent consumers. */
    private inner class Cache {
        val memory = ConcurrentHashMap<String, BitmapDrawable>()
        val probes = Channel<Unit>(Channel.UNLIMITED)
        val diskStarts = Channel<String>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val decodes = AtomicInteger()
        fun chain(url: String): Interceptor.Chain {
            val request = ImageRequest.Builder(context).data(url).size(160)
                .setParameter(ARTWORK_METADATA_PARAMETER,
                    ArtworkRequestMetadata(url, 160, visibleThumbnail = true), null).build()
            return object : Interceptor.Chain {
                override val request = request
                override val size = Size(160, 160)
                override fun withRequest(request: ImageRequest) = error("Not needed")
                override fun withSize(size: Size) = error("Already sized")
                override suspend fun proceed(request: ImageRequest): ImageResult {
                    memory[url]?.let { return SuccessResult(it, request, DataSource.MEMORY_CACHE) }
                    if (!request.diskCachePolicy.readEnabled) {
                        probes.send(Unit)
                        return ErrorResult(null, request, IOException("Memory miss"))
                    }
                    check(!request.networkCachePolicy.readEnabled) { "A disk hit must not fetch from the network" }
                    decodes.incrementAndGet()
                    diskStarts.send(url)
                    release.await()
                    val image = BitmapDrawable(context.resources, Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888))
                    memory[url] = image
                    return SuccessResult(image, request, DataSource.DISK)
                }
            }
        }
    }

    @Test fun repeatedVisibleCoverDecodesOnceAndReusesMemoryDuringScrolling() = runBlocking {
        val cache = Cache()
        val url = "https://covers.example/shared.jpg"
        BackgroundWorkCoordinator.foreground(true)
        BackgroundWorkCoordinator.scrolling(true)
        val jobs = List(6) { async { ArtworkResolutionInterceptor().intercept(cache.chain(url)) } }
        try {
            withTimeout(3_000) { repeat(6) { cache.probes.receive() }; cache.diskStarts.receive() }
            assertNull("The same cover must not decode again while its first decode is active",
                withTimeoutOrNull(200) { cache.diskStarts.receive() })
            cache.release.complete(Unit)
            val results = withTimeout(3_000) { jobs.awaitAll() }
            assertEquals(1, cache.decodes.get())
            assertTrue(results.all { it is SuccessResult })
        } finally {
            cache.release.complete(Unit); jobs.forEach { it.cancel() }; jobs.joinAll()
            BackgroundWorkCoordinator.scrolling(false); BackgroundWorkCoordinator.foreground(false)
        }
    }

    @Test fun coldVisibleDiskDecodesAreBoundedButMemoryHitsDoNotWaitForThem() = runBlocking {
        val cache = Cache()
        val jobs = List(8) { index -> async { ArtworkResolutionInterceptor().intercept(cache.chain("https://covers.example/$index.jpg")) } }
        try {
            withTimeout(3_000) { repeat(8) { cache.probes.receive() }; repeat(2) { cache.diskStarts.receive() } }
            assertNull("Visible disk work must use the two existing artwork permits",
                withTimeoutOrNull(200) { cache.diskStarts.receive() })
            val cachedUrl = "https://covers.example/already-loaded.jpg"
            cache.memory[cachedUrl] = BitmapDrawable(context.resources, Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888))
            val hit = withTimeout(1_000) { ArtworkResolutionInterceptor().intercept(cache.chain(cachedUrl)) }
            assertEquals(DataSource.MEMORY_CACHE, (hit as SuccessResult).dataSource)
            cache.release.complete(Unit)
            assertTrue(withTimeout(3_000) { jobs.awaitAll() }.all { it is SuccessResult })
            assertEquals(8, cache.decodes.get())
        } finally { cache.release.complete(Unit); jobs.forEach { it.cancel() }; jobs.joinAll() }
    }
}
