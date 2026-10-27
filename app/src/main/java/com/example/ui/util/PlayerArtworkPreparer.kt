package com.example.ui.util

import android.graphics.Bitmap
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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

/** Use the original provider's highest suitable cover, including full-screen art. */
internal const val PLAYER_ARTWORK_DIMENSION = 2000
private fun playerArtworkDecodeSize(context: Context): IntSize {
    val pixels = maxOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        .coerceIn(1, PLAYER_ARTWORK_DIMENSION)
    return IntSize(pixels, pixels)
}
private val neutralPlayerBackground = Brush.verticalGradient(listOf(Color(0xff101010), Color(0xff101010)))

/** Only the selected recording is prefetched; no catalog-wide lookups or extra cache. */
internal fun prepareCurrentPlayerArtwork(context: Context, tracks: Flow<MusicTrack?>, foreground: Flow<Boolean>,
    scope: CoroutineScope, loaderFor: (Context) -> ImageLoader = { it.imageLoader }
): Job = scope.launch(AppDispatchers.Io) {
    combine(tracks.distinctUntilChanged { a, b -> a?.id == b?.id && a?.coverUrl == b?.coverUrl },
        foreground.distinctUntilChanged()) { track, visible -> track to visible }
        .collectLatest { (track, visible) ->
            if (track == null || !visible) return@collectLatest
            try {
                val request = buildOptimizedImageRequest(context, track.coverUrl, PLAYER_ARTWORK_DIMENSION,
                    track, decodeSize = playerArtworkDecodeSize(context), currentTrack = true)
                val loader = loaderFor(context)
                val result = loader.execute(request) as? SuccessResult ?: return@collectLatest
                val key = playerPaletteKey(track, true, Color.White, Color.Black, request.data.toString())
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
                    TrackPaletteCache.put(playerPaletteKey(track, true, Color.White, Color.Black,
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
    "album-colors-v3|${track.id}|${track.title}|${track.artist}|${track.album}|$artworkIdentity"

/** The URL is ready on the first composition; no metadata API or navigation wait. */
@Composable
internal fun rememberPlayerArtworkRequest(track: MusicTrack, active: Boolean): ImageRequest {
    val context = LocalContext.current
    val visibility = remember(context, track.id, track.coverUrl) { ArtworkVisibility(active) }
    SideEffect { visibility.active.value = active }
    return remember(context, track.id, track.coverUrl, visibility) {
        buildOptimizedImageRequest(context, track.coverUrl, PLAYER_ARTWORK_DIMENSION, track,
            decodeSize = playerArtworkDecodeSize(context), visibility = visibility, currentTrack = true)
    }
}

/** Reuse the selected provider source at the actual header's bounded pixel size. */
internal fun playerHeaderArtworkRequest(artwork: ImageRequest, targetPixels: Int = 192): ImageRequest = artwork.newBuilder()
    .size(targetPixels.coerceAtLeast(1))
    // Small shared-transition targets must never evict the full-size decoded cover.
    .memoryCachePolicy(CachePolicy.READ_ONLY)
    .crossfade(false)
    .build()

/** Sample the exact selected provider URL without another metadata lookup or provider fallback. */
internal fun playerPaletteRequest(artwork: ImageRequest): ImageRequest = artwork.newBuilder()
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

/** Called on CpuBounded with the small software sample, never with a full-size hardware bitmap. */
@Suppress("UNUSED_PARAMETER")
internal fun extractPlayerArtworkColors(bitmap: Bitmap, dark: Boolean): ExtractedTrackColors? {
    val palette = Palette.from(bitmap)
        .resizeBitmapArea(ImageConfig.PALETTE_THUMBNAIL_SIZE * ImageConfig.PALETTE_THUMBNAIL_SIZE)
        .maximumColorCount(24)
        // White/black artwork is meaningful. Palette's default skin-tone/neutral
        // filters can otherwise discard most of a cover and exaggerate a tiny logo.
        .clearFilters()
        .generate()
    val dominant = palette.dominantSwatch ?: return null
    val total = palette.swatches.sumOf { it.population }.coerceAtLeast(1)
    val substantial = palette.swatches.filter {
        it.population >= dominant.population * 0.20f && it.population >= total * 0.05f
    }
    // Use real, sufficiently populated swatches. Target getters can manufacture
    // light/dark vibrant colors or return an unrelated theme accent on neutral art.
    val accent = substantial.maxByOrNull { it.population * (0.25f + 0.75f * it.hsl[1]) } ?: dominant
    val vibrant = substantial.maxByOrNull { it.population * (0.50f + 0.50f * it.hsl[1]) } ?: dominant
    // Keep actual artwork colors. Color mode must not lighten or darken the player.
    return ExtractedTrackColors(Color(dominant.rgb), Color(accent.rgb), Color(vibrant.rgb))
}
