package com.example.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.AppThemePreset
import com.example.data.local.CustomThemeState
import com.example.data.local.ThemePreferences
import com.example.data.local.ThemePresets
import com.example.ui.theme.LocalAppColors

/**
 * Color data models for pickers
 */
data class AccentPreset(
    val name: String,
    val hex400: String,
    val hex500: String,
    val defaultShade: String = "400"
)

data class ColorOption(
    val name: String,
    val hex: String,
    val description: String,
    val isDark: Boolean = true
)

data class GradientOption(
    val name: String,
    val description: String,
    val colors: List<Color>,
    val isDark: Boolean = true
)

object ThemeCustomizationPresets {
    val accentPresets = listOf(
        AccentPreset("White", "#FFFFFF", "#F8FAFC", "400"),
        AccentPreset("Cyan", "#38BDF8", "#0EA5E9", "400"),
        AccentPreset("Electric Teal", "#00E5FF", "#06B6D4", "400"),
        AccentPreset("Emerald", "#34D399", "#10B981", "500"),
        AccentPreset("Neon Purple", "#C084FC", "#A855F7", "500"),
        AccentPreset("Sunset Orange", "#FB923C", "#F97316", "500"),
        AccentPreset("Rose Pink", "#FB7185", "#F43F5E", "500"),
        AccentPreset("Golden Amber", "#FBBF24", "#F59E0B", "400"),
        AccentPreset("Crimson Red", "#F87171", "#EF4444", "500"),
        AccentPreset("Indigo", "#818CF8", "#6366F1", "500")
    )

    val canvasOptionsDark = listOf(
        ColorOption("Pitch Black", "#000000", "True AMOLED zero-power pure black", isDark = true),
        ColorOption("Studio Midnight", "#071424", "Deep oceanic studio navy canvas", isDark = true),
        ColorOption("Astral Violet", "#120924", "Deep cosmic dusk purple canvas", isDark = true),
        ColorOption("Forest Obsidian", "#061A14", "Deep emerald night canvas", isDark = true),
        ColorOption("Warm Espresso", "#1A0F0A", "Rich roasted coffee dark canvas", isDark = true),
        ColorOption("Gunmetal Zinc", "#18181B", "Refined contemporary neutral zinc canvas", isDark = true)
    )

    val canvasOptionsLight = listOf(
        ColorOption("Clean Daylight", "#F8FAFD", "Crisp modern minimalist daylight canvas", isDark = false),
        ColorOption("Pure Snow White", "#FFFFFF", "Ultra bright spotless white canvas", isDark = false),
        ColorOption("Soft Cool Slate", "#F1F5F9", "Gentle cool slate tinted canvas", isDark = false),
        ColorOption("Warm Alabaster", "#FAF8F5", "Cozy warm cream paper canvas", isDark = false),
        ColorOption("Pastel Mint", "#F0FDF4", "Refreshing pale sage light canvas", isDark = false),
        ColorOption("Lavender Mist", "#F5F3FF", "Delicate ethereal lilac light canvas", isDark = false)
    )

    val canvasOptions = canvasOptionsDark + canvasOptionsLight

    val cardOptionsDark = listOf(
        ColorOption("Pure Pitch Black", "#000000", "True AMOLED pure black card", isDark = true),
        ColorOption("Midnight Slate", "#1E293B", "Classic studio navy card", isDark = true),
        ColorOption("Deep Violet Glass", "#22133B", "Rich purple dusk elevated card", isDark = true),
        ColorOption("Emerald Shadow", "#0E2820", "Deep forest teal card", isDark = true),
        ColorOption("Dark Espresso", "#251711", "Warm dark amber roasted card", isDark = true),
        ColorOption("Steel Zinc", "#27272A", "Contemporary neutral zinc card", isDark = true)
    )

    val cardOptionsLight = listOf(
        ColorOption("Pure Crisp White", "#FFFFFF", "Clean floating card with sharp clarity", isDark = false),
        ColorOption("Cool Ice Slate", "#E2E8F0", "Defined cool slate light card", isDark = false),
        ColorOption("Warm Linen", "#F5EFE6", "Soft organic warm cream card", isDark = false),
        ColorOption("Morning Sky", "#E0F2FE", "Fresh subtle sky blue tinted card", isDark = false),
        ColorOption("Spring Mint", "#DCFCE7", "Refreshing delicate pale mint card", isDark = false),
        ColorOption("Silken Lilac", "#EDE9FE", "Soft elegant lilac card", isDark = false)
    )

