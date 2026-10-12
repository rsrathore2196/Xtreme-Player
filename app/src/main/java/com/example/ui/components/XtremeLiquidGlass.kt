package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LocalAppColors
import kotlin.math.ceil

val LocalDynamicGlassEnabled = compositionLocalOf { true }

/** A shared GPU display list. Glass controls must remain outside their own source. */
class LiquidGlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var position by mutableStateOf(Offset.Zero)
    internal var revision by mutableIntStateOf(0)
    private var recording = 0
    internal fun recorded() { revision = ++recording }
}

val LocalLiquidGlassBackdrop = compositionLocalOf<LiquidGlassBackdrop?> { null }

@Composable
fun rememberLiquidGlassBackdrop(): LiquidGlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { LiquidGlassBackdrop(layer) }
}

object XtremeLiquidGlassDefaults {
    val BlurRadius: Dp = 20.dp
    const val SurfaceTintAlpha: Float = 0.12f
    val RimBorderWidth: Dp = 0.8.dp
    val PillShape: Shape = CircleShape
    val Elevation: Dp = 10.dp
    const val BackdropSampleScale: Float = 1f
}

/** Record once for all floating surfaces; no bitmap readback or per-frame coroutine. */
@Composable
fun Modifier.xtremeSharedBackdropSource(
    enabled: Boolean = true,
    sampleScale: Float = XtremeLiquidGlassDefaults.BackdropSampleScale,
    backdrop: LiquidGlassBackdrop? = LocalLiquidGlassBackdrop.current
): Modifier {
    if (!enabled || backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return this.onGloballyPositioned { backdrop.position = it.positionInWindow() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            backdrop.recorded()
            drawLayer(backdrop.layer)
        }
}

/**
 * Smoky glass with real blurred backdrop, a clear center and a reflective curved rim.
 * Only the sampled background is blurred; foreground text, icons and artwork stay sharp.
 * Android 11 and the reduced-effects preference use the same finish without GPU blur.
 */
@Composable
fun Modifier.xtremeLiquidGlassBackground(
    shape: Shape = XtremeLiquidGlassDefaults.PillShape,
    blurRadius: Dp = XtremeLiquidGlassDefaults.BlurRadius,
    surfaceTintAlpha: Float = XtremeLiquidGlassDefaults.SurfaceTintAlpha,
    tintColor: Color = LocalAppColors.current.primaryAccent,
    borderWidth: Dp = XtremeLiquidGlassDefaults.RimBorderWidth,
    elevation: Dp = XtremeLiquidGlassDefaults.Elevation,
    enabled: Boolean = LocalDynamicGlassEnabled.current,
    appColors: AppThemeColors = LocalAppColors.current,
    backdrop: LiquidGlassBackdrop? = LocalLiquidGlassBackdrop.current
): Modifier {
    val density = LocalDensity.current
    val radius = with(density) { blurRadius.toPx() }
    val supportsBlur = enabled && radius > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blur = remember(supportsBlur, radius) {
        if (supportsBlur) android.graphics.RenderEffect.createBlurEffect(
            radius, radius, android.graphics.Shader.TileMode.CLAMP
        ).asComposeRenderEffect() else null
    }
    val sample = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    val dark = appColors.isDark
    val rim = remember(dark) {
        Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (dark) 0.48f else 0.92f),
            Color.White.copy(alpha = if (dark) 0.08f else 0.32f),
            Color.White.copy(alpha = if (dark) 0.06f else 0.15f),
            (if (dark) Color.White else Color(0xFF708090)).copy(alpha = 0.30f)
        ))
    }
    return this
        .shadow(elevation, shape, clip = false,
            spotColor = Color.Black.copy(alpha = if (dark) 0.42f else 0.14f),
            ambientColor = Color.Black.copy(alpha = 0.08f))
        .onGloballyPositioned { position = it.positionInWindow() }
        .clip(shape)
        .drawWithCache {
            val base = if (dark) Color(0xFF101013) else Color(0xFFF8FAFD)
            val tint = tintColor.copy(alpha = surfaceTintAlpha.coerceIn(0f, 0.65f) * tintColor.alpha)
            val colorWash = Brush.linearGradient(listOf(tint, Color.Transparent, tint.copy(alpha = tint.alpha * 0.45f)),
                Offset.Zero, Offset(size.width, size.height))
            val sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.10f else 0.36f),
                0.32f to Color.White.copy(alpha = 0.015f), 0.65f to Color.Transparent,
                1f to Color.White.copy(alpha = if (dark) 0.055f else 0.14f))
            val reflection = Brush.radialGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.065f else 0.18f), Color.Transparent),
                center = Offset(size.width * 0.72f, size.height * 1.6f), radius = size.width.coerceAtLeast(size.height) * 0.65f)
            // Extra pixels avoid dark seams when the blur kernel reaches the pill's edge.
            val bleed = ceil(radius * 2).toInt()
            val sampleSize = IntSize(ceil(size.width).toInt() + bleed * 2, ceil(size.height).toInt() + bleed * 2)
            onDrawWithContent {
                val ready = blur != null && backdrop != null && backdrop.revision > 0 && !backdrop.layer.isReleased
                if (ready) {
                    val offset = backdrop!!.position - position
                    sample.renderEffect = blur
                    sample.record(size = sampleSize) {
                        translate(offset.x + bleed, offset.y + bleed) { drawLayer(backdrop.layer) }
                    }
                    translate(-bleed.toFloat(), -bleed.toFloat()) { drawLayer(sample) }
                }
                drawRect(base.copy(alpha = if (ready || (backdrop == null && enabled)) { if (dark) 0.50f else 0.64f } else 0.90f))
                drawRect(colorWash)
                drawRect(sheen)
                drawRect(reflection)
                drawContent()
            }
        }
        .border(BorderStroke(borderWidth, rim), shape)
}

