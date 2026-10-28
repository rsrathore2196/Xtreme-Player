package com.example.ui.util

import android.graphics.Canvas
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.example.ui.screens.listeningTime

internal fun statsShortTime(millis: Long): String = listeningTime(millis).let {
    it.first + if (millis >= 3_600_000L) " hr" else " min"
}

/** A rich ink tint of the chosen accent, with the user's card colour underneath. */
internal fun statsCardBase(accent: Color, surface: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtLeast(.65f)
    hsv[2] = .10f
    return Color(ColorUtils.blendARGB(surface.toArgb(), android.graphics.Color.HSVToColor(hsv), .90f))
}

internal fun statsCardHighlight(accent: Color, surface: Color): Color {
    var amount = .65f
    while (amount > .15f && ColorUtils.calculateContrast(android.graphics.Color.WHITE,
            ColorUtils.blendARGB(surface.toArgb(), accent.toArgb(), amount)) < 7.0) amount -= .05f
    return Color(ColorUtils.blendARGB(surface.toArgb(), accent.toArgb(), amount))
}

/** Recorded once per size/palette, not rebuilt while scrolling or on every frame. */
internal fun Modifier.statsCardDecoration(accent: Color, surface: Color): Modifier = drawWithCache {
    val picture = Picture()
    val canvas = picture.beginRecording(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
    drawStatsCardFrame(canvas, RectF(0f, 0f, size.width, size.height), accent.toArgb(), surface.toArgb(), 24.dp.toPx())
    picture.endRecording()
    onDrawBehind { drawIntoCanvas { it.nativeCanvas.drawPicture(picture) } }
}

private fun alpha(color: Int, amount: Float) = ColorUtils.setAlphaComponent(color, (amount * 255).toInt().coerceIn(0, 255))

/** Same static edge lighting and ribbon detail for the cards and exported artwork. */
internal fun drawStatsCardFrame(canvas: Canvas, bounds: RectF, accent: Int, surface: Int, radius: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val outline = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }
    canvas.save()
    canvas.clipPath(outline)
    paint.color = surface
    canvas.drawRect(bounds, paint)
    val highlight = statsCardHighlight(Color(accent), Color(surface)).toArgb()
    paint.shader = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
        intArrayOf(highlight, surface, ColorUtils.blendARGB(surface, accent, .12f)),
        floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
    canvas.drawRect(bounds, paint)
    paint.shader = RadialGradient(bounds.right, bounds.top, bounds.width() * .55f,
        intArrayOf(alpha(accent, .25f), alpha(accent, 0f)), null, Shader.TileMode.CLAMP)
    canvas.drawRect(bounds, paint)
    val wave = Path().apply {
        moveTo(bounds.left - bounds.width() * .1f, bounds.bottom)
        cubicTo(bounds.left + bounds.width() * .2f, bounds.bottom + bounds.height() * .12f,
            bounds.left + bounds.width() * .55f, bounds.top + bounds.height() * .57f,
            bounds.right + bounds.width() * .1f, bounds.top + bounds.height() * .72f)
        lineTo(bounds.right, bounds.bottom)
        close()
    }
    paint.shader = LinearGradient(bounds.left, bounds.bottom, bounds.right, bounds.top + bounds.height() * .65f,
        intArrayOf(alpha(accent, .02f), alpha(accent, .30f), alpha(accent, .07f)), null, Shader.TileMode.CLAMP)
    canvas.drawPath(wave, paint)
    // Multiple softly shaded ribbons give the whole interior depth, not just an outlined edge.
    paint.style = Paint.Style.STROKE
    for (offset in listOf(-.04f, .035f, .09f)) {
        val ribbon = Path().apply {
            moveTo(bounds.left - bounds.width() * .1f, bounds.bottom + bounds.height() * offset)
            cubicTo(bounds.left + bounds.width() * .3f, bounds.bottom + bounds.height() * (.08f + offset),
                bounds.left + bounds.width() * .65f, bounds.top + bounds.height() * (.64f + offset),
                bounds.right + bounds.width() * .1f, bounds.top + bounds.height() * (.69f + offset))
        }
        paint.shader = LinearGradient(bounds.left, bounds.bottom, bounds.right, bounds.top + bounds.height() * .7f,
            intArrayOf(alpha(accent, 0f), alpha(accent, .08f), alpha(accent, .25f), alpha(accent, .04f)),
            floatArrayOf(0f, .45f, .75f, 1f), Shader.TileMode.CLAMP)
        paint.strokeWidth = bounds.width() * .045f
        canvas.drawPath(ribbon, paint)
        paint.strokeWidth = (bounds.width() * .003f).coerceAtLeast(.8f)
        canvas.drawPath(ribbon, paint)
    }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = (bounds.width() * .004f).coerceAtLeast(1f)
    paint.shader = LinearGradient(bounds.left, bounds.bottom, bounds.right, bounds.top + bounds.height() * .6f,
        intArrayOf(alpha(accent, 0f), alpha(accent, .55f), alpha(accent, 0f)), null, Shader.TileMode.CLAMP)
    canvas.drawPath(wave, paint)
    canvas.restore()
    paint.shader = null
    paint.style = Paint.Style.STROKE
    val inset = bounds.width() * .004f
    val rim = RectF(bounds).apply { inset(inset, inset) }
    paint.strokeWidth = (bounds.width() * .014f).coerceAtLeast(2f)
    paint.color = alpha(accent, .12f)
    canvas.drawRoundRect(rim, radius, radius, paint)
    paint.strokeWidth = (bounds.width() * .004f).coerceIn(1f, 1.8f)
    paint.shader = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
        intArrayOf(alpha(accent, .95f), alpha(accent, .35f), alpha(accent, .9f)), null, Shader.TileMode.CLAMP)
    canvas.drawRoundRect(rim, radius, radius, paint)
}

internal fun drawStatsPosterBackground(canvas: Canvas, accent: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(android.graphics.Color.rgb(2, 10, 24))
    paint.shader = RadialGradient(620f, 130f, 610f,
        intArrayOf(alpha(accent, .19f), alpha(accent, 0f)), null, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, 720f, 1280f, paint)
    val ribbons = listOf(
        Path().apply { moveTo(480f, -45f); cubicTo(535f, 75f, 625f, 99f, 765f, 142f) },
        Path().apply { moveTo(700f, 182f); cubicTo(500f, 256f, 664f, 343f, 790f, 492f) },
        Path().apply { moveTo(-80f, 1194f); cubicTo(160f, 1268f, 356f, 1031f, 794f, 1211f) },
        Path().apply { moveTo(-80f, 1105f); cubicTo(196f, 1190f, 395f, 1248f, 788f, 1218f) }
    )
    for (path in ribbons) {
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
        paint.strokeWidth = 28f
        paint.color = alpha(accent, .65f)
        canvas.drawPath(path, paint)
        paint.maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
        paint.strokeWidth = 10f
        paint.color = alpha(accent, .8f)
        canvas.drawPath(path, paint)
        paint.maskFilter = null
        paint.strokeWidth = 1.4f
        paint.color = ColorUtils.blendARGB(accent, android.graphics.Color.WHITE, .45f)
        canvas.drawPath(path, paint)
    }
}
