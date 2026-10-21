package com.example.ui.util

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.Size
import com.example.data.repository.MediaItem
import com.example.data.repository.iTunesArtworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.selects.select

const val ARTWORK_METADATA_PARAMETER = "com.example.artwork.metadata"

/** Visibility controls unfinished work, not the content/painter/cache identity. */
class ArtworkVisibility internal constructor(active: Boolean) {
    internal val active = MutableStateFlow(active)
}

/** Raw metadata affects request identity, while the resolved URL remains Coil's cache key. */
data class ArtworkRequestMetadata(
    val item: MediaItem?,
    val providerUrl: String,
    val sourceDimension: Int,
    val trackId: String? = null,
    val album: String? = null,
    val preferStableArtwork: Boolean = false,
    val targetSize: Int = sourceDimension,
    val providerArtworkOnly: Boolean = false,
    val visibleContent: Boolean = true,
    val startupPreparation: Boolean = false,
    val visibility: ArtworkVisibility? = null
)

/** Cached/provider art paints first; optional upgrades remain inside the same cancellable Coil request. */
class ArtworkResolutionInterceptor internal constructor(
    private val repositoryFor: (Context) -> iTunesArtworkRepository
) : Interceptor {
    constructor() : this({ iTunesArtworkRepository.getInstance(it) })
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val playlist = chain.request.parameters.value<Boolean>(PLAYLIST_COVER_PARAMETER) == true
        val metadata = chain.request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)
        if (!playlist && metadata == null) return chain.proceed(chain.request)
        suspend fun load(): ImageResult = if (playlist) playlistCover(chain) else resolve(chain, checkNotNull(metadata))
        val visibility = metadata?.visibility ?: chain.request.parameters.value<ArtworkVisibility>(ARTWORK_VISIBILITY_PARAMETER)
        visibility?.let { visibility ->
            while (true) {
                visibility.active.first { it }
                val result = coroutineScope {
                    val hidden = async(start = CoroutineStart.UNDISPATCHED) { visibility.active.first { !it } }
                    val loading = async(com.example.performance.AppDispatchers.Io) { load() }
                    try {
                        select<ImageResult?> {
                            loading.onAwait { it }
                            hidden.onAwait { null }
                        }
                    } finally { hidden.cancel(); loading.cancel() }
                }
                // Successful requests finish once. Hiding a retained tab then
                // leaves its painter intact; only unfinished work resumes later.
                if (result != null) return result
            }
        }
        if (metadata?.visibleContent == false) {
            // Coil can use its memory placeholder. Do not report a fake failure
            // or decode an unseen file/content URI. Visibility changes the
            // remembered request and cancels this suspended painter operation.
            awaitCancellation()
        }
        return withContext(com.example.performance.AppDispatchers.Io) { load() }
    }

    /** Raw playlist cover URLs never need song matching or provider URL upgrades. */
    private suspend fun playlistCover(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val cachedRequest = request.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()
        val memory = chain.proceed(cachedRequest.newBuilder().diskCachePolicy(CachePolicy.DISABLED).build())
        if (memory is SuccessResult) return memory
        val url = request.data.toString()
        return admittedArtwork(url) {
            val prepared = chain.proceed(cachedRequest)
            if (prepared is SuccessResult) prepared else chain.proceed(request)
        }
    }

    /** Recheck after BOTH resource waits; never wait for interaction holding a slot/URL lock. */
    private suspend fun <T> admittedArtwork(url: String, startup: Boolean = false, block: suspend () -> T): T {
        val coordinator = com.example.performance.BackgroundWorkCoordinator
        val lock = artworkFlights[(url.hashCode() and Int.MAX_VALUE) % artworkFlights.size]
        while (true) {
            // Visible cache misses need the short gesture-idle gate, not optional
            // enrichment's fifteen-second admission. Explicit startup prefetch is
            // already bounded by the initial presentation's own artwork budget.
            if (!startup) coordinator.yieldToInteraction(awaitFrameQuiet = false)
            coldArtworkRequests.acquire()
            try {
                lock.lock()
                try {
                    if (startup || coordinator.isInteractionIdle(awaitFrameQuiet = false))
                        return coordinator.finishBoundedBatch { coordinator.traceWork("Artwork decode or fetch", block) }
                } finally { lock.unlock() }
            } finally { coldArtworkRequests.release() }
        }
    }

    private suspend fun resolve(originalChain: Interceptor.Chain, metadata: ArtworkRequestMetadata): ImageResult {
        val chain = if (originalChain.size.width !is Dimension.Pixels && originalChain.size.height !is Dimension.Pixels) {
            originalChain.withSize(Size(metadata.sourceDimension, metadata.sourceDimension))
        } else originalChain
        val request = chain.request
        val coordinator = com.example.performance.BackgroundWorkCoordinator
        val startupRequest = coordinator.initialPresentationInProgress && !coordinator.startupReadiness.value.complete
        val sourceDimension = maxOf(metadata.sourceDimension,
            (chain.size.width as? Dimension.Pixels)?.px ?: 0,
            (chain.size.height as? Dimension.Pixels)?.px ?: 0)
        val providerUrls = listOfNotNull(
            metadata.providerUrl, metadata.item?.sourceArtworkUrl,
            upgradeProviderUrl(metadata.providerUrl, sourceDimension),
            metadata.item?.sourceArtworkUrl?.let { upgradeProviderUrl(it, sourceDimension) }
        ).filter(String::isNotBlank).distinct()
        val attempted = mutableSetOf<String>()
        val cachedImages = mutableMapOf<String, SuccessResult?>()
        var lastError: ImageResult? = null
        var initialNetworkBudgetMs = 3_000L
        suspend fun load(url: String?, cacheOnly: Boolean = false, initialFetch: Boolean = false): SuccessResult? {
            if (url.isNullOrBlank() || !cacheOnly && !attempted.add(url)) return null
            if (cacheOnly && cachedImages.containsKey(url)) return cachedImages[url]
            val candidate = request.newBuilder().data(url).apply {
                if (cacheOnly) networkCachePolicy(CachePolicy.DISABLED)
            }.build()
            val result = admittedArtwork(url, metadata.startupPreparation) {
                if (cacheOnly) chain.proceed(candidate) else {
                    // Another request can fill the source while we wait for a slot.
                    val hit = chain.proceed(candidate.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build())
                    if (hit is SuccessResult && sufficientSize(hit, chain.size)) hit else if (!initialFetch) chain.proceed(candidate)
                    else if (initialNetworkBudgetMs <= 0L) null else {
                        val started = android.os.SystemClock.elapsedRealtime()
                        try { withTimeoutOrNull(initialNetworkBudgetMs) { chain.proceed(candidate) } }
                        finally { initialNetworkBudgetMs -= (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L) }
                    }
                }
            }
            val loaded = when (result) {
                is SuccessResult -> result
                else -> { if (!cacheOnly) lastError = result; null }
            }
            if (cacheOnly) cachedImages[url] = loaded
            return loaded
        }
        val requiredWidth = (chain.size.width as? Dimension.Pixels)?.px ?: sourceDimension
        val requiredHeight = (chain.size.height as? Dimension.Pixels)?.px ?: sourceDimension
        fun sufficient(result: SuccessResult): Boolean {
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
            return (bitmap?.width ?: result.drawable.intrinsicWidth) >= requiredWidth &&
                (bitmap?.height ?: result.drawable.intrinsicHeight) >= requiredHeight
        }
        // Instant memory hits need no interaction gate. Disk decoding and cold
        // work wait until scrolling, dragging and navigation have settled.
        if (request.data is String && request.data.toString().startsWith("http")) {
            val memory = chain.proceed(request.newBuilder().diskCachePolicy(CachePolicy.DISABLED)
                .networkCachePolicy(CachePolicy.DISABLED).build())
            if (memory is SuccessResult && sufficient(memory)) return memory
        }
        if (!metadata.startupPreparation) coordinator.awaitNavigationIdle()
        suspend fun providerFallback(cacheOnly: Boolean = false, firstUsable: Boolean = false): SuccessResult? {
            var small: SuccessResult? = null
            for (url in providerUrls) {
                val result = load(url, cacheOnly, initialFetch = firstUsable) ?: continue
                if (firstUsable || sufficient(result)) return result
                if (small == null || result.drawable.intrinsicWidth.toLong() * result.drawable.intrinsicHeight >
                    small.drawable.intrinsicWidth.toLong() * small.drawable.intrinsicHeight) small = result
            }
            return small
        }
        // Probe the already selected URL and provider cache before constructing
        // the metadata repository or touching its persistent lookup cache.
        val directlyCached = load(request.data as? String, cacheOnly = true)
        if (directlyCached != null && sufficient(directlyCached)) return directlyCached
        val provider = providerFallback(cacheOnly = true)
        if (provider != null && sufficient(provider)) return provider
        val item = metadata.item
        val repository = item?.takeUnless { metadata.providerArtworkOnly }?.let { repositoryFor(request.context) }
        suspend fun cachedPreferred(): SuccessResult? {
            val url = if (item != null && repository != null) withContext(com.example.performance.AppDispatchers.CpuBounded) {
                repository.getMemoryCachedResolvedUrl(item, sourceDimension)
            } else null
            return load(url, cacheOnly = true)
        }
        // A sufficient local image is final. Do not start an iTunes request just
        // because its displayed provider image was already in Coil's disk cache.
        val preferredCached = cachedPreferred()
        if (preferredCached != null && sufficient(preferredCached)) return preferredCached
        var interim = preferredCached ?: provider ?: directlyCached
        suspend fun present(result: SuccessResult?) {
            if (request.target == null || result == null) return
            withContext(Dispatchers.Main.immediate) {
                currentCoroutineContext().ensureActive()
                request.target?.onStart(result.drawable)
            }
        }
        present(interim)
        if (!metadata.startupPreparation && startupRequest) {
            // Startup has one bounded, explicit image batch. Ordinary card
            // requests join its cache results, rather than racing its downloads.
            coordinator.startupReadiness.first {
                it.tasks.getValue(com.example.performance.StartupTask.INITIAL_ARTWORK).terminal
            }
            cachedImages.clear() // The bounded batch may have filled earlier misses.
            cachedPreferred()?.let { if (sufficient(it)) return it }
            providerFallback(cacheOnly = true)?.let { if (sufficient(it)) return it }
        }
        // Do not delay a usable provider cover behind metadata search or the
        // optional idle budget. Cache misses are rechecked after acquiring a slot.
        if (interim == null) {
            cachedImages.clear()
            interim = cachedPreferred() ?: providerFallback(cacheOnly = true) ?:
                providerFallback(firstUsable = true)
            present(interim)
        }
        val initial = interim
        // Prefetch has no visible target to upgrade. Its caller has a bounded
        // viewport budget; metadata enrichment must never extend that budget.
        if (initial != null && (sufficient(initial) || metadata.startupPreparation || request.target == null)) return initial
        suspend fun fetchUpgrade(): ImageResult {
            // Requests queued for a permit may now have a cache hit prepared by
            // another card/startup request using the same URL and decode bounds.
            cachedImages.clear()
            cachedPreferred()?.let { if (sufficient(it)) return it }
            providerFallback(cacheOnly = true)?.let { if (sufficient(it)) return it }
            val resolved = if (item != null && repository != null)
                withTimeoutOrNull(5_000L) { repository.resolveArtwork(item, sourceDimension) } else null
            val preferred = resolved?.takeUnless { it in providerUrls }?.let { load(it) }
            return preferred ?: providerFallback() ?: initial ?: lastError ?: admittedArtwork(request.data.toString(), metadata.startupPreparation) { chain.proceed(request) }
        }
        // A request which began under the modal stays optional even if its
        // dispatcher continuation resumes after the modal has already closed.
        val optional = !metadata.startupPreparation && (metadata.preferStableArtwork || startupRequest ||
            initial != null && metadata.targetSize >= 0)
        return coordinator.traceWork("Visible artwork") {
            if (optional) {
                // Never hold an image/network permit or cache lock while idle.
                coordinator.awaitInitialPresentation()
                coordinator.startup(com.example.performance.BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                    label = "Optional artwork upgrade") { fetchUpgrade() }
            } else fetchUpgrade()
        }
    }

    private fun sufficientSize(result: SuccessResult, size: Size): Boolean =
        result.drawable.intrinsicWidth >= ((size.width as? Dimension.Pixels)?.px ?: 0) &&
            result.drawable.intrinsicHeight >= ((size.height as? Dimension.Pixels)?.px ?: 0)

    private fun upgradeProviderUrl(url: String, sourceDimension: Int): String = when {
        url.contains("150x150.jpg") -> url.replace("150x150.jpg", "500x500.jpg")
        url.contains("50x50.jpg") -> url.replace("50x50.jpg", "500x500.jpg")
        youtubeThumbnail.containsMatchIn(url) -> youtubeThumbnail.replace(url, "/maxresdefault.jpg")
        url.contains("100x100bb") -> url.replace("100x100bb", "${sourceDimension}x${sourceDimension}bb")
        url.contains("100x100") -> url.replace("100x100", "${sourceDimension}x${sourceDimension}")
        else -> url
    }

    private companion object {
        val coldArtworkRequests = Semaphore(2)
        val artworkFlights = Array(32) { Mutex() }
        val youtubeThumbnail = Regex("/(?:default|mqdefault|hqdefault|sddefault)\\.jpg")
    }
}
