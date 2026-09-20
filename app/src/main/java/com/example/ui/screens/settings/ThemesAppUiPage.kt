package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.AppThemeMode
import com.example.ui.theme.LocalAppColors

@Composable
fun ThemesAppUiPage(
    isDarkMode: Boolean,
    themeMode: AppThemeMode,
    onSelectThemeMode: (AppThemeMode) -> Unit,
    customThemeState: com.example.data.local.CustomThemeState? = null,
    onUpdateCustomThemeState: (com.example.data.local.CustomThemeState) -> Unit = {},
    onApplyPreset: (com.example.data.local.AppThemePreset) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent
    val dividerColor = appColors.dividerColor

    var showMiniHeart by remember { mutableStateOf(true) }
    var showMiniNext by remember { mutableStateOf(true) }
    var ambientGlowEnabled by remember { mutableStateOf(true) }
    var highContrastTypography by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Theme Selection Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Color Theme Mode",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Select your preferred visual style",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeModeOptionCard(
                        mode = AppThemeMode.SYSTEM,
                        title = "System",
                        subtitle = "Auto adapt",
                        icon = Icons.Default.BrightnessAuto,
                        isSelected = themeMode == AppThemeMode.SYSTEM,
                        isDarkMode = isDarkMode,
                        onClick = { onSelectThemeMode(AppThemeMode.SYSTEM) },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeModeOptionCard(
                        mode = AppThemeMode.DARK,
                        title = "Dark",
                        subtitle = "Studio night",
                        icon = Icons.Default.DarkMode,
                        isSelected = themeMode == AppThemeMode.DARK,
                        isDarkMode = isDarkMode,
                        onClick = { onSelectThemeMode(AppThemeMode.DARK) },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeModeOptionCard(
                        mode = AppThemeMode.LIGHT,
                        title = "Light",
                        subtitle = "Clean daylight",
                        icon = Icons.Default.LightMode,
                        isSelected = themeMode == AppThemeMode.LIGHT,
                        isDarkMode = isDarkMode,
                        onClick = { onSelectThemeMode(AppThemeMode.LIGHT) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Theme Customization Options (Accent Color, Canvas, Card, Gradients, 10 Presets)
        ThemeCustomizationSection(
            isDarkMode = isDarkMode,
            customThemeState = customThemeState,
            onUpdateCustomThemeState = onUpdateCustomThemeState,
            onApplyPreset = onApplyPreset
        )

        Spacer(modifier = Modifier.height(14.dp))

        // App UI & Mini Player Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Mini Player Controls",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Customize persistent playback bar",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Favorite Heart in Mini Player",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = appColors.textPrimary
                            )
                            Text(
                                text = "One-tap like/unlike while browsing",
                                fontSize = 11.sp,
                                color = appColors.textMuted
                            )
                        }
                    }
                    Switch(
                        checked = showMiniHeart,
                        onCheckedChange = { showMiniHeart = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = appColors.onPrimaryAccent,
                            checkedTrackColor = accentColor
                        ),
                        modifier = Modifier.testTag("switch_mini_player_heart")
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Next Track Control",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = appColors.textPrimary
                            )
                            Text(
                                text = "Quick skip button on bottom bar",
                                fontSize = 11.sp,
                                color = appColors.textMuted
                            )
                        }
                    }
                    Switch(
                        checked = showMiniNext,
                        onCheckedChange = { showMiniNext = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = appColors.onPrimaryAccent,
                            checkedTrackColor = accentColor
                        ),
                        modifier = Modifier.testTag("switch_mini_player_next")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Visual Effects & Glow Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ViewCarousel,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Player Screen Visuals",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Aesthetic ambient blur and rendering",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dynamic Ambient Album Glow",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Diffuses album artwork color onto player background",
                            fontSize = 11.sp,
                            color = appColors.textMuted
                        )
                    }
                    Switch(
                        checked = ambientGlowEnabled,
                        onCheckedChange = { ambientGlowEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = appColors.onPrimaryAccent,
                            checkedTrackColor = accentColor
                        ),
                        modifier = Modifier.testTag("switch_ambient_glow")
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "High Contrast Typography",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Enhances text visibility on bright album art",
                            fontSize = 11.sp,
                            color = appColors.textMuted
                        )
                    }
                    Switch(
                        checked = highContrastTypography,
                        onCheckedChange = { highContrastTypography = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = appColors.onPrimaryAccent,
                            checkedTrackColor = accentColor
                        ),
                        modifier = Modifier.testTag("switch_high_contrast")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ThemeModeOptionCard(
    mode: AppThemeMode,
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    isDarkMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val appColors = LocalAppColors.current
    val accentColor = appColors.primaryAccent

    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) {
            accentColor.copy(alpha = if (isDarkMode) 0.14f else 0.10f)
        } else {
            appColors.cardBackgroundElevated
        },
        border = BorderStroke(
            width = if (isSelected) 1.8.dp else 1.dp,
            color = if (isSelected) accentColor else appColors.cardBorder
        ),
        modifier = modifier.testTag("theme_button_${mode.storageKey}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            accentColor.copy(alpha = if (isDarkMode) 0.22f else 0.15f)
                        } else {
                            appColors.cardBackground
                        }
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isSelected) {
                                accentColor.copy(alpha = 0.5f)
                            } else {
                                appColors.cardBorder
                            }
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isSelected) {
                        accentColor
                    } else {
                        appColors.textPrimary
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = title,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 13.5.sp,
                color = if (isSelected) {
                    accentColor
                } else {
                    appColors.textPrimary
                },
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtitle,
                fontSize = 10.5.sp,
                color = appColors.textMuted,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (isSelected) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accentColor.copy(alpha = 0.16f),
                    border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.4f)),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.4.sp,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }
}
