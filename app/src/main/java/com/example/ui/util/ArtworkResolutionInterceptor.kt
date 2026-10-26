package com.example.ui.util

import android.graphics.drawable.BitmapDrawable
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.request.ErrorResult
import java.io.IOException
import coil.size.Dimension
import coil.size.Size
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

/** Provider identity and viewport ownership; no metadata search or cross-provider matching. */
data class ArtworkRequestMetadata(
    val providerUrl: String,
    val sourceDimension: Int,
    val trackId: String? = null,
    val preferStableArtwork: Boolean = false,
    val targetSize: Int = sourceDimension,
    val visibleContent: Boolean = true,
    val startupPreparation: Boolean = false,
    val visibility: ArtworkVisibility? = null,
    val currentTrack: Boolean = false
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
        val resized = youtubeArtworkSize.replace(url) { "/maxresdefault.${it.groupValues[1]}" }
        // Catalog video thumbnails carry a signed resize recipe (sqp/rs). Keeping
        // that recipe on maxresdefault can still return the tiny list thumbnail.
        // Strip only those image-transform fields on recognized public thumbnails.
        if (resized != url || youtubeMaxArtworkSize.containsMatchIn(url)) {
            val fragment = resized.indexOf('#').let { if (it < 0) resized.length else it }
            val query = resized.indexOf('?').takeIf { it in 0 until fragment } ?: return resized
            val kept = resized.substring(query + 1, fragment).split('&').filterNot {
                val name = it.substringBefore('=')
                name == "sqp" || name == "rs"
            }
            return resized.substring(0, query) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + resized.substring(fragment)
        }
        return resized
    }
    if (host == "saavncdn.com" || host.endsWith(".saavncdn.com")) {
        // This provider supplies covers up to 500px; never invent a larger tier.
        return providerCoverSize.replace(url) { match ->
            val requested = dimension.coerceAtMost(500)
            "${maxOf(match.groupValues[1].toInt(), requested)}x${maxOf(match.groupValues[2].toInt(), requested)}"
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
private val youtubeMaxArtworkSize = Regex("/maxresdefault\\.(jpg|webp)(?=[?#]|$)")

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
private val providerCoverSize = Regex("(\\d+)x(\\d+)(?=\\.(?:jpg|png|webp)(?:[?#]|$))", RegexOption.IGNORE_CASE)
private val artworkVideoId = Regex("[A-Za-z0-9_-]{11}")
private val youtubeArtworkId = Regex("/(?:vi|vi_webp)/([A-Za-z0-9_-]{11})/")

/** Resolve only larger renditions of the supplied provider image and same-video fallbacks. */
class ArtworkResolutionInterceptor : Interceptor {
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
        // Foreground/current player artwork must execute immediately without waiting for background permits
        if (startup) {
            lock.lock()
            try {
                return block()
            } finally { lock.unlock() }
        }
        while (true) {
            // Visible cache misses need the short gesture-idle gate, not optional
            // enrichment's fifteen-second admission. Explicit startup prefetch is
            // already bounded by the initial presentation's own artwork budget.
            coordinator.yieldToInteraction(awaitFrameQuiet = false)
            coldArtworkRequests.acquire()
            try {
                lock.lock()
                try {
                    if (coordinator.isInteractionIdle(awaitFrameQuiet = false))
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
        val sourceDimension = maxOf(metadata.sourceDimension,
            (chain.size.width as? Dimension.Pixels)?.px ?: 0,
            (chain.size.height as? Dimension.Pixels)?.px ?: 0)
        val originals = listOf(metadata.providerUrl).filter(String::isNotBlank)
        val urls = (originals.map { providerArtworkUrl(it, sourceDimension) } +
            youtubeArtworkAlternatives(metadata.trackId, originals) + originals).distinct()
        // A smaller view can reuse a larger rendition already fetched by Home or
        // the player. These alternatives are cache probes, never extra downloads.
        val cacheUrls = (urls.take(1) + originals.flatMap { source ->
            listOf(600, 1200, 2000, 200).map { providerArtworkUrl(source, it) }
        } + urls.drop(1)).distinct()
        val attempted = mutableSetOf<String>()
        val cachedImages = mutableMapOf<String, SuccessResult?>()
        var lastError: ImageResult? = null
        var networkBudgetMs = if (metadata.startupPreparation) 1_000L else if (metadata.currentTrack) 6_000L else 3_000L
        fun sufficient(result: SuccessResult) = sufficientSize(result, chain.size) ||
            // The provider can cap a requested 2000px rendition at its real source
            // resolution. A full, unsampled decode cannot improve by downloading
            // that same URL again. Sampled thumbnail decodes still need upgrading.
            result.request.data == urls.firstOrNull() && !result.isSampled &&
                minOf((result.drawable as? BitmapDrawable)?.bitmap?.width ?: result.drawable.intrinsicWidth,
                    (result.drawable as? BitmapDrawable)?.bitmap?.height ?: result.drawable.intrinsicHeight) >= 480
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
            val result = if (memoryOnly) chain.proceed(candidate) else admittedArtwork(url, metadata.startupPreparation || metadata.currentTrack) {
                if (cacheOnly) chain.proceed(candidate) else {
                    // Recheck after resource waits so duplicate cards share Coil's file.
                    val hit = chain.proceed(candidate.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build())
                    val budgetMs = networkBudgetMs
                    if (hit is SuccessResult && sufficient(hit)) hit
                    else if (!attempted.add(url) || budgetMs <= 0L) hit
                    else {
                        val started = android.os.SystemClock.elapsedRealtime()
                        try { withTimeoutOrNull(budgetMs) { chain.proceed(candidate) } ?: hit }
                        finally {
                            val elapsed = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                            networkBudgetMs -= elapsed
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
        // Memory is usable during gestures. Completed requests keep their painter
        // and identity across retained-tab visibility changes.
        val memory = best(cacheUrls, memoryOnly = true)
        if (memory != null && sufficient(memory)) return memory
        present(memory)
        val persisted = better(memory, best(cacheUrls, cacheOnly = true))
        if (persisted != null && sufficient(persisted)) return persisted
        present(persisted)
        val loaded = better(persisted, best(urls))
        return loaded ?: lastError ?: ErrorResult(null, request, IOException("Provider artwork unavailable"))
    }

    private fun sufficientSize(result: SuccessResult, size: Size): Boolean {
        // BitmapDrawable intrinsic dimensions are density-scaled dp equivalents,
        // not source pixels. A 120px cover must not pass as 360px on a 3x device.
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
        val width = bitmap?.width ?: result.drawable.intrinsicWidth
        val height = bitmap?.height ?: result.drawable.intrinsicHeight
        val targetWidth = (size.width as? Dimension.Pixels)?.px ?: 0
        val targetHeight = (size.height as? Dimension.Pixels)?.px ?: 0
        return width >= targetWidth && height >= targetHeight
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
