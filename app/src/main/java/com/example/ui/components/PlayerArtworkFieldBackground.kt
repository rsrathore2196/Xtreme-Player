package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.ui.util.ArtworkColorField
import kotlin.math.roundToInt

/** Retain the previous prepared texture until the next one is ready; animate only its opacity. */
@Composable
internal fun PlayerArtworkFieldBackground(field: ArtworkColorField, artworkBounds: State<Rect?>?, modifier: Modifier = Modifier) {
    val image = remember(field) {
        Bitmap.createBitmap(field.pixels, field.dimension, field.dimension, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    var shown by remember { mutableStateOf(image) }
    var incoming by remember { mutableStateOf<ImageBitmap?>(null) }
    val opacity = remember { Animatable(0f) }
    val origin = remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(image) {
        incoming?.let { shown = it }
        incoming = null
        if (shown === image) return@LaunchedEffect
        incoming = image
        opacity.snapTo(0f)
        opacity.animateTo(1f, tween(380))
        shown = image
        incoming = null
    }
    Canvas(modifier.fillMaxSize().onGloballyPositioned { origin.value = it.positionInRoot() }) {
        val bounds = artworkBounds?.value
        val top = ((bounds?.top ?: origin.value.y) - origin.value.y).roundToInt().coerceIn(0, size.height.roundToInt())
        val bottom = ((bounds?.bottom ?: (origin.value.y + size.height)) - origin.value.y).roundToInt()
            .coerceIn(top, size.height.roundToInt())
        clipRect {
            drawArtworkField(shown, top, bottom, 1f)
            incoming?.let { drawArtworkField(it, top, bottom, opacity.value) }
        }
    }
}

private fun DrawScope.drawArtworkField(image: ImageBitmap, top: Int, bottom: Int, alpha: Float) {
    val width = size.width.roundToInt().coerceAtLeast(1)
    if (top > 0) drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, 1),
        dstSize = IntSize(width, top), alpha = alpha)
    if (bottom > top) drawImage(image, dstOffset = IntOffset(0, top), dstSize = IntSize(width, bottom - top), alpha = alpha)
    val remaining = size.height.roundToInt() - bottom
    if (remaining > 0) drawImage(image, srcOffset = IntOffset(0, image.height - 1), srcSize = IntSize(image.width, 1),
        dstOffset = IntOffset(0, bottom), dstSize = IntSize(width, remaining), alpha = alpha)
}
