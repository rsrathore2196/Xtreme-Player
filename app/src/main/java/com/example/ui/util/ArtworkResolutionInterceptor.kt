package com.example.ui.util

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.request.ErrorResult
import java.io.IOException
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
import java.net.URI

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

/** Request a larger rendition of the same provider image, never a different recording. */
internal fun providerArtworkUrl(url: String, minimumDimension: Int): String {
    val host = try { URI(url).host?.lowercase(java.util.Locale.ROOT) } catch (_: Exception) { null }
        ?: return url
    val dimension = minimumDimension.coerceIn(1, 3_000)
    if (host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com")) {
        // Music thumbnails commonly carry =w60-h60-l90-rj or =s120. Keep
        // cropping/format flags, query tokens and already larger renditions.
        val pathEnd = listOf(url.indexOf('?'), url.indexOf('#')).filter { it >= 0 }.minOrNull() ?: url.length
        val equals = url.lastIndexOf('=', pathEnd - 1)
        if (equals <= url.lastIndexOf('/', pathEnd - 1)) return url
        val suffix = url.substring(equals + 1, pathEnd)
        val resized = googleArtworkSize.replace(suffix) { match ->
            match.groupValues[1] + match.groupValues[2] +
                maxOf(match.groupValues[3].toIntOrNull() ?: dimension, dimension)
        }
        return url.substring(0, equals + 1) + resized + url.substring(pathEnd)
    }
    if (host.endsWith(".ytimg.com") || host == "img.youtube.com") {
        return youtubeArtworkSize.replace(url) { "/maxresdefault.${it.groupValues[1]}" }
    }
    if (host.endsWith(".mzstatic.com")) {
        return appleArtworkSize.replace(url) { match ->
            val width = maxOf(match.groupValues[1].toIntOrNull() ?: dimension, dimension)
            val height = maxOf(match.groupValues[2].toIntOrNull() ?: dimension, dimension)
            "${width}x${height}${match.groupValues[3]}"
        }
    }
    return url
}

private fun isYouTubeArtworkUrl(url: String): Boolean {
    val host = try { URI(url).host?.lowercase(java.util.Locale.ROOT) } catch (_: Exception) { null } ?: return false
    return host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com") ||
        host.endsWith(".ytimg.com") || host == "img.youtube.com"
}

private val googleArtworkSize = Regex("(^|-)([whs])(\\d+)(?=-|$)")
private val youtubeArtworkSize = Regex("/(?:default|mqdefault|hqdefault|sddefault)\\.(jpg|webp)(?=[?#]|$)")
private val appleArtworkSize = Regex("(?<=/)(\\d+)x(\\d+)(bb)?(?=\\.(?:jpg|png|webp))", RegexOption.IGNORE_CASE)

/** Known catalog identity supplies safe alternatives when a thumbnail URL is absent or expires. */
internal fun youtubeArtworkAlternatives(trackId: String?, urls: List<String>): List<String> {
    val videoId = trackId?.takeIf { it.startsWith("yt_") }?.removePrefix("yt_")
        ?.takeIf { artworkVideoId.matches(it) } ?: urls.firstNotNullOfOrNull { url ->
            if (!isYouTubeArtworkUrl(url)) null else youtubeArtworkId.find(url)?.groupValues?.get(1)
        } ?: return emptyList()
    return listOf("maxresdefault", "sddefault", "hqdefault").map {
        "https://i.ytimg.com/vi/$videoId/$it.jpg"
    }
}
private val artworkVideoId = Regex("[A-Za-z0-9_-]{11}")
private val youtubeArtworkId = Regex("/(?:vi|vi_webp)/([A-Za-z0-9_-]{11})/")

