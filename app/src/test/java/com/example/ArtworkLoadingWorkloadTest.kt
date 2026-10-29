package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Size
import coil.target.Target
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
    private val provider = "https://covers.example/album.jpg"
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val coordinator = com.example.performance.BackgroundWorkCoordinator
        coordinator.foreground(false)
        (coordinator::class.java.getDeclaredField("navigationUntil").apply { isAccessible = true }
            .get(null) as java.util.concurrent.atomic.AtomicLong).set(0L)
        coordinator.interaction()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300))
    }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun request(target: Target? = object : Target {}) = ImageRequest.Builder(context)
        .data(provider).size(300, 300).target(target)
        .setParameter(ARTWORK_METADATA_PARAMETER, ArtworkRequestMetadata(provider, 600), null).build()

    private inner class Chain(
        override val request: ImageRequest,
        val cached: Set<String> = emptySet(),
        val failures: Set<String> = emptySet(),
        val cachedDimension: Int = 600,
        override val size: Size = Size(300, 300),
        val calls: MutableList<Pair<String, Boolean>> = CopyOnWriteArrayList(),
        val onNetwork: suspend (String) -> Unit = {},
        val dimensions: Map<String, Int> = emptyMap(),
        val densityScaled: Boolean = false,
        val nativeCache: Boolean = false
    ) : Interceptor.Chain {
        override fun withRequest(request: ImageRequest) = Chain(request, cached, failures, cachedDimension, size, calls, onNetwork, dimensions, densityScaled, nativeCache)
        override fun withSize(size: Size) = Chain(request, cached, failures, cachedDimension, size, calls, onNetwork, dimensions, densityScaled, nativeCache)
        override suspend fun proceed(request: ImageRequest): ImageResult {
            val url = request.data.toString()
            val remote = request.networkCachePolicy.readEnabled
            calls.add(url to remote)
            if ((!remote && url !in cached) || url in failures)
                return ErrorResult(null, request, IOException("No available image"))
            if (remote) onNetwork(url)
            val dimension = dimensions[url] ?: if (!remote) cachedDimension else 600
            val drawable = BitmapDrawable(context.resources,
                Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888))
            if (densityScaled) {
                drawable.bitmap.density = 160
                drawable.setTargetDensity(480)
            }
            return SuccessResult(drawable, request, if (remote) DataSource.NETWORK else DataSource.DISK,
                isSampled = !nativeCache && !remote && dimension < 300)
        }
    }

    @Test fun failedHiddenPreparationWaitsForARealVisitRatherThanFailingOrRetryingInABackgroundLoop() = runBlocking {
        val batch = ViewportArtworkPreparation()
        val visibility = ArtworkVisibility(false)
        visibility.prepare(checkNotNull(batch.register("cover")))
        val req = request().newBuilder().setParameter(ARTWORK_METADATA_PARAMETER,
            ArtworkRequestMetadata(provider, 600, visibility = visibility), null).build()
        var attempts = 0
        val chain = Chain(req, onNetwork = { if (++attempts == 1) throw IOException("offline during preparation") })
        val image = async { ArtworkResolutionInterceptor().intercept(chain) }
        try {
            withTimeout(3_000) { batch.awaitCompletion() }
            assertEquals(1, attempts)
            assertTrue(image.isActive)
            batch.close()
            visibility.active.value = true
            assertTrue(withTimeout(3_000) { image.await() } is SuccessResult)
            assertEquals(2, attempts)
        } finally { batch.close(); image.cancelAndJoin() }
    }

    @Test fun endingHiddenPreparationCancelsItsFetchButRetainsThePainterRequestForFirstVisit() = runBlocking {
        val batch = ViewportArtworkPreparation()
        val visibility = ArtworkVisibility(false)
        visibility.prepare(checkNotNull(batch.register("cover")))
        val req = request().newBuilder().setParameter(ARTWORK_METADATA_PARAMETER,
            ArtworkRequestMetadata(provider, 600, visibility = visibility), null).build()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var attempts = 0
        val chain = Chain(req, onNetwork = {
            if (++attempts == 1) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
        })
        val image = async { ArtworkResolutionInterceptor().intercept(chain) }
        try {
            withTimeout(3_000) { entered.await() }
            batch.close()
            withTimeout(3_000) { stopped.await() }
            assertTrue(image.isActive)
            visibility.active.value = true
            assertTrue(withTimeout(3_000) { image.await() } is SuccessResult)
            assertEquals(2, attempts)
        } finally { batch.close(); image.cancelAndJoin() }
    }

    @Test fun providerArtworkLoadsDirectlyWithoutAnyMetadataSearch() = runBlocking {
        val chain = Chain(request())
        val result = ArtworkResolutionInterceptor().intercept(chain) as SuccessResult
        assertEquals(provider, result.request.data)
        assertEquals(listOf(provider), chain.calls.filter { it.second }.map { it.first })
    }

    @Test fun cachedSmallArtworkDoesNotPreventARealResolutionImprovement() = runBlocking {
        val small = "https://lh3.googleusercontent.com/album=w60-h60-l90-rj"
        val large = providerArtworkUrl(small, 600)
        val req = request().newBuilder().data(small).setParameter(ARTWORK_METADATA_PARAMETER,
            ArtworkRequestMetadata(small, 600), null).build()
        val chain = Chain(req, cached = setOf(small), cachedDimension = 60)
        val result = ArtworkResolutionInterceptor().intercept(chain) as SuccessResult
        assertEquals(large, result.request.data)
        assertEquals(listOf(large), chain.calls.filter { it.second }.map { it.first })
    }

    @Test fun nativeListThumbnailCannotFinishAFullPlayerRequest() = runBlocking {
        val small = "https://lh3.googleusercontent.com/album=w60-h60-l90-rj"
        val large = providerArtworkUrl(small, 2000)
        val request = request().newBuilder().data(large).size(1600, 1600).setParameter(ARTWORK_METADATA_PARAMETER,
            ArtworkRequestMetadata(small, 2000, currentTrack = true), null).build()
        val chain = Chain(request, cached = setOf(small), cachedDimension = 60, nativeCache = true,
            size = Size(1600, 1600), dimensions = mapOf(large to 2000))
        val result = ArtworkResolutionInterceptor().intercept(chain) as SuccessResult
        assertEquals(large, result.request.data)
        assertTrue(chain.calls.any { it.first == large && it.second })
    }

    @Test fun nativePrimaryCacheBelowViewportDoesNotHideSharperSameRecordingArtwork() = runBlocking {
        val small = "https://lh3.googleusercontent.com/album=w60-h60-l90-rj"
        val primary = providerArtworkUrl(small, 2000)
        val sharper = youtubeArtworkAlternatives("yt_abcdefghijk", emptyList()).first()
        val req = request().newBuilder().data(primary).size(1600, 1600).setParameter(ARTWORK_METADATA_PARAMETER,
            ArtworkRequestMetadata(small, 2000, trackId = "yt_abcdefghijk", currentTrack = true), null).build()
        val chain = Chain(req, cached = setOf(primary), nativeCache = true,
            size = Size(1600, 1600), dimensions = mapOf(primary to 480, sharper to 1600))
        val result = ArtworkResolutionInterceptor().intercept(chain) as SuccessResult
        assertEquals(sharper, result.request.data)
        assertEquals(1600, (result.drawable as BitmapDrawable).bitmap.width)
        assertEquals(listOf(sharper), chain.calls.filter { it.second }.map { it.first })
    }

    @Test fun undersizedNetworkPrimaryContinuesToTheSharperSameRecordingRendition() = runBlocking {
        val small = "https://lh3.googleusercontent.com/album=w60-h60-l90-rj"
        val primary = providerArtworkUrl(small, 2000)
        val sharper = youtubeArtworkAlternatives("yt_abcdefghijk", emptyList()).first()
        val req = request().newBuilder().data(primary).size(1600, 1600).setParameter(ARTWORK_METADATA_PARAMETER,
            ArtworkRequestMetadata(small, 2000, trackId = "yt_abcdefghijk", currentTrack = true), null).build()
        val chain = Chain(req, nativeCache = true, size = Size(1600, 1600),
            dimensions = mapOf(primary to 480, sharper to 1600))
        val result = ArtworkResolutionInterceptor().intercept(chain) as SuccessResult
        assertEquals(sharper, result.request.data)
        assertEquals(listOf(primary, sharper), chain.calls.filter { it.second }.map { it.first })
    }
    @Test fun cachedSixHundredPixelCoverIsPreviewOnlyAndSlowFullCoverStillCompletes() = runBlocking {
        val small = "https://lh3.googleusercontent.com/album=w60-h60-l90-rj"
        val preview = providerArtworkUrl(small, 600)
        val large = providerArtworkUrl(small, 2000)
        val request = request().newBuilder().data(large).size(1600, 1600).setParameter(ARTWORK_METADATA_PARAMETER,
            ArtworkRequestMetadata(small, 2000, currentTrack = true), null).build()
        val chain = Chain(request, cached = setOf(preview), cachedDimension = 600, nativeCache = true,
            size = Size(1600, 1600), dimensions = mapOf(large to 2000), onNetwork = { delay(1600) })
        val result = withTimeout(4000) { ArtworkResolutionInterceptor().intercept(chain) } as SuccessResult
        assertEquals(large, result.request.data)
        assertEquals(listOf(large), chain.calls.filter { it.second }.map { it.first })
    }
    @Test fun missingMaxResolutionUsesTheSameVideosAvailableStandardCover() = runBlocking {
        val urls = youtubeArtworkAlternatives("yt_abcdefghijk", emptyList())
        val req = request().newBuilder().data("").setParameter(ARTWORK_METADATA_PARAMETER,
            ArtworkRequestMetadata("", 600, trackId = "yt_abcdefghijk"), null).build()
        val chain = Chain(req, failures = setOf(urls.first()))
        assertEquals(urls[1], (ArtworkResolutionInterceptor().intercept(chain) as SuccessResult).request.data)
        assertTrue(chain.calls.all { it.first in urls })
    }

    @Test fun densityScalingCannotMakeATinyCachedBitmapPassThePixelCheck() = runBlocking {
        val chain = Chain(request(), cached = setOf(provider), cachedDimension = 120, densityScaled = true)
        ArtworkResolutionInterceptor().intercept(chain)
        assertTrue(chain.calls.any { it.second })
    }

    @Test fun currentTrackFetchDoesNotWaitForThePlayerOpeningTransition() = runBlocking {
        val lease = com.example.performance.BackgroundWorkCoordinator.navigationBusy()
        try {
            val req = request().newBuilder().setParameter(ARTWORK_METADATA_PARAMETER,
                ArtworkRequestMetadata(provider, 2000, currentTrack = true), null).build()
            assertTrue(withTimeout(1000) { ArtworkResolutionInterceptor().intercept(Chain(req)) } is SuccessResult)
        } finally { lease.close() }
    }

    @Test fun identicalColdArtworkSharesDownloadAndRechecksCoilAfterTheFirstCommit() = runBlocking {
        val cached = ConcurrentHashMap.newKeySet<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val downloads = AtomicInteger()
        val hook: suspend (String) -> Unit = { url ->
            downloads.incrementAndGet(); entered.complete(Unit); release.await(); cached.add(url)
        }
        val first = Chain(request(), cached = cached, onNetwork = hook)
        val second = Chain(request(), cached = cached, onNetwork = hook)
        val interceptor = ArtworkResolutionInterceptor()
        val one = async { interceptor.intercept(first) }
        withTimeout(3_000) { entered.await() }
        val two = async { interceptor.intercept(second) }
        release.complete(Unit)
        assertTrue(withTimeout(3_000) { one.await() } is SuccessResult)
        assertTrue(withTimeout(3_000) { two.await() } is SuccessResult)
        assertEquals(1, downloads.get())
    }

    @Test fun hiddenUnfinishedArtworkCancelsNetworkAndResumesOnlyWhenVisible() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val downloads = AtomicInteger()
        val visibility = ArtworkVisibility(true)
        val visibleRequest = request().newBuilder().setParameter(ARTWORK_METADATA_PARAMETER,
            ArtworkRequestMetadata(provider, 600, visibility = visibility), null).build()
        val chain = Chain(visibleRequest, onNetwork = {
            if (downloads.incrementAndGet() == 1) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        })
        val result = async { ArtworkResolutionInterceptor().intercept(chain) }
        withTimeout(3_000) { entered.await() }
        visibility.active.value = false
        withTimeout(3_000) { cancelled.await() }
        assertFalse(result.isCompleted)
        visibility.active.value = true
        assertTrue(withTimeout(3_000) { result.await() } is SuccessResult)
        assertEquals(2, downloads.get())
    }
    @Test fun unfinishedPlaylistCoverPausesWhileHiddenWithoutRecreatingItsRequest() = runBlocking {
        val visibility = ArtworkVisibility(true)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val downloads = AtomicInteger()
        val cover = ImageRequest.Builder(context).data(provider).size(300, 300)
            .setParameter(PLAYLIST_COVER_PARAMETER, true, null)
            .setParameter(ARTWORK_VISIBILITY_PARAMETER, visibility, null).build()
        val chain = Chain(cover, onNetwork = {
            if (downloads.incrementAndGet() == 1) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        })
        val result = async { ArtworkResolutionInterceptor().intercept(chain) }
        try {
            withTimeout(3_000) { entered.await() }
            visibility.active.value = false
            withTimeout(3_000) { cancelled.await() }
            assertFalse(result.isCompleted)
            visibility.active.value = true
            assertTrue(withTimeout(3_000) { result.await() } is SuccessResult)
            assertEquals(2, downloads.get())
        } finally { result.cancelAndJoin() }
    }

    @Test fun coldArtworkWaitsForNavigation() = runBlocking {
        val coordinator = com.example.performance.BackgroundWorkCoordinator
        val lease = coordinator.navigationBusy()
        val chain = Chain(request())
        val result = async { ArtworkResolutionInterceptor().intercept(chain) }
        try {
            delay(100)
            assertFalse(result.isCompleted)
            assertTrue(chain.calls.none { it.second })
        } finally { lease.close() }
        assertTrue(withTimeout(3_000) { result.await() } is SuccessResult)
    }

    @Test fun navigationBeginningDuringASlotWaitPreventsANewNetworkBatch() = runBlocking {
        val coordinator = com.example.performance.BackgroundWorkCoordinator
        val slots = ArtworkResolutionInterceptor::class.java.getDeclaredField("coldArtworkRequests")
            .apply { isAccessible = true }.get(null) as kotlinx.coroutines.sync.Semaphore
        slots.acquire(); slots.acquire()
        val chain = Chain(request())
        val result = async { ArtworkResolutionInterceptor().intercept(chain) }
        var lease: AutoCloseable? = null
        try {
            delay(100) // Let the request reach the occupied resource, not a fake UI delay.
            lease = coordinator.navigationBusy()
            slots.release(); slots.release()
            delay(100)
            assertFalse(result.isCompleted)
            assertTrue(chain.calls.none { it.second })
            lease.close(); lease = null
            assertTrue(withTimeout(3_000) { result.await() } is SuccessResult)
        } finally { lease?.close(); result.cancelAndJoin() }
    }

    @Test fun coldArtworkPausesDuringScrollAndResumesWithoutTheOptionalFifteenSecondGate() = runBlocking {
        val coordinator = com.example.performance.BackgroundWorkCoordinator
        val scrollOwner = Any()
        coordinator.scrolling(scrollOwner, true)
        val networkStarted = CompletableDeferred<Unit>()
        val chain = Chain(request(), onNetwork = { networkStarted.complete(Unit) })
        val result = async { ArtworkResolutionInterceptor().intercept(chain) }
        try {
            assertNull("A fling must not admit cold artwork", withTimeoutOrNull(2_000) { networkStarted.await() })
            assertFalse(result.isCompleted)
            assertTrue(chain.calls.none { it.second })
            coordinator.scrolling(scrollOwner, false)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300))
            assertTrue(withTimeout(3_000) { result.await() } is SuccessResult)
        } finally { coordinator.scrolling(scrollOwner, false); result.cancelAndJoin() }
    }

    @Test fun sufficientMemoryArtworkStillDisplaysDuringAScroll() = runBlocking {
        val coordinator = com.example.performance.BackgroundWorkCoordinator
        val scrollOwner = Any()
        coordinator.scrolling(scrollOwner, true)
        try {
            val chain = Chain(request(), cached = setOf(provider))
            val result = withTimeout(1_000) {
                ArtworkResolutionInterceptor().intercept(chain)
            }
            assertTrue(result is SuccessResult)
            assertEquals(1, chain.calls.size)
            assertTrue(chain.calls.none { it.second })
        } finally { coordinator.scrolling(scrollOwner, false) }
    }

    @Test fun scrollingBeginningDuringASlotWaitReleasesResourcesUntilIdle() = runBlocking {
        val coordinator = com.example.performance.BackgroundWorkCoordinator
        val scrollOwner = Any()
        val slots = ArtworkResolutionInterceptor::class.java.getDeclaredField("coldArtworkRequests")
            .apply { isAccessible = true }.get(null) as kotlinx.coroutines.sync.Semaphore
        slots.acquire(); slots.acquire()
        var holdingSlots = true
        val chain = Chain(request())
        val result = async { ArtworkResolutionInterceptor().intercept(chain) }
        try {
            delay(100)
            coordinator.scrolling(scrollOwner, true)
            slots.release(); slots.release(); holdingSlots = false
            delay(100)
            assertFalse(result.isCompleted)
            assertTrue(chain.calls.none { it.second })
            assertEquals("Idle wait must release both resource permits", 2, slots.availablePermits)
            coordinator.scrolling(scrollOwner, false)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300))
            assertTrue(withTimeout(3_000) { result.await() } is SuccessResult)
        } finally {
            if (holdingSlots) { slots.release(); slots.release() }
            coordinator.scrolling(scrollOwner, false)
            result.cancelAndJoin()
        }
    }

    @Test fun explicitStartupArtworkKeepsItsBoundedPreparationPath() = runBlocking {
        val coordinator = com.example.performance.BackgroundWorkCoordinator
        val scrollOwner = Any()
        coordinator.scrolling(scrollOwner, true)
        try {
            val initial = request().newBuilder().setParameter(ARTWORK_METADATA_PARAMETER,
                ArtworkRequestMetadata(provider, 600, startupPreparation = true), null).build()
            val chain = Chain(initial)
            assertTrue(withTimeout(3_000) {
                ArtworkResolutionInterceptor().intercept(chain)
            } is SuccessResult)
            assertTrue(chain.calls.any { it.second })
        } finally { coordinator.scrolling(scrollOwner, false) }
    }

}
