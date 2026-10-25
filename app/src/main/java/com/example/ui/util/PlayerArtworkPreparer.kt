package com.example.ui.util

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers
import com.example.performance.BackgroundWorkCoordinator
import com.example.data.repository.iTunesArtworkRepository
import com.example.data.repository.toMediaItem
import com.example.ui.theme.AppThemeColors
import kotlinx.coroutines.withContext

/** The player always uses one high-quality iTunes rendition, including full-screen art. */
internal const val PLAYER_ARTWORK_DIMENSION = 2000
private val neutralPlayerBackground = Brush.verticalGradient(listOf(Color(0xff101010), Color(0xff101010)))

/** Neutral player controls are independent of app accents, album colors, and color mode. */
internal fun playerAppearanceColors(source: AppThemeColors): AppThemeColors = source.copy(
    isDark = true, isAmoled = false,
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

/** No provider thumbnails, motion artwork, or cross-provider placeholders in the full player. */
@Composable
internal fun rememberPlayerArtworkRequest(track: MusicTrack, active: Boolean,
    repositoryFor: (android.content.Context) -> iTunesArtworkRepository = { iTunesArtworkRepository.getInstance(it) }
): ImageRequest {
    val context = LocalContext.current
    val direct = track.coverUrl.takeIf(iTunesArtworkRepository::isITunesArtworkUrl)
    val item = remember(track.id, track.title, track.artist, track.album, track.durationMs) { track.toMediaItem() }
    val url by key(track.id, item.rawTitle, item.rawArtist, item.album, item.durationMs, direct) {
        val initial = direct?.let { iTunesArtworkRepository.formatArtworkUrl(it, PLAYER_ARTWORK_DIMENSION) }
            ?: iTunesArtworkRepository.peekInstance()?.peekMemoryCachedUrl(item, PLAYER_ARTWORK_DIMENSION)
                ?.takeIf(iTunesArtworkRepository::isITunesArtworkUrl)
        produceState<String?>(initial, active) {
            if (initial != null || !active) return@produceState
            value = withContext(AppDispatchers.Io) {
                initial ?: repositoryFor(context).let { repo ->
                    repo.cachedArtwork(item, PLAYER_ARTWORK_DIMENSION)?.takeIf(iTunesArtworkRepository::isITunesArtworkUrl) ?: if (active) {
                        BackgroundWorkCoordinator.awaitNavigationIdle(requireForeground = true)
                        repo.resolveArtwork(item, PLAYER_ARTWORK_DIMENSION)?.takeIf(iTunesArtworkRepository::isITunesArtworkUrl)
                    } else null
                }
            }
        }
    }
    return remember(context, url) {
        ImageRequest.Builder(context).data(url).allowHardware(true).crossfade(false)
            .placeholderMemoryCacheKey(url).memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED).interceptorDispatcher(AppDispatchers.Io)
            .fetcherDispatcher(AppDispatchers.Io).decoderDispatcher(AppDispatchers.CpuBounded).build()
    }
}

/** Sample the exact selected iTunes URL without another metadata lookup or provider fallback. */
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
