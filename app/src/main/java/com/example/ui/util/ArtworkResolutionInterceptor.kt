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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first

const val ARTWORK_METADATA_PARAMETER = "com.example.artwork.metadata"

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
    val startupPreparation: Boolean = false
)

/** Cached/provider art paints first; optional upgrades remain inside the same cancellable Coil request. */
class ArtworkResolutionInterceptor internal constructor(
    private val repositoryFor: (Context) -> iTunesArtworkRepository
) : Interceptor {
    constructor() : this({ iTunesArtworkRepository.getInstance(it) })
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val metadata = chain.request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)
            ?: return chain.proceed(chain.request)
        if (!metadata.visibleContent) {
            // Coil can use its memory placeholder. Do not report a fake failure
            // or decode an unseen file/content URI. Visibility changes the
            // remembered request and cancels this suspended painter operation.
            awaitCancellation()
        }
        return withContext(com.example.performance.AppDispatchers.Io) { resolve(chain, metadata) }
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
        suspend fun load(url: String?, cacheOnly: Boolean = false): SuccessResult? {
            if (url.isNullOrBlank() || !cacheOnly && !attempted.add(url)) return null
            if (cacheOnly && cachedImages.containsKey(url)) return cachedImages[url]
            val candidate = request.newBuilder().data(url).apply {
                if (cacheOnly) networkCachePolicy(CachePolicy.DISABLED)
            }.build()
            val loaded = when (val result = chain.proceed(candidate)) {
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
        // Instant memory hits need no navigation gate. Disk decoding and cold
        // work wait until an active pager/player transition has completed.
        if (request.data is String && request.data.toString().startsWith("http")) {
            val memory = chain.proceed(request.newBuilder().diskCachePolicy(CachePolicy.DISABLED)
                .networkCachePolicy(CachePolicy.DISABLED).build())
            if (memory is SuccessResult && sufficient(memory)) return memory
        }
        if (!metadata.startupPreparation) coordinator.awaitNavigationIdle()
        suspend fun providerFallback(cacheOnly: Boolean = false, firstUsable: Boolean = false): SuccessResult? {
            var small: SuccessResult? = null
            for (url in providerUrls) {
                val result = load(url, cacheOnly) ?: continue
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
            interim = coldArtworkRequests.withPermit {
                cachedImages.clear()
                cachedPreferred() ?: providerFallback(cacheOnly = true) ?:
                    withTimeoutOrNull(3_000L) { providerFallback(firstUsable = true) }
            }
            present(interim)
        }
        val initial = interim
        // Prefetch has no visible target to upgrade. Its caller has a bounded
        // viewport budget; metadata enrichment must never extend that budget.
        if (initial != null && (sufficient(initial) || metadata.startupPreparation || request.target == null)) return initial
        suspend fun fetchUpgrade(): ImageResult = coldArtworkRequests.withPermit {
            // Requests queued for a permit may now have a cache hit prepared by
            // another card/startup request using the same URL and decode bounds.
            cachedImages.clear()
            cachedPreferred()?.let { if (sufficient(it)) return@withPermit it }
            providerFallback(cacheOnly = true)?.let { if (sufficient(it)) return@withPermit it }
            val resolved = if (item != null && repository != null)
                withTimeoutOrNull(5_000L) { repository.resolveArtwork(item, sourceDimension) } else null
            val preferred = resolved?.takeUnless { it in providerUrls }?.let { load(it) }
            preferred ?: providerFallback() ?: initial ?: lastError ?: chain.proceed(request)
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
        val youtubeThumbnail = Regex("/(?:default|mqdefault|hqdefault|sddefault)\\.jpg")
    }
}