    val cardOptions = cardOptionsDark + cardOptionsLight

    val backgroundGradients = listOf(
        GradientOption(
            "Pure Black Solid",
            "Pitch black AMOLED without gradients",
            listOf(Color(0xFF000000), Color(0xFF000000)),
            isDark = true
        ),
        GradientOption(
            "Studio Night",
            "Midnight blue into pitch black",
            listOf(Color(0xFF081426), Color(0xFF040A14), Color(0xFF000206)),
            isDark = true
        ),
        GradientOption(
            "Cosmic Purple",
            "Deep astral violet into obsidian",
            listOf(Color(0xFF1E1035), Color(0xFF0D061A), Color(0xFF000000)),
            isDark = true
        ),
        GradientOption(
            "Deep Cyan Glow",
            "Cyan aura into dark slate",
            listOf(Color(0xFF04202C), Color(0xFF02121A), Color(0xFF000000)),
            isDark = true
        ),
        GradientOption(
            "Sunset Ember",
            "Deep amber and crimson depth",
            listOf(Color(0xFF261008), Color(0xFF140703), Color(0xFF000000)),
            isDark = true
        ),
        GradientOption(
            "Emerald Nebula",
            "Deep oceanic teal into dark slate",
            listOf(Color(0xFF062117), Color(0xFF02120C), Color(0xFF000000)),
            isDark = true
        ),
        GradientOption(
            "Frost Sky Dawn",
            "Crisp light blue daylight gradient",
            listOf(Color(0xFFFFFFFF), Color(0xFFF0F6FE), Color(0xFFE0F2FE)),
            isDark = false
        ),
        GradientOption(
            "Golden Sunrise",
            "Warm solar amber light gradient",
            listOf(Color(0xFFFFFBEB), Color(0xFFFEF3C7), Color(0xFFFDE68A)),
            isDark = false
        ),
        GradientOption(
            "Spring Meadow",
            "Refreshing mint green light gradient",
            listOf(Color(0xFFF0FDF4), Color(0xFFDCFCE7), Color(0xFFBBF7D0)),
            isDark = false
        ),
        GradientOption(
            "Lavender Breeze",
            "Soft ethereal violet light gradient",
            listOf(Color(0xFFFAF5FF), Color(0xFFF3E8FF), Color(0xFFE9D5FF)),
            isDark = false
        )
    )

    val cardGradients = listOf(
        GradientOption("Solid Flat", "Uniform flat surface", listOf(Color(0xFF111827), Color(0xFF111827)), isDark = true),
        GradientOption("Subtle Sheen", "Soft vertical light transition", listOf(Color(0xFF1F2937), Color(0xFF111827)), isDark = true),
        GradientOption("Neon Edge", "Subtle top border aura", listOf(Color(0xFF1E293B), Color(0xFF0F172A)), isDark = true),
        GradientOption("Obsidian Glass", "Translucent dark glass effect", listOf(Color(0xFF1C1C28), Color(0xFF0F0F16)), isDark = true),
        GradientOption("Pure White Silk", "Clean white daylight card", listOf(Color(0xFFFFFFFF), Color(0xFFF8FAFC)), isDark = false),
        GradientOption("Frosted Pearl", "Gentle light cool tint", listOf(Color(0xFFFFFFFF), Color(0xFFF1F5F9)), isDark = false)
    )

    val bottomSheetGradients = listOf(
        GradientOption("Pitch Black", "Pure AMOLED depth", listOf(Color(0xFF0A0A0A), Color(0xFF000000)), isDark = true),
        GradientOption("Studio Midnight", "Classic dark navy depth", listOf(Color(0xFF0E1F36), Color(0xFF040A14)), isDark = true),
        GradientOption("Cosmic Depth", "Deep violet dusk", listOf(Color(0xFF180E2B), Color(0xFF080312)), isDark = true),
        GradientOption("Deep Obsidian", "Neutral minimal charcoal", listOf(Color(0xFF121218), Color(0xFF07070B)), isDark = true),
        GradientOption("Daylight Pearl", "Crisp light porcelain sheet", listOf(Color(0xFFFFFFFF), Color(0xFFF8FAFC)), isDark = false),
        GradientOption("Soft Frosted Sky", "Gentle sky-tinted light sheet", listOf(Color(0xFFF8FAFC), Color(0xFFEFF6FF)), isDark = false)
    )
}

