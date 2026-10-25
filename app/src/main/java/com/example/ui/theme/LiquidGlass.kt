package com.example.ui.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass UI & Kinetic Motion Architecture.
 * Provides multi-layer depth, frosted glass background, real-time translucent gradient blur,
 * specular refraction edge highlights, and bouncy tactile spring animations that adapt cleanly
 * to system light/dark mode and user-selected custom dynamic accent colors.
 */
object LiquidGlass {

    /** Derived display colors only: never changes the user's saved palette. */
    internal fun contrastColor(color: Color, backgrounds: List<Color>, minimum: Float): Color {
        val surfaces = backgrounds.map { it.copy(alpha = 1f) }
        val initial = color.compositeOver(surfaces.first()).copy(alpha = 1f)
        fun score(candidate: Color): Float = surfaces.minOf { surface ->
            val a = candidate.luminance(); val b = surface.luminance()
            (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
        }
        if (score(initial) >= minimum) return initial
        var best = initial
        var bestScore = score(initial)
        val targets = listOf(Color.Black, Color.White)
        for (step in 1..32) {
            for (target in targets) {
                val candidate = lerp(initial, target, step / 32f)
                val contrast = score(candidate)
                if (contrast >= minimum) return candidate
                if (contrast > bestScore) { best = candidate; bestScore = contrast }
            }
        }
        return best
    }

    internal fun lightSurfaceColors(colors: AppThemeColors, tintAccent: Boolean): List<Color> {
        val base = colors.cardBackground.copy(alpha = 1f)
        val top = if (tintAccent) colors.primaryAccent.copy(alpha = 0.035f).compositeOver(base) else base
        return listOf(top.copy(alpha = 0.94f), base.copy(alpha = 0.96f),
            lerp(base, colors.cardBackgroundElevated.copy(alpha = 1f), 0.25f).copy(alpha = 0.94f))
    }

    internal fun lightOutlineColor(colors: AppThemeColors, selected: Boolean = false): Color = contrastColor(
        if (selected) colors.primaryAccent else colors.cardBorder,
        listOf(colors.cardBackground, colors.scaffoldBackground), if (selected) 3f else 1.5f)

    internal fun lightContentColor(colors: AppThemeColors, color: Color): Color = if (colors.isDark) color else
        contrastColor(color, listOf(colors.cardBackground, colors.scaffoldBackground) +
            lightSurfaceColors(colors, true).map { it.compositeOver(colors.scaffoldBackground) }, 4.5f)

    internal fun settingsColors(colors: AppThemeColors): AppThemeColors = if (colors.isDark) colors else colors.copy(
        cardBorder = lightOutlineColor(colors),
        textPrimary = lightContentColor(colors, colors.textPrimary),
        textSecondary = lightContentColor(colors, colors.textSecondary),
        textMuted = lightContentColor(colors, colors.textMuted),
        dividerColor = lightOutlineColor(colors).copy(alpha = 0.8f))

    /**
     * Bouncy spring animation specification for tab switches, icon hops, and playful micro-interactions.
     */
    val BouncySpring = spring<Float>(
        dampingRatio = 0.58f,
        stiffness = Spring.StiffnessMediumLow
    )

    /**
     * Kinetic push/pop spring specification for opening/closing player drawers, sheets, and full screens.
     */
    val PushPopSpring = spring<Float>(
        dampingRatio = 0.82f,
        stiffness = 520f
    )

    val PushPopIntOffsetSpring = spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = 0.82f,
        stiffness = 520f
    )

    val BouncyIntSizeSpring = spring<androidx.compose.ui.unit.IntSize>(
        dampingRatio = 0.58f,
        stiffness = Spring.StiffnessMediumLow
    )

    /**
     * Fast & snappy spring specification for responsive UI gestures.
     */
    val SnappySpring = spring<Float>(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMedium
    )

