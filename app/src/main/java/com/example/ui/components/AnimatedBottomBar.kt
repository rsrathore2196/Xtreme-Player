package com.example.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LocalAppColors
import com.example.performance.BackgroundWorkCoordinator
import kotlin.math.max
import kotlin.math.roundToInt

internal val CompactDockTabWidth = 48.dp
internal val DockHorizontalPadding = 6.dp
internal val CompactDockHorizontalPadding = 12.dp
internal val CompactPrimaryDockWidth = CompactDockTabWidth * 2 + CompactDockHorizontalPadding * 2
private val DockSpatialMotion = spring<Float>(dampingRatio = .72f, stiffness = 480f, visibilityThreshold = .001f)
private val DockEffectMotion = spring<Float>(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = .001f)

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
 *    expands/shrinks while the old and new selection backgrounds fade continuously.
 * 2. Label & Icon Visibility State: Unselected items display only their icon; selected item displays
 *    both its icon and animated text label.
 * 3. Smooth Spring Transition: One selection transition coordinates capsule width and layer
 *    translations while keeping all measurements independent of animation frames.
 * 4. Scale & Alpha Feedback: Smooth fade-in/fade-out and horizontal expansion/contraction for the text
 *    label, with restrained, non-overshooting icon emphasis and colour changes.
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
    ambientGlowColor: Color = appColors.primaryAccent,
    compactProgress: State<Float>? = null,
    compact: Boolean = false,
    compactTabIndex: Int = selectedTabIndex,
    compactCompanionTabIndex: Int? = null
) {
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val labelStyle = androidx.compose.material3.LocalTextStyle.current.copy(
        fontWeight = FontWeight.Bold, fontSize = 14.5.sp, letterSpacing = 0.2.sp)
    val expandedWidths = remember(items, textMeasurer, density, labelStyle) {
        items.map { item ->
            val labelWidth = textMeasurer.measure(item.title, labelStyle, maxLines = 1, softWrap = false).size.width
            with(density) { 24.dp.roundToPx() + 16.dp.roundToPx() * 2 + 6.dp.roundToPx() + labelWidth }.toFloat()
        }
    }
    val selectionTransition = updateTransition(selectedTabIndex, label = "bottom_bar_selection")
    val selectedContent = if (appColors.isDark) appColors.onPrimaryAccent else com.example.ui.theme.LiquidGlass.contrastColor(
        appColors.onPrimaryAccent, listOf(appColors.primaryAccent), 4.5f)
    val unselectedContent = if (appColors.isDark) Color.White.copy(alpha = .65f) else Color.Black
    val motions = items.map { item ->
        val expansion = selectionTransition.animateFloat(
            transitionSpec = { DockSpatialMotion },
            label = "tab_expansion_${item.index}"
        ) { selected -> if (selected == item.index) 1f else 0f }
        // Width, reveal and opacity follow the same retargetable spring in both
        // directions; an outgoing label must not vanish before its pill contracts.
        val labelAlpha = expansion
        val indicatorAlpha = selectionTransition.animateFloat(transitionSpec = { DockEffectMotion },
            label = "tab_indicator_${item.index}") { selected -> if (selected == item.index) 1f else 0f }
        val foreground = selectionTransition.animateColor(
            transitionSpec = { spring(dampingRatio = 1f, stiffness = 700f) },
            label = "tab_foreground_${item.index}") { selected -> if (selected == item.index) selectedContent else unselectedContent }
        remember(expansion, labelAlpha, indicatorAlpha, foreground) { DockTabMotion(expansion, labelAlpha, indicatorAlpha, foreground) }
    }
    val selectedPosition = rememberUpdatedState(items.indexOfFirst { it.index == compactTabIndex }.coerceAtLeast(0))
    val companionPosition = items.indexOfFirst { it.index == compactCompanionTabIndex }
    val geometry = remember(expandedWidths, motions, density, compactProgress, selectedPosition, companionPosition) {
        with(density) {
            DockGeometry(expandedWidths, motions, (24.dp.roundToPx() + 14.dp.roundToPx() * 2).toFloat(),
                4.dp.toPx(), DockHorizontalPadding.toPx(), compactProgress, CompactDockTabWidth.toPx(),
                selectedPosition, companionPosition, CompactDockHorizontalPadding.toPx())
        }
    }
    val viewportShape = remember(geometry) { DockViewportShape(horizontalScale = { geometry.width / geometry.reservedWidth }) }
    // Spatial feedback remains available with either glass or the plain surface;
    // the glass preference controls rendering, not the navigation spring.
    val touchInteraction = rememberDockGlassInteraction()
    val glassInteraction = touchInteraction.takeIf { appColors.dynamicGlassEnabled && LocalDynamicGlassEnabled.current }
    val pointerHandled = remember { mutableStateOf(false) }
    val pointerTab = remember { mutableIntStateOf(selectedTabIndex) }
    val pointerSelection = rememberUpdatedState<(Offset, IntSize, Boolean) -> Unit> { point, size, started ->
        if (started) { pointerHandled.value = false; pointerTab.intValue = selectedTabIndex }
        val hit = geometry.hit(point, Size(size.width.toFloat(), size.height.toFloat()), direction,
            with(density) { 8.dp.toPx() }, compact)
        if (hit != null && items[hit].index != pointerTab.intValue) {
            pointerHandled.value = true
            pointerTab.intValue = items[hit].index
            BackgroundWorkCoordinator.cancelNonEssentialWork()
            onTabSelected(items[hit].index)
        }
    }
    DisposableEffect(selectionTransition.isRunning) {
        val work = if (selectionTransition.isRunning) BackgroundWorkCoordinator.navigationBusy() else null
        onDispose { work?.close() }
    }
    val activePillBrush = remember(appColors.primaryAccent) {
        Brush.verticalGradient(listOf(appColors.primaryAccent.copy(alpha = 1f), appColors.primaryAccent.copy(alpha = 1f)))
    }
    val activeBorder = if (!appColors.dynamicGlassEnabled) appColors.cardBorder
        else Color.White.copy(alpha = if (appColors.isDark) 0.10f else 0.40f)
    val specularSheen = remember(appColors.isDark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = if (appColors.isDark) 0.10f else 0.26f), Color.Transparent),
            startY = 0f, endY = 22f)
    }
    val origin = if (direction == LayoutDirection.Ltr) TransformOrigin(0f, 0.5f) else TransformOrigin(1f, 0.5f)

    Box(
        modifier = modifier
            .graphicsLayer {
                // Tab sizing and touch share this outer layer so the glass,
                // pill and glyphs spring together without new measurements.
                val touchScale = touchInteraction.scale
                scaleX = touchScale * geometry.width / geometry.reservedWidth
                scaleY = touchScale
            }
            .observeDockGlassTouches(touchInteraction,
                onPosition = { point, size, started -> pointerSelection.value(point, size, started) },
                onFinish = { pointerHandled.value = false })
            .testTag("floating_bottom_navigation_bar")
            .wrapContentWidth()
            .height(64.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.matchParentSize().testTag("bottom_bar_outer_glass").xtremeLiquidGlassBackground(
            shape = viewportShape,
            blurRadius = XtremeLiquidGlassDefaults.FloatingDockBlurRadius,
            surfaceTintAlpha = XtremeLiquidGlassDefaults.FloatingDockTintAlpha,
            tintColor = ambientGlowColor, borderWidth = 0.8.dp, elevation = 16.dp, appColors = appColors,
            baseFillAlpha = XtremeLiquidGlassDefaults.FloatingDockDarkFillAlpha,
            neutralFinish = true,
            stableCaptureWidthPx = geometry.reservedWidth + with(density) { 12.dp.toPx() },
            interaction = glassInteraction,
            innerWhiteTintAlpha = if (appColors.isDark) 0f else 0.07f
        ))
        Layout(
            modifier = Modifier.graphicsLayer {
                // Cancel the viewport's horizontal scale for sharp, unstretched text/icons.
                scaleX = geometry.reservedWidth / geometry.width
            },
            content = {
                items.forEachIndexed { position, item -> key(item.index) {
                    val motion = motions[position]
                    val selected = selectedTabIndex == item.index
                    val hidden = compact && !geometry.retained(position)
                    val expansion = remember(motion, compactProgress) {
                        derivedStateOf { motion.expansion.value * (1f - (compactProgress?.value ?: 0f).coerceIn(0f, 1f)) }
                    }
                    val labelAlpha = remember(motion, compactProgress) {
                        derivedStateOf { motion.labelAlpha.value * (1f - (compactProgress?.value ?: 0f).coerceIn(0f, 1f)) }
                    }
                    val selectionEffect = remember(motion, compactProgress) {
                        derivedStateOf { motion.indicatorAlpha.value * (1f - (compactProgress?.value ?: 0f).coerceIn(0f, 1f)) }
                    }
                    val onClick = {
                        // Pointer-down/drag selection already navigated. A normal
                        // click on the active tab or a semantics/key click still works.
                        if (!pointerHandled.value) {
                            BackgroundWorkCoordinator.cancelNonEssentialWork()
                            onTabSelected(item.index)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                role = Role.Tab, enabled = !hidden, onClick = onClick
                            )
                            .then(if (hidden) Modifier.clearAndSetSemantics { } else Modifier.semantics { this.selected = selected })
                            .testTag("nav_tab_${item.title.lowercase()}_${if (selected) "active" else "inactive"}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.matchParentSize().graphicsLayer {
                            // The outer tab layer supplies the changing hit rectangle. Undo
                            // its scale inside so the glyphs and capsule radius stay exact.
                            scaleX = expandedWidths[position] / geometry.tabWidth(position)
                            transformOrigin = origin
                        }) {
                            Box(Modifier.matchParentSize().graphicsLayer {
                                alpha = motion.expansion.value.coerceIn(0f, 1f)
                                val width = geometry.tabWidth(position)
                                shape = DockPillShape(width, direction == LayoutDirection.Rtl, geometry.pillHeight(size.height))
                                clip = true
                                shadowElevation = 6.dp.toPx()
                                spotShadowColor = if (!appColors.dynamicGlassEnabled) Color.Black.copy(alpha = 0.15f)
                                    else appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.50f else 0.30f)
                                ambientShadowColor = Color.Transparent
                            }.drawWithCache {
                                val stroke = 0.6.dp.toPx()
                                val borderStyle = Stroke(stroke)
                                onDrawBehind {
                                    val width = geometry.tabWidth(position)
                                    val pillHeight = geometry.pillHeight(size.height)
                                    val top = (size.height - pillHeight) / 2f
                                    val left = if (direction == LayoutDirection.Ltr) 0f else size.width - width
                                    // Paint the live spring bounds, including its small
                                    // overshoot beyond the fixed measurement endpoint.
                                    drawRect(activePillBrush, Offset(left, top), Size(width, pillHeight))
                                    if (appColors.dynamicGlassEnabled) drawRect(specularSheen, Offset(left, top), Size(width, pillHeight))
                                    drawRoundRect(activeBorder, Offset(left + stroke / 2f, top + stroke / 2f),
                                        Size((width - stroke).coerceAtLeast(0f), (pillHeight - stroke).coerceAtLeast(0f)),
                                        CornerRadius((pillHeight - stroke).coerceAtLeast(0f) / 2f), style = borderStyle)
                                }
                            })
                            BottomBarTabContent(expansion, labelAlpha, selectionEffect, item, selected, motion.foreground,
                                modifier = Modifier.align(Alignment.Center), hideLabel = compact,
                                compactProgress = compactProgress)
                        }
                    }
                } }
            }
        ) { measurables, constraints ->
            val width = constraints.constrainWidth(geometry.reservedWidth.roundToInt())
            val height = constraints.constrainHeight(64.dp.roundToPx())
            val tabHeight = 52.dp.roundToPx().coerceAtMost(height)
            val tabs = measurables.mapIndexed { index, measurable ->
                measurable.measure(Constraints.fixed(expandedWidths[index].roundToInt(), tabHeight))
            }
            // Neither measurement nor placement reads an animating State. Only layer
            // properties below observe the springs, so ancestors never remeasure per frame.
            layout(width, height) {
                tabs.forEachIndexed { index, tab ->
                    tab.placeWithLayer(0, (height - tab.height) / 2) {
                        val tabWidth = geometry.tabWidth(index)
                        val left = geometry.left(index, width.toFloat(), direction)
                        scaleX = tabWidth / tab.width
                        alpha = if (geometry.retained(index)) 1f else 1f - geometry.progress
                        transformOrigin = origin
                        translationX = if (direction == LayoutDirection.Ltr) left else left + tabWidth - tab.width
                        translationY = -1.5f * motions[index].expansion.value.coerceIn(0f, 1f) * (1f - geometry.progress)
                    }
                }
            }
        }
    }
}