/**
 * Custom Theme Settings Section matching user's screenshot
 */
@Composable
fun ThemeCustomizationSection(
    isDarkMode: Boolean,
    customThemeState: CustomThemeState? = null,
    onUpdateCustomThemeState: (CustomThemeState) -> Unit = {},
    onApplyPreset: (AppThemePreset) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var customState by remember(customThemeState) {
        mutableStateOf(customThemeState ?: ThemePreferences.getCustomThemeState(context))
    }

    val appColors = com.example.ui.theme.LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val dividerColor = appColors.dividerColor
    val accentColor = appColors.primaryAccent

    // Dialog state
    var showAccentDialog by remember { mutableStateOf(false) }
    var showCanvasDialog by remember { mutableStateOf(false) }
    var showCardDialog by remember { mutableStateOf(false) }
    var showBgGradientDialog by remember { mutableStateOf(false) }
    var showCardGradientDialog by remember { mutableStateOf(false) }
    var showBottomSheetGradientDialog by remember { mutableStateOf(false) }
    var showThemePresetsDialog by remember { mutableStateOf(false) }

    fun updateAndPersist(newState: CustomThemeState) {
        customState = newState
        ThemePreferences.saveCustomThemeState(context, newState)
        onUpdateCustomThemeState(newState)
    }

    Column(modifier = modifier.fillMaxWidth()) {

        // =====================================================================
        // CARD 1: Choose Theme Preset (5 Dark & 5 Light Curated Aesthetic Themes)
        // =====================================================================
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showThemePresetsDialog = true
                }
                .testTag("choose_theme_preset_card")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.15f))
                            .border(1.dp, accentColor.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Theme Presets",
                            tint = accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "Choose Theme Preset",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "10 aesthetic presets (5 Dark, 5 Light)",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = accentColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = customState.currentThemeName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // =====================================================================
        // CARD 2: Accent Color & Hue, Canvas Color, Card Color
        // =====================================================================
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Item 1: Accent Color & Hue
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                        .clickable { showAccentDialog = true }
                        .padding(horizontal = 18.dp, vertical = 15.dp)
                        .testTag("item_accent_color_hue")
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Accent Color & Hue",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${customState.accentName}, ${customState.accentShade}",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }

                    // Circular Preview
                    val accentParsed = remember(customState.accentColorHex) {
                        try {
                            Color(android.graphics.Color.parseColor(customState.accentColorHex))
                        } catch (e: Exception) {
                            Color.White
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(accentParsed)
                            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(horizontal = 18.dp))

                // Item 2: Canvas Color
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCanvasDialog = true }
                        .padding(horizontal = 18.dp, vertical = 15.dp)
                        .testTag("item_canvas_color")
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Canvas Color",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Color of Background Canvas",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }

                    Text(
                        text = customState.canvasColorName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = appColors.textPrimary
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(horizontal = 18.dp))

                // Item 3: Card Color
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
                        .clickable { showCardDialog = true }
                        .padding(horizontal = 18.dp, vertical = 15.dp)
                        .testTag("item_card_color")
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Card Color",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Color of Search Bar, Alert Dialogs, Cards",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }

                    Text(
                        text = customState.cardColorName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = appColors.textPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // =====================================================================
        // CARD 3: Background Gradient, Card Gradient, Bottom Sheets Gradient
        // =====================================================================
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Item 1: Background Gradient
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                        .clickable { showBgGradientDialog = true }
                        .padding(horizontal = 18.dp, vertical = 15.dp)
                        .testTag("item_bg_gradient")
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Background Gradient",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Gradient used as background everywhere",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }

                    // Circle Gradient Preview
                    val bgGradColors = remember(customState.bgGradientName) {
                        ThemeCustomizationPresets.backgroundGradients.find { it.name == customState.bgGradientName }?.colors
                            ?: listOf(Color(0xFF000000), Color(0xFF000000))
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(bgGradColors))
                            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(horizontal = 18.dp))

                // Item 2: Card Gradient
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCardGradientDialog = true }
                        .padding(horizontal = 18.dp, vertical = 15.dp)
                        .testTag("item_card_gradient")
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Card Gradient",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Gradient used in Cards",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }

                    // Circle Gradient Preview
                    val cardGradColors = remember(customState.cardGradientName) {
                        ThemeCustomizationPresets.cardGradients.find { it.name == customState.cardGradientName }?.colors
                            ?: listOf(Color(0xFF111827), Color(0xFF111827))
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(cardGradColors))
                            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(horizontal = 18.dp))

                // Item 3: Bottom Sheets Gradient
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
                        .clickable { showBottomSheetGradientDialog = true }
                        .padding(horizontal = 18.dp, vertical = 15.dp)
                        .testTag("item_bottom_sheets_gradient")
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Bottom Sheets Gradient",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Gradient used in Bottom Sheets",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }

                    // Circle Gradient Preview
                    val bsGradColors = remember(customState.bottomSheetGradientName) {
                        ThemeCustomizationPresets.bottomSheetGradients.find { it.name == customState.bottomSheetGradientName }?.colors
                            ?: listOf(Color(0xFF0A0A0A), Color(0xFF000000))
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(bsGradColors))
                            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // =====================================================================
        // CARD 4: Current Theme & Save Theme
        // =====================================================================
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Item 1: Current Theme
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                        .clickable { showThemePresetsDialog = true }
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                        .testTag("item_current_theme")
                ) {
                    Text(
                        text = "Current Theme",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = appColors.textPrimary
                    )

                    Text(
                        text = customState.currentThemeName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = appColors.textPrimary
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(horizontal = 18.dp))

                // Item 2: Save Theme
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            ThemePreferences.saveCustomThemeState(context, customState)
                            Toast.makeText(context, "Theme saved successfully! Preset updated.", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                        .testTag("item_save_theme")
                ) {
                    Text(
                        text = "Save Theme",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = appColors.textPrimary
                    )

                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Save Theme",
                        tint = appColors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    // =========================================================================
    // DIALOGS FOR CUSTOMIZATION
    // =========================================================================

    // 1. Accent Color & Hue Dialog
    if (showAccentDialog) {
        AccentColorHueDialog(
            currentAccentName = customState.accentName,
            currentShade = customState.accentShade,
            isDarkMode = isDarkMode,
            onDismiss = { showAccentDialog = false },
            onSelect = { name, shade, hex ->
                val updated = customState.copy(
                    isPresetDark = isDarkMode,
                    isAmoled = false,
                    accentName = name,
                    accentShade = shade,
                    accentColorHex = hex,
                    currentThemeName = "Custom"
                )
                updateAndPersist(updated)
                showAccentDialog = false
            }
        )
    }

    // 2. Canvas Color Dialog
    if (showCanvasDialog) {
        ColorSelectionDialog(
            title = "Select Canvas Color",
            options = ThemeCustomizationPresets.canvasOptions,
            currentSelection = customState.canvasColorName,
            isDarkMode = isDarkMode,
            onDismiss = { showCanvasDialog = false },
            onSelect = { option ->
                val updated = customState.copy(
                    isAmoled = option.name == "Black" && customState.cardColorName == "Grey900",
                    canvasColorName = option.name,
                    canvasColorHex = option.hex,
                    currentThemeName = "Custom"
                )
                updateAndPersist(updated)
                showCanvasDialog = false
            }
        )
    }

    // 3. Card Color Dialog
    if (showCardDialog) {
        ColorSelectionDialog(
            title = "Select Card Color",
            options = ThemeCustomizationPresets.cardOptions,
            currentSelection = customState.cardColorName,
            isDarkMode = isDarkMode,
            onDismiss = { showCardDialog = false },
            onSelect = { option ->
                val updated = customState.copy(
                    cardColorName = option.name,
                    cardColorHex = option.hex,
                    currentThemeName = "Custom"
                )
                updateAndPersist(updated)
                showCardDialog = false
            }
        )
    }

    // 4. Background Gradient Dialog
    if (showBgGradientDialog) {
        GradientSelectionDialog(
            title = "Background Gradient",
            options = ThemeCustomizationPresets.backgroundGradients,
            currentSelection = customState.bgGradientName,
            isDarkMode = isDarkMode,
            onDismiss = { showBgGradientDialog = false },
            onSelect = { option ->
                val updated = customState.copy(
                    bgGradientName = option.name,
                    currentThemeName = "Custom"
                )
                updateAndPersist(updated)
                showBgGradientDialog = false
            }
        )
    }

    // 5. Card Gradient Dialog
    if (showCardGradientDialog) {
        GradientSelectionDialog(
            title = "Card Gradient",
            options = ThemeCustomizationPresets.cardGradients,
            currentSelection = customState.cardGradientName,
            isDarkMode = isDarkMode,
            onDismiss = { showCardGradientDialog = false },
            onSelect = { option ->
                val updated = customState.copy(
                    cardGradientName = option.name,
                    currentThemeName = "Custom"
                )
                updateAndPersist(updated)
                showCardGradientDialog = false
            }
        )
    }

    // 6. Bottom Sheet Gradient Dialog
    if (showBottomSheetGradientDialog) {
        GradientSelectionDialog(
            title = "Bottom Sheets Gradient",
            options = ThemeCustomizationPresets.bottomSheetGradients,
            currentSelection = customState.bottomSheetGradientName,
            isDarkMode = isDarkMode,
            onDismiss = { showBottomSheetGradientDialog = false },
            onSelect = { option ->
                val updated = customState.copy(
                    bottomSheetGradientName = option.name,
                    currentThemeName = "Custom"
                )
                updateAndPersist(updated)
                showBottomSheetGradientDialog = false
            }
        )
    }

    // 7. Theme Presets Dialog (5 Dark, 5 Light aesthetic presets)
    if (showThemePresetsDialog) {
        ThemePresetsDialog(
            currentPresetId = customState.presetId,
            currentTheme = customState.currentThemeName,
            isDarkMode = isDarkMode,
            onDismiss = { showThemePresetsDialog = false },
            onSelectPreset = { preset ->
                updateAndPersist(preset.toCustomThemeState())
                onApplyPreset(preset)
                Toast.makeText(context, "✨ Applied ${preset.name} theme!", Toast.LENGTH_SHORT).show()
                showThemePresetsDialog = false
            }
        )
    }
}

/**
 * Dialog for choosing Accent Color and Hue
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentColorHueDialog(
    currentAccentName: String,
    currentShade: String,
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onSelect: (name: String, shade: String, hex: String) -> Unit
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent

    var selectedPreset by remember {
        mutableStateOf(
            ThemeCustomizationPresets.accentPresets.find { it.name == currentAccentName }
                ?: ThemeCustomizationPresets.accentPresets.first()
        )
    }
    var selectedShade by remember { mutableStateOf(currentShade) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "Accent Color & Hue",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Choose accent tone and shade",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = appColors.textMuted)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Presets Grid
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ThemeCustomizationPresets.accentPresets.forEach { preset ->
                        val isSelected = preset.name == selectedPreset.name
                        val colorHex = if (selectedShade == "400") preset.hex400 else preset.hex500
                        val swatchColor = try {
                            Color(android.graphics.Color.parseColor(colorHex))
                        } catch (e: Exception) {
                            Color.White
                        }

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(swatchColor)
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) {
                                        if (appColors.isDark) Color.White else Color.Black
                                    } else {
                                        if (appColors.isDark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.2f)
                                    },
                                    shape = CircleShape
                                )
                                .clickable {
                                    selectedPreset = preset
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = if (preset.name == "White" || preset.name == "Cyan") Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Shade selector
                Text(
                    text = "Shade / Hue Weight",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = appColors.textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("300", "400", "500", "600", "700").forEach { shade ->
                        val isShadeSelected = selectedShade == shade
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isShadeSelected) accentColor else Color.Transparent,
                            border = BorderStroke(1.dp, if (isShadeSelected) accentColor else cardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedShade = shade }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            ) {
                                Text(
                                    text = shade,
                                    fontSize = 12.sp,
                                    fontWeight = if (isShadeSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isShadeSelected) appColors.onPrimaryAccent else appColors.textPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        val hex = if (selectedShade == "400") selectedPreset.hex400 else selectedPreset.hex500
                        onSelect(selectedPreset.name, selectedShade, hex)
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = appColors.onPrimaryAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text(text = "Apply Accent", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * Dialog for choosing Canvas or Card solid color
 */
@Composable
private fun ColorSelectionDialog(
    title: String,
    options: List<ColorOption>,
    currentSelection: String,
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onSelect: (ColorOption) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent

    var selectedFilterTab by remember { mutableIntStateOf(if (isDarkMode) 1 else 2) } // 0: All, 1: Dark / AMOLED, 2: Light Mode

    val displayedOptions = remember(selectedFilterTab, options) {
        when (selectedFilterTab) {
            1 -> options.filter { it.isDark }
            2 -> options.filter { !it.isDark }
            else -> options
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Choose canvas or card surface tone",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = appColors.textMuted)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Segmented Filter Tabs: All | Dark / AMOLED | Light Mode
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val tabs = listOf("All", "🌙 Dark / AMOLED", "☀️ Light Mode")
                    tabs.forEachIndexed { index, label ->
                        val isTabSelected = selectedFilterTab == index
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isTabSelected) accentColor else accentColor.copy(alpha = 0.08f),
                            border = BorderStroke(
                                1.dp,
                                if (isTabSelected) accentColor else cardBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedFilterTab = index
                                }
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isTabSelected) appColors.onPrimaryAccent else appColors.textPrimary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    items(displayedOptions) { option ->
                        val isSelected = option.name == currentSelection
                        val swatchColor = try {
                            Color(android.graphics.Color.parseColor(option.hex))
                        } catch (e: Exception) {
                            Color.Black
                        }

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) accentColor.copy(alpha = 0.15f) else Color.Transparent
                            ),
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accentColor else cardBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(option) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor)
                                            .border(1.dp, if (appColors.isDark) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.18f), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = option.name,
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = appColors.textPrimary
                                        )
                                        Text(
                                            text = option.description,
                                            fontSize = 11.sp,
                                            color = appColors.textMuted
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = accentColor,
                                        modifier = Modifier.size(18.dp)
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

/**
 * Dialog for choosing Gradient configurations
 */
@Composable
private fun GradientSelectionDialog(
    title: String,
    options: List<GradientOption>,
    currentSelection: String,
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onSelect: (GradientOption) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent

    var selectedFilterTab by remember { mutableIntStateOf(if (isDarkMode) 1 else 2) } // 0: All, 1: Dark / AMOLED, 2: Light Mode

    val displayedOptions = remember(selectedFilterTab, options) {
        when (selectedFilterTab) {
            1 -> options.filter { it.isDark }
            2 -> options.filter { !it.isDark }
            else -> options
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Choose curated atmosphere gradient",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = appColors.textMuted)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Segmented Filter Tabs: All | Dark / AMOLED | Light Mode
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val tabs = listOf("All", "🌙 Dark / AMOLED", "☀️ Light Mode")
                    tabs.forEachIndexed { index, label ->
                        val isTabSelected = selectedFilterTab == index
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isTabSelected) accentColor else accentColor.copy(alpha = 0.08f),
                            border = BorderStroke(
                                1.dp,
                                if (isTabSelected) accentColor else cardBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedFilterTab = index
                                }
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isTabSelected) appColors.onPrimaryAccent else appColors.textPrimary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    items(displayedOptions) { option ->
                        val isSelected = option.name == currentSelection

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) accentColor.copy(alpha = 0.15f) else Color.Transparent
                            ),
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accentColor else cardBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(option) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Brush.linearGradient(option.colors))
                                            .border(1.dp, if (appColors.isDark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.2f), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = option.name,
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = appColors.textPrimary
                                        )
                                        Text(
                                            text = option.description,
                                            fontSize = 11.sp,
                                            color = appColors.textMuted
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = accentColor,
                                        modifier = Modifier.size(18.dp)
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

/**
 * Dialog for choosing Full Theme Presets
 */
@Composable
private fun ThemePresetsDialog(
    currentPresetId: String,
    currentTheme: String,
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onSelectPreset: (AppThemePreset) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val appColors = com.example.ui.theme.LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent

    var selectedFilterTab by remember { mutableIntStateOf(0) } // 0: All (10), 1: Dark (5), 2: Light (5)

    val displayedPresets = remember(selectedFilterTab) {
        when (selectedFilterTab) {
            1 -> ThemePresets.darkPresets
            2 -> ThemePresets.lightPresets
            else -> ThemePresets.allPresets
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = cardBg,
            border = BorderStroke(1.2.dp, cardBorder),
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = "Theme Presets",
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Theme Presets",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = appColors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "10 Curated Aesthetic Studio Themes",
                                fontSize = 11.5.sp,
                                color = appColors.textMuted
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(appColors.cardBackgroundElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = appColors.textSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Segmented Filter Tabs: All (10) | Dark (5) | Light (5)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val tabs = listOf("All (10)", "🌙 Dark (5)", "☀️ Light (5)")
                    tabs.forEachIndexed { index, label ->
                        val isTabSelected = selectedFilterTab == index
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isTabSelected) accentColor else appColors.cardBackgroundElevated,
                            border = BorderStroke(
                                1.dp,
                                if (isTabSelected) accentColor else cardBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedFilterTab = index
                                }
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.5.sp,
                                fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isTabSelected) appColors.onPrimaryAccent else appColors.textPrimary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(displayedPresets, key = { it.id }) { preset ->
                        val isSelected = preset.name == currentTheme || preset.id == currentPresetId

                        val presetAccent = remember(preset.accentColorHex) {
                            try {
                                Color(android.graphics.Color.parseColor(preset.accentColorHex))
                            } catch (_: Exception) {
                                accentColor
                            }
                        }

                        val presetCanvas = remember(preset.canvasColorHex) {
                            try {
                                Color(android.graphics.Color.parseColor(preset.canvasColorHex))
                            } catch (_: Exception) {
                                Color.Black
                            }
                        }

                        val presetCard = remember(preset.cardColorHex) {
                            try {
                                Color(android.graphics.Color.parseColor(preset.cardColorHex))
                            } catch (_: Exception) {
                                Color.DarkGray
                            }
                        }

                        val gradientColors = remember(preset.bgGradientColorsHex) {
                            preset.bgGradientColorsHex.mapNotNull { hex ->
                                try {
                                    Color(android.graphics.Color.parseColor(hex))
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }

                        // Card container background adapts to the currently active theme
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    accentColor.copy(alpha = 0.10f)
                                } else {
                                    appColors.cardBackgroundElevated
                                }
                            ),
                            border = BorderStroke(
                                width = if (isSelected) 1.8.dp else 1.dp,
                                color = if (isSelected) accentColor else cardBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSelectPreset(preset)
                                }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    // Clean Theme Name with NO tint behind/around it
                                    Text(
                                        text = preset.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) accentColor else appColors.textPrimary,
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(accentColor),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = appColors.onPrimaryAccent,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(3.dp))

                                Text(
                                    text = preset.subtitle,
                                    fontSize = 11.5.sp,
                                    color = appColors.textMuted,
                                    lineHeight = 15.sp
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                // Modern Visual Palette Preview Row
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    // Swatches
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Canvas color preview
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(presetCanvas)
                                                .border(1.dp, cardBorder, CircleShape)
                                        )

                                        // Card color preview
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(presetCard)
                                                .border(1.dp, cardBorder, CircleShape)
                                        )

                                        // Accent color preview
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(presetAccent)
                                                .border(1.dp, cardBorder, CircleShape)
                                        )
                                    }

                                    // Gradient preview bar
                                    if (gradientColors.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(10.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(Brush.horizontalGradient(gradientColors))
                                                .border(0.5.dp, cardBorder, RoundedCornerShape(5.dp))
                                        )
                                    }

                                    // Mode label cleanly styled with cardBorder, no dark/light tint overlay
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, cardBorder)
                                    ) {
                                        Text(
                                            text = if (preset.isAmoled) "AMOLED" else if (preset.isDark) "DARK" else "LIGHT",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = appColors.textMuted,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
}
