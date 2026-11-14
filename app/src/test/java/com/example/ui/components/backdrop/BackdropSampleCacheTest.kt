package com.example.ui.components.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import com.example.ui.components.backdrop.internal.BackdropRaster
import com.example.ui.components.backdrop.internal.BackdropSampleCache
import org.junit.Assert.*
import org.junit.Test

class BackdropSampleCacheTest {
    @Test fun everyContentOrGeometryChangeRequiresANewCapture() {
        val cache = BackdropSampleCache()
        val raster = BackdropRaster(.33f, .33f, Size(100f, 20f), Offset(.2f, .3f))
        val matrix = Matrix()
        assertFalse(cache.matches(1, raster, 8f, matrix.values))
        cache.recorded(1, raster, 8f, matrix.values)
        assertTrue(cache.matches(1, raster, 8f, matrix.values))
        assertFalse("Stationary artwork arrival invalidates capture", cache.matches(2, raster, 8f, matrix.values))
        assertFalse(cache.matches(1, raster.copy(size = Size(110f, 20f)), 8f, matrix.values))
        assertFalse(cache.matches(1, raster.copy(phase = Offset(.4f, .3f)), 8f, matrix.values))
        assertFalse(cache.matches(1, raster.copy(scaleX = .4f), 8f, matrix.values))
        assertFalse(cache.matches(1, raster, 9f, matrix.values))
        // A whole-pixel move keeps the raster phase but changes the sampled source.
        matrix.translate(1f, 0f)
        assertFalse(cache.matches(1, raster, 8f, matrix.values))
        cache.recorded(1, raster, 8f, matrix.values)
        matrix.translate(.05f, 0f)
        assertFalse("Store the matrix by value, including subpixel motion", cache.matches(1, raster, 8f, matrix.values))
        cache.recorded(1, raster, 8f, matrix.values)
        cache.invalidate()
        assertFalse("A replacement source must not reuse the previous layer", cache.matches(1, raster, 8f, matrix.values))
    }
}