    /**
     * Generates a multi-stop liquid glass background gradient deeply adapting to active theme colors.
     * Uses 85% opacity (allowing 15% visibility of the app background) with dynamic accent refraction.
     */
    /**
     * Neutral, semi-transparent clear-water glass fill for outer container bars, floating shells, and cards.
     * Set to strictly 10% visibility (90% blur translucency).
     */
    fun glassBrush(
        colors: AppThemeColors,
        translucency: Float = 0.75f,
        tintAccent: Boolean = false
    ): Brush {
        if (!colors.dynamicGlassEnabled) return Brush.linearGradient(listOf(colors.cardBackground, colors.cardBackground))
        val isDark = colors.isDark
        val accent = colors.primaryAccent

        return if (isDark) {
            if (colors.isAmoled) {
                if (tintAccent) {
                    Brush.verticalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.24f),
                            Color(0xFF141414).copy(alpha = 0.88f),
                            Color(0xFF040404).copy(alpha = 0.95f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF181818).copy(alpha = 0.90f),
                            Color(0xFF101010).copy(alpha = 0.92f),
                            Color(0xFF040404).copy(alpha = 0.96f)
                        )
                    )
                }
            } else if (tintAccent) {
                Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.28f),
                        Color(0xFF222B3D).copy(alpha = 0.72f),
                        Color(0xFF10141D).copy(alpha = 0.80f)
                    )
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF222B3D).copy(alpha = 0.72f),
                        Color(0xFF171E2B).copy(alpha = 0.76f),
                        Color(0xFF10141D).copy(alpha = 0.80f)
                    )
                )
            }
        } else Brush.verticalGradient(lightSurfaceColors(colors, tintAccent))
    }

    /**
     * Smoky Frosted Glass Base Brush for Mini Player and Bottom Navigation Bar:
     * High-opacity (88%-96%) smoky frosted glass base that completely prevents background
     * screen text and cards from being clearly visible, serving as the canvas for the
     * ambient blurry tint color of the background screen.
     */
    fun miniPlayerAndBottomBarBrush(colors: AppThemeColors): Brush {
        if (!colors.dynamicGlassEnabled) return Brush.linearGradient(listOf(colors.cardBackground, colors.cardBackground))
        return if (colors.isDark) {
            if (colors.isAmoled) {
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1E1F24).copy(alpha = 0.88f),
                        Color(0xFF131417).copy(alpha = 0.94f),
                        Color(0xFF0C0D0F).copy(alpha = 0.96f)
                    )
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF262830).copy(alpha = 0.88f),
                        Color(0xFF1A1C22).copy(alpha = 0.93f),
                        Color(0xFF121418).copy(alpha = 0.96f)
                    )
                )
            }
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFFFFFFF).copy(alpha = 0.88f),
                    Color(0xFFF2F4F8).copy(alpha = 0.92f),
                    Color(0xFFE5E9F0).copy(alpha = 0.95f)
                )
            )
        }
    }

    /**
     * Water Drop Specular Border Brush:
     * Clean, neutral, high-refraction meniscus stroke simulating physical clear glass refraction.
     */
    fun waterDropSpecularBorderBrush(colors: AppThemeColors): Brush {
        if (!colors.dynamicGlassEnabled) return Brush.linearGradient(listOf(colors.cardBorder, colors.cardBorder))
        val isDark = colors.isDark
        return Brush.linearGradient(
            colors = if (isDark) {
                listOf(
                    Color.White.copy(alpha = 0.45f),
                    Color.White.copy(alpha = 0.18f),
                    Color.White.copy(alpha = 0.08f)
                )
            } else listOf(lightOutlineColor(colors), lightOutlineColor(colors))
        )
    }

    /**
     * Centerpiece control (Play/Pause) clear liquid glass brush with optical lens depth.
     */
    fun primaryGlassBrush(
        colors: AppThemeColors,
        translucency: Float = 0.82f
    ): Brush {
        if (!colors.dynamicGlassEnabled) return Brush.linearGradient(listOf(colors.cardBackground, colors.cardBackground))
        val isDark = colors.isDark
        val accent = colors.primaryAccent

        return if (isDark) {
            if (colors.isAmoled) {
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF282828).copy(alpha = 0.92f),
                        Color(0xFF181818).copy(alpha = 0.95f),
                        Color(0xFF0E0E0E).copy(alpha = 0.98f)
                    )
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF2E394E).copy(alpha = 0.82f),
                        accent.copy(alpha = 0.22f),
                        Color(0xFF141924).copy(alpha = 0.85f)
                    )
                )
            }
        } else {
            val base = colors.cardBackground.copy(alpha = 1f)
            Brush.verticalGradient(
                colors = listOf(
                    base.copy(alpha = 0.96f),
                    accent.copy(alpha = 0.10f).compositeOver(base).copy(alpha = 0.94f),
                    base.copy(alpha = 0.96f)
                )
            )
        }
    }

    /**
     * Subtle 1dp specular border stroke simulating physical light reflection:
     * - rgba(255, 255, 255, 0.40) on top/left edges
     * - rgba(255, 255, 255, 0.08) on bottom/right edges
     * No heavy opaque borders or solid dark fills.
     */
    fun specularBorderBrush(
        colors: AppThemeColors,
        highlightAlpha: Float = 0.22f
    ): Brush {
        if (!colors.dynamicGlassEnabled) return Brush.linearGradient(listOf(colors.cardBorder, colors.cardBorder))
        val isDark = colors.isDark
        val accent = colors.primaryAccent

        return if (isDark) {
            val topAlpha = highlightAlpha.coerceIn(0.14f, 0.50f)
            val botAlpha = 0.08f
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = topAlpha),
                    accent.copy(alpha = (topAlpha + botAlpha) * 0.4f),
                    accent.copy(alpha = botAlpha)
                )
            )
        } else {
            val outline = lightOutlineColor(colors)
            Brush.linearGradient(listOf(outline, outline))
        }
    }

    /**
     * Specular BorderStroke for liquid glass surfaces (1.3dp standard).
     */
    fun border(
        colors: AppThemeColors,
        width: Dp = 1.3.dp,
        highlightAlpha: Float = 0.22f
    ): BorderStroke {
        return BorderStroke(width, specularBorderBrush(colors, highlightAlpha))
    }
}

