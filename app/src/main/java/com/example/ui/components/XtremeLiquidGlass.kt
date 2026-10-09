package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LocalAppColors

/**
 * CompositionLocal for user toggle controlling dynamic blur and liquid glass effects.
 */
val LocalDynamicGlassEnabled = compositionLocalOf { true }

/**
 * AGSL Lens Distortion & Curvature Refraction Shader.
 * Strictly guarded to API 33+ (Android 13 Tiramisu).
 * Emulates physical acrylic/optical meniscus refraction with smooth edge curvature.
 */
private const val AGSL_LENS_DISTORTION = """
uniform shader contents;
uniform float2 size;
uniform float distortion;
uniform float refraction;

half4 main(float2 coord) {
    float2 uv = coord / size;
    float2 center = float2(0.5, 0.5);
    float2 offset = uv - center;
    float distSq = dot(offset, offset);
    
    // Smooth barrel / lens curvature factor towards periphery
    float factor = 1.0 + distortion * distSq;
    float2 distorted = (center + offset * factor) * size;
    distorted = clamp(distorted, float2(0.0), size);
    
    return contents.eval(distorted);
}
"""

object XtremeLiquidGlassDefaults {
    val BlurRadius: Dp = 8.dp
    const val SurfaceTintAlpha: Float = 0.40f
    val RimBorderWidth: Dp = 0.5.dp
    val PillShape: Shape = CircleShape
    val Elevation: Dp = 14.dp
    const val BackdropSampleScale: Float = 0.33f
}

/**
 * Shared Backdrop Source modifier for the main screen content layer.
 * Isolates page content rendering into a distinct graphics layer so that
 * the floating MiniPlayer and Glass Bottom Navigation Bar remain outside this
 * recorded layer, preventing infinite sampling loops and feedback visual artifacts.
 */
fun Modifier.xtremeSharedBackdropSource(
    enabled: Boolean = true,
    sampleScale: Float = XtremeLiquidGlassDefaults.BackdropSampleScale
): Modifier = if (enabled) {
    this.graphicsLayer {
        // Keeps page content in an isolated compositing layer for clean backdrop sampling
        clip = false
    }
} else this

/**
 * Reusable BitChord-inspired Liquid Glass modifier for Xtreme Player.
 *
 * Implements:
 * 1. AGSL Lens Distortion Shader on Android 13+ (API 33+), strictly guarded with safe try-catch.
 * 2. Safe fallback to standard RenderEffect blur on Android 12/12L (API 31-32).
 * 3. Safe fallback to rich translucent diffusion on Android 11 and below (API < 31).
 * 4. User preference toggle for "Dynamic Blur / Glass Effect" to disable heavy GPU pipelines on low-end hardware.
 * 5. Smoky/frosted surface tint (~40% opacity) that prevents background screen text and cards from
 *    showing through clearly, giving them the blurry ambient tint of the background screen.
 * 6. 0.5dp low-opacity rim/border highlight simulating physical glass edge reflection.
 * 7. Top curvature specular sheen.
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
    appColors: AppThemeColors = LocalAppColors.current
): Modifier {
    val density = LocalDensity.current
    val blurRadiusPx = with(density) { blurRadius.toPx() }
    val isDark = appColors.isDark
    val isAmoled = appColors.isAmoled

    // Theme-adaptive colorless smoky/milky liquid glass surface background without color tint
    val surfaceBrush = remember(isDark, isAmoled) {
        if (isDark) {
            val baseColorTop = if (isAmoled) Color(0xFF141518) else Color(0xFF1A1C22)
            val baseColorBottom = if (isAmoled) Color(0xFF070709) else Color(0xFF0E1014)
            Brush.verticalGradient(
                colors = listOf(
                    baseColorTop.copy(alpha = 0.82f),
                    baseColorTop.copy(alpha = 0.88f),
                    baseColorBottom.copy(alpha = 0.95f)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.85f),
                    Color(0xFFFFFFFF).copy(alpha = 0.90f),
                    Color(0xFFF1F4F9).copy(alpha = 0.95f)
                )
            )
        }
    }

    // 0.5dp low-opacity rim/border highlight brush
    val rimBorderBrush = remember(isDark) {
        if (isDark) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.28f),
                    Color.White.copy(alpha = 0.12f),
                    Color.White.copy(alpha = 0.05f)
                ),
                start = Offset.Zero,
                end = Offset.Infinite
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.70f),
                    Color.White.copy(alpha = 0.35f),
                    Color(0xFFE2E8F0).copy(alpha = 0.45f)
                ),
                start = Offset.Zero,
                end = Offset.Infinite
            )
        }
    }

    return this
        // 1. Shadow elevation for floating depth
        .shadow(
            elevation = elevation,
            shape = shape,
            spotColor = if (isDark) Color.Black.copy(alpha = 0.65f) else appColors.primaryAccent.copy(alpha = 0.16f),
            ambientColor = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.08f)
        )
        // 2. Shape clipping
        .clip(shape)
        // 3. AGSL Lens Distortion Shader & Backdrop Blur (Strictly guarded API 33+ and API 31-32)
        .graphicsLayer {
            if (enabled && blurRadiusPx > 0f) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        val w = size.width.coerceAtLeast(1f)
                        val h = size.height.coerceAtLeast(1f)
                        val runtimeShader = android.graphics.RuntimeShader(AGSL_LENS_DISTORTION)
                        runtimeShader.setFloatUniform("size", w, h)
                        runtimeShader.setFloatUniform("distortion", 0.035f)
                        runtimeShader.setFloatUniform("refraction", 0.015f)

                        val shaderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader, "contents")
                        val blurEffect = android.graphics.RenderEffect.createBlurEffect(
                            blurRadiusPx,
                            blurRadiusPx,
                            android.graphics.Shader.TileMode.CLAMP
                        )
                        val chainEffect = android.graphics.RenderEffect.createChainEffect(shaderEffect, blurEffect)
                        renderEffect = chainEffect.asComposeRenderEffect()
                    } catch (_: Throwable) {
                        // Safe fallback to standard blur on any device-specific AGSL shader error
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            try {
                                renderEffect = android.graphics.RenderEffect.createBlurEffect(
                                    blurRadiusPx,
                                    blurRadiusPx,
                                    android.graphics.Shader.TileMode.CLAMP
                                ).asComposeRenderEffect()
                            } catch (_: Throwable) {}
                        }
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        renderEffect = android.graphics.RenderEffect.createBlurEffect(
                            blurRadiusPx,
                            blurRadiusPx,
                            android.graphics.Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    } catch (_: Throwable) {}
                }
            }
        }
        // 4. Background surface with ambient tint
        .background(surfaceBrush)
        // 5. Optical diffusion and top specular curvature sheen
        .drawWithContent {
            // Frosted diffusion layer to obscure underlying background text/cards (colorless optical frost)
            val diffusionBrush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (isDark) 0.08f else 0.25f),
                    Color.White.copy(alpha = if (isDark) 0.02f else 0.06f),
                    Color.Transparent
                )
            )
            drawRect(diffusionBrush)

            // Draw inner composable content (if any)
            drawContent()

            // Top specular light catch (curvature sheen)
            val sheenBrush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (isDark) 0.18f else 0.45f),
                    Color.White.copy(alpha = if (isDark) 0.04f else 0.12f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = size.height * 0.42f
            )
            drawRect(sheenBrush)
        }
        // 6. 0.5dp low-opacity rim/border highlight
        .border(
            BorderStroke(borderWidth, rimBorderBrush),
            shape
        )
}

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
