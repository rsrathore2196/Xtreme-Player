package com.example.ui.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.R
import com.example.data.local.ListeningPeriod
import com.example.data.local.ListeningTotal
import com.example.data.repository.ArtistPhotoRepository
import com.example.data.repository.ListeningSummary
import com.example.performance.AppDispatchers
import com.example.ui.screens.listeningTime
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Frozen at the explicit share action; later listening/period updates cannot change the image. */
internal data class ListeningStatsImageRequest(val summary: ListeningSummary, val period: ListeningPeriod, val accent: Int)

internal data class ListeningStatsPosterText(val subtitle: String, val value: String, val unit: String, val totals: String)

internal fun listeningStatsPosterText(request: ListeningStatsImageRequest, name: String): ListeningStatsPosterText {
    val summary = request.summary
    val time = listeningTime(summary.millis)
    val subtitle = name.trim().takeIf(String::isNotEmpty)?.let { "$it's Listening History" } ?: "Your Listening History"
    return ListeningStatsPosterText(subtitle, time.first,
        if (summary.millis >= 3_600_000L) "HOURS LISTENED" else "MINUTES LISTENED",
        summary.plays.toString() + " Plays · " + summary.songCount + " songs · " + summary.artistCount + " artists")
}

/** Explicit, bounded export work only. No startup work, catalog searches or extra image cache. */
internal object ListeningStatsImage {
    private val regular by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    private val bold by lazy { Typeface.create("sans-serif", Typeface.BOLD) }
    private const val NAVY = 0xFF020A18.toInt()
    private const val SECONDARY = 0xFFB9C5D9.toInt()