/** Opt-in control finish; other screens keep their existing button appearance. */
@Composable
fun Modifier.playerLiquidGlassButton(
    colors: AppThemeColors,
    shape: Shape = CircleShape,
    elevation: Dp = 4.dp,
    isActive: Boolean = false,
    translucency: Float = 0.75f,
    borderWidth: Dp = 0.8.dp
): Modifier = xtremeLiquidGlassBackground(
    shape = shape, elevation = elevation, appColors = colors,
    tintColor = colors.primaryAccent,
    surfaceTintAlpha = if (isActive) 0.30f else 0.07f,
    borderWidth = borderWidth
)

@Composable
fun Modifier.playerPrimaryLiquidGlassButton(
    colors: AppThemeColors,
    shape: Shape = CircleShape,
    elevation: Dp = 10.dp,
    translucency: Float = 0.82f,
    borderWidth: Dp = 1.dp
): Modifier = xtremeLiquidGlassBackground(
    shape = shape, elevation = elevation, appColors = colors,
    tintColor = colors.primaryAccent, surfaceTintAlpha = 0.25f, borderWidth = borderWidth
)

/**
 * Reusable modifier alias for backwards compatibility.
 * Note: To prevent child blurring, prefer using [XtremeLiquidGlassContainer] or applying
 * [xtremeLiquidGlassBackground] on a dedicated Layer 1 background Box.
 */
@Composable
fun Modifier.xtremeLiquidGlass(
    shape: Shape = XtremeLiquidGlassDefaults.PillShape,
    blurRadius: Dp = XtremeLiquidGlassDefaults.BlurRadius,
    surfaceTintAlpha: Float = XtremeLiquidGlassDefaults.SurfaceTintAlpha,
    tintColor: Color = LocalAppColors.current.primaryAccent,
    borderWidth: Dp = XtremeLiquidGlassDefaults.RimBorderWidth,
    elevation: Dp = XtremeLiquidGlassDefaults.Elevation,
    enabled: Boolean = LocalDynamicGlassEnabled.current,
    appColors: AppThemeColors = LocalAppColors.current
): Modifier = xtremeLiquidGlassBackground(
    shape = shape,
    blurRadius = blurRadius,
    surfaceTintAlpha = surfaceTintAlpha,
    tintColor = tintColor,
    borderWidth = borderWidth,
    elevation = elevation,
    enabled = enabled,
    appColors = appColors
)

/**
 * Reusable Liquid Glass Container enforcing the 2-layer stacked architecture:
 * LAYER 1: Glass background surface only (blur, AGSL shader, tint, sheen, border)
 * LAYER 2: Crisp foreground content, completely unblurred and sharp.
 */
@Composable
fun XtremeLiquidGlassContainer(
    modifier: Modifier = Modifier,
    shape: Shape = XtremeLiquidGlassDefaults.PillShape,
    blurRadius: Dp = XtremeLiquidGlassDefaults.BlurRadius,
    surfaceTintAlpha: Float = XtremeLiquidGlassDefaults.SurfaceTintAlpha,
    tintColor: Color = LocalAppColors.current.primaryAccent,
    borderWidth: Dp = XtremeLiquidGlassDefaults.RimBorderWidth,
    elevation: Dp = XtremeLiquidGlassDefaults.Elevation,
    enabled: Boolean = LocalDynamicGlassEnabled.current,
    appColors: AppThemeColors = LocalAppColors.current,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        // LAYER 1: GLASS BACKGROUND SURFACE ONLY
        Box(
            modifier = Modifier
                .matchParentSize()
                .xtremeLiquidGlassBackground(
                    shape = shape,
                    blurRadius = blurRadius,
                    surfaceTintAlpha = surfaceTintAlpha,
                    tintColor = tintColor,
                    borderWidth = borderWidth,
                    elevation = elevation,
                    enabled = enabled,
                    appColors = appColors
                )
        )

        // LAYER 2: UNBLURRED FOREGROUND CONTENT
        content()
    }
}

/**
 * Reusable Liquid Glass Surface component wrapping content with Xtreme Liquid Glass styling
 * using the 2-layer stacked architecture so child composables remain sharp.
 */
@Composable
fun XtremeLiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = XtremeLiquidGlassDefaults.PillShape,
    blurRadius: Dp = XtremeLiquidGlassDefaults.BlurRadius,
    surfaceTintAlpha: Float = XtremeLiquidGlassDefaults.SurfaceTintAlpha,
    tintColor: Color = LocalAppColors.current.primaryAccent,
    borderWidth: Dp = XtremeLiquidGlassDefaults.RimBorderWidth,
    elevation: Dp = XtremeLiquidGlassDefaults.Elevation,
    enabled: Boolean = LocalDynamicGlassEnabled.current,
    appColors: AppThemeColors = LocalAppColors.current,
    content: @Composable () -> Unit
) {
    XtremeLiquidGlassContainer(
        modifier = modifier,
        shape = shape,
        blurRadius = blurRadius,
        surfaceTintAlpha = surfaceTintAlpha,
        tintColor = tintColor,
        borderWidth = borderWidth,
        elevation = elevation,
        enabled = enabled,
        appColors = appColors
    ) {
        content()
    }
}
