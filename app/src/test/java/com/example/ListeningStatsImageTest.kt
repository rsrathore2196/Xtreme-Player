package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ListeningPeriod
import com.example.data.local.ListeningTotal
import com.example.data.repository.ListeningSummary
import com.example.ui.util.*
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningStatsImageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun song(index: Int, plays: Long = 2) = ListeningTotal("o", "2026-10", "song", "song-$index",
        "track-$index", "Song $index", "Artist $index", "UC_$index", "", 180_000L, "", 60_000L, plays)

    @Before fun resetProviderRootsForTheFreshTestApplication() {
        // Robolectric creates a new cache directory per test; AndroidX's static roots otherwise outlive it.
        val roots = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (roots.get(null) as MutableMap<*, *>).clear()
    }

    @Test fun exportCountsUseAllDistinctListeningRowsWithoutChangingTopFiveOrQualifiedPlayRules() {
        val songs = (1..8).map { song(it) } + song(1).copy(origin = "backup", listenedMs = 30_000)
        val artists = songs.map { it.copy(kind = "artist", identity = it.artistId) }
        val result = ListeningSummary.from(songs + song(9, plays = 0), artists)
        assertEquals(9, result.songCount)
        assertEquals(8, result.artistCount)
        assertEquals(5, result.songs.size)
        assertEquals(5, result.artists.size)
        assertEquals(18, result.plays)
        assertEquals(570_000, result.millis)
        assertEquals("song-1", result.songs.first().identity)
    }

    @Test fun posterUsesSelectedPeriodActualTotalsAndProfileNameIncludingEmptyAndHours() {
        val request = ListeningStatsImageRequest(ListeningSummary(900_000, 12, emptyList(), emptyList(), 22, 16),
            ListeningPeriod.MONTHLY, Color.CYAN)
        val text = listeningStatsPosterText(request, "  Maya  ")
        assertEquals("Maya's Listening History", text.subtitle)
        assertEquals("15", text.value)
        assertEquals("MINUTES LISTENED", text.unit)
        assertEquals("12 Plays · 22 songs · 16 artists", text.totals)
        assertEquals("Your Listening History", listeningStatsPosterText(request, " ").subtitle)
        assertEquals("HOURS LISTENED", listeningStatsPosterText(request.copy(summary = request.summary.copy(millis = 7_200_000)), "Maya").unit)
    }

    @Test fun highResolutionPosterContainsActualArtworkAndNeverRecyclesSharedCachedBitmaps() {
        val rows = (1..5).map { song(it) }
        val summary = ListeningSummary(900_000, 10, rows, rows.map { it.copy(kind = "artist") }, 22, 16)
        val request = ListeningStatsImageRequest(summary, ListeningPeriod.WEEKLY, Color.rgb(0, 174, 255))
        val songArt = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val artistArt = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        val images = rows.flatMap { listOf("song:" + it.identity to songArt, "artist:" + it.identity to artistArt) }.toMap()
        val poster = ListeningStatsImage.render(context, request, "Maya", images)
        try {
            assertEquals(1080, poster.width); assertEquals(1920, poster.height)
            fun pixel(x: Int, y: Int) = poster.getPixel((x * 1.5).toInt(), (y * 1.5).toInt())
            assertEquals(Color.MAGENTA, pixel(100, 490))
            assertEquals(Color.YELLOW, pixel(445, 499))
            assertNotEquals("Artist portraits are circular, not square", Color.YELLOW, pixel(417, 471))
            assertEquals(Color.MAGENTA, pixel(420, 1000))
            assertEquals(Color.YELLOW, pixel(100, 1000))
            assertFalse(songArt.isRecycled); assertFalse(artistArt.isRecycled)
            File("/tmp/listening-stats-poster.png").outputStream().use { poster.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { poster.recycle(); songArt.recycle(); artistArt.recycle() }
    }

    @Test fun creationProducesRealPngShareGrantsReadAccessAndSaveCopiesTheSameImage() = runBlocking {
        val request = ListeningStatsImageRequest(ListeningSummary(0, 0, emptyList(), emptyList()), ListeningPeriod.LIFETIME, Color.CYAN)
        val file = ListeningStatsImage.create(context, request, "Maya")
        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            assertEquals(1080, bitmap.width); assertEquals(1920, bitmap.height); bitmap.recycle()
            val share = ListeningStatsImage.shareIntent(context, file)
            assertEquals(Intent.ACTION_SEND, share.action); assertEquals("image/png", share.type)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme); assertEquals(context.packageName + ".provider", uri.authority)
            assertEquals(uri, share.clipData!!.getItemAt(0).uri)
            val output = ByteArrayOutputStream()
            val destination = Uri.parse("content://test/stats-image.png")
            org.robolectric.Shadows.shadowOf(context.contentResolver).registerOutputStream(destination, output)
            ListeningStatsImage.save(context, file, destination)
            assertArrayEquals(file.readBytes(), output.toByteArray())
        } finally { file.delete() }
    }

    @Test fun generationLoadsRealArtworkOffMainAndReusesTheAppsImageCache() {
        val previous = Coil.imageLoader(context)
        val fetches = AtomicInteger()
        val fetchedOnMain = AtomicBoolean()
        val art = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Any> {
                override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
                    fetches.incrementAndGet()
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) fetchedOnMain.set(true)
                    DrawableResult(BitmapDrawable(context.resources, art), false, DataSource.NETWORK)
                }
            })
        }.build()
        Coil.setImageLoader(loader)
        val row = song(1).copy(coverUrl = "https://art.test/the-actual-cover.png")
        val request = ListeningStatsImageRequest(ListeningSummary(60_000, 2, listOf(row), emptyList()),
            ListeningPeriod.WEEKLY, Color.CYAN)
        fun generate(): File {
            // Keep the simulated Android looper available for Coil's lifecycle callbacks.
            val pending = java.util.concurrent.CompletableFuture.supplyAsync {
                runBlocking { ListeningStatsImage.create(context, request, "Maya") }
            }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!pending.isDone && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("The explicit export must finish within its bounded artwork budget", pending.isDone)
            return pending.get()
        }
        var first: File? = null; var second: File? = null
        try {
            first = generate()
            val decoded = BitmapFactory.decodeFile(first.absolutePath)
            assertEquals(Color.MAGENTA, decoded.getPixel(150, 735))
            decoded.recycle()
            assertEquals(1, fetches.get())
            second = generate()
            assertEquals("Repeated exports must reuse the existing image cache", 1, fetches.get())
            assertFalse(fetchedOnMain.get())
            assertFalse("The shared cached cover must stay valid", art.isRecycled)
        } finally {
            first?.delete(); second?.delete()
            Coil.setImageLoader(previous); loader.shutdown(); art.recycle()
        }
    }
}
