package com.example.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.data.local.ThemeExportManager
import com.example.data.local.AppThemePreset
import com.example.data.local.CustomThemeState
import com.example.data.local.ThemePreferences
import com.example.data.local.ThemePresets
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassPill
import com.example.ui.theme.bouncyClickable

/**
 * Color data models for pickers
 */
fun computeAccentHexForShade(baseHex: String, shade: String): String {
    val colorInt = try {
        android.graphics.Color.parseColor(baseHex)
    } catch (e: Exception) {
        android.graphics.Color.parseColor("#38BDF8")
    }
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(colorInt, hsl)

    val isWhiteOrGrey = hsl[1] < 0.08f
    when (shade) {
        "300" -> {
            if (isWhiteOrGrey) {
                hsl[2] = 1.0f
            } else {
                hsl[2] = (hsl[2] + 0.18f).coerceIn(0.20f, 0.84f)
                hsl[1] = (hsl[1] * 0.90f).coerceIn(0.15f, 1.0f)
            }
        }
        "400" -> {
            if (isWhiteOrGrey) {
                hsl[2] = 0.95f
            } else {
                hsl[2] = (hsl[2] + 0.08f).coerceIn(0.20f, 0.74f)
                hsl[1] = (hsl[1] * 0.96f).coerceIn(0.15f, 1.0f)
            }
        }
        "500" -> {
            if (isWhiteOrGrey) {
                hsl[2] = 0.88f
            } else {
                hsl[2] = hsl[2].coerceIn(0.35f, 0.60f)
            }
        }
        "600" -> {
            if (isWhiteOrGrey) {
                hsl[2] = 0.70f
            } else {
                hsl[2] = (hsl[2] - 0.12f).coerceIn(0.18f, 0.52f)
                hsl[1] = (hsl[1] * 1.06f).coerceIn(0.15f, 1.0f)
            }
        }
        "700" -> {
            if (isWhiteOrGrey) {
                hsl[2] = 0.52f
            } else {
                hsl[2] = (hsl[2] - 0.22f).coerceIn(0.12f, 0.42f)
                hsl[1] = (hsl[1] * 1.12f).coerceIn(0.15f, 1.0f)
            }
        }
    }
    val adjusted = androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    return String.format("#%06X", 0xFFFFFF and adjusted)
}

/**
 * Smoothly computes hex for continuous shade weight (100 to 900)
 */
fun computeAccentHexForContinuousShade(baseHex: String, shadeWeight: Int): String {
    val colorInt = try {
        android.graphics.Color.parseColor(baseHex)
    } catch (e: Exception) {
        android.graphics.Color.parseColor("#38BDF8")
    }
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(colorInt, hsl)

    val isWhiteOrGrey = hsl[1] < 0.08f
    val delta = (shadeWeight - 500) / 1000f // -0.4f .. +0.4f
    if (isWhiteOrGrey) {
        hsl[2] = (1.0f - (shadeWeight / 1000f) * 0.6f).coerceIn(0.15f, 1.0f)
    } else {
        // Lower weight = higher lightness (lighter tint), higher weight = deeper tone
        hsl[2] = (hsl[2] - delta * 0.75f).coerceIn(0.12f, 0.90f)
        if (shadeWeight > 500) {
            hsl[1] = (hsl[1] * (1f + delta * 0.5f)).coerceIn(0.2f, 1.0f)
        } else {
            hsl[1] = (hsl[1] * (1f + delta * 0.6f)).coerceIn(0.2f, 1.0f)
        }
    }
    val adjusted = androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    return String.format("#%06X", 0xFFFFFF and adjusted)
}

/**
 * Modern 360° circular color gamut wheel (spectrum picker).
 * Allows continuous 360-degree hue and saturation picking with smooth interactive thumb indicator.
 */