/**
 * Modifier applying full Theme-Adaptive Liquid Glass layer to any UI container:
 * - Clean elevation without dark inner double shadows (ambientColor transparent)
 * - Shape clipping
 * - Frosted glass translucent background gradient with 10% visibility & 90% blur
 * - Directional specular refraction border highlight
 * - Curvature gloss reflection sheen across upper half
 */
/**
 * Universal Master Liquid Glass modifier:
 * - Background Blur: Native blur / RenderEffect for API 31+ with safe software fallback.
 * - Dynamic Album-Art Tinting: Blends ultra-subtle translucent surface tint extracted from album art or theme palette.
 * - Glass Highlight Edge: Specular border with vertical gradient fading from top-left to bottom-right.
 * - Dynamic Icon/Text Contrast: Automatically switches inside content colors using calculateGlassContentColor().
 */
private fun Modifier.matteThemeSurface(colors: AppThemeColors, shape: Shape, elevation: Dp,
    borderWidth: Dp, active: Boolean = false): Modifier = this
    .shadow(elevation, shape)
    .clip(shape)
    .background(if (active) colors.primaryAccent.copy(alpha = 0.16f).compositeOver(colors.cardBackground)
        else colors.cardBackground.copy(alpha = 1f))
    .border(borderWidth, if (!colors.isDark) LiquidGlass.lightOutlineColor(colors, active)
        else if (active) colors.primaryAccent.copy(alpha = 0.45f) else colors.cardBorder, shape)

