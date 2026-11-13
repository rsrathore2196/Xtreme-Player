package com.example

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.ui.util.extractPlayerArtworkColors
import org.junit.Assert.*
import org.junit.Test

class PlayerArtworkPixelColorsTest {
    private val size = 120
    private val blue = 0xff1838bc.toInt()
    private fun sample(fill: Int, draw: (IntArray) -> Unit = {}) =
        IntArray(size * size) { fill }.also(draw).let { extractPlayerArtworkColors(it, size, size)!! }

    @Test fun whiteBordersUseTheColoredSubjectIncludingThePreviouslyIgnoredCenter() {
        for (fill in listOf(-1, 0xffdddddd.toInt())) {
            val colors = sample(fill) { pixels ->
                for (y in 40 until 80) for (x in 40 until 80) pixels[y * size + x] = blue
            }
            assertEquals(Color(blue), colors.dominantColor)
            assertEquals(Color(blue), colors.topEdgeColor)
            assertEquals(Color(blue), colors.bottomEdgeColor)
        }
    }

    @Test fun nearWhiteArtPrefersASubstantialMatchingColorOverWhiteAndASmallLogo() {
        val teal = 0xff00796b.toInt()
        val colors = sample(0xfff8f5ee.toInt()) { pixels ->
            for (y in 30 until 90) for (x in 30 until 90) pixels[y * size + x] = teal
            for (y in 20 until 24) for (x in 10 until 20) pixels[y * size + x] = 0xffff0000.toInt()
        }
        assertEquals(Color(teal), colors.dominantColor)
        assertEquals(Color(teal), colors.topEdgeColor)
        assertEquals(Color(teal), colors.bottomEdgeColor)
    }

    @Test fun whitePixelsDoNotWashColoredEdgesOutToAPastelTint() {
        val colors = sample(blue) { pixels ->
            for (y in listOf(0, 1, 118, 119)) for (x in 0 until size) pixels[y * size + x] = -1
        }
        assertEquals(Color(blue), colors.topEdgeColor)
        assertEquals(Color(blue), colors.bottomEdgeColor)
    }

    @Test fun monochromeWhiteAndNearWhiteNeverProduceAWhitePlayerOrAnInventedHue() {
        for (fill in listOf(-1, 0xfff4f3f1.toInt())) {
            val colors = sample(fill)
            for (color in listOf(colors.dominantColor, colors.accentColor, colors.vibrantColor,
                colors.topEdgeColor, colors.bottomEdgeColor)) {
                assertEquals(color.red, color.green, 0.001f)
                assertEquals(color.red, color.blue, 0.001f)
                assertTrue(color.luminance() < 0.1f)
            }
        }
        assertEquals(Color.Black, sample(0xff000000.toInt()).dominantColor)
    }

    @Test fun aTinyColoredLogoDoesNotRecolorAnOtherwiseMonochromeCover() {
        val colors = sample(-1) { pixels ->
            for (y in 0 until 4) for (x in 0 until size) pixels[y * size + x] = 0xffff0000.toInt()
        }
        assertEquals(colors.dominantColor.red, colors.dominantColor.green, 0.001f)
        assertEquals(colors.dominantColor.red, colors.dominantColor.blue, 0.001f)
    }

    @Test fun differentColoredEdgesRetainTheirOwnHuesDespiteTheCenterSubject() {
        val purple = 0xff6a1b9a.toInt()
        val orange = 0xffef6c00.toInt()
        val colors = sample(purple) { pixels ->
            for (y in 60 until size) for (x in 0 until size) pixels[y * size + x] = orange
            for (y in 15 until 105) for (x in 12 until 108) pixels[y * size + x] = 0xff00ff00.toInt()
        }
        assertEquals(Color(purple), colors.topEdgeColor)
        assertEquals(Color(orange), colors.bottomEdgeColor)
    }

    @Test fun lightVibrantArtworkKeepsItsColorWithoutAGlaringBackground() {
        val colors = sample(0xff80ff80.toInt())
        for (color in listOf(colors.dominantColor, colors.topEdgeColor, colors.bottomEdgeColor)) {
            assertTrue(color.luminance() <= 0.40f)
            assertEquals(color.red, color.blue, 0.005f)
            assertTrue(color.green > color.red * 1.8f)
        }
    }

    @Test fun aPaleCoverRetainsItsAvailableTintWhenThereIsNoStrongerColor() {
        val colors = sample(0xffe8f1ff.toInt())
        assertTrue(colors.dominantColor.blue > colors.dominantColor.red)
        assertTrue(colors.dominantColor.luminance() < .40f)
        assertEquals(colors.dominantColor, colors.topEdgeColor)
    }

    @Test fun transparentEdgesUseTheSelectedCoverColorWithoutBlackContamination() {
        val colors = sample(blue) { pixels ->
            for (y in 0 until 8) for (x in 0 until 60) pixels[y * size + x] = 0
            for (y in 112 until size) for (x in 0 until size) pixels[y * size + x] = 0
        }
        assertEquals(Color(blue), colors.topEdgeColor)
        assertEquals(colors.dominantColor, colors.bottomEdgeColor)
        assertNull(extractPlayerArtworkColors(IntArray(4), 2, 2))
        assertNull(extractPlayerArtworkColors(IntArray(4), 3, 3))
    }

    @Test fun coloredBackgroundWinsOverANeutralPortrait() {
        val red = 0xffb81424.toInt()
        val colors = sample(red) { pixels ->
            for (y in 24 until 110) for (x in 24 until 96) pixels[y * size + x] = 0xff646464.toInt()
        }
        assertEquals(Color(red), colors.dominantColor)
    }

    @Test fun brightAccentsScaleTogetherWithoutClippingAndShiftingTheHue() {
        val colors = sample(0xffef6c00.toInt())
        val expectedRatio = colors.dominantColor.green / colors.dominantColor.red
        assertEquals(expectedRatio, colors.accentColor.green / colors.accentColor.red, .005f)
        assertEquals(expectedRatio, colors.vibrantColor.green / colors.vibrantColor.red, .005f)
    }
}
