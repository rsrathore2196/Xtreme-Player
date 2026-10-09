package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.bouncyClickable

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key

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
 * Standalone, reusable AnimatedBottomBar implementing the LastWave Native tab-changing animation.
 *
 * Key Animation Behaviors:
 * 1. Pill-Style Active Indicator: Active tab is contained within a rounded pill background that
 *    dynamically expands/shrinks and slides horizontally between selected tab items.
 * 2. Label & Icon Visibility State: Unselected items display only their icon; selected item displays
 *    both its icon and animated text label.
 * 3. Smooth Spring / Layout Transition: Uses spring-based animateContentSize to smoothly animate
 *    width and layout position during selection switches.
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
                    blurRadius = 8.dp,
                    surfaceTintAlpha = 0f,
                    tintColor = Color.Transparent,
                    borderWidth = 0.5.dp,
                    elevation = 16.dp,
                    appColors = appColors
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

                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.12f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = 0.68f,
                            stiffness = 1200f
                        ),
                        label = "tab_icon_scale_${item.index}"
                    )

                    val bounceOffsetY by animateFloatAsState(
                        targetValue = if (isSelected) -1.5f else 0f,
                        animationSpec = spring(
                            dampingRatio = 0.72f,
                            stiffness = 1100f
                        ),
                        label = "tab_bounce_y_${item.index}"
                    )

                    val activePillBrush = if (appColors.isDark) {
                        Brush.verticalGradient(
                            listOf(
                                appColors.primaryAccent.copy(alpha = 0.88f),
                                appColors.primaryAccent.copy(alpha = 0.72f),
                                appColors.secondaryAccent.copy(alpha = 0.80f)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                appColors.primaryAccent.copy(alpha = 0.92f),
                                appColors.primaryAccent.copy(alpha = 0.82f),
                                appColors.secondaryAccent.copy(alpha = 0.88f)
                            )
                        )
                    }

                    val activeBorderStroke = BorderStroke(
                        1.3.dp,
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.85f),
                                appColors.primaryAccent.copy(alpha = 0.60f),
                                Color.White.copy(alpha = 0.30f)
                            )
                        )
                    )

                    // Individual tab item container: Active pill with 3D liquid glass capsule and theme accent blur
                    Box(
                        modifier = Modifier
                            .height(52.dp)
                            .graphicsLayer {
                                translationY = bounceOffsetY
                            }
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .shadow(
                                            elevation = 6.dp,
                                            shape = CircleShape,
                                            spotColor = appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.50f else 0.30f),
                                            ambientColor = Color.Transparent
                                        )
                                        .clip(CircleShape)
                                        .background(activePillBrush)
                                        .border(activeBorderStroke, CircleShape)
                                } else {
                                    Modifier.clip(CircleShape)
                                }
                            )
                            .animateContentSize(
                                animationSpec = spring(
                                    dampingRatio = 0.76f,
                                    stiffness = 1050f
                                )
                            )
                            .bouncyClickable { onTabSelected(item.index) }
                            .testTag(
                                if (isSelected) "nav_tab_${item.title.lowercase()}_active"
                                else "nav_tab_${item.title.lowercase()}_inactive"
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            // 3D Liquid Glass Specular Highlight Sheen across top curvature (unblurred)
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(CircleShape)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = if (appColors.isDark) 0.35f else 0.48f),
                                                Color.Transparent
                                            ),
                                            startY = 0f,
                                            endY = 22f
                                        )
                                    )
                            )
                        }
                        
                        val isPillLight = appColors.isAmoled && (appColors.primaryAccent == Color.White || (0.299 * appColors.primaryAccent.red + 0.587 * appColors.primaryAccent.green + 0.114 * appColors.primaryAccent.blue) > 0.65f) || (!appColors.isDark && (0.299 * appColors.primaryAccent.red + 0.587 * appColors.primaryAccent.green + 0.114 * appColors.primaryAccent.blue) > 0.65f)
                        val selectedTabContentColor = if (isPillLight) Color(0xFF0A0A0A) else Color.White

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(
                                horizontal = if (isSelected) 16.dp else 14.dp,
                                vertical = 8.dp
                            )
                        ) {
                            Icon(
                                imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.title,
                                tint = if (isSelected) {
                                    selectedTabContentColor
                                } else {
                                    if (appColors.isDark) Color.White.copy(alpha = 0.65f) else Color(0xFF64748B)
                                },
                                modifier = Modifier
                                    .size(24.dp)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    }
                            )

                            // Smooth snappy horizontal expansion and fade-in / shrink and fade-out for label (120 FPS tuned)
                            AnimatedVisibility(
                                visible = isSelected,
                                enter = fadeIn(
                                    animationSpec = tween(120)
                                ) + expandHorizontally(
                                    animationSpec = spring(
                                        dampingRatio = 0.76f,
                                        stiffness = 1050f
                                    ),
                                    expandFrom = Alignment.Start
                                ),
                                exit = fadeOut(
                                    animationSpec = tween(90)
                                ) + shrinkHorizontally(
                                    animationSpec = spring(
                                        dampingRatio = 0.76f,
                                        stiffness = 1050f
                                    ),
                                    shrinkTowards = Alignment.Start
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = item.title,
                                        color = selectedTabContentColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.5.sp,
                                        letterSpacing = 0.2.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
