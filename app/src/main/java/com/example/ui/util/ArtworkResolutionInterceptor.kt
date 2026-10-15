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
import kotlinx.coroutines.launch
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
    val targetSize: Int = sourceDimension
)

/** One visible decode; enrichment only fills the existing verified URL/Room cache. */
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
        val repository = item?.let { iTunesArtworkRepository.getInstance(request.context) }
        val cached = item?.let { repository?.getMemoryCachedResolvedUrl(it, sourceDimension) }
        load(cached)?.let { return it }
        val lookupKnown = item != null && repository?.getMemoryCachedUrl(item, sourceDimension) != null
        val minimum = minOf(200,
            (chain.size.width as? Dimension.Pixels)?.px ?: 200,
            (chain.size.height as? Dimension.Pixels)?.px ?: 200)
        var smallFallback: SuccessResult? = null
        // A 480/500px source is useful even when the source-resolution hint is 600/1200.
        // Never decode another successful candidate to satisfy that hint. Small displayed
        // images also remain bounded by Coil's measured size rather than a 200px minimum.
        for (url in providerUrls) {
            val result = load(url) ?: continue
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
            val shortest = minOf(bitmap?.width ?: result.drawable.intrinsicWidth,
                bitmap?.height ?: result.drawable.intrinsicHeight)
            if (shortest >= minimum) {
                if (item != null && repository != null && !lookupKnown) warm(repository, item, sourceDimension)
                return result
            }
            // Only a genuinely undersized/missing cover needs inline recovery;
            // never decode all provider variants after one has already succeeded.
            smallFallback = result
            break
        }
        // Only a missing/failed provider blocks on an enrichment lookup.
        if (item != null && repository != null) {
            load(repository.resolveArtwork(item, sourceDimension))?.let { return it }
        }
        return smallFallback ?: lastError ?: chain.proceed(request)
    }

    private fun warm(repository: iTunesArtworkRepository, item: MediaItem, dimension: Int) {
        val key = "${item.rawTitle}|${item.rawArtist}"
        synchronized(warmups) {
            if (warmups[key]?.isActive == true || warmups.size >= 64) return
            val job = enrichmentScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    com.example.performance.BackgroundWorkCoordinator.awaitIdle(minimumUptimeMillis = 3_500L)
                    enrichmentSlots.withPermit { repository.resolveArtwork(item, dimension) }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* Visible provider artwork remains valid. */ }
            }
            warmups[key] = job
            job.invokeOnCompletion { synchronized(warmups) { if (warmups[key] === job) warmups.remove(key) } }
            job.start()
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
        val youtubeThumbnail = Regex("/(?:default|mqdefault|hqdefault|sddefault)\\.jpg")
        val enrichmentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val enrichmentSlots = kotlinx.coroutines.sync.Semaphore(4)
        val warmups = mutableMapOf<String, kotlinx.coroutines.Job>()
    }
}
