package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers
import com.example.ui.util.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerArtworkColorsTest {
    private fun hsl(color: Color) = FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
    private inline fun sample(fill: Int, body: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(fill); body(bitmap) } finally { bitmap.recycle() }
    }

    @Test fun largeCoverAreasWinOverASmallBrightLogo() = sample(0xff1838bc.toInt()) { bitmap ->
        for (y in 0 until 4) for (x in 0 until bitmap.width) bitmap.setPixel(x, y, 0xffff2040.toInt())
        val expectedHue = hsl(Color(0xff1838bc))[0]
        val colors = extractPlayerArtworkColors(bitmap, dark = true)!!
        for (color in listOf(colors.dominantColor, colors.accentColor, colors.vibrantColor)) {
            assertEquals("A small red logo must not recolor the blue cover", expectedHue, hsl(color)[0], 5f)
        }
    }

    @Test fun neutralCoversDoNotAcquireAnUnrelatedVibrantColor() = sample(android.graphics.Color.WHITE) { bitmap ->
        for (y in 0 until 4) for (x in 0 until bitmap.width) bitmap.setPixel(x, y, android.graphics.Color.RED)
        val colors = extractPlayerArtworkColors(bitmap, dark = true)!!
        assertEquals(0f, hsl(colors.dominantColor)[1], 0.02f)
        assertEquals(0f, hsl(colors.accentColor)[1], 0.02f)
        assertEquals(0f, hsl(colors.vibrantColor)[1], 0.02f)
    }

    @Test fun blackAndWhiteArtworkRemainValidPalettes() {
        for (fill in listOf(android.graphics.Color.BLACK, android.graphics.Color.WHITE)) sample(fill) { bitmap ->
            for (dark in listOf(true, false)) {
                val colors = extractPlayerArtworkColors(bitmap, dark)!!
                assertEquals(0f, hsl(colors.dominantColor)[1], 0.02f)
                assertEquals(0f, hsl(colors.accentColor)[1], 0.02f)
            }
        }
    }

    @Test fun colorModeDoesNotShadeOrRecolorTheArtworkPalette() = sample(0xffd02844.toInt()) { bitmap ->
        val dark = extractPlayerArtworkColors(bitmap, true)!!
        val light = extractPlayerArtworkColors(bitmap, false)!!
        assertEquals(hsl(dark.dominantColor)[0], hsl(light.dominantColor)[0], 1f)
        assertEquals(hsl(dark.dominantColor)[1], hsl(light.dominantColor)[1], 0.02f)
        assertEquals(dark, light)
        assertTrue(kotlin.math.abs(0xd0 - android.graphics.Color.red(dark.dominantColor.toArgb())) <= 8)
        assertTrue(kotlin.math.abs(0x28 - android.graphics.Color.green(dark.dominantColor.toArgb())) <= 8)
        assertTrue(kotlin.math.abs(0x44 - android.graphics.Color.blue(dark.dominantColor.toArgb())) <= 8)
    }

    @Test fun samplingPreservesResolvedCoverMetadataWithoutReplacingTheFullSizeMemoryEntry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metadata = ArtworkRequestMetadata("https://i.ytimg.com/vi/catalog/hqdefault.jpg", 1200)
        val cover = ImageRequest.Builder(context).data("https://art.example/resolved-provider.jpg")
            .setParameter(ARTWORK_METADATA_PARAMETER, metadata, null).size(1080).allowHardware(true).build()
        val sample = playerPaletteRequest(cover)
        assertEquals(cover.data, sample.data)
        assertEquals(metadata.copy(providerUrl = cover.data.toString(), targetSize = 120),
            sample.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER))
        assertEquals(Size(120, 120), sample.sizeResolver.size())
        assertFalse(sample.allowHardware)
        assertEquals(Bitmap.Config.ARGB_8888, sample.bitmapConfig)
        assertSame(AppDispatchers.CpuBounded, sample.decoderDispatcher)
        assertEquals(CachePolicy.READ_ONLY, sample.memoryCachePolicy)
        val header = playerHeaderArtworkRequest(cover)
        assertEquals(cover.data, header.data)
        assertEquals(metadata.copy(providerUrl = cover.data.toString(), targetSize = 192),
            header.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER))
        assertEquals(Size(192, 192), header.sizeResolver.size())
        assertEquals(CachePolicy.READ_ONLY, header.memoryCachePolicy)
        assertTrue(header.allowHardware)
        assertEquals(Size(260, 260), playerHeaderArtworkRequest(cover, 260).sizeResolver.size())
        assertTrue(cover.allowHardware)
        assertEquals(Size(1080, 1080), cover.sizeResolver.size())
    }

    @Test fun resolvedArtworkChangesInvalidateColorsButAudioUrlRefreshesDoNot() {
        val track = MusicTrack("track", "Title", "Artist", "Album", 180_000, "catalog-cover", "audio")
        fun key(song: MusicTrack = track, art: String = "resolved-cover") =
            playerPaletteKey(song, true, Color.Blue, Color.Black, art)
        assertNotEquals(key(), key(art = "replacement-cover"))
        assertNotEquals(key(), key(track.copy(album = "Corrected album")))
        assertEquals(key(), key(track.copy(audioUrl = "refreshed-signed-url", bitrateKbps = 320)))
        assertEquals(key(), playerPaletteKey(track, false, Color.Red, Color.White, "resolved-cover"))
    }
}
