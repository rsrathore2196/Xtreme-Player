package com.example.ui.util

import android.graphics.Bitmap
import android.graphics.Color
import coil.size.Size
import coil.transform.Transformation
import com.example.performance.AppDispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Remove baked-in, symmetric pillarboxing from a video thumbnail used as a cover.
 * Square covers and full-frame photographs retain their original pixels. Hero only. */
internal object HeroArtworkCropTransformation : Transformation {
    override val cacheKey = "hero-artwork-content-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(AppDispatchers.CpuBounded) {
        val width = input.width
        val height = input.height
        if (width < height * 1.25f || width > height * 2.25f || height < 48) return@withContext input
        val rows = IntArray(24) { (it + 1) * (height - 1) / 25 }
        fun close(first: Int, second: Int) = Color.alpha(first) >= 250 && Color.alpha(second) >= 250 &&
            abs(Color.red(first) - Color.red(second)) <= 16 &&
            abs(Color.green(first) - Color.green(second)) <= 16 && abs(Color.blue(first) - Color.blue(second)) <= 16
        val reference = rows.map { input.getPixel(1, it) }
        // Both edges must contain the same matte, across the full picture height.
        if (rows.indices.any { !close(reference[it], input.getPixel(width - 2, rows[it])) }) return@withContext input
        fun matte(x: Int) = rows.indices.all { close(reference[it], input.getPixel(x, rows[it])) }
        val cap = width * 3 / 10
        var left = 0
        var right = 0
        while (left < cap && matte(left)) left++
        while (right < cap && matte(width - 1 - right)) right++
        val content = width - left - right
        if (left < width * .06f || right < width * .06f || abs(left - right) > maxOf(3, width / 100) ||
            content.toFloat() / height !in .8f..1.25f) return@withContext input
        Bitmap.createBitmap(input, left, 0, content, height)
    }
}