@Stable
private class DockTabMotion(val expansion: State<Float>, val labelAlpha: State<Float>,
    val indicatorAlpha: State<Float>, val foreground: State<Color>)

@Stable
private class DockGeometry(
    private val expanded: List<Float>, private val motions: List<DockTabMotion>,
    private val collapsed: Float, private val gap: Float, private val padding: Float,
    private val compactProgress: State<Float>?, private val compactTabWidth: Float,
    private val selectedPosition: State<Int>, private val companionPosition: Int,
    private val compactPadding: Float
) {
    val reservedWidth = (collapsed * expanded.size + (expanded.maxOrNull()?.minus(collapsed) ?: 0f) +
        gap * (expanded.size - 1).coerceAtLeast(0) + padding * 2).coerceAtLeast(1f)
    val progress: Float get() = (compactProgress?.value ?: 0f).coerceIn(0f, 1f)
    fun pillHeight(fullHeight: Float): Float = fullHeight + (minOf(fullHeight, compactTabWidth) - fullHeight) * progress
    private val edgePadding: Float get() = padding + (compactPadding - padding) * progress
    fun retained(index: Int): Boolean = index == selectedPosition.value || index == companionPosition
    private fun fullTabWidth(index: Int): Float =
        (collapsed + (expanded[index] - collapsed) * motions[index].expansion.value).coerceAtLeast(1f)
    fun tabWidth(index: Int): Float {
        val full = fullTabWidth(index)
        val target = if (retained(index)) compactTabWidth else 0f
        return (full + (target - full) * progress).coerceAtLeast(0.01f)
    }
    val width: Float get() = (expanded.indices.sumOf { tabWidth(it).toDouble() }.toFloat() +
        gap * (1f - progress) * (expanded.size - 1).coerceAtLeast(0) + edgePadding * 2).coerceAtLeast(1f)
    fun left(index: Int, viewport: Float, direction: LayoutDirection): Float {
        var preceding = edgePadding
        for (i in 0 until index) preceding += tabWidth(i) + gap * (1f - progress)
        return if (direction == LayoutDirection.Ltr) (viewport - width) / 2f + preceding
            else (viewport + width) / 2f - preceding - tabWidth(index)
    }

    fun hit(point: Offset, viewport: Size, direction: LayoutDirection, bridge: Float, compact: Boolean): Int? {
        // The foreground counteracts the viewport's width transform. Hit-test
        // in that same live coordinate space while the tabs are still moving.
        val x = (point.x - viewport.width / 2f) * width / reservedWidth + viewport.width / 2f
        val tabHeight = minOf(collapsed, viewport.height)
        val top = (viewport.height - tabHeight) / 2f
        val dy = maxOf(top - point.y, 0f, point.y - top - tabHeight)
        if (dy > bridge) return null
        var nearest: Int? = null
        var distance = Float.POSITIVE_INFINITY
        for (index in expanded.indices) {
            if (compact && !retained(index)) continue
            val left = left(index, viewport.width, direction)
            val dx = maxOf(left - x, 0f, x - left - tabWidth(index))
            if (dx == 0f && dy == 0f) return index
            if (dx <= bridge && dx * dx + dy * dy < distance) {
                nearest = index
                distance = dx * dx + dy * dy
            }
        }
        return nearest
    }
}

