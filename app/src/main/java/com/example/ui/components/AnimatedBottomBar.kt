package com.example.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.bouncyClickable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Data model for AnimatedBottomBar tabs.
 */
@Immutable
data class AnimatedBottomBarItem(
    val index: Int,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

/**
 * Standalone, reusable AnimatedBottomBar implementing fluid tab-changing animation.
 *
 * Key Animation Behaviors:
 * 1. Pill-Style Active Indicator: Active tab is contained within a rounded pill background that
 *    dynamically expands/shrinks and slides horizontally between selected tab items.
 * 2. Label & Icon Visibility State: Unselected items display only their icon; selected item displays
 *    both its icon and animated text label.
 * 3. Smooth Spring / Layout Transition: One selection transition coordinates capsule width and
 *    placement while keeping icon and label measurements independent of animation frames.
 * 4. Scale & Alpha Feedback: Smooth fade-in/fade-out and horizontal expansion/contraction for the text
 *    label, paired with subtle icon bounce scaling.
 * 5. 3D Liquid Glass UI: 15% visibility translucent background with frosted blur, specular edge highlight,
 *    and curvature sheen.
 */
@Composable
fun AnimatedBottomBar(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    items: List<AnimatedBottomBarItem>,
    modifier: Modifier = Modifier,
    appColors: AppThemeColors = LocalAppColors.current,
    ambientGlowColor: Color = appColors.primaryAccent
) {
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = androidx.compose.material3.LocalTextStyle.current.copy(
        fontWeight = FontWeight.Bold, fontSize = 14.5.sp, letterSpacing = 0.2.sp)
    val captureWidth = remember(items, textMeasurer, density, labelStyle) {
        val labelWidth = items.maxOfOrNull { textMeasurer.measure(it.title, labelStyle, maxLines = 1, softWrap = false).size.width } ?: 0
        with(density) {
            // Existing 52dp collapsed tabs, expanded padding/gap, outer padding,
            // gaps, and a small spring overshoot margin. This is never a layout width.
            52.dp.toPx() * items.size + labelWidth + 10.dp.toPx() +
                12.dp.toPx() + 4.dp.toPx() * (items.size - 1).coerceAtLeast(0) + 12.dp.toPx()
        }
    }
    val selectionTransition = updateTransition(
        targetState = selectedTabIndex,
        label = "bottom_bar_selection"
    )

    Box(
        modifier = modifier
            .testTag("floating_bottom_navigation_bar")
            .wrapContentWidth()
            .height(64.dp),
        contentAlignment = Alignment.Center
    ) {
        // LAYER 1: GLASS BACKGROUND SURFACE ONLY (Colorless Liquid Glass)
        Box(
            modifier = Modifier
                .matchParentSize()
                .xtremeLiquidGlassBackground(
                    shape = CircleShape,
                    blurRadius = 20.dp,
                    surfaceTintAlpha = 0.10f,
                    tintColor = ambientGlowColor,
                    borderWidth = 0.8.dp,
                    elevation = 16.dp,
                    appColors = appColors,
                    stableCaptureWidthPx = captureWidth
                )
        )

        // LAYER 2: UNBLURRED FOREGROUND CONTENT
        Row(
            modifier = Modifier
                .wrapContentWidth()
                .height(64.dp)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
        ) {
            items.forEach { item ->
                key(item.index) {
                    val isSelected = selectedTabIndex == item.index

                    val expansion = selectionTransition.animateFloat(
                        transitionSpec = { spring(dampingRatio = 0.68f, stiffness = 500f) },
                        label = "tab_expansion_${item.index}"
                    ) { selectedIndex ->
                        if (selectedIndex == item.index) 1f else 0f
                    }

                    val iconScale = selectionTransition.animateFloat(
                        transitionSpec = { spring(dampingRatio = 0.58f, stiffness = 420f) },
                        label = "tab_icon_scale_${item.index}"
                    ) { selectedIndex ->
                        if (selectedIndex == item.index) 1.12f else 1.0f
                    }

                    val bounceOffsetY = selectionTransition.animateFloat(
                        transitionSpec = { spring(dampingRatio = 0.62f, stiffness = 380f) },
                        label = "tab_bounce_y_${item.index}"
                    ) { selectedIndex ->
                        if (selectedIndex == item.index) -1.5f else 0f
                    }

                    val labelAlpha = selectionTransition.animateFloat(
                        transitionSpec = { tween(if (targetState == item.index) 180 else 140) },
                        label = "tab_label_alpha_${item.index}"
                    ) { selectedIndex ->
                        if (selectedIndex == item.index) 1f else 0f
                    }

                    val activePillBrush = remember(appColors.primaryAccent, appColors.isDark, appColors.dynamicGlassEnabled, appColors.bottomBarIndicator) {
                        if (!appColors.dynamicGlassEnabled) Brush.verticalGradient(listOf(appColors.bottomBarIndicator, appColors.bottomBarIndicator))
                        else Brush.verticalGradient(listOf(
                        appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.24f else 0.20f),
                        appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.14f else 0.12f)
                    )) }
                    val activeBorderStroke = remember(appColors.isDark, appColors.dynamicGlassEnabled, appColors.cardBorder) {
                        BorderStroke(0.6.dp, if (!appColors.dynamicGlassEnabled) appColors.cardBorder else Color.White.copy(alpha = if (appColors.isDark) 0.10f else 0.40f)) }

                    // Individual tab item container: Active pill with 3D liquid glass capsule and theme accent blur
                    Box(
                        modifier = Modifier
                            .height(52.dp)
                            .graphicsLayer {
                                translationY = bounceOffsetY.value
                            }
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .shadow(
                                            elevation = 6.dp,
                                            shape = CircleShape,
                                            spotColor = if (!appColors.dynamicGlassEnabled) Color.Black.copy(alpha = 0.15f) else appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.50f else 0.30f),
                                            ambientColor = Color.Transparent
                                        )
                                        .clip(CircleShape)
                                        .background(activePillBrush)
                                        .border(activeBorderStroke, CircleShape)
                                } else {
                                    Modifier.clip(CircleShape)
                                }
                            )
                            .bouncyClickable { onTabSelected(item.index) }
                            .testTag(
                                if (isSelected) "nav_tab_${item.title.lowercase()}_active"
                                else "nav_tab_${item.title.lowercase()}_inactive"
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected && appColors.dynamicGlassEnabled) {
                            // 3D Liquid Glass Specular Highlight Sheen across top curvature (unblurred)
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(CircleShape)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = if (appColors.isDark) 0.10f else 0.26f),
                                                Color.Transparent
                                            ),
                                            startY = 0f,
                                            endY = 22f
                                        )
                                    )
                            )
                        }

                        val selectedTabContentColor = appColors.textPrimary

                        BottomBarTabContent(
                            expansion = expansion,
                            labelAlpha = labelAlpha,
                            iconScale = iconScale,
                            item = item,
                            isSelected = isSelected,
                            selectedContentColor = selectedTabContentColor,
                            unselectedContentColor = if (appColors.isDark) {
                                Color.White.copy(alpha = 0.65f)
                            } else {
                                Color(0xFF64748B)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBarTabContent(
    expansion: State<Float>,
    labelAlpha: State<Float>,
    iconScale: State<Float>,
    item: AnimatedBottomBarItem,
    isSelected: Boolean,
    selectedContentColor: Color,
    unselectedContentColor: Color
) {
    val gapPx = with(LocalDensity.current) { 6.dp.toPx() }
    val labelDirection = LocalLayoutDirection.current
    Layout(
        content = {
            Icon(
                imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                contentDescription = item.title,
                tint = if (isSelected) selectedContentColor else unselectedContentColor,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = iconScale.value
                        scaleY = iconScale.value
                    }
            )

            // The label stays composed and measured even when its reveal is fully closed.
            Text(
                text = item.title,
                modifier = (if (isSelected) Modifier else Modifier.clearAndSetSemantics { })
                    .drawWithContent {
                        // Keep the exact old reveal window without a fresh Shape/Outline.
                        val reveal = ((size.width + gapPx) * expansion.value - gapPx).coerceIn(0f, size.width)
                        val left = if (labelDirection == LayoutDirection.Ltr) 0f else size.width - reveal
                        clipRect(left = left, right = left + reveal) { this@drawWithContent.drawContent() }
                    },
                color = selectedContentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 14.5.sp,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    ) { measurables, constraints ->
        val verticalPadding = 8.dp.roundToPx()
        val contentMaxHeight = if (constraints.hasBoundedHeight) {
            (constraints.maxHeight - verticalPadding * 2).coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }
        // Fixed child constraints let Compose reuse the icon/text placeables as sibling widths
        // change. Only this small layout observes the spring for its reported capsule width.
        val contentConstraints = Constraints(maxHeight = contentMaxHeight)
        val icon = measurables[0].measure(contentConstraints)
        val label = measurables[1].measure(contentConstraints)
        val collapsedInset = 14.dp.roundToPx().toFloat()
        val expandedInset = 16.dp.roundToPx().toFloat()
        val labelGap = 6.dp.roundToPx().toFloat()
        // Preserve the original 52dp collapsed endpoint: a 24dp icon and 14dp on either side.
        val collapsedWidth = icon.width + collapsedInset * 2
        val expandedWidth = icon.width + expandedInset * 2 + labelGap + label.width
        val width = constraints.constrainWidth(
            (collapsedWidth + (expandedWidth - collapsedWidth) * expansion.value)
                .roundToInt()
                .coerceAtLeast(0)
        )
        val height = constraints.constrainHeight(
            max(icon.height, label.height) + verticalPadding * 2
        )

        layout(width, height) {
            val progress = expansion.value
            val startInset = collapsedInset + (expandedInset - collapsedInset) * progress
            icon.placeRelative(
                x = startInset.roundToInt(),
                y = (height - icon.height) / 2
            )
            label.placeRelativeWithLayer(
                x = (startInset + icon.width + labelGap).roundToInt(),
                y = (height - label.height) / 2
            ) {
                alpha = labelAlpha.value
                clip = true
                shape = androidx.compose.ui.graphics.RectangleShape
            }
        }
    }
}
