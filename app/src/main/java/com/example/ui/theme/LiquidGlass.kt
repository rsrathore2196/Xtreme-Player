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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass UI & Kinetic Motion Architecture.
 * Provides multi-layer depth, frosted glass background, real-time translucent gradient blur,
 * specular refraction edge highlights, and bouncy tactile spring animations that adapt cleanly
 * to system light/dark mode and user-selected custom dynamic accent colors.
 */
object LiquidGlass {

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
        } else {
            if (tintAccent) {
                Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.88f),
                        Color(0xFFF1F5F9).copy(alpha = 0.86f)
                    )
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFFFFFF).copy(alpha = 0.88f),
                        Color(0xFFF8FAFC).copy(alpha = 0.82f),
                        Color(0xFFF1F5F9).copy(alpha = 0.86f)
                    )
                )
            }
        }
    }

    /**
     * Smoky Frosted Glass Base Brush for Mini Player and Bottom Navigation Bar:
     * High-opacity (88%-96%) smoky frosted glass base that completely prevents background
     * screen text and cards from being clearly visible, serving as the canvas for the
     * ambient blurry tint color of the background screen.
     */
    fun miniPlayerAndBottomBarBrush(colors: AppThemeColors): Brush {
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
        val isDark = colors.isDark
        return Brush.linearGradient(
            colors = if (isDark) {
                listOf(
                    Color.White.copy(alpha = 0.45f),
                    Color.White.copy(alpha = 0.18f),
                    Color.White.copy(alpha = 0.08f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.85f),
                    Color.White.copy(alpha = 0.40f),
                    Color.White.copy(alpha = 0.20f)
                )
            }
        )
    }

    /**
     * Centerpiece control (Play/Pause) clear liquid glass brush with optical lens depth.
     */
    fun primaryGlassBrush(
        colors: AppThemeColors,
        translucency: Float = 0.82f
    ): Brush {
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
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.90f),
                    accent.copy(alpha = 0.18f),
                    Color(0xFFF1F5F9).copy(alpha = 0.88f)
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
            // Light Theme 3D Liquid Glass Border:
            // Top/Left Edge (Light Refraction): Semi-transparent white/accent highlight stroke (rgba(255, 255, 255, 0.8) blended with accentColor * 0.15)
            // Bottom/Right Edge (Glass Depth Shadow): Low-opacity theme accent stroke (accentColor at 20%-30% opacity or subtle light slate/blue glass tint)
            val topHighlight = Color.White.copy(alpha = 0.80f)
            val midBlend = accent.copy(alpha = 0.18f)
            val botShadow = accent.copy(alpha = 0.28f)
            Brush.linearGradient(
                colors = listOf(
                    topHighlight,
                    midBlend,
                    botShadow
                )
            )
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
    val isDark = colors.isDark
    val surfaceBase = if (isDark) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.15f)
    val effectiveTint = albumArtTint ?: if (tintAccent) colors.primaryAccent else null

    val glassBackgroundBrush = when {
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
        Brush.verticalGradient(
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
            spotColor = if (isDark) Color.Black.copy(alpha = 0.50f) else (effectiveTint ?: colors.primaryAccent).copy(alpha = 0.12f),
            ambientColor = if (isDark) Color.Black.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.05f)
        )
        .clip(shape)
        .background(glassBackgroundBrush)
        .drawWithContent {
            // Optical frosted diffusion layer beneath content
            val frostedDiffusion = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (colors.isDark) 0.09f else 0.38f),
                    Color.White.copy(alpha = if (colors.isDark) 0.02f else 0.10f)
                )
            )
            drawRect(frostedDiffusion)
            drawContent()
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
                        Color.White.copy(alpha = 0.60f),
                        Color.White.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.55f
                )
            }
            drawRect(sheenBrush)
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
    val activePillBrush = if (colors.isDark) {
        Brush.verticalGradient(
            listOf(
                colors.primaryAccent.copy(alpha = 0.35f),
                colors.primaryAccent.copy(alpha = 0.18f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                colors.primaryAccent.copy(alpha = 0.25f),
                colors.primaryAccent.copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.35f)
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
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.85f),
                    colors.primaryAccent.copy(alpha = 0.65f),
                    colors.primaryAccent.copy(alpha = 0.30f)
                )
            )
        }
    )

    return this
        .shadow(
            elevation = if (colors.isDark) (if (isActive) (elevation + 2.dp) else elevation) else (elevation + 2.dp),
            shape = shape,
            spotColor = if (colors.isDark) Color.Black.copy(alpha = 0.45f) else colors.primaryAccent.copy(alpha = if (isActive) 0.16f else 0.10f),
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
        .drawWithContent {
            // Optical frosted diffusion layer beneath content
            val frostedDiffusion = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (colors.isDark) 0.09f else 0.35f),
                    Color.White.copy(alpha = if (colors.isDark) 0.02f else 0.10f)
                )
            )
            drawRect(frostedDiffusion)
            drawContent()
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
                        Color.White.copy(alpha = if (isActive) 0.45f else 0.60f),
                        Color.White.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.52f
                )
            }
            drawRect(sheenBrush)
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
): Modifier = this
    .shadow(
        elevation = if (colors.isDark) elevation else (elevation + 2.dp),
        shape = shape,
        spotColor = colors.primaryAccent.copy(alpha = if (colors.isDark) 0.35f else 0.20f),
        ambientColor = if (colors.isDark) Color.Transparent else Color.Black.copy(alpha = 0.05f)
    )
    .clip(shape)
    .background(LiquidGlass.primaryGlassBrush(colors, translucency))
    .drawWithContent {
        // Optical frosted diffusion layer beneath content
        val frostedDiffusion = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (colors.isDark) 0.12f else 0.40f),
                Color.White.copy(alpha = if (colors.isDark) 0.03f else 0.12f)
            )
        )
        drawRect(frostedDiffusion)
        drawContent()
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
                    Color.White.copy(alpha = 0.65f),
                    Color.White.copy(alpha = 0.15f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = size.height * 0.55f
            )
        }
        drawRect(sheenBrush)
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
): Modifier = this
    .shadow(
        elevation = if (colors.isDark) elevation else (elevation + 2.dp),
        shape = shape,
        spotColor = if (colors.isDark) Color.Black.copy(alpha = 0.45f) else colors.primaryAccent.copy(alpha = 0.10f),
        ambientColor = if (colors.isDark) Color.Black.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.05f)
    )
    .clip(shape)
    .background(LiquidGlass.glassBrush(colors, translucency, tintAccent))
    .drawWithContent {
        // Optical frosted diffusion layer beneath content
        val frostedDiffusion = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (colors.isDark) 0.09f else 0.35f),
                Color.White.copy(alpha = if (colors.isDark) 0.02f else 0.08f)
            )
        )
        drawRect(frostedDiffusion)
        drawContent()
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
                    Color.White.copy(alpha = 0.60f),
                    Color.White.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = size.height * 0.50f
            )
        }
        drawRect(sheen)
    }
    .border(
        BorderStroke(borderWidth, LiquidGlass.specularBorderBrush(colors, highlightAlpha = highlightAlpha)),
        shape
    )

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
): Modifier {
    val activePillBrush = if (colors.isDark) {
        Brush.verticalGradient(
            listOf(
                colors.primaryAccent.copy(alpha = 0.35f),
                colors.primaryAccent.copy(alpha = 0.18f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                colors.primaryAccent.copy(alpha = 0.25f),
                colors.primaryAccent.copy(alpha = 0.12f),
                Color.White.copy(alpha = 0.40f)
            )
        )
    }

    val activeBorderStroke = BorderStroke(
        borderWidth,
        if (colors.isDark) {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.55f),
                    colors.primaryAccent.copy(alpha = 0.60f)
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.85f),
                    colors.primaryAccent.copy(alpha = 0.65f),
                    colors.primaryAccent.copy(alpha = 0.30f)
                )
            )
        }
    )

    return this
        .shadow(
            elevation = if (colors.isDark) (if (isActive) (elevation + 2.dp) else elevation) else (elevation + 1.dp),
            shape = shape,
            spotColor = if (colors.isDark) Color.Black.copy(alpha = 0.40f) else colors.primaryAccent.copy(alpha = if (isActive) 0.18f else 0.08f),
            ambientColor = if (colors.isDark) Color.Transparent else Color.Black.copy(alpha = 0.04f)
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
        .drawWithContent {
            // Optical frosted diffusion layer beneath content
            val frostedDiffusion = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (colors.isDark) 0.08f else 0.35f),
                    Color.White.copy(alpha = if (colors.isDark) 0.02f else 0.08f)
                )
            )
            drawRect(frostedDiffusion)
            drawContent()
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
                        Color.White.copy(alpha = if (isActive) 0.45f else 0.60f),
                        Color.White.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.52f
                )
            }
            drawRect(sheenBrush)
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
    return androidx.compose.material3.SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = appColors.primaryAccent.copy(alpha = if (isDark) 0.65f else 0.85f),
        checkedBorderColor = appColors.primaryAccent,
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

