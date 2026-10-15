package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.components.backdrop.backdrops.LayerBackdrop
import com.example.ui.components.backdrop.backdrops.layerBackdrop
import com.example.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.example.ui.components.backdrop.drawBackdrop
import com.example.ui.components.backdrop.effects.blur
import com.example.ui.components.backdrop.effects.lens
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LocalAppColors

val LocalDynamicGlassEnabled = compositionLocalOf { true }

/**
 * One shared RenderNode source, using the same vendored Kyant backdrop renderer
 * as BitChord. Controls remain outside their source; no bitmap readback or frame loop.
 */
class LiquidGlassBackdrop internal constructor(internal val source: LayerBackdrop) {
    internal var sampleScale by mutableFloatStateOf(XtremeLiquidGlassDefaults.BackdropSampleScale)
}

val LocalLiquidGlassBackdrop = compositionLocalOf<LiquidGlassBackdrop?> { null }

@Composable
@Suppress("UNUSED_PARAMETER")
fun rememberLiquidGlassBackdrop(captureVisibleRegions: Boolean = false): LiquidGlassBackdrop {
    // Retain the call-site API. The node renderer records the page once and each
    // reader samples only its own bounds at reduced resolution.
    val source = rememberLayerBackdrop()
    return remember(source) { LiquidGlassBackdrop(source) }
}

object XtremeLiquidGlassDefaults {
    val BlurRadius: Dp = 20.dp
    const val SurfaceTintAlpha: Float = 0.12f
    val RimBorderWidth: Dp = 0.8.dp
    val PillShape: Shape = CircleShape
    val Elevation: Dp = 10.dp
    const val BackdropSampleScale: Float = 0.33f
}

/** BitChord's record-once source: composite that display list instead of drawing twice. */
@Composable
fun Modifier.xtremeSharedBackdropSource(
    enabled: Boolean = true,
    sampleScale: Float = XtremeLiquidGlassDefaults.BackdropSampleScale,
    backdrop: LiquidGlassBackdrop? = LocalLiquidGlassBackdrop.current
): Modifier {
    if (!enabled || !LocalDynamicGlassEnabled.current || backdrop == null ||
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    val processingScale = sampleScale.takeIf { it.isFinite() }?.coerceIn(0.1f, 1f)
        ?: XtremeLiquidGlassDefaults.BackdropSampleScale
    SideEffect { backdrop.sampleScale = processingScale }
    return layerBackdrop(backdrop.source)
}

/**
 * Keep Xtreme's existing shape, tint, rim, shadow and sharp foreground. Only the
 * backdrop renderer changes: one-third-resolution blur and cached lens effects.
 * Android 12 uses blur; Android 13+ also supports the AGSL refraction lens.
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
    backdrop: LiquidGlassBackdrop? = LocalLiquidGlassBackdrop.current,
    stableCaptureWidthPx: Float = 0f
): Modifier {
    if (!enabled || !LocalDynamicGlassEnabled.current) return this
        .shadow(elevation, shape)
        .clip(shape)
        .background(appColors.cardBackground.copy(alpha = 1f))
        .border(borderWidth, appColors.cardBorder, shape)

    val density = LocalDensity.current
    val radius = with(density) { blurRadius.toPx() }
    val processingScale = backdrop?.sampleScale ?: XtremeLiquidGlassDefaults.BackdropSampleScale
    val useBackdrop = backdrop != null && radius > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val lensSize = with(density) { 24.dp.toPx() } * processingScale
    val effects: com.example.ui.components.backdrop.BackdropEffectScope.() -> Unit =
        remember(radius, processingScale, lensSize, shape) {
            {
                blur(radius * processingScale)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shape is CornerBasedShape) {
                    lens(
                        refractionHeight = lensSize,
                        refractionAmount = lensSize,
                        depthEffect = true,
                        chromaticAberration = true
                    )
                }
            }
        }
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
        .clip(shape)
        .then(
            if (useBackdrop) Modifier.drawBackdrop(
                backdrop = requireNotNull(backdrop).source,
                shape = { shape },
                effects = effects,
                // Preserve the existing finish; do not stack the renderer's
                // default shadow/highlight over Xtreme's shadow and rim.
                highlight = null,
                shadow = null,
                backdropScale = processingScale,
                stableCaptureWidthPx = stableCaptureWidthPx
            ) else Modifier
        )
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
            onDrawWithContent {
                drawRect(base.copy(alpha = if (useBackdrop || (backdrop == null && enabled)) {
                    if (dark) 0.50f else 0.64f
                } else 0.90f))
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
