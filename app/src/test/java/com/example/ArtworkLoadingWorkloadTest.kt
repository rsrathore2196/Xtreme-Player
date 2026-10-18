package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Size
import coil.target.Target
import com.example.data.local.ArtworkCacheDao
import com.example.data.local.ArtworkCacheEntity
import com.example.data.remote.iTunesApiService
import com.example.data.remote.iTunesSearchResponse
import com.example.data.remote.iTunesSongResult
import com.example.data.repository.AudioSource
import com.example.data.repository.MediaItem
import com.example.data.repository.iTunesArtworkRepository
import com.example.ui.util.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class, coil.annotation.ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArtworkLoadingWorkloadTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val provider = "https://covers.example/provider.jpg"
    private val apple = "https://covers.example/600x600bb.jpg"
    private val item get() = MediaItem("Test Song", "Test Artist", AudioSource.JIOSAAVN, provider, 180_000)
    private lateinit var lookupScope: CoroutineScope
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    @After fun teardown() {
        runBlocking { lookupScope.coroutineContext[Job]!!.cancelAndJoin() }
        Dispatchers.resetMain()
    }

    private class Dao : ArtworkCacheDao {
        val rows = ConcurrentHashMap<String, ArtworkCacheEntity>()
        override suspend fun getUrl(key: String) = rows[key]?.rawArtworkUrl
        override suspend fun getNewest(limit: Int) = rows.values.take(limit)
        override suspend fun insert(entity: ArtworkCacheEntity) { rows[entity.cacheKey] = entity }
        override suspend fun deleteExpired(expiryTime: Long) = Unit
        override suspend fun getCacheCount() = rows.size
    }
    private fun repository(search: suspend () -> iTunesSearchResponse) = iTunesArtworkRepository(
        object : iTunesApiService {
            override suspend fun searchSong(term: String, media: String, limit: Int): iTunesSearchResponse {
                assertNotEquals("Metadata lookup must stay off Main", Looper.getMainLooper(), Looper.myLooper())
                return search()
            }
        }, Dao(), lookupScope)
    private fun match() = iTunesSearchResponse(1, listOf(iTunesSongResult(
        "https://covers.example/100x100bb.jpg", "Test Song", "Test Artist", durationMs = 180_000)))
    private fun request(target: Target? = object : Target {}) = ImageRequest.Builder(context)
        .data(provider).size(300, 300).target(target)
        .setParameter(ARTWORK_METADATA_PARAMETER, ArtworkRequestMetadata(item, provider, 600), null)
        .build()

    private inner class Chain(
        override val request: ImageRequest,
        val cached: Set<String> = emptySet(),
        val failures: Set<String> = emptySet(),
        val cachedDimension: Int = 600,
        override val size: Size = Size(300, 300),
        val calls: MutableList<Pair<String, Boolean>> = CopyOnWriteArrayList()
    ) : Interceptor.Chain {
        override fun withRequest(request: ImageRequest) = Chain(request, cached, failures, cachedDimension, size, calls)
        override fun withSize(size: Size) = Chain(request, cached, failures, cachedDimension, size, calls)
        override suspend fun proceed(request: ImageRequest): ImageResult {
            val url = request.data.toString()
            val remote = request.networkCachePolicy.readEnabled
            calls.add(url to remote)
            if ((!remote && url !in cached) || url in failures)
                return ErrorResult(null, request, IOException("No available image"))
            val dimension = if (!remote) cachedDimension else 600
            val drawable = BitmapDrawable(context.resources,
                Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888))
            return SuccessResult(drawable, request, if (remote) DataSource.NETWORK else DataSource.DISK)
        }
    }

    @Test fun aColdCardFetchesOnlyThePreferredImageWithoutSpeculativeProviderDownload() = runBlocking {
        val repo = repository { match() }
        val chain = Chain(request())
        val result = ArtworkResolutionInterceptor { repo }.intercept(chain) as SuccessResult
        assertEquals(apple, result.request.data)
        assertEquals(listOf(apple to true), chain.calls.filter { it.second })
        assertEquals(Size(300, 300), chain.size)
    }

    @Test fun cachedProviderStaysVisibleWhileITunesResolvesWithoutAnotherProviderDownload() = runBlocking {
        var interim: Drawable? = null
        val repo = repository { assertNotNull("Cached cover must appear before lookup completes", interim); match() }
        val chain = Chain(request(object : Target {
            override fun onStart(placeholder: Drawable?) { interim = placeholder }
        }), cached = setOf(provider))
        val result = ArtworkResolutionInterceptor { repo }.intercept(chain) as SuccessResult
        assertNotNull(interim)
        assertEquals(apple, result.request.data)
        assertEquals(listOf(apple to true), chain.calls.filter { it.second })
    }

    @Test fun providerIsFetchedRemotelyOnlyAfterPreferredArtworkIsUnavailable() = runBlocking {
        val searches = AtomicInteger()
        val repo = repository { searches.incrementAndGet(); iTunesSearchResponse() }
        val chain = Chain(request())
        val result = ArtworkResolutionInterceptor { repo }.intercept(chain) as SuccessResult
        assertTrue(searches.get() > 0)
        assertEquals(provider, result.request.data)
        assertEquals(listOf(provider to true), chain.calls.filter { it.second })
    }

    @Test fun failedPreferredImageStillRecoversProviderArtwork() = runBlocking {
        val repo = repository { match() }
        val chain = Chain(request(), failures = setOf(apple))
        val result = ArtworkResolutionInterceptor { repo }.intercept(chain) as SuccessResult
        assertEquals(provider, result.request.data)
        assertEquals(listOf(apple to true, provider to true), chain.calls.filter { it.second })
    }

    @Test fun reenteringACardReusesResolvedIdentityAndDoesNotRepeatMetadataSearch() = runBlocking {
        val searches = AtomicInteger()
        val repo = repository { searches.incrementAndGet(); match() }
        assertEquals(apple, repo.resolveArtwork(item, 600))
        val chain = Chain(request())
        val result = ArtworkResolutionInterceptor { repo }.intercept(chain) as SuccessResult
        assertEquals(apple, result.request.data)
        assertEquals(1, searches.get())
        assertEquals(listOf(apple to true), chain.calls)
    }

    @Test fun blurryCachedProviderDoesNotPreventARealResolutionImprovement() = runBlocking {
        val repo = repository { iTunesSearchResponse() }
        val chain = Chain(request(), cached = setOf(provider), cachedDimension = 20)
        val result = ArtworkResolutionInterceptor { repo }.intercept(chain) as SuccessResult
        assertEquals(DataSource.NETWORK, result.dataSource)
        assertEquals(600, result.drawable.intrinsicWidth)
        assertEquals(listOf(provider to true), chain.calls.filter { it.second })
    }

    @Test fun cancellingTheCardCancelsItsUnneededLookupAndPublishesNoLateImage() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val repo = repository {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val starts = AtomicInteger()
        val chain = Chain(request(object : Target {
            override fun onStart(placeholder: Drawable?) { starts.incrementAndGet() }
        }))
        val job = async { ArtworkResolutionInterceptor { repo }.intercept(chain) }
        withTimeout(5_000) { entered.await() }
        job.cancelAndJoin()
        withTimeout(5_000) { cancelled.await() }
        assertEquals(0, starts.get())
        assertTrue(chain.calls.none { it.second })
    }

    @Test fun oneCancelledConsumerDoesNotCancelAnotherCardsSharedMetadataLookup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val searches = AtomicInteger()
        val repo = repository { searches.incrementAndGet(); entered.complete(Unit); finish.await(); match() }
        val first = async { repo.resolveArtwork(item, 200) }
        withTimeout(5_000) { entered.await() }
        // Start undispatched so the second reader joins before the first departs.
        val second = async(start = CoroutineStart.UNDISPATCHED) { repo.resolveArtwork(item, 600) }
        // Both metadata entry points suspend onto the shared dispatcher. Wait for
        // the actual two-reader state rather than relying on a timer.
        withTimeout(5_000) {
            while (true) {
                val field = iTunesArtworkRepository::class.java.getDeclaredField("inFlight").apply { isAccessible = true }
                val flights = field.get(repo) as MutableMap<*, *>
                val readers = synchronized(flights) { flights.values.firstOrNull()?.let {
                    it.javaClass.getDeclaredField("readers").apply { isAccessible = true }.getInt(it)
                } ?: 0 }
                if (readers == 2) break
                yield()
            }
        }
        first.cancelAndJoin()
        finish.complete(Unit)
        assertEquals(apple, withTimeout(5_000) { second.await() })
        assertEquals(1, searches.get())
    }
}
