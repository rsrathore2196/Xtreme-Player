package com.example.ui.util

import android.graphics.drawable.BitmapDrawable
import android.content.Context
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.Size
import com.example.data.repository.MediaItem
import com.example.data.repository.iTunesArtworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    val providerArtworkOnly: Boolean = false
)

/** Resolve once inside Coil before the final decode; no Compose URL swaps or per-card jobs. */
class ArtworkResolutionInterceptor internal constructor(
    private val repositoryFor: (Context) -> iTunesArtworkRepository
) : Interceptor {
    constructor() : this({ iTunesArtworkRepository.getInstance(it) })
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val metadata = chain.request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)
            ?: return chain.proceed(chain.request)
        return withContext(Dispatchers.IO) { resolve(chain, metadata) }
    }

    private suspend fun resolve(originalChain: Interceptor.Chain, metadata: ArtworkRequestMetadata): ImageResult {
        val chain = if (originalChain.size.width !is Dimension.Pixels && originalChain.size.height !is Dimension.Pixels) {
            originalChain.withSize(Size(metadata.sourceDimension, metadata.sourceDimension))
        } else originalChain
        val request = chain.request
        val sourceDimension = maxOf(metadata.sourceDimension,
            (chain.size.width as? Dimension.Pixels)?.px ?: 0,
            (chain.size.height as? Dimension.Pixels)?.px ?: 0)
        val providerUrls = listOfNotNull(
            upgradeProviderUrl(metadata.providerUrl, sourceDimension), metadata.providerUrl,
            metadata.item?.sourceArtworkUrl?.let { upgradeProviderUrl(it, sourceDimension) },
            metadata.item?.sourceArtworkUrl
        ).filter(String::isNotBlank).distinct()
        val attempted = mutableSetOf<String>()
        var lastError: ImageResult? = null
        suspend fun load(url: String?, cacheOnly: Boolean = false): SuccessResult? {
            if (url.isNullOrBlank() || !cacheOnly && !attempted.add(url)) return null
            // Coil performs its O(1) memory lookup and disk lookup with the actual
            // requested decode bounds. Do not copy/scan its entire key collection.
            val candidate = request.newBuilder().data(url).apply {
                // An interim cover must not start a second remote image fetch/decode
                // alongside iTunes. Respect the caller's memory/disk cache policies.
                if (cacheOnly) networkCachePolicy(CachePolicy.DISABLED)
            }.build()
            return when (val result = chain.proceed(candidate)) {
                is SuccessResult -> result
                else -> { if (!cacheOnly) lastError = result; null }
            }
        }
        val item = metadata.item
        val repository = item?.takeUnless { metadata.providerArtworkOnly }?.let { repositoryFor(request.context) }
        val cached = item?.let {
            // A cold metadata cache may sanitize/normalize here. Bound that work
            // with ranking/decoding instead of consuming another IO worker's CPU.
            withContext(com.example.performance.BackgroundWorkCoordinator.optionalCpuDispatcher) {
                repository?.getMemoryCachedResolvedUrl(it, sourceDimension)
            }
        }
        load(cached)?.let { return it }
        val requiredWidth = (chain.size.width as? Dimension.Pixels)?.px ?: sourceDimension
        val requiredHeight = (chain.size.height as? Dimension.Pixels)?.px ?: sourceDimension
        fun sufficient(result: SuccessResult): Boolean {
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
            return (bitmap?.width ?: result.drawable.intrinsicWidth) >= requiredWidth &&
                (bitmap?.height ?: result.drawable.intrinsicHeight) >= requiredHeight
        }
        suspend fun providerFallback(cacheOnly: Boolean = false): SuccessResult? {
            var smallFallback: SuccessResult? = null
            for (url in providerUrls) {
                val result = load(url, cacheOnly) ?: continue
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                val width = bitmap?.width ?: result.drawable.intrinsicWidth
                val height = bitmap?.height ?: result.drawable.intrinsicHeight
                if (sufficient(result)) return result
                if (smallFallback == null || width.toLong() * height >
                    smallFallback.drawable.intrinsicWidth.toLong() * smallFallback.drawable.intrinsicHeight)
                    smallFallback = result
            }
            return smallFallback
        }
        if (item == null || repository == null) return providerFallback() ?: lastError ?: chain.proceed(request)
        suspend fun loadPreferredArtwork(): SuccessResult? {
            val resolved = withTimeoutOrNull(5_000L) { repository.resolveArtwork(item, sourceDimension) }
            return if (resolved != null && resolved !in providerUrls) load(resolved) else null
        }
        suspend fun preferredArtwork(): SuccessResult? {
            if (metadata.preferStableArtwork) return thumbnailEnrichment.withPermit {
                // Provider/cached images can appear independently. Optional shelf
                // upgrades do not start a cold metadata/decode burst during gestures.
                com.example.performance.BackgroundWorkCoordinator.yieldToInteraction(requireForeground = true)
                loadPreferredArtwork()
            }
            return loadPreferredArtwork()
        }
        // Palette/prefetch requests have no visible target and need no interim
        // decode. Preserve the preferred-source path without speculative image IO.
        if (request.target == null) return preferredArtwork() ?: providerFallback() ?: lastError ?: chain.proceed(request)
        // The only interim drawable is an existing cache hit. A cold card has one
        // preferred remote image, not a speculative provider download racing iTunes.
        // The original target/metadata identity remains owned by Coil cancellation.
        val interim = providerFallback(cacheOnly = true)
        if (interim != null) {
            withContext(Dispatchers.Main.immediate) {
                currentCoroutineContext().ensureActive()
                request.target?.onStart(interim.drawable)
            }
        }
        return com.example.performance.BackgroundWorkCoordinator.traceWork("Visible artwork") {
            preferredArtwork() ?: interim?.takeIf(::sufficient) ?: providerFallback() ?: interim ?:
                lastError ?: chain.proceed(request)
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
        val thumbnailEnrichment = Semaphore(2)
        val youtubeThumbnail = Regex("/(?:default|mqdefault|hqdefault|sddefault)\\.jpg")
    }
}
