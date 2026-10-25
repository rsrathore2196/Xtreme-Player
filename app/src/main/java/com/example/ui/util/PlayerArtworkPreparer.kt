package com.example.ui.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers

/** The expanded player owns artwork/palette work; the collapsed dock never prefetches it. */
fun playerPaletteKey(track: MusicTrack, dark: Boolean, accent: Color, base: Color,
    artworkIdentity: String = track.coverUrl): String =
    "album-colors-v2|${track.id}|${track.title}|${track.artist}|${track.album}|$artworkIdentity|$dark|${accent.toArgb()}|${base.toArgb()}"

/** Sample the same iTunes/YouTube resolution path as the cover, not the raw catalog thumbnail. */
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
    val backgroundHsl = dominant.hsl.copyOf()
    // Adapt brightness for readable controls while retaining the dominant cover's
    // hue and saturation, rather than selecting a different-colored minority area.
    backgroundHsl[2] = if (dark) backgroundHsl[2].coerceAtMost(0.28f) else backgroundHsl[2].coerceAtLeast(0.72f)
    return ExtractedTrackColors(Color(ColorUtils.HSLToColor(backgroundHsl)), Color(accent.rgb), Color(vibrant.rgb))
}