fun Modifier.liquidGlass(
    colors: AppThemeColors,
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 6.dp,
    translucency: Float = 0.75f,
    borderWidth: Dp = 1.3.dp,
    tintAccent: Boolean = true,
    albumArtTint: Color? = null,
    sheenAlpha: Float = 0.16f,
    highlightAlpha: Float = 0.40f
): Modifier {
    if (!colors.dynamicGlassEnabled) return matteThemeSurface(colors, shape, elevation, borderWidth, false)
    val isDark = colors.isDark
    val surfaceBase = if (isDark) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.15f)
    val effectiveTint = albumArtTint ?: if (tintAccent) colors.primaryAccent else null

    val glassBackgroundBrush = when {
        !isDark -> LiquidGlass.glassBrush(colors, translucency, effectiveTint != null)
        effectiveTint != null -> {
            Brush.verticalGradient(
                listOf(
                    effectiveTint.copy(alpha = if (isDark) 0.22f else 0.16f),
                    surfaceBase,
                    surfaceBase
                )
            )
        }
        else -> LiquidGlass.glassBrush(colors, translucency, tintAccent)
    }

    val specularBorder = BorderStroke(
        borderWidth,
        if (!isDark) LiquidGlass.specularBorderBrush(colors) else Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = highlightAlpha),
                Color.White.copy(alpha = highlightAlpha * 0.25f),
                Color.Transparent
            )
        )
    )

    return this
        .shadow(
            elevation = if (isDark) elevation else (elevation + 2.dp),
            shape = shape,
            spotColor = if (isDark) Color.Black.copy(alpha = 0.50f) else Color.Black.copy(alpha = 0.12f),
            ambientColor = if (isDark) Color.Black.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.05f)
        )
        .clip(shape)
        .background(glassBackgroundBrush)
        .drawWithCache {
            // Optical frosted diffusion layer beneath content
            val frostedDiffusion = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (colors.isDark) 0.09f else 0.10f),
                    Color.White.copy(alpha = if (colors.isDark) 0.02f else 0.025f)
                )
            )
            // Top specular reflection sheen (glass curvature light catch)
            val sheenBrush = if (colors.isDark) {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = sheenAlpha),
                        Color.White.copy(alpha = sheenAlpha * 0.25f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.48f
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.035f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.55f
                )
            }
            onDrawWithContent {
                drawRect(frostedDiffusion)
                if (!colors.isDark) drawRect(sheenBrush)
                drawContent()
                if (colors.isDark) drawRect(sheenBrush)
            }
        }
        .border(specularBorder, shape)
}

/**
 * Liquid Glass Pill Button modifier adapting to theme accent:
 * - Active: dynamically adapts active theme color with translucent frosted accent capsule
 * - Inactive: translucent frosted glass with specular border
 */
fun Modifier.liquidGlassButton(
    colors: AppThemeColors,
    shape: Shape = CircleShape,
    elevation: Dp = 4.dp,
    isActive: Boolean = false,
    translucency: Float = 0.75f,
    borderWidth: Dp = 1.3.dp
): Modifier {
    if (!colors.dynamicGlassEnabled) return matteThemeSurface(colors, shape, elevation, borderWidth, isActive)
    val activePillBrush = if (colors.isDark) {
        Brush.verticalGradient(
            listOf(
                colors.primaryAccent.copy(alpha = 0.35f),
                colors.primaryAccent.copy(alpha = 0.18f)
            )
        )
    } else {
        val base = colors.cardBackground.copy(alpha = 1f)
        Brush.verticalGradient(
            listOf(
                colors.primaryAccent.copy(alpha = 0.12f).compositeOver(base).copy(alpha = 0.96f),
                colors.primaryAccent.copy(alpha = 0.08f).compositeOver(base).copy(alpha = 0.94f),
                base.copy(alpha = 0.96f)
            )
        )
    }

    val activeBorderStroke = BorderStroke(
        borderWidth,
        if (colors.isDark) {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.55f),
                    colors.primaryAccent.copy(alpha = 0.50f)
                )
            )
        } else {
            val outline = LiquidGlass.lightOutlineColor(colors, true)
            Brush.verticalGradient(listOf(outline, outline))
        }
    )

    return this
        .shadow(
            elevation = if (colors.isDark) (if (isActive) (elevation + 2.dp) else elevation) else (elevation + 2.dp),
            shape = shape,
            spotColor = if (colors.isDark) Color.Black.copy(alpha = 0.45f) else Color.Black.copy(alpha = if (isActive) 0.14f else 0.10f),
            ambientColor = if (colors.isDark) Color.Transparent else Color.Black.copy(alpha = 0.05f)
        )
        .clip(shape)
        .then(
            if (isActive) {
                Modifier.background(activePillBrush)
            } else {
                Modifier.background(
                    LiquidGlass.glassBrush(
                        colors = colors,
                        translucency = translucency,
                        tintAccent = false
                    )
                )
            }
        )
        .drawWithCache {
            // Optical frosted diffusion layer beneath content
            val frostedDiffusion = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (colors.isDark) 0.09f else 0.10f),
                    Color.White.copy(alpha = if (colors.isDark) 0.02f else 0.025f)
                )
            )
            val sheenBrush = if (colors.isDark) {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isActive) 0.20f else 0.14f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.48f
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isActive) 0.20f else 0.16f),
                        Color.White.copy(alpha = 0.035f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.52f
                )
            }
            onDrawWithContent {
                drawRect(frostedDiffusion)
                if (!colors.isDark) drawRect(sheenBrush)
                drawContent()
                if (colors.isDark) drawRect(sheenBrush)
            }
        }
        .then(
            if (isActive) {
                Modifier.border(activeBorderStroke, shape)
            } else {
                Modifier.border(
                    BorderStroke(
                        borderWidth,
                        LiquidGlass.specularBorderBrush(
                            colors = colors,
                            highlightAlpha = if (colors.isDark) 0.38f else 0.50f
                        )
                    ),
                    shape
                )
            }
        )
}

