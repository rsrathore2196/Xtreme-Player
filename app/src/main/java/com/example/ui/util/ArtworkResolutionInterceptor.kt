package com.example.ui.util

import android.graphics.drawable.BitmapDrawable
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.Size
import com.example.data.repository.MediaItem
import com.example.data.repository.iTunesArtworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
class ArtworkResolutionInterceptor : Interceptor {
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
        suspend fun load(url: String?): SuccessResult? {
            if (url.isNullOrBlank() || !attempted.add(url)) return null
            // Coil performs its O(1) memory lookup and disk lookup with the actual
            // requested decode bounds. Do not copy/scan its entire key collection.
            return when (val result = chain.proceed(request.newBuilder().data(url).build())) {
                is SuccessResult -> result
                else -> { lastError = result; null }
            }
        }
        val item = metadata.item
        val repository = item?.takeUnless { metadata.providerArtworkOnly }?.let { iTunesArtworkRepository.getInstance(request.context) }
        val cached = item?.let { repository?.getMemoryCachedResolvedUrl(it, sourceDimension) }
        load(cached)?.let { return it }
        // Recommendation cards used to opt out entirely. A successful 200px provider
        // decode also returned before iTunes could improve a full-screen cover.
        // Resolve in this cancellable Coil request so the visible image receives the
        // verified high-resolution result, not just a future memory-cache warmup.
        if (item != null && repository != null) {
            val resolved = withTimeoutOrNull(5_000L) { repository.resolveArtwork(item, sourceDimension) }
            if (!resolved.isNullOrBlank() && resolved !in providerUrls) load(resolved)?.let { return it }
        }
        var smallFallback: SuccessResult? = null
        val requiredWidth = (chain.size.width as? Dimension.Pixels)?.px ?: sourceDimension
        val requiredHeight = (chain.size.height as? Dimension.Pixels)?.px ?: sourceDimension
        for (url in providerUrls) {
            val result = load(url) ?: continue
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
            val width = bitmap?.width ?: result.drawable.intrinsicWidth
            val height = bitmap?.height ?: result.drawable.intrinsicHeight
            if (width >= requiredWidth && height >= requiredHeight) return result
            if (smallFallback == null || width.toLong() * height >
                smallFallback.drawable.intrinsicWidth.toLong() * smallFallback.drawable.intrinsicHeight)
                smallFallback = result
        }
        return smallFallback ?: lastError ?: chain.proceed(request)
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
