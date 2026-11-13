package com.example

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.ui.util.extractPlayerArtworkColors
import org.junit.Assert.*
import org.junit.Test

class ArtworkColorFieldTest {
    @Test fun horizontalArtworkColorsRemainVisibleEvenWhenTheEdgeAveragesAreIdentical() {
        val pixels = IntArray(120 * 120) { if (it % 120 < 60) 0xffd02030.toInt() else 0xff2040c0.toInt() }
        val field = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        val left = Color(field.pixels[field.dimension * 20 + 4])
        val right = Color(field.pixels[field.dimension * 20 + 35])
        assertTrue(left.red > left.blue * 2)
        assertTrue(right.blue > right.red * 2)
    }

    @Test fun nearWhiteRegionsInheritTheAvailableSubjectColorWithoutBecomingWhite() {
        val pixels = IntArray(120 * 120) { -1 }
        for (y in 40 until 80) for (x in 40 until 80) pixels[y * 120 + x] = 0xff1838bc.toInt()
        val field = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        for (pixel in field.pixels) {
            val color = Color(pixel)
            assertTrue(color.blue > color.red * 2)
            assertTrue(color.luminance() < .18f)
        }
    }

    @Test fun darkArtworkStaysDarkWhileItsInteriorColorIsPreserved() {
        val pixels = IntArray(120 * 120) { 0xff000000.toInt() }
        for (y in 48 until 72) for (x in 48 until 72) pixels[y * 120 + x] = 0xffd02030.toInt()
        val field = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        assertEquals(Color.Black, Color(field.pixels.first()))
        assertTrue(Color(field.pixels[field.dimension * 20 + 20]).red > .25f)
    }

    @Test fun texturesAreSmallOpaqueDeterministicAndReadableBehindWhiteControls() {
        val pixels = IntArray(120 * 120) { index ->
            val r = (index * 31) and 255
            val g = (index / 120 * 17) and 255
            val b = index and 255
            (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        val first = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        val second = extractPlayerArtworkColors(pixels, 120, 120)!!.colorField!!
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertTrue(first.pixels.size <= 4096)
        for (pixel in first.pixels) {
            val color = Color(pixel)
            assertEquals(1f, color.alpha, 0f)
            assertTrue("Background must support white controls: $color", color.luminance() <= .18f)
        }
    }
}