/**
 * Primary Liquid Glass Centerpiece Button (Play / Pause):
 * Optical lens depth, translucent frosted core, convex light catch, and theme glow.
 */
fun Modifier.primaryLiquidGlassButton(
    colors: AppThemeColors,
    shape: Shape = CircleShape,
    elevation: Dp = 10.dp,
    translucency: Float = 0.82f,
    borderWidth: Dp = 1.3.dp
): Modifier {
    if (!colors.dynamicGlassEnabled) return matteThemeSurface(colors, shape, elevation, borderWidth)
    return this
    .shadow(
        elevation = if (colors.isDark) elevation else (elevation + 2.dp),
        shape = shape,
        spotColor = if (colors.isDark) colors.primaryAccent.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.14f),
        ambientColor = if (colors.isDark) Color.Transparent else Color.Black.copy(alpha = 0.05f)
    )
    .clip(shape)
    .background(LiquidGlass.primaryGlassBrush(colors, translucency))
    .drawWithCache {
        // Optical frosted diffusion layer beneath content
        val frostedDiffusion = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.12f),
                Color.White.copy(alpha = if (colors.isDark) 0.03f else 0.025f)
            )
        )
        val sheenBrush = if (colors.isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.25f),
                    Color.White.copy(alpha = 0.05f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = size.height * 0.50f
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.20f),
                    Color.White.copy(alpha = 0.035f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = size.height * 0.55f
            )
        }
        onDrawWithContent {
            drawRect(frostedDiffusion)
            if (!colors.isDark) drawRect(sheenBrush)
            drawContent()
            if (colors.isDark) drawRect(sheenBrush)
        }
    }
    .border(
        BorderStroke(
            borderWidth,
            LiquidGlass.specularBorderBrush(
                colors = colors,
                highlightAlpha = if (colors.isDark) 0.40f else 0.55f
            )
        ),
        shape
    )

}

/**
 * Lightweight Liquid Glass Card modifier (for list cards, settings items, shelf items):
 * Frosted translucent glass fill with 1.3dp specular border and upper curvature sheen.
 */