/** Song covers use verified iTunes artwork, then the same recording's YouTube image. */
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
        val item = metadata.item?.takeUnless { metadata.providerArtworkOnly }
        val originals = listOfNotNull(metadata.providerUrl, item?.sourceArtworkUrl).filter(String::isNotBlank).distinct()
        val appleSources = originals.filter(iTunesArtworkRepository::isITunesArtworkUrl)
        // A song's fallback is its own YouTube thumbnail, not unrelated artwork
        // or a small JioSaavn image. Artist/URL-only requests keep their providers.
        val fallbackSources = originals.filter { item == null || isYouTubeArtworkUrl(it) }
        val fallbackUrls = (fallbackSources.map { providerArtworkUrl(it, sourceDimension) } + fallbackSources +
            youtubeArtworkAlternatives(metadata.trackId, fallbackSources)).distinct()
        val explicitAppleUrls = (appleSources.map { providerArtworkUrl(it, sourceDimension) } + appleSources).distinct()
        var repository: iTunesArtworkRepository? = null
        fun repository(): iTunesArtworkRepository? = item?.let {
            repository ?: repositoryFor(request.context).also { repository = it }
        }
        suspend fun preferredUrl(): String? {
            if (item == null) return null
            val repo = repository() ?: return null
            return withContext(com.example.performance.AppDispatchers.CpuBounded) {
                repo.getMemoryCachedResolvedUrl(item, sourceDimension)
            }
        }
        val attempted = mutableSetOf<String>()
        val cachedImages = mutableMapOf<String, SuccessResult?>()
        var lastError: ImageResult? = null
        var primaryNetworkBudgetMs = if (metadata.startupPreparation) 750L else 2_000L
        var fallbackNetworkBudgetMs = if (metadata.startupPreparation) 1_000L else 3_000L
        fun sufficient(result: SuccessResult) = sufficientSize(result, chain.size)
        fun better(first: SuccessResult?, second: SuccessResult?): SuccessResult? = when {
            first == null -> second
            second == null -> first
            pixelArea(second) > pixelArea(first) -> second
            else -> first
        }
        suspend fun load(url: String, cacheOnly: Boolean = false, memoryOnly: Boolean = false): SuccessResult? {
            if (url.isBlank()) return null
            if (cacheOnly && !memoryOnly && cachedImages.containsKey(url)) return cachedImages[url]
            val candidate = request.newBuilder().data(url).apply {
                if (cacheOnly || memoryOnly) networkCachePolicy(CachePolicy.DISABLED)
                if (memoryOnly) diskCachePolicy(CachePolicy.DISABLED)
            }.build()
            val result = if (memoryOnly) chain.proceed(candidate) else admittedArtwork(url, metadata.startupPreparation) {
                if (cacheOnly) chain.proceed(candidate) else {
                    // Recheck after resource waits so duplicate cards share Coil's file.
                    val hit = chain.proceed(candidate.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build())
                    val fallbackRequest = url in fallbackUrls
                    val budgetMs = if (fallbackRequest) fallbackNetworkBudgetMs else primaryNetworkBudgetMs
                    if (hit is SuccessResult && sufficient(hit)) hit
                    else if (!attempted.add(url) || budgetMs <= 0L) hit
                    else {
                        val started = android.os.SystemClock.elapsedRealtime()
                        try { withTimeoutOrNull(budgetMs) { chain.proceed(candidate) } ?: hit }
                        finally {
                            val elapsed = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                            if (fallbackRequest) fallbackNetworkBudgetMs -= elapsed else primaryNetworkBudgetMs -= elapsed
                        }
                    }
                }
            }
            val loaded = (result as? SuccessResult).also { if (it == null && !cacheOnly && !memoryOnly) lastError = result }
            if (cacheOnly && !memoryOnly) cachedImages[url] = loaded
            return loaded
        }
        suspend fun best(urls: List<String>, cacheOnly: Boolean = false, memoryOnly: Boolean = false): SuccessResult? {
            var small: SuccessResult? = null
            for (url in urls.distinct()) {
                // Never redownload a cached low-resolution rendition: only the
                // larger rendition can improve it. Keep the small file as fallback.
                val cachedFallback = cachedImages[url]?.takeIf {
                    !cacheOnly && !memoryOnly && providerArtworkUrl(url, sourceDimension) != url
                }
                val result = cachedFallback ?: load(url, cacheOnly, memoryOnly) ?: continue
                if (sufficient(result)) return result
                small = better(small, result)
            }
            return small
        }
        suspend fun present(result: SuccessResult?) {
            if (request.target == null || result == null) return
            withContext(Dispatchers.Main.immediate) {
                currentCoroutineContext().ensureActive()
                request.target?.onStart(result.drawable)
            }
        }
        // An already selected iTunes image can paint immediately, including
        // during a fling, without constructing the metadata repository.
        val selectedApple = (request.data as? String)?.takeIf(iTunesArtworkRepository::isITunesArtworkUrl)
        val appleMemory = best(listOfNotNull(selectedApple) + explicitAppleUrls, memoryOnly = true)
        if (appleMemory != null && sufficient(appleMemory)) return appleMemory
        val fallbackMemory = best(fallbackUrls, memoryOnly = true)
        if (item == null && fallbackMemory != null && sufficient(fallbackMemory)) return fallbackMemory
        present(appleMemory ?: fallbackMemory)
        if (!metadata.startupPreparation) coordinator.awaitNavigationIdle()
        val persistedPreferred = preferredUrl() ?: item?.let { repository()?.cachedArtwork(it, sourceDimension) }
        var preferred = best(listOfNotNull(persistedPreferred) + explicitAppleUrls, cacheOnly = true)
        if (preferred != null && sufficient(preferred)) return preferred
        var fallback = best(fallbackUrls, cacheOnly = true)
        present(preferred ?: fallback)
        if (!metadata.startupPreparation && startupRequest) {
            // Join the bounded first-viewport batch instead of racing its downloads.
            coordinator.startupReadiness.first {
                it.tasks.getValue(com.example.performance.StartupTask.INITIAL_ARTWORK).terminal
            }
            cachedImages.clear()
            preferred = best(listOfNotNull(preferredUrl()) + explicitAppleUrls, cacheOnly = true)
            if (preferred != null && sufficient(preferred)) return preferred
            fallback = better(fallback, best(fallbackUrls, cacheOnly = true))
        }
        // Explicit Apple artwork already belongs to this recording and needs no
        // repeat search. Otherwise iTunes remains the primary lookup, even when
        // a provider thumbnail is cached. Shared flights/negative TTLs avoid storms.
        preferred = better(preferred, best(explicitAppleUrls))
        if (preferred != null && sufficient(preferred)) return preferred
        if (item != null) {
            if (!metadata.startupPreparation) coordinator.yieldToInteraction(awaitFrameQuiet = false)
            val hasUsableFallback = fallback?.let(::sufficient) == true
            // Startup can finish on a crisp cached fallback. The visible request
            // owns its later iTunes upgrade and cancels it when the row hides.
            if (metadata.startupPreparation && hasUsableFallback) return checkNotNull(fallback)
            val resolved = if (hasUsableFallback) {
                // Admission time is not a network timeout. Do not repeatedly cancel
                // an optional lookup before the fifteen-second idle gate can open.
                repository()?.resolveArtwork(item, sourceDimension, optionalUpgrade = true)
            } else withTimeoutOrNull(if (metadata.startupPreparation) 750L else 5_000L) {
                repository()?.resolveArtwork(item, sourceDimension)
            }
            if (!resolved.isNullOrBlank()) preferred = better(preferred, load(resolved))
            if (preferred != null && sufficient(preferred)) return preferred
            present(preferred ?: fallback)
        }
        // Fix undersized YouTube fallbacks in the visible-image lane, not behind
        // optional enrichment's fifteen-second gate. Higher renditions go first.
        fallback = better(fallback, best(fallbackUrls))
        return when {
            preferred != null && sufficient(preferred) -> preferred
            fallback != null && sufficient(fallback) -> fallback
            else -> better(preferred, fallback) ?: lastError ?:
                ErrorResult(null, request, IOException("No matching album artwork available"))
        }
    }

    private fun sufficientSize(result: SuccessResult, size: Size): Boolean {
        // BitmapDrawable intrinsic dimensions are density-scaled dp equivalents,
        // not source pixels. A 120px cover must not pass as 360px on a 3x device.
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
        return (bitmap?.width ?: result.drawable.intrinsicWidth) >= ((size.width as? Dimension.Pixels)?.px ?: 0) &&
            (bitmap?.height ?: result.drawable.intrinsicHeight) >= ((size.height as? Dimension.Pixels)?.px ?: 0)
    }

    private fun pixelArea(result: SuccessResult): Long {
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
        return (bitmap?.width ?: result.drawable.intrinsicWidth).toLong() *
            (bitmap?.height ?: result.drawable.intrinsicHeight)
    }

    private companion object {
        val coldArtworkRequests = Semaphore(2)
        val artworkFlights = Array(32) { Mutex() }
    }
}
