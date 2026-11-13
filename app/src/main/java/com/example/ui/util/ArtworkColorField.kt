package com.example.ui.util

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import kotlin.math.roundToInt

/** A small, immutable, already softened texture. No full-screen blur runs while drawing. */
@Immutable
class ArtworkColorField internal constructor(internal val pixels: IntArray, val dimension: Int) {
    override fun equals(other: Any?) = other is ArtworkColorField && dimension == other.dimension && pixels.contentEquals(other.pixels)
    override fun hashCode() = 31 * dimension + pixels.contentHashCode()
}

internal fun playerBackdropTone(color: Color): Color {
    val luminance = color.luminance()
    val scale = if (luminance > .16f) (.15f / luminance).pow(1f / 2.2f) else 1f
    return Color((color.red * scale * 255).roundToInt(), (color.green * scale * 255).roundToInt(),
        (color.blue * scale * 255).roundToInt())
}

/** Preserve broad regions, including colors the cover's outermost rows do not contain. */
internal fun artworkColorField(pixels: IntArray, width: Int, height: Int, fallback: Color): ArtworkColorField {
    val grid = 5
    val sums = Array(grid * grid) { DoubleArray(4) }
    for (y in 0 until height) for (x in 0 until width) {
        val pixel = pixels[y * width + x]
        val alpha = ((pixel ushr 24) and 255) / 255.0
        if (alpha < .5) continue
        val r = (pixel ushr 16) and 255
        val g = (pixel ushr 8) and 255
        val b = pixel and 255
        // White borders carry no useful hue. They inherit the selected cover
        // tone instead of washing a colored subject into a pale background.
        if (maxOf(r, g, b) >= 210 && maxOf(r, g, b) - minOf(r, g, b) < 36) continue
        val cell = sums[(y * grid / height) * grid + x * grid / width]
        cell[0] += r * alpha; cell[1] += g * alpha; cell[2] += b * alpha; cell[3] += alpha
    }
    val colors = sums.map { cell ->
        if (cell[3] == 0.0) playerBackdropTone(fallback) else playerBackdropTone(Color(
            (cell[0] / cell[3]).roundToInt(), (cell[1] / cell[3]).roundToInt(), (cell[2] / cell[3]).roundToInt()))
    }
    val dimension = 40
    var texture = IntArray(dimension * dimension)
    fun blend(a: Color, b: Color, fraction: Float) = Color(
        a.red + (b.red - a.red) * fraction, a.green + (b.green - a.green) * fraction,
        a.blue + (b.blue - a.blue) * fraction)
    for (y in 0 until dimension) for (x in 0 until dimension) {
        val fy = y.toFloat() / (dimension - 1) * (grid - 1)
        val fx = x.toFloat() / (dimension - 1) * (grid - 1)
        val row = fy.toInt().coerceAtMost(grid - 2)
        val col = fx.toInt().coerceAtMost(grid - 2)
        val top = blend(colors[row * grid + col], colors[row * grid + col + 1], fx - col)
        val bottom = blend(colors[(row + 1) * grid + col], colors[(row + 1) * grid + col + 1], fx - col)
        texture[y * dimension + x] = blend(top, bottom, fy - row).toArgb()
    }
    // Bake the softness once. The GPU only stretches this opaque 6.4 KB image.
    repeat(3) {
        val blurred = IntArray(texture.size)
        for (y in 0 until dimension) for (x in 0 until dimension) {
            var r = 0; var g = 0; var b = 0
            for (dy in -1..1) for (dx in -1..1) {
                val weight = (if (dx == 0) 2 else 1) * (if (dy == 0) 2 else 1)
                val pixel = texture[(y + dy).coerceIn(0, dimension - 1) * dimension + (x + dx).coerceIn(0, dimension - 1)]
                r += ((pixel ushr 16) and 255) * weight
                g += ((pixel ushr 8) and 255) * weight
                b += (pixel and 255) * weight
            }
            blurred[y * dimension + x] = (0xff shl 24) or ((r / 16) shl 16) or ((g / 16) shl 8) or (b / 16)
        }
        texture = blurred
    }
    return ArtworkColorField(texture, dimension)
}