fun Modifier.liquidGlassCard(
    colors: AppThemeColors,
    shape: Shape = RoundedCornerShape(18.dp),
    elevation: Dp = 4.dp,
    translucency: Float = 0.75f,
    tintAccent: Boolean = false,
    borderWidth: Dp = 1.3.dp,
    highlightAlpha: Float = 0.38f
): Modifier = composed {
    if (!colors.dynamicGlassEnabled) return@composed matteThemeSurface(colors, shape, elevation, borderWidth)
    this
    .shadow(
        elevation = if (colors.isDark) elevation else (elevation + 2.dp),
        shape = shape,
        spotColor = if (colors.isDark) Color.Black.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.10f),
        ambientColor = if (colors.isDark) Color.Black.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.05f)
    )
    .clip(shape)
    .background(remember(colors, translucency, tintAccent) { LiquidGlass.glassBrush(colors, translucency, tintAccent) })
    .drawWithCache {
        // Optical frosted diffusion layer beneath content
        val frostedDiffusion = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (colors.isDark) 0.09f else 0.10f),
                Color.White.copy(alpha = if (colors.isDark) 0.02f else 0.025f)
            )
        )
        val sheen = if (colors.isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (colors.isDark) 0.14f else 0.22f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = size.height * 0.45f
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.16f),
                    Color.White.copy(alpha = 0.035f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = size.height * 0.50f
            )
        }
        onDrawWithContent {
            drawRect(frostedDiffusion)
            if (!colors.isDark) drawRect(sheen)
            drawContent()
            if (colors.isDark) drawRect(sheen)
        }
    }
    .border(
        remember(colors, borderWidth, highlightAlpha) { BorderStroke(borderWidth, LiquidGlass.specularBorderBrush(colors, highlightAlpha = highlightAlpha)) },
        shape
    )

}

/**
 * Liquid Glass Pill modifier for genre pills, search pills, tags, and category chips:
 * - Active: dynamically adapts active theme color with translucent frosted accent capsule
 * - Inactive: translucent frosted glass with specular border and curvature reflection sheen
 */
fun Modifier.liquidGlassPill(
    colors: AppThemeColors,
    shape: Shape = CircleShape,
    elevation: Dp = 2.dp,
    isActive: Boolean = false,
    translucency: Float = 0.75f,
    borderWidth: Dp = 1.3.dp,
    sheenAlpha: Float = 0.14f
): Modifier = composed {
    this.rememberLiquidGlassPill(colors, shape, elevation, isActive, translucency, borderWidth, sheenAlpha)
}

/** Direct Compose entry point: one remembered complete chain, without per-chip
 * composed-modifier materialization or rebuilding brushes on unrelated updates. */
@Composable
fun Modifier.rememberLiquidGlassPill(
    colors: AppThemeColors,
    shape: Shape = CircleShape,
    elevation: Dp = 2.dp,
    isActive: Boolean = false,
    translucency: Float = 0.75f,
    borderWidth: Dp = 1.3.dp,
    sheenAlpha: Float = 0.14f
): Modifier {
    val decoration = remember(colors, shape, elevation, isActive, translucency, borderWidth, sheenAlpha) {
        createLiquidGlassPill(colors, shape, elevation, isActive, translucency, borderWidth, sheenAlpha)
    }
    return this.then(decoration)
}

private fun createLiquidGlassPill(
    colors: AppThemeColors,
    shape: Shape = CircleShape,
    elevation: Dp = 2.dp,
    isActive: Boolean = false,
    translucency: Float = 0.75f,
    borderWidth: Dp = 1.3.dp,
    sheenAlpha: Float = 0.14f
): Modifier {
    if (!colors.dynamicGlassEnabled) return Modifier.matteThemeSurface(colors, shape, elevation, borderWidth, isActive)
    val fill = if (!isActive) LiquidGlass.glassBrush(colors, translucency, false) else if (colors.isDark) {
        Brush.verticalGradient(
            listOf(
                colors.primaryAccent.copy(alpha = 0.35f),
                colors.primaryAccent.copy(alpha = 0.18f)
            )
        )
    } else {
        val base = colors.cardBackground.copy(alpha = 1f)
        Brush.verticalGradient(
            listOf(
                colors.primaryAccent.copy(alpha = 0.12f).compositeOver(base).copy(alpha = 0.96f),
                colors.primaryAccent.copy(alpha = 0.08f).compositeOver(base).copy(alpha = 0.94f),
                base.copy(alpha = 0.96f)
            )
        )
    }

    val border = if (!isActive) BorderStroke(borderWidth,
        LiquidGlass.specularBorderBrush(colors, highlightAlpha = if (colors.isDark) 0.38f else 0.50f)) else BorderStroke(
        borderWidth,
        if (colors.isDark) {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.55f),
                    colors.primaryAccent.copy(alpha = 0.60f)
                )
            )
        } else {
            val outline = LiquidGlass.lightOutlineColor(colors, true)
            Brush.verticalGradient(listOf(outline, outline))
        }
    )

    return Modifier
        .shadow(
            elevation = if (colors.isDark) (if (isActive) (elevation + 2.dp) else elevation) else (elevation + 1.dp),
            shape = shape,
            spotColor = if (colors.isDark) Color.Black.copy(alpha = 0.40f) else Color.Black.copy(alpha = if (isActive) 0.14f else 0.08f),
            ambientColor = if (colors.isDark) Color.Transparent else Color.Black.copy(alpha = 0.04f)
        )
        .clip(shape)
        .background(fill)
        .drawWithCache {
            // Optical frosted diffusion layer beneath content
            val frostedDiffusion = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (colors.isDark) 0.08f else 0.10f),
                    Color.White.copy(alpha = if (colors.isDark) 0.02f else 0.025f)
                )
            )
            val sheenBrush = if (colors.isDark) {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isActive) 0.18f else sheenAlpha),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.48f
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isActive) 0.20f else 0.16f),
                        Color.White.copy(alpha = 0.035f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.52f
                )
            }
            onDrawWithContent {
                drawRect(frostedDiffusion)
                if (!colors.isDark) drawRect(sheenBrush)
                drawContent()
                if (colors.isDark) drawRect(sheenBrush)
            }
        }
        .border(border, shape)
}

