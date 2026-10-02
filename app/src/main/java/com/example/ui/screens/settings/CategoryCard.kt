package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import com.example.util.AppHaptics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.liquidGlassCard

import com.example.ui.theme.bouncyClickable

@Composable
fun SettingsCategoryCardGroup(
    categories: List<SettingsCategory>,
    isDarkMode: Boolean,
    onCategoryClick: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val dividerColor = appColors.dividerColor

    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            categories.forEachIndexed { index, category ->
                SettingsCategoryItemRow(
                    category = category,
                    isDarkMode = isDarkMode,
                    onClick = { onCategoryClick(category) }
                )
                if (index < categories.size - 1) {
                    HorizontalDivider(
                        color = dividerColor.copy(alpha = 0.6f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsCategoryItemRow(
    category: SettingsCategory,
    isDarkMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val accentColor = appColors.primaryAccent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable {
                AppHaptics.performTap(context, haptic)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(category.tag)
    ) {
        // Left: Squircle Icon + Title & Subtitle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Squircle icon container with specular border and theme glass
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(LiquidGlass.glassBrush(appColors, translucency = 0.80f, tintAccent = true))
                    .border(
                        BorderStroke(1.2.dp, LiquidGlass.specularBorderBrush(appColors, highlightAlpha = 0.55f)),
                        RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = category.title,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = category.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp,
                    letterSpacing = (-0.2).sp,
                    color = appColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = category.subtitle,
                    fontSize = 11.sp,
                    color = appColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Trailing Chevron arrow (matching screenshot design)
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = "Open ${category.title}",
            tint = appColors.textMuted,
            modifier = Modifier.size(13.dp)
        )
    }
}
