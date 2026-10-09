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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassPill
import com.example.ui.theme.liquidGlassSwitchColors
import com.example.ui.theme.bouncyClickable
import com.example.ui.theme.contrastingContentColor

@Composable
fun ThemesAppUiPage(
    isDarkMode: Boolean,
    themeMode: AppThemeMode,
    onSelectThemeMode: (AppThemeMode) -> Unit,
    customThemeState: com.example.data.local.CustomThemeState? = null,
    onUpdateCustomThemeState: (com.example.data.local.CustomThemeState) -> Unit = {},
    onApplyPreset: (com.example.data.local.AppThemePreset) -> Unit = {},
    isDynamicGlassEnabled: Boolean = true,
    onToggleDynamicGlass: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = remember(customThemeState, appColors.primaryAccent) {
        customThemeState?.accentColorHex?.let { hex ->
            try {
                Color(android.graphics.Color.parseColor(hex.trim()))
            } catch (_: Exception) {
                appColors.primaryAccent
            }
        } ?: appColors.primaryAccent
    }
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
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
                        accentColor = accentColor,
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
                        accentColor = accentColor,
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
                        accentColor = accentColor,
                        onClick = { onSelectThemeMode(AppThemeMode.LIGHT) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Theme Customization Options (Accent Color, Canvas, Card, Gradients, 10 Presets)
        ThemeCustomizationSection(
            isDarkMode = isDarkMode,
            customThemeState = customThemeState,
            onUpdateCustomThemeState = onUpdateCustomThemeState,
            onApplyPreset = onApplyPreset
        )

        Spacer(modifier = Modifier.height(16.dp))

        // App UI & Mini Player Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
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
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
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
                        colors = liquidGlassSwitchColors(appColors),
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
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
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
                        colors = liquidGlassSwitchColors(appColors),
                        modifier = Modifier.testTag("switch_mini_player_next")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Visual Effects & Glow Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
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
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
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
                        colors = liquidGlassSwitchColors(appColors),
                        modifier = Modifier.testTag("switch_ambient_glow")
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
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
                        colors = liquidGlassSwitchColors(appColors),
                        modifier = Modifier.testTag("switch_high_contrast")
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Dynamic Blur & Liquid Glass",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "BitChord-inspired AGSL lens distortion & backdrop blur on Mini Player and bottom bar. Toggle off for low-end hardware.",
                            fontSize = 11.sp,
                            color = appColors.textMuted
                        )
                    }
                    Switch(
                        checked = isDynamicGlassEnabled,
                        onCheckedChange = onToggleDynamicGlass,
                        colors = liquidGlassSwitchColors(appColors),
                        modifier = Modifier.testTag("switch_dynamic_glass")
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
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val appColors = LocalAppColors.current
    val cardShape = remember { RoundedCornerShape(16.dp) }
    val isAmoled = appColors.isAmoled

    // Clear Water 3D Liquid Glass body: strictly 10% visibility (90% blur translucency)
    val glassBackground = if (isSelected) {
        accentColor.copy(alpha = if (isAmoled) 0.18f else 0.14f)
    } else {
        if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.88f)
    }

    // Specular border: Crisp top specular edge highlight
    val borderBrush = if (isSelected) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.85f),
                accentColor.copy(alpha = 0.70f),
                Color.White.copy(alpha = 0.30f)
            ),
            start = Offset(0f, 0f),
            end = Offset(300f, 300f)
        )
    } else {
        if (isDarkMode) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.25f),
                    Color.White.copy(alpha = 0.08f)
                ),
                start = Offset(0f, 0f),
                end = Offset(300f, 300f)
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.80f),
                    accentColor.copy(alpha = 0.18f),
                    accentColor.copy(alpha = 0.28f)
                ),
                start = Offset(0f, 0f),
                end = Offset(300f, 300f)
            )
        }
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = if (isSelected) 5.dp else 2.dp,
                shape = cardShape,
                spotColor = if (isDarkMode) Color.Black.copy(alpha = 0.40f) else accentColor.copy(alpha = if (isSelected) 0.18f else 0.08f),
                ambientColor = if (isDarkMode) Color.Black.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.04f)
            )
            .clip(cardShape)
            .background(glassBackground)
            .border(
                BorderStroke(if (isSelected) 1.5.dp else 1.3.dp, borderBrush),
                cardShape
            )
            .bouncyClickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .testTag("theme_button_${mode.storageKey}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val onAccentColor = remember(accentColor) {
                contrastingContentColor(accentColor)
            }

            val circleBg = if (isSelected) {
                if (isAmoled && accentColor == Color.White) Color.White else accentColor.copy(alpha = 0.22f)
            } else {
                if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
            }
            val circleBorder = if (isSelected) {
                accentColor
            } else {
                if (isDarkMode) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.10f)
            }
            val iconTint = if (isSelected) {
                if (isAmoled && accentColor == Color.White) Color(0xFF0A0A0A) else accentColor
            } else {
                appColors.textSecondary
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(circleBg)
                    .border(BorderStroke(if (isSelected) 1.2.dp else 1.dp, circleBorder), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = title,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                fontSize = 13.5.sp,
                color = if (isSelected) (if (isAmoled && accentColor == Color.White) Color.White else accentColor) else appColors.textPrimary,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtitle,
                fontSize = 10.5.sp,
                color = if (isSelected) (if (isAmoled && accentColor == Color.White) Color.White.copy(alpha = 0.85f) else accentColor.copy(alpha = 0.85f)) else appColors.textMuted,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor)
                        .border(BorderStroke(1.dp, accentColor), RoundedCornerShape(6.dp))
                ) {
                    Text(
                        text = "ACTIVE",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp,
                        color = onAccentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }
}
