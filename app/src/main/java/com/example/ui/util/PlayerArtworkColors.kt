package com.example.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.pow
import kotlin.math.roundToInt

/** Pure pixel analysis shared by prefetch and the visible player. */
internal fun extractPlayerArtworkColors(pixels: IntArray, w: Int, h: Int): ExtractedTrackColors? {
    if (w <= 0 || h <= 0 || w.toLong() * h > pixels.size) return null
    // Spatial Multi-Region Analysis:
    // Partition into a 3x3 grid (9 zones):
    // 0: Top-Left (Corner)      1: Top-Center (Edge)       2: Top-Right (Corner)
    // 3: Mid-Left (Edge)        4: Mid-Center (Subject)    5: Mid-Right (Edge)
    // 6: Bottom-Left (Corner)   7: Bottom-Center (Edge)    8: Bottom-Right (Corner)
    val zoneWeights = floatArrayOf(
        2.2f, 1.8f, 2.2f,
        1.5f, 0.05f, 1.5f,
        1.8f, 1.2f, 1.8f
    )

    val binSumR = FloatArray(32768)
    val binSumG = FloatArray(32768)
    val binSumB = FloatArray(32768)
    val binTotalW = FloatArray(32768)
    val binZoneMask = ShortArray(32768)
    val populations = FloatArray(32768)
    var opaquePopulation = 0f
    var chromaticPopulation = 0f

    val xDiv1 = w / 3
    val xDiv2 = (2 * w) / 3
    val yDiv1 = h / 3
    val yDiv2 = (2 * h) / 3

    for (y in 0 until h) {
        val rowZone = if (y < yDiv1) 0 else if (y < yDiv2) 3 else 6
        val rowOffset = y * w
        for (x in 0 until w) {
            val colZone = if (x < xDiv1) 0 else if (x < xDiv2) 1 else 2
            val zone = rowZone + colZone

            val pixel = pixels[rowOffset + x]
            val a = (pixel ushr 24) and 0xFF
            if (a < 128) continue

            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF

            val alpha = a / 255f
            val weight = zoneWeights[zone] * alpha
            val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)

            populations[bin] += alpha
            opaquePopulation += alpha
            if (isChromatic(r, g, b)) chromaticPopulation += alpha
            binSumR[bin] += r * weight
            binSumG[bin] += g * weight
            binSumB[bin] += b * weight
            binTotalW[bin] += weight
            binZoneMask[bin] = (binZoneMask[bin].toInt() or (1 shl zone)).toShort()
        }
    }

    // Evaluate candidate bins:
    // Outer zone contiguity is paramount: true backgrounds occupy multiple outer zones.
    // Localized logos, watermarks, or text occupy only 1 or 2 outer zones.
    var bestBin = -1
    var bestScore = -1f

    for (i in 0 until 32768) {
        val totalW = binTotalW[i]
        if (totalW <= 0f) continue

        val mask = binZoneMask[i].toInt()
        val outerMask = mask and 0b111011111 // Exclude center zone 4
        val outerCount = Integer.bitCount(outerMask)
        if (outerCount == 0) continue

        // Spatial scale factor based on number of outer zones spanned:
        val spatialFactor = when (outerCount) {
            1 -> 0.25f
            2 -> 0.70f
            3 -> 1.40f
            4 -> 2.20f
            5 -> 3.00f
            else -> 4.00f
        }

        val score = totalW * spatialFactor
        if (score > bestScore) {
            bestScore = score
            bestBin = i
        }
    }

    if (opaquePopulation == 0f) return null
    if (bestBin < 0) bestBin = populations.indices.maxBy { populations[it] }
    fun binColor(bin: Int) = Color(
        (binSumR[bin] / binTotalW[bin]).roundToInt().coerceIn(0, 255),
        (binSumG[bin] / binTotalW[bin]).roundToInt().coerceIn(0, 255),
        (binSumB[bin] / binTotalW[bin]).roundToInt().coerceIn(0, 255))

    // White margins are often the largest "background" region. Look throughout
    // the actual cover, including its center, for a substantial colored subject.
    // A tiny logo alone must not turn otherwise monochrome art into that color.
    if (isLightNeutral(binColor(bestBin))) {
        val hasColor = chromaticPopulation >= opaquePopulation * 0.05f
        val candidate = populations.indices.filter { populations[it] > 0f }.filter { bin ->
            val c = binColor(bin)
            if (hasColor) isChromatic(c) else !isLightNeutral(c) || hasUsefulTint(c)
        }.maxByOrNull { bin ->
            val c = binColor(bin)
            val saturation = (maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)) /
                maxOf(c.red, c.green, c.blue).coerceAtLeast(0.001f)
            populations[bin] * if (hasColor) 0.5f + saturation else 1f
        }
        if (candidate != null && (hasColor || populations[candidate] >= opaquePopulation * 0.05f)) bestBin = candidate
    }

    // Merge adjacent bins (Manhattan distance <= 1 in 5-bit space) to avoid quantization split
    val bestR5 = (bestBin shr 10) and 0x1F
    val bestG5 = (bestBin shr 5) and 0x1F
    val bestB5 = bestBin and 0x1F

    var mergedSumR = 0f
    var mergedSumG = 0f
    var mergedSumB = 0f
    var mergedTotalW = 0f

    for (dr in -1..1) {
        val r5 = bestR5 + dr
        if (r5 !in 0..31) continue
        for (dg in -1..1) {
            val g5 = bestG5 + dg
            if (g5 !in 0..31) continue
            for (db in -1..1) {
                if (kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db) > 1) continue
                val b5 = bestB5 + db
                if (b5 !in 0..31) continue
                val neighborBin = (r5 shl 10) or (g5 shl 5) or b5
                if (binTotalW[neighborBin] > 0f) {
                    mergedSumR += binSumR[neighborBin]
                    mergedSumG += binSumG[neighborBin]
                    mergedSumB += binSumB[neighborBin]
                    mergedTotalW += binTotalW[neighborBin]
                }
            }
        }
    }

    val domR = if (mergedTotalW > 0f) (mergedSumR / mergedTotalW).roundToInt().coerceIn(0, 255)
        else (binSumR[bestBin] / binTotalW[bestBin]).roundToInt().coerceIn(0, 255)
    val domG = if (mergedTotalW > 0f) (mergedSumG / mergedTotalW).roundToInt().coerceIn(0, 255)
        else (binSumG[bestBin] / binTotalW[bestBin]).roundToInt().coerceIn(0, 255)
    val domB = if (mergedTotalW > 0f) (mergedSumB / mergedTotalW).roundToInt().coerceIn(0, 255)
        else (binSumB[bestBin] / binTotalW[bestBin]).roundToInt().coerceIn(0, 255)

    val dominant = playerBackdropTone(backgroundTone(Color(domR, domG, domB)))
    // Scale RGB together: retain the selected hue without mixing in a white tint.
    fun scaled(factor: Float): Color {
        val scale = minOf(factor, 1f / maxOf(dominant.red, dominant.green, dominant.blue).coerceAtLeast(0.001f))
        return Color(dominant.red * scale, dominant.green * scale, dominant.blue * scale)
    }
    return ExtractedTrackColors(dominant, scaled(1.12f), scaled(0.90f),
        edgeColor(pixels, w, h, bottom = false, fallback = dominant),
        edgeColor(pixels, w, h, bottom = true, fallback = dominant),
        artworkColorField(pixels, w, h, dominant))
}