internal data class DockPillShape(val width: Float, val fromRight: Boolean, val height: Float? = null) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = if (fromRight) size.width - width else 0f
        val pillHeight = (height ?: size.height).coerceIn(0f, size.height)
        val top = (size.height - pillHeight) / 2f
        return Outline.Rounded(RoundRect(left, top, left + width, top + pillHeight, CornerRadius(pillHeight / 2f)))
    }
}

/** Counter the viewport's horizontal layer scale so the resting ends stay circular.
 * Keep a CornerBasedShape so the existing liquid-glass lens remains enabled. */
internal class DockViewportShape(private val horizontalScale: () -> Float,
    topStart: CornerSize = CornerSize(50), topEnd: CornerSize = CornerSize(50),
    bottomEnd: CornerSize = CornerSize(50), bottomStart: CornerSize = CornerSize(50)
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {
    override fun createOutline(size: Size, topStart: Float, topEnd: Float, bottomEnd: Float,
        bottomStart: Float, layoutDirection: LayoutDirection): Outline {
        val radius = size.height / 2f
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height,
            CornerRadius(radius / horizontalScale().coerceAtLeast(0.01f), radius)))
    }
    override fun copy(topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize,
        bottomStart: CornerSize): CornerBasedShape = DockViewportShape(horizontalScale, topStart, topEnd, bottomEnd, bottomStart)
}

