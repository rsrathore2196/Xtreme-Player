package com.example.ui.components.backdrop.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Rasterize on the screen's sampling grid, not the moving control's grid. In
 * particular, the dock's width is a graphicsLayer scale, not its measured width.
 * A local 1px texel otherwise covers a different part of the page on every frame
 * of the spring, even when the page has stopped scrolling.
 */
internal data class BackdropRaster(
    val scaleX: Float,
    val scaleY: Float,
    val size: Size,
    val phase: Offset
) {
    fun layerSize(padding: Float) = IntSize(
        ceil(size.width + phase.x + 2f * padding).toInt().coerceAtLeast(1),
        ceil(size.height + phase.y + 2f * padding).toInt().coerceAtLeast(1)
    )
}

internal fun backdropRaster(size: Size, sampleScale: Float, coordinates: LayoutCoordinates?): BackdropRaster {
    if (coordinates?.isAttached != true || size.isEmpty()) return localBackdropRaster(size, sampleScale)
    val origin = coordinates.localToRoot(Offset.Zero)
    val right = coordinates.localToRoot(Offset(size.width, 0f)) - origin
    val bottom = coordinates.localToRoot(Offset(0f, size.height)) - origin
    // The floating chrome uses positive, axis-aligned scales. Keep the general
    // renderer's local-space behavior for rotated, mirrored or perspective layers.
    if (abs(right.y) > .001f || abs(bottom.x) > .001f) return localBackdropRaster(size, sampleScale)
    return backdropRaster(size, sampleScale, origin, right.x / size.width, bottom.y / size.height)
}

internal fun backdropRaster(
    size: Size,
    sampleScale: Float,
    origin: Offset,
    ancestorScaleX: Float,
    ancestorScaleY: Float
): BackdropRaster {
    if (!origin.x.isFinite() || !origin.y.isFinite() || !ancestorScaleX.isFinite() ||
        !ancestorScaleY.isFinite() || ancestorScaleX <= .0001f || ancestorScaleY <= .0001f) {
        return localBackdropRaster(size, sampleScale)
    }
    val x = origin.x * sampleScale
    val y = origin.y * sampleScale
    val scaleX = sampleScale * ancestorScaleX
    val scaleY = sampleScale * ancestorScaleY
    return BackdropRaster(scaleX, scaleY, Size(size.width * scaleX, size.height * scaleY),
        Offset(x - floor(x), y - floor(y)))
}

private fun localBackdropRaster(size: Size, scale: Float) =
    BackdropRaster(scale, scale, size * scale, Offset.Zero)
