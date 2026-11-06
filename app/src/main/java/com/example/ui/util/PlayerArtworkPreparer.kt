package com.example.ui.util

import android.graphics.Bitmap
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.ImageLoader
import coil.imageLoader
import coil.size.Precision
import com.example.data.model.MusicTrack
import com.example.data.remote.resolveYouTubePlayerCover
import com.example.data.remote.playerCoverIdentity
import com.example.data.remote.playerCoverLookupIdentity
import com.example.playback.PlayerUiState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.example.performance.AppDispatchers
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.theme.AppThemeColors
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Presentation-only cover; playback/catalog identity and the native shelf image stay intact. */
internal data class ResolvedPlayerCover(val identity: String, val url: String)
// Remember only selected, verified song-cover identities. Image bytes remain owned by Coil.
private val selectedSongCovers = android.util.LruCache<String, String>(64)
internal fun isSelectedSongCover(trackId: String?, url: String): Boolean =
    trackId != null && selectedSongCovers.get(trackId) == url
internal fun hasNativeSquareCover(url: String): Boolean {
    val uri = try { java.net.URI(url) } catch (_: Exception) { return false }
    val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
    if (!host.endsWith(".googleusercontent.com") && !host.endsWith(".ggpht.com")) return false
    val width = Regex("(?:=|-)w(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
    val height = Regex("(?:=|-)h(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
    return width != null && width > 0 && width == height || Regex("=s[1-9][0-9]*(?:-|$)").containsMatchIn(url)
}
internal fun playerCoverState(state: PlayerUiState, cover: ResolvedPlayerCover?): PlayerUiState {
    val track = state.currentTrack ?: return state
    if (cover == null || cover.identity != playerCoverIdentity(track) || cover.url == track.coverUrl) return state
    return state.copy(currentTrack = track.copy(coverUrl = cover.url))
}

/** Use the original provider's highest suitable cover, including full-screen art. */
private fun playerArtworkDecodeSize(context: Context): IntSize {
    val pixels = minOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        .coerceIn(1, 3000)
    return IntSize(pixels, pixels)
}
private val neutralPlayerBackground = Brush.verticalGradient(listOf(Color(0xff101010), Color(0xff101010)))

/** Only the selected recording is prefetched; no catalog-wide artwork lookups. */
internal fun prepareCurrentPlayerArtwork(context: Context, tracks: Flow<MusicTrack?>, foreground: Flow<Boolean>,
    scope: CoroutineScope,
    onCoverResolved: (ResolvedPlayerCover) -> Unit = {},
    resolveCover: suspend (MusicTrack) -> String? = ::resolveYouTubePlayerCover,
    loaderFor: (Context) -> ImageLoader = { it.imageLoader }
): Job = scope.launch(AppDispatchers.Io) {
    combine(tracks.distinctUntilChanged { a, b -> a === b || a != null && b != null && playerCoverLookupIdentity(a) == playerCoverLookupIdentity(b) },
        foreground.distinctUntilChanged()) { track, visible -> track to visible }
        .collectLatest { (track, visible) ->
            if (track == null || !visible) return@collectLatest
            try {
                val cover = resolveCover(track)
                currentCoroutineContext().ensureActive()
                val artworkTrack = cover?.let { track.copy(coverUrl = it) } ?: track
                val request = buildOptimizedImageRequest(context, artworkTrack.coverUrl, ImageConfig.PLAYER_SCREEN_SIZE,
                    artworkTrack, decodeSize = playerArtworkDecodeSize(context), currentTrack = true, requireSquareSongArtwork = cover != null)
                val loader = loaderFor(context)
                val result = loader.execute(request) as? SuccessResult
                if (result == null) {
                    if (cover != null) com.example.data.remote.rejectSongCover(track, cover)
                    return@collectLatest
                }
                if (cover != null) {
                    // Publish only usable square art, already in Coil's cache. A failed
                    // cover download never blanks the player's current artwork.
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    val width = bitmap?.width ?: result.drawable.intrinsicWidth
                    val height = bitmap?.height ?: result.drawable.intrinsicHeight
                    if (width <= 0 || width != height) {
                        com.example.data.remote.rejectSongCover(track, cover)
                        return@collectLatest
                    }
                    currentCoroutineContext().ensureActive()
                    selectedSongCovers.put(track.id, cover)
                    onCoverResolved(ResolvedPlayerCover(playerCoverIdentity(track), cover))
                }
                val key = playerPaletteKey(artworkTrack, true, Color.White, Color.Black, request.data.toString())
                if (TrackPaletteCache.get(key) == null) {
                    // Network and decoding started at selection; optional color analysis
                    // still yields during navigation and shares the existing CPU budget.
                    BackgroundWorkCoordinator.awaitNavigationIdle(requireForeground = true)
                    val sample = loader.execute(playerPaletteRequest(result.request)) as? SuccessResult
                        ?: return@collectLatest
                    val bitmap = (sample.drawable as? BitmapDrawable)?.bitmap ?: return@collectLatest
                    val colors = withContext(AppDispatchers.CpuBounded) { extractPlayerArtworkColors(bitmap, true) }
                        ?: return@collectLatest
                    TrackPaletteCache.put(key, colors)
                    TrackPaletteCache.put(playerPaletteKey(artworkTrack, true, Color.White, Color.Black,
                        result.request.data.toString()), colors)
                    TrackPaletteCache.put(track.id, colors)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A failed prefetch never prevents normal on-demand image loading. */ }
        }
}

/** Neutral player controls are independent of app accents, album colors, and color mode. */
internal fun playerAppearanceColors(source: AppThemeColors): AppThemeColors = source.copy(
    isDark = true, isAmoled = false, neutralSurfaces = false, settingsSurface = false,
    screenBackground = neutralPlayerBackground,
    scaffoldBackground = Color(0xff101010), cardBackground = Color(0xff181818),
    cardBackgroundElevated = Color(0xff202020), cardBorder = Color.White.copy(alpha = 0.22f),
    textPrimary = Color.White, textSecondary = Color.White.copy(alpha = 0.75f),
    textMuted = Color.White.copy(alpha = 0.55f), primaryAccent = Color.White, secondaryAccent = Color.White,
    inputBackground = Color(0xff202020), chipBackground = Color(0xff202020),
    chipBorder = Color.White.copy(alpha = 0.22f), dividerColor = Color.White.copy(alpha = 0.15f)
)

/** Legacy theme arguments are retained for callers; the palette itself is mode independent. */
@Suppress("UNUSED_PARAMETER")
fun playerPaletteKey(track: MusicTrack, dark: Boolean, accent: Color, base: Color,
    artworkIdentity: String = track.coverUrl): String =
    "album-bg-colors-v6|${track.id}|${track.title}|${track.artist}|${track.album}|$artworkIdentity"

/** The URL is ready on the first composition; no metadata API or navigation wait. */
@Composable
internal fun rememberPlayerArtworkRequest(track: MusicTrack, active: Boolean): ImageRequest {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val sourceSize = remember(context, configuration.screenWidthDp, configuration.screenHeightDp, configuration.densityDpi) {
        playerArtworkDecodeSize(context)
    }
    val visibility = remember(context, track.id, track.coverUrl) { ArtworkVisibility(active) }
    SideEffect { visibility.active.value = active }
    return remember(context, track.id, track.coverUrl, visibility, sourceSize) {
        // AsyncImage resolves the actual rendered bounds. Prefetch still has an
        // explicit window-sized target; both use the same provider source file.
        buildOptimizedImageRequest(context, track.coverUrl, ImageConfig.PLAYER_SCREEN_SIZE, track,
            visibility = visibility, currentTrack = true, sourceSize = sourceSize)
    }
}

/** Reuse the selected provider source at the actual header's bounded pixel size. */
internal fun playerHeaderArtworkRequest(artwork: ImageRequest, targetPixels: Int = 192): ImageRequest = playerArtworkDerivative(artwork, targetPixels)
    .size(targetPixels.coerceAtLeast(1))
    // Small shared-transition targets must never evict the full-size decoded cover.
    .memoryCachePolicy(CachePolicy.READ_ONLY)
    .crossfade(false)
    .build()

/** Sample the exact selected provider URL without another metadata lookup or provider fallback. */
internal fun playerPaletteRequest(artwork: ImageRequest): ImageRequest = playerArtworkDerivative(artwork, ImageConfig.PALETTE_THUMBNAIL_SIZE)
    .size(ImageConfig.PALETTE_THUMBNAIL_SIZE)
    .precision(Precision.EXACT)
    .allowHardware(false)
    .bitmapConfig(Bitmap.Config.ARGB_8888)
    // A small software derivative must not replace the player's full-size decoded cover.
    .memoryCachePolicy(CachePolicy.READ_ONLY)
    .decoderDispatcher(AppDispatchers.CpuBounded)
    .transformationDispatcher(AppDispatchers.CpuBounded)
    .crossfade(false)
    .build()

private fun playerArtworkDerivative(artwork: ImageRequest, targetPixels: Int): ImageRequest.Builder = artwork.newBuilder().apply {
    val metadata = artwork.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)
    val source = artwork.data as? String
    if (metadata != null && !source.isNullOrBlank()) {
        // A successful request may have selected a sharper same-recording fallback.
        // Derivatives must reuse that source instead of resolving the original again.
        setParameter(ARTWORK_METADATA_PARAMETER, metadata.copy(providerUrl = source, targetSize = targetPixels), null)
    }
}

/** Called on CpuBounded with the small software sample, never with a full-size hardware bitmap. */
@Suppress("UNUSED_PARAMETER")
internal fun extractPlayerArtworkColors(bitmap: Bitmap, dark: Boolean): ExtractedTrackColors? {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null

    // Read pixels directly into an IntArray - zero allocation overhead and avoids canvas drawing bugs.
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val marginX = (w * 0.14f).toInt().coerceIn(2, 24)
    val marginY = (h * 0.14f).toInt().coerceIn(2, 24)

    // Bins for 5-bit RGB quantization (32x32x32 = 32768)
    val binWeights = FloatArray(32768)
    val binSumR = FloatArray(32768)
    val binSumG = FloatArray(32768)
    val binSumB = FloatArray(32768)
    val binTotalW = FloatArray(32768)

    // Sample perimeter / border pixels where the album art background color lives.
    // The subject/figure is in the center, while the background fills the top, sides, and corners.
    for (y in 0 until h) {
        val isTop = y < marginY
        val isBottom = y >= h - marginY
        val rowOffset = y * w
        for (x in 0 until w) {
            val isLeft = x < marginX
            val isRight = x >= w - marginX

            if (isTop || isBottom || isLeft || isRight) {
                val pixel = pixels[rowOffset + x]
                val a = (pixel ushr 24) and 0xFF
                if (a < 128) continue // Ignore transparent pixels

                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF

                // Weight based on location:
                // Top corners and top border are almost always 100% background color.
                val locWeight = when {
                    isTop && (isLeft || isRight) -> 4.0f // Top corners
                    isTop -> 3.0f                        // Top edge
                    isLeft || isRight -> 2.5f            // Side edges
                    isBottom && (isLeft || isRight) -> 2.0f // Bottom corners
                    else -> 1.0f                         // Bottom edge
                }

                // Saturation: prioritize vivid background color over desaturated suits/text/greys
                val maxC = maxOf(r, maxOf(g, b))
                val minC = minOf(r, minOf(g, b))
                val sat = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC
                val adjustedWeight = locWeight * (1.0f + 2.5f * sat)

                val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                binWeights[bin] += adjustedWeight
                binSumR[bin] += r * adjustedWeight
                binSumG[bin] += g * adjustedWeight
                binSumB[bin] += b * adjustedWeight
                binTotalW[bin] += adjustedWeight
            }
        }
    }

    // Find the highest-weighted bin (dominant background color)
    var bestBin = -1
    var maxWeight = 0f
    for (i in 0 until 32768) {
        if (binWeights[i] > maxWeight) {
            maxWeight = binWeights[i]
            bestBin = i
        }
    }

    if (bestBin == -1 || binTotalW[bestBin] <= 0f) {
        // Fallback to Palette if image border was completely transparent
        val palette = Palette.from(bitmap).clearFilters().generate()
        val dom = palette.dominantSwatch ?: return null
        return ExtractedTrackColors(Color(dom.rgb), Color(dom.rgb), Color(dom.rgb))
    }

    val domR = (binSumR[bestBin] / binTotalW[bestBin]).toInt().coerceIn(0, 255)
    val domG = (binSumG[bestBin] / binTotalW[bestBin]).toInt().coerceIn(0, 255)
    val domB = (binSumB[bestBin] / binTotalW[bestBin]).toInt().coerceIn(0, 255)
    val dominantColor = Color(domR, domG, domB)

    // Derive harmonious accent and vibrant variations in the exact background hue
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(domR, domG, domB, hsv)

    val accHsv = hsv.clone().apply {
        this[1] = (this[1] * 1.08f).coerceIn(0f, 1f)
        this[2] = (this[2] * 1.15f).coerceIn(0f, 1f)
    }
    val vibHsv = hsv.clone().apply {
        this[1] = (this[1] * 1.20f).coerceIn(0f, 1f)
        this[2] = (this[2] * 0.90f).coerceIn(0f, 1f)
    }

    val accentColor = Color(android.graphics.Color.HSVToColor(accHsv))
    val vibrantColor = Color(android.graphics.Color.HSVToColor(vibHsv))

    return ExtractedTrackColors(dominantColor, accentColor, vibrantColor)
}
