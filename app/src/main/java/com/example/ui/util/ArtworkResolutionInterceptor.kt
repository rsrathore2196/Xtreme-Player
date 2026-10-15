package com.example.ui.util

import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.Size
import com.example.data.repository.MediaItem
import com.example.data.repository.iTunesArtworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val ARTWORK_METADATA_PARAMETER = "com.example.artwork.metadata"

/** Raw metadata affects request identity, while the resolved URL remains Coil's cache key. */
data class ArtworkRequestMetadata(
    val item: MediaItem?,
    val providerUrl: String,
    val sourceDimension: Int,
    val trackId: String? = null,
    val album: String? = null,
    val preferStableArtwork: Boolean = false,
    val targetSize: Int = sourceDimension
)

/**
 * Resolves a cover inside one Coil request, without a second painter or composition state.
 * Repository parsing/matching runs on Default; its Room and HTTP work runs on IO.
 * Only the final result reaches the target, including when a candidate needs a fallback.
 */
class ArtworkResolutionInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val metadata = chain.request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)
            ?: return chain.proceed(chain.request)
        return withContext(Dispatchers.Default) { resolve(chain, metadata) }
    }

    private suspend fun resolve(originalChain: Interceptor.Chain, metadata: ArtworkRequestMetadata): ImageResult {
        // An explicitly original-sized prefetch must still have a bounded decode. Displayed
        // requests normally arrive here with AsyncImage's measured pixel constraints.
        val chain = if (originalChain.size.width !is Dimension.Pixels && originalChain.size.height !is Dimension.Pixels) {
            originalChain.withSize(Size(metadata.sourceDimension, metadata.sourceDimension))
        } else originalChain
        val request = chain.request
        val required = RequiredSize(
            (chain.size.width as? Dimension.Pixels)?.px
                ?: (chain.size.height as? Dimension.Pixels)?.px ?: metadata.sourceDimension,
            (chain.size.height as? Dimension.Pixels)?.px
                ?: (chain.size.width as? Dimension.Pixels)?.px ?: metadata.sourceDimension
        )
        val sourceDimension = maxOf(metadata.sourceDimension, required.width, required.height)
        val providerUrls = listOfNotNull(
            upgradeProviderUrl(metadata.providerUrl, sourceDimension),
            metadata.providerUrl,
            metadata.item?.sourceArtworkUrl?.let { upgradeProviderUrl(it, sourceDimension) },
            metadata.item?.sourceArtworkUrl
        ).filter { it.isNotBlank() }.distinct()
        val attempted = mutableSetOf<String>()
        var best: SuccessResult? = null
        var lastError: ErrorResult? = null

        suspend fun load(url: String?): SuccessResult? {
            if (url.isNullOrBlank() || !attempted.add(url)) return null
            // Preserve the target/lifecycle/sizeResolver references required by Coil 2.7.
            // URL-derived memory and disk keys let every candidate share the existing caches.
            return when (val result = chain.proceed(request.newBuilder().data(url).build())) {
                is SuccessResult -> result.also {
                    if (best == null || it.quality(required) > best!!.quality(required)) best = it
                }
                is ErrorResult -> { lastError = result; null }
            }
        }

        val repository = metadata.item?.let { iTunesArtworkRepository.getInstance(request.context) }
        val cachedResolved = metadata.item?.let { repository?.getMemoryCachedResolvedUrl(it, sourceDimension) }
        load(cachedResolved)?.let { if (it.quality(required) >= 1.0) return it }

        // A URL's size suffix proves nothing about the image it returns. Only an actual
        // decoded provider bitmap may skip enrichment, and a failed reload still resolves.
        val cachedProvider = if (metadata.item != null) {
            findAdequateProvider(request.context.imageLoader.memoryCache, providerUrls, required)
        } else null
        load(cachedProvider)?.let { if (it.quality(required) >= 1.0) return it }

        val resolved = metadata.item?.let { repository?.resolveArtwork(it, sourceDimension) }
        if (resolved != metadata.item?.sourceArtworkUrl && resolved !in providerUrls) {
            load(resolved)?.let { if (it.quality(required) >= 1.0) return it }
        }
        for (url in providerUrls) {
            load(url)?.let { if (it.quality(required) >= 1.0) return it }
        }
        // Keep a decoded fallback when no candidate reaches the requested bounds.
        return best ?: lastError ?: chain.proceed(request)
    }

    private fun findAdequateProvider(cache: MemoryCache?, urls: List<String>, required: RequiredSize): String? {
        if (cache == null) return null
        val keys = cache.keys
        return urls.firstOrNull { url ->
            keys.any { key ->
                key.key == url && cache[key]?.bitmap?.let { bitmap ->
                    bitmap.width >= required.width && bitmap.height >= required.height
                } == true
            }
        }
    }

    private data class RequiredSize(val width: Int, val height: Int)

    private fun SuccessResult.quality(required: RequiredSize): Double {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        val width = bitmap?.width ?: drawable.intrinsicWidth
        val height = bitmap?.height ?: drawable.intrinsicHeight
        return minOf(width.coerceAtLeast(0).toDouble() / required.width,
            height.coerceAtLeast(0).toDouble() / required.height)
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
        val youtubeThumbnail = Regex("/(?:default|mqdefault|hqdefault|sddefault)\\.jpg")
    }
}
