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
    "album-bg-colors-v7|${track.id}|${track.title}|${track.artist}|${track.album}|$artworkIdentity"

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

    // Spatial Multi-Region Analysis:
    // Partition into a 3x3 grid (9 zones):
    // 0: Top-Left (Corner)      1: Top-Center (Edge)       2: Top-Right (Corner)
    // 3: Mid-Left (Edge)        4: Mid-Center (Subject)    5: Mid-Right (Edge)
    // 6: Bottom-Left (Corner)   7: Bottom-Center (Edge)    8: Bottom-Right (Corner)
    val zoneWeights = floatArrayOf(
        2.2f, 1.8f, 2.2f,
        1.5f, 0.05f, 1.5f,
        1.8f, 1.2f, 1.8f
    )

    val binWeights = FloatArray(32768)
    val binSumR = FloatArray(32768)
    val binSumG = FloatArray(32768)
    val binSumB = FloatArray(32768)
    val binTotalW = FloatArray(32768)
    val binZoneMask = ShortArray(32768)

    val xDiv1 = w / 3
    val xDiv2 = (2 * w) / 3
    val yDiv1 = h / 3
    val yDiv2 = (2 * h) / 3

    for (y in 0 until h) {
        val rowZone = if (y < yDiv1) 0 else if (y < yDiv2) 3 else 6
        val rowOffset = y * w
        for (x in 0 until w) {
            val colZone = if (x < xDiv1) 0 else if (x < xDiv2) 1 else 2
            val zone = rowZone + colZone

            val pixel = pixels[rowOffset + x]
            val a = (pixel ushr 24) and 0xFF
            if (a < 128) continue

            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF

            val weight = zoneWeights[zone]
            val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)

            binWeights[bin] += weight
            binSumR[bin] += r * weight
            binSumG[bin] += g * weight
            binSumB[bin] += b * weight
            binTotalW[bin] += weight
            binZoneMask[bin] = (binZoneMask[bin].toInt() or (1 shl zone)).toShort()
        }
    }

    // Evaluate candidate bins:
    // Outer zone contiguity is paramount: true backgrounds occupy multiple outer zones.
    // Localized logos, watermarks, or text occupy only 1 or 2 outer zones.
    var bestBin = -1
    var bestScore = -1f

    for (i in 0 until 32768) {
        val totalW = binTotalW[i]
        if (totalW <= 0f) continue

        val mask = binZoneMask[i].toInt()
        val outerMask = mask and 0b111011111 // Exclude center zone 4
        val outerCount = Integer.bitCount(outerMask)
        if (outerCount == 0) continue

        // Spatial scale factor based on number of outer zones spanned:
        val spatialFactor = when (outerCount) {
            1 -> 0.25f
            2 -> 0.70f
            3 -> 1.40f
            4 -> 2.20f
            5 -> 3.00f
            else -> 4.00f
        }

        val score = totalW * spatialFactor
        if (score > bestScore) {
            bestScore = score
            bestBin = i
        }
    }

    if (bestBin == -1 || binTotalW[bestBin] <= 0f) {
        val palette = Palette.from(bitmap).clearFilters().generate()
        val swatch = palette.dominantSwatch ?: palette.mutedSwatch ?: palette.vibrantSwatch ?: return null
        val c = Color(swatch.rgb)
        return ExtractedTrackColors(c, c, c)
    }

    // Merge adjacent bins (Manhattan distance <= 1 in 5-bit space) to avoid quantization split
    val bestR5 = (bestBin shr 10) and 0x1F
    val bestG5 = (bestBin shr 5) and 0x1F
    val bestB5 = bestBin and 0x1F

    var mergedSumR = 0f
    var mergedSumG = 0f
    var mergedSumB = 0f
    var mergedTotalW = 0f

    for (dr in -1..1) {
        val r5 = bestR5 + dr
        if (r5 !in 0..31) continue
        for (dg in -1..1) {
            val g5 = bestG5 + dg
            if (g5 !in 0..31) continue
            for (db in -1..1) {
                if (kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db) > 1) continue
                val b5 = bestB5 + db
                if (b5 !in 0..31) continue
                val neighborBin = (r5 shl 10) or (g5 shl 5) or b5
                if (binTotalW[neighborBin] > 0f) {
                    mergedSumR += binSumR[neighborBin]
                    mergedSumG += binSumG[neighborBin]
                    mergedSumB += binSumB[neighborBin]
                    mergedTotalW += binTotalW[neighborBin]
                }
            }
        }
    }

    val domR = if (mergedTotalW > 0f) (mergedSumR / mergedTotalW).toInt().coerceIn(0, 255)
        else (binSumR[bestBin] / binTotalW[bestBin]).toInt().coerceIn(0, 255)
    val domG = if (mergedTotalW > 0f) (mergedSumG / mergedTotalW).toInt().coerceIn(0, 255)
        else (binSumG[bestBin] / binTotalW[bestBin]).toInt().coerceIn(0, 255)
    val domB = if (mergedTotalW > 0f) (mergedSumB / mergedTotalW).toInt().coerceIn(0, 255)
        else (binSumB[bestBin] / binTotalW[bestBin]).toInt().coerceIn(0, 255)

    // Neutrality Check:
    // Genuinely neutral, black, white, and grayscale backgrounds must never acquire
    // artificial saturation or random chromatic hues.
    val maxC = maxOf(domR, maxOf(domG, domB))
    val minC = minOf(domR, minOf(domG, domB))
    val sat = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC
    val isNeutral = sat < 0.06f || (maxC - minC) < 14

    if (isNeutral) {
        val avg = ((domR + domG + domB) / 3).coerceIn(0, 255)
        val neutral = Color(avg, avg, avg)
        return ExtractedTrackColors(neutral, neutral, neutral)
    }

    val dominantColor = Color(domR, domG, domB)

    // Derive harmonious accent and vibrant variations in the exact background hue
    // without artificially over-saturating.
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(domR, domG, domB, hsv)

    val accHsv = floatArrayOf(
        hsv[0],
        hsv[1],
        (hsv[2] * 1.12f).coerceIn(0f, 1f)
    )
    val vibHsv = floatArrayOf(
        hsv[0],
        hsv[1],
        (hsv[2] * 0.90f).coerceIn(0f, 1f)
    )

    val accentColor = Color(android.graphics.Color.HSVToColor(accHsv))
    val vibrantColor = Color(android.graphics.Color.HSVToColor(vibHsv))

    return ExtractedTrackColors(dominantColor, accentColor, vibrantColor)
}