    suspend fun create(context: Context, request: ListeningStatsImageRequest, name: String): File = withContext(AppDispatchers.Io) {
        val images = ConcurrentHashMap<String, Bitmap>()
        val sources = buildList {
            request.summary.songs.take(5).forEach { add("song:" + it.identity to it.coverUrl) }
            request.summary.artists.take(5).forEach { row ->
                val cached = ArtistPhotoRepository.cachedArtist(row.artist)
                val artist = if (cached.id.isBlank() && row.artistId.startsWith("UC")) cached.copy(id = row.artistId) else cached
                ArtistPhotoRepository.initialPhoto(artist)?.let { add("artist:" + row.identity to it) }
            }
        }.filter { it.second.isNotBlank() }.distinctBy { it.first }
        val permits = Semaphore(2)
        // Reuse the existing provider URLs and Coil caches. A missing portrait never blocks sharing.
        withTimeoutOrNull(2_500L) {
            coroutineScope {
                sources.forEach { (key, url) -> launch {
                    permits.withPermit {
                        try {
                            val result = context.imageLoader.execute(ImageRequest.Builder(context)
                                .data(providerArtworkUrl(url, 200)).size(200).allowHardware(false)
                                .interceptorDispatcher(AppDispatchers.Io).fetcherDispatcher(AppDispatchers.Io)
                                .decoderDispatcher(AppDispatchers.CpuBounded).build())
                            ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap?.let { images[key] = it }
                        } catch (cancelled: CancellationException) { throw cancelled }
                          catch (_: Exception) { /* Keep actual names/initials when artwork is unavailable. */ }
                    }
                } }
            }
        }
        ensureActive()
        var bitmap: Bitmap? = null
        var file: File? = null
        try {
            // Retain ownership even if cancellation arrives while the CPU dispatcher returns the bitmap.
            withContext(AppDispatchers.CpuBounded) { bitmap = render(context, request, name, images) }
            val directory = File(context.cacheDir, "listening-stats").apply { check(isDirectory || mkdirs()) }
            val expiry = System.currentTimeMillis() - 86_400_000L
            directory.listFiles()?.filter { it.isFile && it.lastModified() < expiry }?.forEach { it.delete() }
            file = File.createTempFile("Xtreme-Player-" + request.period.label + "-", ".png", directory)
            file.outputStream().use { check(checkNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it)) { "Stats image could not be saved" } }
            ensureActive()
            file
        } catch (error: Exception) { file?.delete(); throw error }
        finally { bitmap?.recycle() } // Artwork bitmaps belong to Coil and are never recycled here.
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Listening Stats", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun save(context: Context, file: File, destination: Uri) = withContext(AppDispatchers.Io) {
        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IOException("The selected location could not be opened")
    }

    /** Pure fixed-ratio poster renderer: never captures or alters any app screen. */
    internal fun render(context: Context, request: ListeningStatsImageRequest, name: String,
        images: Map<String, Bitmap> = emptyMap()): Bitmap {
        val output = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.scale(1.5f, 1.5f)
            val text = listeningStatsPosterText(request, name)
            // Keep the selected hue readable on the poster's dark canvas.
            var accent = request.accent
            if (ColorUtils.calculateContrast(accent, NAVY) < 3.0) {
                for (step in 1..10) {
                    accent = ColorUtils.blendARGB(request.accent, Color.WHITE, step / 10f)
                    if (ColorUtils.calculateContrast(accent, NAVY) >= 3.0) break
                }
            }
            drawStatsPosterBackground(canvas, accent)
            canvas.label("Listening Stats", 46f, 132f, 36f, 370f, bold = true)
            canvas.label(text.subtitle, 47f, 167f, 20f, 380f, SECONDARY)
            canvas.label(request.period.label.uppercase(Locale.getDefault()), 47f, 202f, 13f, 380f, accent, tracking = .15f)
            drawOriginalBrand(canvas, context)
            canvas.label("XTREME", 547f, 130f, 25f, 132f, accent, bold = true)
            canvas.label("PLAYER", 547f, 157f, 25f, 132f, bold = true)
            val number = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 104f; typeface = bold
                shader = LinearGradient(0f, 235f, 0f, 320f, Color.WHITE, accent, Shader.TileMode.CLAMP)
            }
            val fitting = TextUtils.ellipsize(text.value, number, 620f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(fitting, 44f, 312f, number)
            canvas.label(text.unit, 47f, 349f, 20f, 625f, SECONDARY, tracking = .15f)
            canvas.label(text.totals, 47f, 389f, 19f, 630f, SECONDARY)
            canvas.label("TOP SONGS", 47f, 441f, 18f, 290f, SECONDARY, true, .18f)
            canvas.label("TOP ARTISTS", 378f, 441f, 18f, 300f, SECONDARY, true, .18f)
            val summary = request.summary
            summary.songs.take(5).forEachIndexed { index, row ->
                val y = 470f + index * 79f
                canvas.label((index + 1).toString(), 47f, y + 38f, 23f, 25f, accent, true)
                canvas.cover(images["song:" + row.identity], row.title, 78f, y, 59f, false, accent)
                canvas.label(row.title, 149f, y + 23f, 20f, 192f, bold = true)
                canvas.label(row.artist + " · " + statsShortTime(row.listenedMs), 149f, y + 49f, 15f, 194f, SECONDARY)
            }
            summary.artists.take(5).forEachIndexed { index, row ->
                val y = 470f + index * 79f
                canvas.label((index + 1).toString(), 378f, y + 38f, 23f, 25f, accent, true)
                canvas.cover(images["artist:" + row.identity], row.artist, 416f, y, 59f, true, accent)
                canvas.label(row.artist, 496f, y + 23f, 20f, 181f, bold = true)
                canvas.label(statsShortTime(row.listenedMs), 496f, y + 49f, 16f, 181f, SECONDARY)
            }
            if (summary.songs.isEmpty()) canvas.label("No qualified plays yet", 47f, 493f, 16f, 285f, SECONDARY)
            if (summary.artists.isEmpty()) canvas.label("No listening activity yet", 378f, 493f, 16f, 300f, SECONDARY)
            canvas.featureCard(summary.artists.firstOrNull(), true, 29f, accent, images)
            canvas.featureCard(summary.songs.firstOrNull(), false, 369f, accent, images)
            return output
        } catch (error: Exception) { output.recycle(); throw error }
    }

    private fun drawOriginalBrand(canvas: Canvas, context: Context) {
        // Unmodified original brand asset, not a newly drawn approximation.
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.stats_brand_original,
            BitmapFactory.Options().apply { inScaled = false; inSampleSize = 2 }) ?: return
        try {
            val source = Rect((logo.width * .195f).toInt(), (logo.height * .14f).toInt(),
                (logo.width * .83f).toInt(), (logo.height * .66f).toInt())
            val target = RectF(438f, 87f, 539f, 178f)
            val layer = canvas.saveLayer(target, null)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(logo, source, target, paint)
            // Feather the source's existing navy canvas into the poster; retain its original icon pixels.
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            paint.shader = LinearGradient(target.left, 0f, target.right, 0f,
                intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, .10f, .90f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(target, paint)
            paint.shader = LinearGradient(0f, target.top, 0f, target.bottom,
                intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, .08f, .92f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(target, paint)
            canvas.restoreToCount(layer)
        } finally { logo.recycle() }
    }

    private fun Canvas.featureCard(row: ListeningTotal?, artist: Boolean, x: Float, accent: Int, images: Map<String, Bitmap>) {
        drawStatsCardFrame(this, RectF(x, 893f, x + 324f, 1097f), accent, NAVY, 28f)
        label(if (artist) "TOP ARTIST" else "MOST PLAYED SONG", x + 26f, 932f, 15f, 276f, SECONDARY, false, .16f)
        if (row == null) {
            label(if (artist) "No listening activity yet" else "No qualified plays yet", x + 26f, 1015f, 18f, 276f, SECONDARY)
            return
        }
        cover(images[(if (artist) "artist:" else "song:") + row.identity], if (artist) row.artist else row.title,
            x + 26f, 951f, 116f, artist, accent)
        label(if (artist) row.artist else row.title, x + 164f, if (artist) 1003f else 991f, 22f, 139f, bold = true)
        if (!artist) label(row.artist, x + 164f, 1020f, 17f, 139f, SECONDARY)
        label(statsShortTime(row.listenedMs), x + 164f, if (artist) 1035f else 1048f, 17f, 139f, SECONDARY)
    }

    private fun Canvas.label(value: String, x: Float, baseline: Float, size: Float, width: Float,
        color: Int = Color.WHITE, bold: Boolean = false, tracking: Float = 0f) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; typeface = if (bold) ListeningStatsImage.bold else regular
            this.color = color; letterSpacing = tracking
        }
        drawText(TextUtils.ellipsize(value, paint, width, TextUtils.TruncateAt.END).toString(), x, baseline, paint)
    }

    private fun Canvas.cover(bitmap: Bitmap?, name: String, x: Float, y: Float, size: Float, circle: Boolean, accent: Int) {
        val rect = RectF(x, y, x + size, y + size)
        val path = Path().apply {
            if (circle) addOval(rect, Path.Direction.CW) else addRoundRect(rect, 8f, 8f, Path.Direction.CW)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        save()
        clipPath(path)
        drawColor(Color.rgb(15, 29, 48))
        if (bitmap != null && !bitmap.isRecycled) {
            val side = minOf(bitmap.width, bitmap.height)
            val left = (bitmap.width - side) / 2; val top = (bitmap.height - side) / 2
            drawBitmap(bitmap, Rect(left, top, left + side, top + side), rect, paint)
        } else {
            val initial = name.trim().take(1).uppercase(Locale.getDefault())
            label(initial, x + size * .32f, y + size * .66f, size * .45f, size * .5f, SECONDARY, true)
        }
        restore()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        paint.color = ColorUtils.setAlphaComponent(accent, 100)
        drawPath(path, paint)
    }
}
