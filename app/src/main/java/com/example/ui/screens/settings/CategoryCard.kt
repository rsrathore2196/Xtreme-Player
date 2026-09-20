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

@Composable
fun SettingsCategoryCardGroup(
    categories: List<SettingsCategory>,
    isDarkMode: Boolean,
    onCategoryClick: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val dividerColor = appColors.dividerColor

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder),
        modifier = modifier.fillMaxWidth()
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
                        color = dividerColor,
                        thickness = 1.dp,
                        modifier = Modifier.padding(start = 68.dp, end = 16.dp)
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
    val iconBg = if (isDarkMode) appColors.primaryAccent.copy(alpha = 0.15f) else appColors.primaryAccent.copy(alpha = 0.12f)
    val iconBorder = if (isDarkMode) appColors.primaryAccent.copy(alpha = 0.35f) else appColors.primaryAccent.copy(alpha = 0.25f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .clickable {
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
            // Squircle icon container (matching screenshot design)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBg)
                    .border(BorderStroke(1.dp, iconBorder), RoundedCornerShape(12.dp)),
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