/**
 * Bouncy tactile button interaction modifier:
 * Scales down with a playful kinetic spring on touch and springs back with damping bounce.
 */
@Composable
fun Modifier.bouncyClickable(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    enabled: Boolean = true,
    targetScaleOnPress: Float = 0.93f,
    onClick: () -> Unit
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) targetScaleOnPress else 1.0f,
        animationSpec = LiquidGlass.BouncySpring,
        label = "bouncy_press_scale"
    )

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (enabled) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
            } else Modifier
        )
}

/**
 * High-visibility 3D Liquid Glass Switch colors for toggles across Settings, Equalizer, and Player:
 * - Active (ON): Vibrant primary accent 3D track with outer glow and high-contrast thumb
 * - Inactive (OFF): Translucent track with specular 1px border outline so it stands out clearly
 */
@Composable
fun liquidGlassSwitchColors(appColors: AppThemeColors): androidx.compose.material3.SwitchColors {
    val isDark = appColors.isDark
    val checkedTrack = appColors.primaryAccent.copy(alpha = if (isDark) 0.65f else 0.85f)
    val checkedThumb = if (isDark) Color.White else remember(appColors, checkedTrack) {
        LiquidGlass.contrastColor(Color.White, listOf(checkedTrack.compositeOver(appColors.cardBackground)), 3f)
    }
    return androidx.compose.material3.SwitchDefaults.colors(
        checkedThumbColor = checkedThumb,
        checkedTrackColor = checkedTrack,
        checkedBorderColor = if (isDark) appColors.primaryAccent else remember(appColors) { LiquidGlass.lightOutlineColor(appColors, true) },
        uncheckedThumbColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF64748B),
        uncheckedTrackColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
        uncheckedBorderColor = if (isDark) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.22f)
    )
}



/**
 * Calculates high-contrast inside content/icon color for an active liquid glass tint:
 * Returns dark (0xFF121212) on bright/light tints, or light (0xFFFFFFFF) on dark tints.
 */
fun calculateGlassContentColor(surfaceTint: Color): Color {
    val luminance = (0.299 * surfaceTint.red + 0.587 * surfaceTint.green + 0.114 * surfaceTint.blue)
    return if (luminance > 0.55) Color(0xFF121212) else Color(0xFFFFFFFF)
}

/**
 * Custom Liquid Glass Surface container:
 * Provides elevation shadow, rounded corners, translucent backdrop, and specular border.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    colors: AppThemeColors = LocalAppColors.current,
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 6.dp,
    translucency: Float = 0.80f,
    tintAccent: Boolean = false,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.liquidGlass(
            colors = colors,
            shape = shape,
            elevation = elevation,
            translucency = translucency,
            tintAccent = tintAccent
        )
    ) {
        content()
    }
}

