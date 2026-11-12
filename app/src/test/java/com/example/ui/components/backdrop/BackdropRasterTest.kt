package com.example.ui.components.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.ui.components.backdrop.internal.backdropRaster
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropRasterTest {
    @Test fun aFixedPagePointKeepsItsSamplingPhaseThroughTheEntireDockSpring() {
        // Screen-space texture coordinates must have the same fractional part
        // regardless of the control's changing origin, size or horizontal scale.
        val pagePoint = Offset(143.27f, 819.63f)
        for (sampleScale in listOf(.2f, .28f, .33f, 1f)) {
            for (progress in listOf(1f, .8f, .4f, .1f, .01f, .001f, .0001f, 0f)) {
                val origin = Offset(19.4f + 137f * progress, 760.3f + 63f * progress)
                val scaleX = .55f + .45f * (1f - progress)
                val scaleY = 1f + .03f * progress
                val raster = backdropRaster(Size(310f, 64f), sampleScale, origin, scaleX, scaleY)
                val local = Offset((pagePoint.x - origin.x) / scaleX, (pagePoint.y - origin.y) / scaleY)
                val texel = Offset(local.x * raster.scaleX, local.y * raster.scaleY) + raster.phase
                val expected = pagePoint * sampleScale
                // Compare coordinates, including the integral capture origin, not
                // modulo 1 where two nearly integral values could straddle zero.
                assertEquals(expected.x, texel.x + floor(origin.x * sampleScale), .0001f)
                assertEquals(expected.y, texel.y + floor(origin.y * sampleScale), .0001f)
                assertEquals("Horizontal compression must not stretch the blur kernel",
                    sampleScale, raster.scaleX / scaleX, .000001f)
                assertEquals(sampleScale, raster.scaleY / scaleY, .000001f)
            }
        }
    }

    @Test fun neighboringControlsUseTheSameScreenPixelGridAndKeepTheirFilterHalo() {
        val sampleScale = .28f
        val point = Offset(240.5f, 900.5f)
        for ((origin, scaleX) in listOf(Offset(21.2f, 844.6f) to .62f,
            Offset(155.8f, 908.3f) to 1f, Offset(-3.2f, 844.6f) to .9999f)) {
            val raster = backdropRaster(Size(315f, 69.5f), sampleScale, origin, scaleX, 1f)
            val padding = 7.1f
            val texelX = (point.x - origin.x) / scaleX * raster.scaleX + raster.phase.x + padding
            val texelY = (point.y - origin.y) * raster.scaleY + raster.phase.y + padding
            val worldX = origin.x + (texelX - raster.phase.x - padding) / sampleScale
            val worldY = origin.y + (texelY - raster.phase.y - padding) / sampleScale
            assertEquals(point.x, worldX, .0001f)
            assertEquals(point.y, worldY, .0001f)
            val bounds = raster.layerSize(padding)
            assertTrue(bounds.width >= raster.size.width + raster.phase.x + 2f * padding)
            assertTrue(bounds.height >= raster.size.height + raster.phase.y + 2f * padding)
        }
    }
}