private fun isLightNeutral(color: Color): Boolean =
    maxOf(color.red, color.green, color.blue) >= 0.82f &&
        maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) < 0.14f

private fun hasUsefulTint(color: Color): Boolean =
    maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) >= 0.04f

private fun isChromatic(r: Int, g: Int, b: Int): Boolean {
    val max = maxOf(r, g, b)
    val chroma = max - minOf(r, g, b)
    return max >= 40 && chroma >= 24 && chroma.toFloat() / max >= 0.18f
}

private fun isChromatic(color: Color): Boolean = isChromatic(
    (color.red * 255).roundToInt(), (color.green * 255).roundToInt(), (color.blue * 255).roundToInt())

/** Keep the artwork's hue, limiting glare from light colors without a theme/white overlay. */
private fun backgroundTone(color: Color): Color {
    if (isLightNeutral(color) && !hasUsefulTint(color)) return Color(0xff303030)
    val luminance = color.luminance()
    val scale = if (luminance > 0.40f) (0.34f / luminance).pow(1f / 2.2f) else 1f
    return Color((color.red * scale * 255).roundToInt(),
        (color.green * scale * 255).roundToInt(), (color.blue * scale * 255).roundToInt())
}

private fun edgeColor(pixels: IntArray, w: Int, h: Int, bottom: Boolean, fallback: Color): Color {
    val rows = (h * 0.06f).roundToInt().coerceIn(1, h)
    var red = 0.0; var green = 0.0; var blue = 0.0; var total = 0.0
    var coloredRed = 0.0; var coloredGreen = 0.0; var coloredBlue = 0.0; var colored = 0.0
    var white = 0.0
    for (row in 0 until rows) {
        val y = if (bottom) h - row - 1 else row
        for (x in 0 until w) {
            val pixel = pixels[y * w + x]
            val weight = ((pixel ushr 24) and 0xff) / 255.0 * (rows - row)
            val r = (pixel ushr 16) and 0xff
            val g = (pixel ushr 8) and 0xff
            val b = pixel and 0xff
            red += r * weight; green += g * weight; blue += b * weight; total += weight
            if (isLightNeutral(Color(r, g, b))) white += weight else {
                coloredRed += r * weight; coloredGreen += g * weight; coloredBlue += b * weight; colored += weight
            }
        }
    }
    if (total == 0.0) return fallback
    // White borders must not dilute colored edges to a pale tint. If an edge is
    // entirely white, extend the cover's selected subject/background tone instead.
    if (white >= total * 0.35) {
        if (colored < total * 0.10) return fallback
        red = coloredRed; green = coloredGreen; blue = coloredBlue; total = colored
    }
    return backgroundTone(Color((red / total).roundToInt(), (green / total).roundToInt(), (blue / total).roundToInt()))
}