@Composable
fun CircularColorGamutWheel(
    selectedHue: Float,
    selectedSaturation: Float,
    onColorChanged: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnColorChanged by androidx.compose.runtime.rememberUpdatedState(onColorChanged)
    val currentHue by androidx.compose.runtime.rememberUpdatedState(selectedHue)

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val maxRadius = minOf(centerX, centerY) * 0.88f

                    fun updatePosition(pos: androidx.compose.ui.geometry.Offset) {
                        val dx = pos.x - centerX
                        val dy = pos.y - centerY
                        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                        val sat = (distance / maxRadius).coerceIn(0.12f, 1.0f)
                        if (distance > 5f) {
                            var angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            if (angle < 0f) angle += 360f
                            currentOnColorChanged(angle, sat)
                        } else {
                            currentOnColorChanged(currentHue, sat)
                        }
                    }

                    updatePosition(down.position)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        updatePosition(change.position)
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val maxRadius = minOf(centerX, centerY) * 0.88f

            // 1. Draw 360-degree sweep gradient for full spectrum
            val sweepGradient = Brush.sweepGradient(
                colors = listOf(
                    Color(0xFFFF0000), // Red 0 deg
                    Color(0xFFFFA500), // Orange 30 deg
                    Color(0xFFFFFF00), // Yellow 60 deg
                    Color(0xFF00FF00), // Green 120 deg
                    Color(0xFF00FFFF), // Cyan 180 deg
                    Color(0xFF0066FF), // Blue 210 deg
                    Color(0xFF0000FF), // Pure Blue 240 deg
                    Color(0xFFFF00FF), // Magenta 300 deg
                    Color(0xFFFF0066), // Rose 330 deg
                    Color(0xFFFF0000)  // Red 360 deg
                ),
                center = Offset(centerX, centerY)
            )

            drawCircle(
                brush = sweepGradient,
                radius = maxRadius,
                center = Offset(centerX, centerY)
            )

            // 2. Radial gradient for saturation: White at center smoothly fading to transparent at rim
            val radialSaturation = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.96f), Color.White.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(centerX, centerY),
                radius = maxRadius
            )
            drawCircle(
                brush = radialSaturation,
                radius = maxRadius,
                center = Offset(centerX, centerY)
            )

            // 3. Translucent outer border
            drawCircle(
                color = Color.White.copy(alpha = 0.22f),
                radius = maxRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.dp.toPx())
            )

            // 4. Calculate thumb position based on selectedHue and selectedSaturation
            val rad = Math.toRadians(selectedHue.toDouble())
            val thumbRadiusDist = (selectedSaturation * maxRadius).coerceIn(0f, maxRadius)
            val thumbX = centerX + (kotlin.math.cos(rad) * thumbRadiusDist).toFloat()
            val thumbY = centerY + (kotlin.math.sin(rad) * thumbRadiusDist).toFloat()

            val thumbColorInt = android.graphics.Color.HSVToColor(floatArrayOf(selectedHue, selectedSaturation, 1.0f))
            val thumbColor = Color(thumbColorInt)

            // Outer drop shadow
            drawCircle(
                color = Color.Black.copy(alpha = 0.55f),
                radius = 16.dp.toPx(),
                center = Offset(thumbX, thumbY)
            )
            // Outer white ring
            drawCircle(
                color = Color.White,
                radius = 13.dp.toPx(),
                center = Offset(thumbX, thumbY),
                style = Stroke(width = 3.dp.toPx())
            )
            // Inner color circle
            drawCircle(
                color = thumbColor,
                radius = 10.dp.toPx(),
                center = Offset(thumbX, thumbY)
            )
        }
    }
}