@Composable
private fun BottomBarTabContent(
    expansion: State<Float>,
    labelAlpha: State<Float>,
    selectionEffect: State<Float>,
    item: AnimatedBottomBarItem,
    isSelected: Boolean,
    contentColor: State<Color>,
    modifier: Modifier = Modifier,
    hideLabel: Boolean = false,
    compactProgress: State<Float>? = null
) {
    val gapPx = with(LocalDensity.current) { 6.dp.toPx() }
    val labelDirection = LocalLayoutDirection.current
    val insetDelta = with(LocalDensity.current) { (16.dp.roundToPx() - 14.dp.roundToPx()).toFloat() }
    val compactInsetDelta = with(LocalDensity.current) { (52.dp.toPx() - CompactDockTabWidth.toPx()) / 2f }
    Layout(
        modifier = modifier,
        content = {
            DockTabIcon(item, isSelected, contentColor,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        // Keep the original selected size, without a second
                        // spatial bounce on top of the pill's width motion.
                        translationX = (insetDelta * expansion.value - compactInsetDelta * (compactProgress?.value ?: 0f)) *
                            if (labelDirection == LayoutDirection.Ltr) 1f else -1f
                        scaleX = 1f + 0.12f * selectionEffect.value
                        scaleY = scaleX
                    }
            )

            // The label stays composed and measured even when its reveal is fully closed.
            DockTabLabel(item.title, contentColor,
                modifier = (if (isSelected && !hideLabel) Modifier else Modifier.clearAndSetSemantics { })
                    .graphicsLayer {
                        alpha = labelAlpha.value.coerceIn(0f, 1f)
                        translationX = insetDelta * expansion.value * if (labelDirection == LayoutDirection.Ltr) 1f else -1f
                    }
                    .drawWithContent {
                        // Keep the exact old reveal window without a fresh Shape/Outline.
                        val reveal = ((size.width + gapPx) * expansion.value - gapPx).coerceIn(0f, size.width)
                        val left = if (labelDirection == LayoutDirection.Ltr) 0f else size.width - reveal
                        clipRect(left = left, right = left + reveal) { this@drawWithContent.drawContent() }
                    },
            )
        }
    ) { measurables, constraints ->
        val verticalPadding = 8.dp.roundToPx()
        val contentMaxHeight = if (constraints.hasBoundedHeight) {
            (constraints.maxHeight - verticalPadding * 2).coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }
        // Children keep fixed endpoint constraints throughout the selection spring.
        val contentConstraints = Constraints(maxHeight = contentMaxHeight)
        val icon = measurables[0].measure(contentConstraints)
        val label = measurables[1].measure(contentConstraints)
        val collapsedInset = 14.dp.roundToPx().toFloat()
        val labelGap = 6.dp.roundToPx().toFloat()
        val expandedWidth = icon.width + 16.dp.roundToPx() * 2 + labelGap + label.width
        val width = constraints.constrainWidth(expandedWidth.roundToInt())
        val height = constraints.constrainHeight(max(icon.height, label.height) + verticalPadding * 2)
        layout(width, height) {
            icon.placeRelative(collapsedInset.roundToInt(), (height - icon.height) / 2)
            label.placeRelative((collapsedInset + icon.width + labelGap).roundToInt(), (height - label.height) / 2)
        }
    }
}

/** Only the glyphs observe animated tint; the dock's layout stays retained. */
@Composable
private fun DockTabIcon(item: AnimatedBottomBarItem, selected: Boolean, color: State<Color>, modifier: Modifier) {
    Icon(if (selected) item.selectedIcon else item.unselectedIcon, item.title, modifier, tint = color.value)
}

@Composable
private fun DockTabLabel(title: String, color: State<Color>, modifier: Modifier) {
    Text(title, modifier, color = color.value, fontWeight = FontWeight.Bold, fontSize = 14.5.sp,
        letterSpacing = .2.sp, maxLines = 1, softWrap = false)
}
