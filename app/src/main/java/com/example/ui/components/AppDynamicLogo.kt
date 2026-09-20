package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import com.example.ui.theme.LocalAppColors

/**
 * High-fidelity theme-adaptive Xtreme Player dynamic logo.
 * Automatically synchronizes with the active theme preset and custom accent colors in real-time,
 * scaling dynamically and rendering the iconic "X" ribbon, 3D play button, ambient glow,
 * and audio wave ripples using dynamic gradient shaders.
 */
@Composable
fun AppDynamicLogo(
    modifier: Modifier = Modifier.size(42.dp),
    primaryAccent: Color = LocalAppColors.current.primaryAccent,
    secondaryAccent: Color = LocalAppColors.current.secondaryAccent,
    cutoutColor: Color = LocalAppColors.current.scaffoldBackground,
    contentDescriptionText: String = "Xtreme Player Logo"
) {
    // Cache the parsed vector paths to ensure ultra-fast 60+ FPS recompositions
    val outerRibbon = remember {
        PathParser.createPathFromPathData("M27,27 L37,27 L50,54 L37,81 L27,81 L40,54 Z").asComposePath()
    }
    val innerRibbon = remember {
        PathParser.createPathFromPathData("M34,35 L42,35 L51,54 L42,73 L34,73 L43,54 Z").asComposePath()
    }
    val topRightArm = remember {
        PathParser.createPathFromPathData("M51,51 L68,27 L78,27 L61,51 Z").asComposePath()
    }
    val topRightGroove = remember {
        PathParser.createPathFromPathData("M56,48 L68,31 L73,31 L61,48 Z").asComposePath()
    }
    val bottomRightArm = remember {
        PathParser.createPathFromPathData("M61,57 L78,81 L68,81 L51,57 Z").asComposePath()
    }
    val bottomRightGroove = remember {
        PathParser.createPathFromPathData("M61,60 L73,77 L68,77 L56,60 Z").asComposePath()
    }
    val playTriangle = remember {
        PathParser.createPathFromPathData("M42,39 L65,54 L42,69 Z").asComposePath()
    }
    val cutoutTriangle = remember {
        PathParser.createPathFromPathData("M46,45 L58,54 L46,63 Z").asComposePath()
    }
    val wave1 = remember {
        PathParser.createPathFromPathData("M66,47 A10,10 0 0,1 66,61").asComposePath()
    }
    val wave2 = remember {
        PathParser.createPathFromPathData("M72,42 A17,17 0 0,1 72,66").asComposePath()
    }
    val wave3 = remember {
        PathParser.createPathFromPathData("M78,37 A24,24 0 0,1 78,71").asComposePath()
    }

    // Dynamic shaders reflecting current theme accent colors
    val glowBrush = remember(primaryAccent, secondaryAccent) {
        Brush.radialGradient(
            0.0f to primaryAccent.copy(alpha = 0.35f),
            0.7f to secondaryAccent.copy(alpha = 0.12f),
            1.0f to Color.Transparent,
            center = Offset(52f, 54f),
            radius = 28f
        )
    }

    val outerRibbonBrush = remember(primaryAccent, secondaryAccent) {
        Brush.linearGradient(
            colors = listOf(
                primaryAccent.copy(alpha = 0.85f),
                primaryAccent,
                secondaryAccent
            ),
            start = Offset(27f, 27f),
            end = Offset(50f, 81f)
        )
    }

    val innerRibbonBrush = remember(primaryAccent, secondaryAccent) {
        Brush.linearGradient(
            colors = listOf(
                secondaryAccent.copy(alpha = 0.9f),
                primaryAccent,
                secondaryAccent
            ),
            start = Offset(34f, 35f),
            end = Offset(51f, 73f)
        )
    }

    val topRightBrush = remember(primaryAccent, secondaryAccent) {
        Brush.linearGradient(
            colors = listOf(
                secondaryAccent,
                primaryAccent,
                primaryAccent.copy(alpha = 0.85f)
            ),
            start = Offset(51f, 51f),
            end = Offset(78f, 27f)
        )
    }

    val topRightGrooveBrush = remember(primaryAccent, secondaryAccent) {
        Brush.linearGradient(
            colors = listOf(
                secondaryAccent.copy(alpha = 0.65f),
                primaryAccent.copy(alpha = 0.65f)
            ),
            start = Offset(56f, 48f),
            end = Offset(73f, 31f)
        )
    }

    val bottomRightBrush = remember(primaryAccent, secondaryAccent) {
        Brush.linearGradient(
            colors = listOf(
                secondaryAccent,
                primaryAccent,
                secondaryAccent
            ),
            start = Offset(51f, 57f),
            end = Offset(78f, 81f)
        )
    }

    val bottomRightGrooveBrush = remember(primaryAccent, secondaryAccent) {
        Brush.linearGradient(
            colors = listOf(
                secondaryAccent.copy(alpha = 0.65f),
                primaryAccent.copy(alpha = 0.65f)
            ),
            start = Offset(56f, 60f),
            end = Offset(73f, 77f)
        )
    }

    val playTriangleBrush = remember(primaryAccent, secondaryAccent) {
        Brush.linearGradient(
            colors = listOf(
                secondaryAccent,
                primaryAccent,
                primaryAccent.copy(alpha = 0.9f)
            ),
            start = Offset(42f, 39f),
            end = Offset(65f, 54f)
        )
    }

    val wave1Brush = remember(primaryAccent, secondaryAccent) {
        Brush.linearGradient(
            colors = listOf(secondaryAccent, primaryAccent),
            start = Offset(66f, 47f),
            end = Offset(71f, 54f)
        )
    }

    val wave2Brush = remember(primaryAccent, secondaryAccent) {
        Brush.linearGradient(
            colors = listOf(primaryAccent, secondaryAccent.copy(alpha = 0.9f)),
            start = Offset(72f, 42f),
            end = Offset(78f, 54f)
        )
    }

    val wave3Brush = remember(primaryAccent, secondaryAccent) {
        Brush.linearGradient(
            colors = listOf(primaryAccent.copy(alpha = 0.85f), secondaryAccent),
            start = Offset(78f, 37f),
            end = Offset(85f, 54f)
        )
    }

    Canvas(
        modifier = modifier.semantics {
            contentDescription = contentDescriptionText
        }
    ) {
        // Uniform viewport scaling relative to vector native 108x108 canvas
        val scaleFactor = size.minDimension / 108f

        scale(scaleX = scaleFactor, scaleY = scaleFactor, pivot = Offset.Zero) {
            // 1. Ambient Glow behind logo
            drawCircle(
                brush = glowBrush,
                radius = 28f,
                center = Offset(52f, 54f)
            )

            // 2. Outer Left Chevron Ribbon (<)
            drawPath(path = outerRibbon, brush = outerRibbonBrush)

            // 3. Inner Nested Left Chevron Ribbon (<)
            drawPath(path = innerRibbon, brush = innerRibbonBrush)

            // 4. Top Right Arm (/)
            drawPath(path = topRightArm, brush = topRightBrush)

            // 5. Top Right Arm Inner Groove
            drawPath(path = topRightGroove, brush = topRightGrooveBrush)

            // 6. Bottom Right Arm (\)
            drawPath(path = bottomRightArm, brush = bottomRightBrush)

            // 7. Bottom Right Arm Inner Groove
            drawPath(path = bottomRightGroove, brush = bottomRightGrooveBrush)

            // 8. Center 3D Play Triangle (▶)
            drawPath(path = playTriangle, brush = playTriangleBrush)

            // 9. Inner Triangle Cutout matching background canvas
            drawPath(path = cutoutTriangle, color = cutoutColor)

            // 10. Sound Waves Ripple Arcs
            val strokeStyle = Stroke(width = 3.2f, cap = StrokeCap.Round)
            drawPath(path = wave1, brush = wave1Brush, style = strokeStyle)
            drawPath(path = wave2, brush = wave2Brush, style = strokeStyle)
            drawPath(path = wave3, brush = wave3Brush, style = strokeStyle)
        }
    }
}
