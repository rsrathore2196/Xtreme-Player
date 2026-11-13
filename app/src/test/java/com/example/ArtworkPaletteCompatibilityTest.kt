package com.example

import androidx.compose.ui.graphics.toArgb
import com.example.ui.util.extractPlayerArtworkColors
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Frozen output covers the palette and every pixel in the existing background texture. */
class ArtworkPaletteCompatibilityTest {
    @Test fun paletteAndTextureRemainIdentical() {
        val expected = listOf(
            "4651c51fcf0de895f906815634ac3b4f7bb51803eaa59824828b678f1d2c0caf",
            "70414cbb8b87eb3772a3b1905e934318e505dc0a68ac51026a3f5e875417d5bf",
            "0d9d39f809073b1233f491d1e72f207f1fdfcc3539193351cf9631ab965aa2c8",
            "4186399246a26c2f0e7cba9ef7adab380177dd9505e970ac719ebc2c41738f05",
            "a14b1e1670b391f0e586f72c3f800028f85b4bc0dda957673c6eb625dbb3294c",
            "b4250e55bfdd9c72809f670698bffd7b2a5b606a754c9a97f0fe9bcf53a08527",
            "c02f30d262bcc1e369da4f8854876169a90da15e135bded8a418bcb8f39488d7"
        )
        val actual = fixtures().map { (width, pixels) ->
            val colors = extractPlayerArtworkColors(pixels, width, pixels.size / width)!!
            val values = intArrayOf(colors.dominantColor.toArgb(), colors.accentColor.toArgb(),
                colors.vibrantColor.toArgb(), colors.topEdgeColor.toArgb(), colors.bottomEdgeColor.toArgb()) + colors.colorField!!.pixels
            val bytes = ByteBuffer.allocate(values.size * 4).apply { values.forEach(::putInt) }.array()
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        assertEquals(expected, actual)
    }

    private fun fixtures(): List<Pair<Int, IntArray>> {
        val random = java.util.Random(734L)
        return listOf(
            120 to IntArray(120 * 120) { 0xff1838bc.toInt() },
            120 to IntArray(120 * 120) { i -> if (i % 120 in 40..79 && i / 120 in 40..79) 0xff00796b.toInt() else -1 },
            120 to IntArray(120 * 120) { random.nextInt() },
            120 to IntArray(120 * 120) { random.nextInt() or (0xff shl 24) },
            3 to intArrayOf(-1, 0x00123456, 0x801838bc.toInt(), 0xff000000.toInt(), 0xffff0000.toInt(), -1, 0xff0000ff.toInt(), -1, -1),
            1 to intArrayOf(0xffe8f1ff.toInt()),
            256 to IntArray(256 * 128) { i -> (0xff shl 24) or ((i shr 10) shl 19) or (((i shr 5) and 31) shl 11) or ((i and 31) shl 3) }
        )
    }
}