data class AccentPreset(
    val name: String,
    val hex400: String,
    val hex500: String,
    val defaultShade: String = "400"
) {
    fun getHexForShade(shade: String): String {
        return computeAccentHexForShade(hex500, shade)
    }
}

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
        ColorOption("Gunmetal Zinc", "#18181B", "Refined contemporary neutral zinc canvas", isDark = true),
        ColorOption("Charcoal Onyx", "#0D1117", "Ultra-deep minimalist charcoal canvas", isDark = true),
        ColorOption("Deep Ruby", "#190A0E", "Rich dark wine velvet canvas", isDark = true),
        ColorOption("Sapphire Abyss", "#031428", "Deep midnight oceanic navy canvas", isDark = true),
        ColorOption("Cyber Carbon", "#121316", "High-tech matte carbon dark canvas", isDark = true)
    )

    val canvasOptionsLight = listOf(
        ColorOption("Clean Daylight", "#F8FAFD", "Crisp modern minimalist daylight canvas", isDark = false),
        ColorOption("Pure Snow White", "#FFFFFF", "Ultra bright spotless white canvas", isDark = false),
        ColorOption("Soft Cool Slate", "#F1F5F9", "Gentle cool slate tinted canvas", isDark = false),
        ColorOption("Warm Alabaster", "#FAF8F5", "Cozy warm cream paper canvas", isDark = false),
        ColorOption("Pastel Mint", "#F0FDF4", "Refreshing pale sage light canvas", isDark = false),
        ColorOption("Lavender Mist", "#F5F3FF", "Delicate ethereal lilac light canvas", isDark = false),
        ColorOption("Morning Sky", "#E0F2FE", "Serene light airy azure canvas", isDark = false),
        ColorOption("Rose Blush", "#FFF1F2", "Gentle soft pastel rose canvas", isDark = false),
        ColorOption("Solar Amber", "#FFFBEB", "Sunlit warm golden glow canvas", isDark = false),
        ColorOption("Silk Cashmere", "#F8F6F0", "Organic premium neutral warm canvas", isDark = false)
    )

    val canvasOptions = canvasOptionsDark + canvasOptionsLight

    val cardOptionsDark = listOf(
        ColorOption("Pure Pitch Black", "#000000", "True AMOLED pure black card", isDark = true),
        ColorOption("Midnight Slate", "#1E293B", "Classic studio navy card", isDark = true),
        ColorOption("Deep Violet Glass", "#22133B", "Rich purple dusk elevated card", isDark = true),
        ColorOption("Emerald Shadow", "#0E2820", "Deep forest teal card", isDark = true),
        ColorOption("Dark Espresso", "#251711", "Warm dark amber roasted card", isDark = true),
        ColorOption("Steel Zinc", "#27272A", "Contemporary neutral zinc card", isDark = true),
        ColorOption("Cyber Slate", "#161B22", "Modern tech translucent surface card", isDark = true),
        ColorOption("Crimson Obsidian", "#2A1015", "Rich garnet illuminated card", isDark = true),
        ColorOption("Deep Cobalt", "#0C203F", "Vibrant high-contrast sapphire card", isDark = true),
        ColorOption("Carbon Matte", "#1E2024", "Refined stealth dark surface card", isDark = true)
    )

    val cardOptionsLight = listOf(
        ColorOption("Pure Crisp White", "#FFFFFF", "Clean floating card with sharp clarity", isDark = false),
        ColorOption("Cool Ice Slate", "#E2E8F0", "Defined cool slate light card", isDark = false),
        ColorOption("Warm Linen", "#F5EFE6", "Soft organic warm cream card", isDark = false),
        ColorOption("Morning Sky", "#E0F2FE", "Fresh subtle sky blue tinted card", isDark = false),
        ColorOption("Spring Mint", "#DCFCE7", "Refreshing delicate pale mint card", isDark = false),
        ColorOption("Silken Lilac", "#EDE9FE", "Soft elegant lilac card", isDark = false),
        ColorOption("Peach Silk", "#FFEDD5", "Delicate warm apricot light card", isDark = false),
        ColorOption("Rose Petal", "#FFE4E6", "Romantic soft blush card", isDark = false),
        ColorOption("Frosted Crystal", "#F8FAFC", "Translucent crisp porcelain card", isDark = false),
        ColorOption("Ivory Cloud", "#FDFBF7", "Luxurious soft warm ivory card", isDark = false)
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
    var showThemePresetsDialog by remember { mutableStateOf(false) }
    var showExportSuccessDialog by remember { mutableStateOf(false) }
    var exportedThemeFilePath by remember { mutableStateOf<String?>(null) }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val result = ThemeExportManager.exportCustomTheme(context, customState)
        result.onSuccess { path ->
            exportedThemeFilePath = path
            showExportSuccessDialog = true
            Toast.makeText(context, "Theme exported to $path", Toast.LENGTH_LONG).show()
        }.onFailure { e ->
            Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun updateAndPersist(newState: CustomThemeState) {
        customState = newState
        ThemePreferences.saveCustomThemeState(context, newState)
        onUpdateCustomThemeState(newState)
    }

    Column(modifier = modifier.fillMaxWidth()) {

        // =====================================================================
        // CARD 1: Choose Theme Preset (5 Dark & 5 Light Curated Aesthetic Themes)
        // =====================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
                .bouncyClickable {
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
                            text = "${ThemePresets.allPresets.size} aesthetic presets (${ThemePresets.darkPresets.size} Dark, ${ThemePresets.lightPresets.size} Light)",
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

        Spacer(modifier = Modifier.height(16.dp))

        // =====================================================================
        // CARD 2: Accent Color & Hue, Canvas Color, Card Color
        // =====================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
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

        Spacer(modifier = Modifier.height(16.dp))

        // =====================================================================
        // CARD 4: Current Theme & Save Theme
        // =====================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
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
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                            ) {
                                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                val result = ThemeExportManager.exportCustomTheme(context, customState)
                                result.onSuccess { path ->
                                    exportedThemeFilePath = path
                                    showExportSuccessDialog = true
                                    Toast.makeText(context, "Theme exported to $path", Toast.LENGTH_LONG).show()
                                }.onFailure { e ->
                                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                        .testTag("item_save_theme")
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Save Theme",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Export customtheme.json to backups & storage",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Save Theme",
                        tint = appColors.primaryAccent,
                        modifier = Modifier.size(22.dp)
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
            currentHex = customState.accentColorHex,
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
                    isAmoled = option.name.contains("Black", ignoreCase = true) && customState.cardColorName.contains("Black", ignoreCase = true),
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

    // 4. Export Theme Success Dialog
    if (showExportSuccessDialog && exportedThemeFilePath != null) {
        ThemeExportSuccessDialog(
            filePath = exportedThemeFilePath!!,
            state = customState,
            onDismiss = { showExportSuccessDialog = false },
            onShare = {
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Xtreme Player Theme - ${customState.currentThemeName}")
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Xtreme Player Custom Theme: ${customState.currentThemeName}\n" +
                            "Accent: ${customState.accentName} (${customState.accentColorHex})\n" +
                            "Shade Weight: ${customState.accentShade}\n" +
                            "Canvas: ${customState.canvasColorName} (${customState.canvasColorHex})\n" +
                            "Card: ${customState.cardColorName} (${customState.cardColorHex})\n" +
                            "Saved location: $exportedThemeFilePath"
                        )
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Custom Theme Configuration"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not open share dialog", Toast.LENGTH_SHORT).show()
                }
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
 * Modern glassmorphic dialog showing theme export confirmation and share options
 */
@Composable
private fun ThemeExportSuccessDialog(
    filePath: String,
    state: CustomThemeState,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cardBg,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Success Badge
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f))
                        .border(1.dp, accentColor.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Success",
                        tint = accentColor,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Theme Exported",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = appColors.textPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Configuration written to customtheme.json in backup directory and downloads.",
                    fontSize = 12.sp,
                    color = appColors.textMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Details Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cardBorder.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Theme: ${state.currentThemeName}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Accent: ${state.accentName} (${state.accentColorHex}, Shade ${state.accentShade})",
                            fontSize = 11.sp,
                            color = appColors.textMuted
                        )
                        Text(
                            text = "• Canvas: ${state.canvasColorName} (${state.canvasColorHex})",
                            fontSize = 11.sp,
                            color = appColors.textMuted
                        )
                        Text(
                            text = "• Card: ${state.cardColorName} (${state.cardColorHex})",
                            fontSize = 11.sp,
                            color = appColors.textMuted
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Path: $filePath",
                            fontSize = 10.sp,
                            color = accentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onShare,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cardBorder.copy(alpha = 0.6f),
                            contentColor = appColors.textPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Share", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = appColors.onPrimaryAccent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Text(text = "Done", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Modern Accent Color & Hue modal overhaul with 360° circular color gamut wheel,
 * live Hex/RGB preview, smooth range slider for shade/hue weight, and real-time glowing action button.
 */
@Composable
private fun AccentColorHueDialog(
    currentAccentName: String,
    currentShade: String,
    currentHex: String = "#38BDF8",
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onSelect: (name: String, shade: String, hex: String) -> Unit
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder

    val initialHsv = remember(currentHex) {
        val hsv = FloatArray(3)
        try {
            android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(currentHex), hsv)
        } catch (e: Exception) {
            hsv[0] = 199f
            hsv[1] = 0.77f
            hsv[2] = 0.97f
        }
        hsv
    }

    var selectedHue by remember { mutableStateOf(initialHsv[0]) }
    var selectedSaturation by remember { mutableStateOf(initialHsv[1].coerceIn(0.15f, 1f)) }
    var shadeSliderValue by remember {
        mutableStateOf(currentShade.toFloatOrNull() ?: 500f)
    }

    val currentShadeInt = shadeSliderValue.roundToInt()
    val baseColorInt = remember(selectedHue, selectedSaturation) {
        android.graphics.Color.HSVToColor(floatArrayOf(selectedHue, selectedSaturation, 1.0f))
    }
    val baseHex = remember(baseColorInt) {
        String.format("#%06X", 0xFFFFFF and baseColorInt)
    }
    val activeHex = remember(baseHex, currentShadeInt) {
        computeAccentHexForContinuousShade(baseHex, currentShadeInt)
    }
    val activeColorInt = remember(activeHex) {
        try {
            android.graphics.Color.parseColor(activeHex)
        } catch (e: Exception) {
            baseColorInt
        }
    }
    val activeColor = remember(activeColorInt) { Color(activeColorInt) }
    val activeRed = android.graphics.Color.red(activeColorInt)
    val activeGreen = android.graphics.Color.green(activeColorInt)
    val activeBlue = android.graphics.Color.blue(activeColorInt)
    val isBrightActive = remember(activeColorInt) {
        androidx.core.graphics.ColorUtils.calculateLuminance(activeColorInt) > 0.55
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cardBg,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Accent Color & Hue",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "360° circular gamut & continuous shade tuner",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = appColors.textMuted)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Live Color Preview Box
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cardBorder.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp), spotColor = activeColor)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(activeColor)
                                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = activeHex,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = appColors.textPrimary
                                )
                                Text(
                                    text = "RGB: $activeRed, $activeGreen, $activeBlue",
                                    fontSize = 11.sp,
                                    color = appColors.textMuted
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 360° Circular Color Gamut Wheel (Spectrum Picker)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularColorGamutWheel(
                        selectedHue = selectedHue,
                        selectedSaturation = selectedSaturation,
                        onColorChanged = { hue, sat ->
                            selectedHue = hue
                            selectedSaturation = sat
                        },
                        modifier = Modifier.size(190.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Hue & Shade Range Slider Bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Hue & Shade Weight",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = appColors.textSecondary
                    )
                    Text(
                        text = "Shade: $currentShadeInt • ${(currentShadeInt / 10)}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = activeColor
                    )
                }

                Slider(
                    value = shadeSliderValue,
                    onValueChange = { shadeSliderValue = it },
                    valueRange = 100f..900f,
                    colors = SliderDefaults.colors(
                        thumbColor = activeColor,
                        activeTrackColor = activeColor,
                        inactiveTrackColor = if (isDarkMode) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Quick Preset Swatches Row
                Text(
                    text = "Quick Presets",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = appColors.textMuted
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ThemeCustomizationPresets.accentPresets.take(10).forEach { preset ->
                        val presetColorInt = try {
                            android.graphics.Color.parseColor(preset.hex400)
                        } catch (e: Exception) {
                            android.graphics.Color.WHITE
                        }
                        val swatchColor = Color(presetColorInt)
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(swatchColor)
                                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                                .clickable {
                                    val hsv = FloatArray(3)
                                    android.graphics.Color.colorToHSV(presetColorInt, hsv)
                                    selectedHue = hsv[0]
                                    selectedSaturation = hsv[1].coerceIn(0.2f, 1.0f)
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Button: dynamically reflects accent color and glow
                Button(
                    onClick = {
                        onSelect("Custom Accent", currentShadeInt.toString(), activeHex)
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = activeColor,
                        contentColor = if (isBrightActive) Color.Black else Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .shadow(elevation = 12.dp, shape = RoundedCornerShape(14.dp), spotColor = activeColor, ambientColor = activeColor)
                        .testTag("apply_accent_button")
                ) {
                    Text(
                        text = "Apply Accent ($activeHex)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * Modern Color Selection Dialog with rigid SegmentedControl and sleek 2-column grid layout
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
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
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

                Spacer(modifier = Modifier.height(14.dp))

                // Rigid Segmented Control (Fixed height 40dp, non-wrapping text, no overflow)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (appColors.isDark) Color(0xFF111520) else Color(0xFFE2E8F0))
                        .border(1.dp, if (appColors.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val tabs = listOf("All", "Dark", "Light")
                        tabs.forEachIndexed { index, label ->
                            val isTabSelected = selectedFilterTab == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (isTabSelected) accentColor else Color.Transparent
                                    )
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedFilterTab = index
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isTabSelected) appColors.onPrimaryAccent else appColors.textPrimary,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Compact 2-Column Grid Layout (Displays double the options in the same vertical space)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 390.dp)
                ) {
                    items(displayedOptions, key = { it.name }) { option ->
                        val isSelected = option.name == currentSelection
                        val swatchColor = try {
                            Color(android.graphics.Color.parseColor(option.hex))
                        } catch (e: Exception) {
                            Color.Black
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) accentColor.copy(alpha = 0.16f) else cardBg.copy(alpha = 0.70f),
                            border = BorderStroke(
                                width = if (isSelected) 1.8.dp else 1.dp,
                                color = if (isSelected) accentColor else cardBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onSelect(option) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 9.dp)
                            ) {
                                // Swatch circle with checkmark if selected
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                        .border(
                                            width = 1.dp,
                                            color = if (appColors.isDark) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.18f),
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        val isBright = try {
                                            androidx.core.graphics.ColorUtils.calculateLuminance(android.graphics.Color.parseColor(option.hex)) > 0.55
                                        } catch (e: Exception) {
                                            false
                                        }
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = if (isBright) Color.Black else Color.White,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isSelected) accentColor else appColors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = option.hex,
                                        fontSize = 10.sp,
                                        color = appColors.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(accentColor)
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

                // Rigid Segmented Control (Fixed height 40dp, non-wrapping text, no overflow)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (appColors.isDark) Color(0xFF111520) else Color(0xFFE2E8F0))
                        .border(1.dp, if (appColors.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val tabs = listOf("All", "Dark", "Light")
                        tabs.forEachIndexed { index, label ->
                            val isTabSelected = selectedFilterTab == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (isTabSelected) accentColor else Color.Transparent
                                    )
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedFilterTab = index
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isTabSelected) appColors.onPrimaryAccent else appColors.textPrimary,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
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
                                text = "${ThemePresets.allPresets.size} Curated Aesthetic Studio & Liquid Glass Themes",
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

                // Segmented Filter Tabs: All | Dark | Light
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val tabs = listOf(
                        "All (${ThemePresets.allPresets.size})",
                        "🌙 Dark (${ThemePresets.darkPresets.size})",
                        "☀️ Light (${ThemePresets.lightPresets.size})"
                    )
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
