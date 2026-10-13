package com.example.ui.util

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.model.MusicTrack
import com.example.data.repository.ArtworkDimensions
import com.example.data.repository.iTunesArtworkRepository
import com.example.data.repository.toMediaItem
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun playerPaletteKey(track: MusicTrack, dark: Boolean, accent: Color, base: Color): String =
    "${track.id}|${track.coverUrl}|$dark|${accent.toArgb()}|${base.toArgb()}"

/** One selected track, cancellable on a skip. Uses the exact cover size and palette algorithm. */
suspend fun preparePlayerArtwork(context: Context, track: MusicTrack, dark: Boolean, accent: Color, base: Color) {
    BackgroundWorkCoordinator.awaitIdle(quietMillis = 1_000L)
    BackgroundWorkCoordinator.work {
        withContext(Dispatchers.IO) {
            val repo = iTunesArtworkRepository.getInstance(context)
            val resolved = repo.resolveArtwork(track.toMediaItem(), ArtworkDimensions.EXPANDED_PLAYER)
            val cover = if (resolved != track.coverUrl) resolved else when {
                resolved.contains("150x150.jpg") -> resolved.replace("150x150.jpg", "500x500.jpg")
                resolved.contains("50x50.jpg") -> resolved.replace("50x50.jpg", "500x500.jpg")
                resolved.contains("hqdefault.jpg") -> resolved.replace("hqdefault.jpg", "maxresdefault.jpg")
                resolved.contains("mqdefault.jpg") -> resolved.replace("mqdefault.jpg", "maxresdefault.jpg")
                resolved.contains("default.jpg") && !resolved.contains("maxresdefault.jpg") ->
                    resolved.replace("default.jpg", "maxresdefault.jpg")
                resolved.contains("100x100") -> repo.formatUrl(resolved, ArtworkDimensions.EXPANDED_PLAYER)
                else -> resolved
            }
            if (cover.isNotBlank()) context.imageLoader.execute(ImageRequest.Builder(context).data(cover)
                .size(coil.size.Size.ORIGINAL).scale(coil.size.Scale.FILL).allowHardware(true).allowRgb565(false).build())
            val key = playerPaletteKey(track, dark, accent, base)
            if (TrackPaletteCache.get(key) != null || track.coverUrl.isBlank()) return@withContext
            val result = context.imageLoader.execute(ImageRequest.Builder(context).data(track.coverUrl)
                .size(ImageConfig.PALETTE_THUMBNAIL_SIZE).allowHardware(false).build()) as? SuccessResult
            val bitmap = (result?.drawable as? BitmapDrawable)?.bitmap ?: return@withContext
            val colors = withContext(Dispatchers.Default) {
                val palette = Palette.from(bitmap).generate()
                fun fallback(color: Color) = android.graphics.Color.rgb((color.red * 255).toInt(),
                    (color.green * 255).toInt(), (color.blue * 255).toInt())
                val baseRgb = fallback(base); val accentRgb = fallback(accent)
                val dom = if (dark) palette.getDarkVibrantColor(palette.getDominantColor(palette.getDarkMutedColor(baseRgb)))
                    else palette.getLightMutedColor(palette.getLightVibrantColor(palette.getDominantColor(baseRgb)))
                val acc = if (dark) palette.getLightVibrantColor(palette.getVibrantColor(accentRgb))
                    else palette.getVibrantColor(palette.getDarkVibrantColor(accentRgb))
                val vib = palette.getVibrantColor(palette.getLightVibrantColor(palette.getDominantColor(accentRgb)))
                ExtractedTrackColors(Color(dom), Color(acc), Color(vib))
            }
            TrackPaletteCache.put(key, colors); TrackPaletteCache.put(track.id, colors)
        }
    }
}
