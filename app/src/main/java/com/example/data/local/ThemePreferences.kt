package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable

enum class AppThemeMode(
    val storageKey: String,
    val title: String,
    val subtitle: String
) {
    SYSTEM("system", "System", "Follows device"),
    DARK("dark", "Dark", "Midnight blue"),
    LIGHT("light", "Light", "Daylight clean");

    companion object {
        fun fromKey(key: String?): AppThemeMode {
            return entries.find { it.storageKey.equals(key, ignoreCase = true) } ?: SYSTEM
        }
    }
}

/**
 * Model representing a curated, eye-catching theme preset.
 */
@Immutable
data class AppThemePreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val isDark: Boolean,
    val accentName: String,
    val accentShade: String,
    val accentColorHex: String,
    val secondaryAccentHex: String,
    val canvasColorName: String,
    val canvasColorHex: String,
    val cardColorName: String,
    val cardColorHex: String,
    val cardBorderHex: String,
    val textPrimaryHex: String,
    val textSecondaryHex: String,
    val textMutedHex: String,
    val bgGradientColorsHex: List<String>,
    val cardGradientColorsHex: List<String>,
    val bottomSheetGradientColorsHex: List<String>,
    val bgGradientName: String,
    val cardGradientName: String,
    val bottomSheetGradientName: String,
    val isAmoled: Boolean = false
) {
    fun toCustomThemeState(): CustomThemeState {
        return CustomThemeState(
            presetId = id,
            currentThemeName = name,
            isPresetDark = isDark,
            isAmoled = isAmoled,
            accentName = accentName,
            accentShade = accentShade,
            accentColorHex = accentColorHex,
            secondaryAccentHex = secondaryAccentHex,
            canvasColorName = canvasColorName,
            canvasColorHex = canvasColorHex,
            cardColorName = cardColorName,
            cardColorHex = cardColorHex,
            cardBorderHex = cardBorderHex,
            textPrimaryHex = textPrimaryHex,
            textSecondaryHex = textSecondaryHex,
            textMutedHex = textMutedHex,
            bgGradientColorsHex = bgGradientColorsHex,
            cardGradientColorsHex = cardGradientColorsHex,
            bottomSheetGradientColorsHex = bottomSheetGradientColorsHex,
            bgGradientName = bgGradientName,
            cardGradientName = cardGradientName,
            bottomSheetGradientName = bottomSheetGradientName
        )
    }
}

/**
 * Curated list of 5 Dark and 5 Light modern, aesthetic, eye-catching presets.
 * Each preset has distinct visual identities, colors, contrasts, and atmosphere.
 */
object ThemePresets {

    // ==========================================
    // 5 DISTINCT DARK MODE PRESETS
    // ==========================================

    val StudioNight = AppThemePreset(
        id = "dark_studio_night",
        name = "Studio Night",
        subtitle = "Signature studio obsidian night with luminous electric azure accents",
        isDark = true,
        accentName = "Electric Azure",
        accentShade = "400",
        accentColorHex = "#00D2FF",
        secondaryAccentHex = "#1D4ED8",
        canvasColorName = "Studio Void",
        canvasColorHex = "#020610",
        cardColorName = "Deep Studio Glass",
        cardColorHex = "#050C1B",
        cardBorderHex = "#0F2A4F",
        textPrimaryHex = "#F8FAFC",
        textSecondaryHex = "#7DD3FC",
        textMutedHex = "#64748B",
        bgGradientColorsHex = listOf("#020610", "#010308", "#000103"),
        cardGradientColorsHex = listOf("#0B1E3B", "#040A17"),
        bottomSheetGradientColorsHex = listOf("#07152B", "#020610"),
        bgGradientName = "Studio Void",
        cardGradientName = "Studio Glass",
        bottomSheetGradientName = "Deep Obsidian"
    )

    val AmoledPureBlack = AppThemePreset(
        id = "dark_amoled_black",
        name = "Amoled Pure Black",
        subtitle = "Zero-power pitch black canvas with sharp pure white contrast",
        isDark = true,
        isAmoled = true,
        accentName = "Pure White",
        accentShade = "400",
        accentColorHex = "#FFFFFF",
        secondaryAccentHex = "#E2E8F0",
        canvasColorName = "Pitch Black",
        canvasColorHex = "#000000",
        cardColorName = "Pitch Black",
        cardColorHex = "#000000",
        cardBorderHex = "#222222",
        textPrimaryHex = "#FFFFFF",
        textSecondaryHex = "#E4E4E7",
        textMutedHex = "#71717A",
        bgGradientColorsHex = listOf("#000000", "#000000"),
        cardGradientColorsHex = listOf("#000000", "#000000"),
        bottomSheetGradientColorsHex = listOf("#000000", "#000000"),
        bgGradientName = "Pure Black Solid",
        cardGradientName = "Solid Flat",
        bottomSheetGradientName = "Pitch Black"
    )

    val CyberpunkNeon = AppThemePreset(
        id = "dark_cyberpunk_neon",
        name = "Cyberpunk Neon",
        subtitle = "Deep synthwave cosmic violet with luminous magenta glow",
        isDark = true,
        accentName = "Neon Purple",
        accentShade = "500",
        accentColorHex = "#C084FC",
        secondaryAccentHex = "#F43F5E",
        canvasColorName = "Abyssal Violet",
        canvasColorHex = "#090514",
        cardColorName = "Dark Violet",
        cardColorHex = "#150C24",
        cardBorderHex = "#3B1B5B",
        textPrimaryHex = "#FAF5FF",
        textSecondaryHex = "#E9D5FF",
        textMutedHex = "#A892BF",
        bgGradientColorsHex = listOf("#200B36", "#0E0519", "#040108"),
        cardGradientColorsHex = listOf("#23123C", "#140A23"),
        bottomSheetGradientColorsHex = listOf("#1D0C30", "#090412"),
        bgGradientName = "Cosmic Purple",
        cardGradientName = "Neon Edge",
        bottomSheetGradientName = "Cosmic Depth"
    )

    val EmeraldNebula = AppThemePreset(
        id = "dark_emerald_nebula",
        name = "Emerald Nebula",
        subtitle = "Deep obsidian jade canvas with energetic mint highlights",
        isDark = true,
        accentName = "Emerald",
        accentShade = "500",
        accentColorHex = "#34D399",
        secondaryAccentHex = "#2DD4BF",
        canvasColorName = "Deep Jade",
        canvasColorHex = "#030E09",
        cardColorName = "Dark Forest",
        cardColorHex = "#091D14",
        cardBorderHex = "#15422D",
        textPrimaryHex = "#ECFDF5",
        textSecondaryHex = "#A7F3D0",
        textMutedHex = "#6B8E7D",
        bgGradientColorsHex = listOf("#062318", "#02120C", "#000503"),
        cardGradientColorsHex = listOf("#0E2C1E", "#071710"),
        bottomSheetGradientColorsHex = listOf("#0A2217", "#020D08"),
        bgGradientName = "Emerald Nebula",
        cardGradientName = "Subtle Sheen",
        bottomSheetGradientName = "Deep Obsidian"
    )

    val SunsetHorizon = AppThemePreset(
        id = "dark_sunset_horizon",
        name = "Sunset Horizon",
        subtitle = "Volcanic obsidian canvas with glowing ember and amber fire",
        isDark = true,
        accentName = "Sunset Orange",
        accentShade = "500",
        accentColorHex = "#FB923C",
        secondaryAccentHex = "#FBBF24",
        canvasColorName = "Dark Ember",
        canvasColorHex = "#100705",
        cardColorName = "Burnt Slate",
        cardColorHex = "#1C0D09",
        cardBorderHex = "#451E14",
        textPrimaryHex = "#FFF7ED",
        textSecondaryHex = "#FED7AA",
        textMutedHex = "#A27E72",
        bgGradientColorsHex = listOf("#291008", "#140703", "#040101"),
        cardGradientColorsHex = listOf("#2E140C", "#170A06"),
        bottomSheetGradientColorsHex = listOf("#220C06", "#0B0402"),
        bgGradientName = "Sunset Ember",
        cardGradientName = "Subtle Sheen",
        bottomSheetGradientName = "Studio Midnight"
    )

    // ==========================================
    // 5 DISTINCT LIGHT MODE PRESETS
    // ==========================================

    val CleanDay = AppThemePreset(
        id = "light_clean_day",
        name = "Clean Day Light",
        subtitle = "Pure daylight canvas with vibrant sapphire blue accents",
        isDark = false,
        accentName = "Ocean Blue",
        accentShade = "500",
        accentColorHex = "#0284C7",
        secondaryAccentHex = "#2563EB",
        canvasColorName = "Pure White",
        canvasColorHex = "#F8FAFD",
        cardColorName = "White",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#CBD5E1",
        textPrimaryHex = "#0F172A",
        textSecondaryHex = "#1E40AF",
        textMutedHex = "#64748B",
        bgGradientColorsHex = listOf("#FFFFFF", "#F0F6FE", "#E0F2FE"),
        cardGradientColorsHex = listOf("#FFFFFF", "#F8FAFC"),
        bottomSheetGradientColorsHex = listOf("#FFFFFF", "#F1F5F9"),
        bgGradientName = "Frost Sky",
        cardGradientName = "Solid Flat",
        bottomSheetGradientName = "Clean White"
    )

    val CleanFrost = CleanDay

    val MinimalIvory = AppThemePreset(
        id = "light_minimal_ivory",
        name = "Minimal Ivory",
        subtitle = "Warm linen paper canvas with clean charcoal typography",
        isDark = false,
        accentName = "Charcoal Ink",
        accentShade = "700",
        accentColorHex = "#18181B",
        secondaryAccentHex = "#78350F",
        canvasColorName = "Linen Ivory",
        canvasColorHex = "#FAF7F2",
        cardColorName = "Warm White",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#E5DFD5",
        textPrimaryHex = "#1C1917",
        textSecondaryHex = "#44403C",
        textMutedHex = "#78716C",
        bgGradientColorsHex = listOf("#FFFFFF", "#FAF7F2", "#F2ECE4"),
        cardGradientColorsHex = listOf("#FFFFFF", "#FDFBF7"),
        bottomSheetGradientColorsHex = listOf("#FFFFFF", "#F7F3EC"),
        bgGradientName = "Warm Paper",
        cardGradientName = "Solid Flat",
        bottomSheetGradientName = "Clean White"
    )

    val CherryBlossom = AppThemePreset(
        id = "light_cherry_blossom",
        name = "Cherry Blossom",
        subtitle = "Pastel sakura blush with vibrant rose and berry tones",
        isDark = false,
        accentName = "Rose Berry",
        accentShade = "500",
        accentColorHex = "#E11D48",
        secondaryAccentHex = "#FB7185",
        canvasColorName = "Sakura Blush",
        canvasColorHex = "#FFF5F7",
        cardColorName = "White Rose",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#FECDD3",
        textPrimaryHex = "#1F1216",
        textSecondaryHex = "#9F1239",
        textMutedHex = "#886570",
        bgGradientColorsHex = listOf("#FFFFFF", "#FFF0F4", "#FDE2EA"),
        cardGradientColorsHex = listOf("#FFFFFF", "#FFF7F9"),
        bottomSheetGradientColorsHex = listOf("#FFFFFF", "#FCE7ED"),
        bgGradientName = "Sakura Breeze",
        cardGradientName = "Solid Flat",
        bottomSheetGradientName = "Clean White"
    )

    val ElectricMint = AppThemePreset(
        id = "light_electric_mint",
        name = "Electric Mint",
        subtitle = "Fresh crisp botanical canvas with vivid emerald mint",
        isDark = false,
        accentName = "Botanical Emerald",
        accentShade = "500",
        accentColorHex = "#059669",
        secondaryAccentHex = "#0D9488",
        canvasColorName = "Fresh Mint",
        canvasColorHex = "#F0FDF4",
        cardColorName = "White Sage",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#BBF7D0",
        textPrimaryHex = "#064E3B",
        textSecondaryHex = "#047857",
        textMutedHex = "#4B6E5F",
        bgGradientColorsHex = listOf("#FFFFFF", "#F0FDF4", "#DCFCE7"),
        cardGradientColorsHex = listOf("#FFFFFF", "#F7FEFA"),
        bottomSheetGradientColorsHex = listOf("#FFFFFF", "#E8FBF0"),
        bgGradientName = "Fresh Mint Breeze",
        cardGradientName = "Solid Flat",
        bottomSheetGradientName = "Clean White"
    )

    val SunsetPeach = AppThemePreset(
        id = "light_sunset_peach",
        name = "Sunset Peach",
        subtitle = "Warm radiant golden hour cream with sunlit amber glow",
        isDark = false,
        accentName = "Golden Amber",
        accentShade = "500",
        accentColorHex = "#D97706",
        secondaryAccentHex = "#EA580C",
        canvasColorName = "Sunlit Cream",
        canvasColorHex = "#FFFBEB",
        cardColorName = "Sunlit White",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#FDE68A",
        textPrimaryHex = "#451A03",
        textSecondaryHex = "#9A3412",
        textMutedHex = "#855A3E",
        bgGradientColorsHex = listOf("#FFFFFF", "#FEF3C7", "#FDE68A"),
        cardGradientColorsHex = listOf("#FFFFFF", "#FFFDF5"),
        bottomSheetGradientColorsHex = listOf("#FFFFFF", "#FEF3C7"),
        bgGradientName = "Golden Hour",
        cardGradientName = "Solid Flat",
        bottomSheetGradientName = "Clean White"
    )

    // ==========================================
    // LIQUID GLASS PRESETS
    // ==========================================

    val OceanicGlass = AppThemePreset(
        id = "oceanic_glass",
        name = "Oceanic Liquid Glass",
        subtitle = "Signature deep oceanic void with electric azure translucent glass",
        isDark = true,
        accentName = "Electric Azure",
        accentShade = "400",
        accentColorHex = "#00D2FF",
        secondaryAccentHex = "#3B82F6",
        canvasColorName = "Oceanic Void",
        canvasColorHex = "#030712",
        cardColorName = "Abyssal Navy Glass",
        cardColorHex = "#0B1220",
        cardBorderHex = "#1E3557",
        textPrimaryHex = "#F9FAFB",
        textSecondaryHex = "#60A5FA",
        textMutedHex = "#64748B",
        bgGradientColorsHex = listOf("#030712", "#070E22", "#02040A"),
        cardGradientColorsHex = listOf("#0E1A30", "#080F1E"),
        bottomSheetGradientColorsHex = listOf("#0A1426", "#030712"),
        bgGradientName = "Oceanic Void",
        cardGradientName = "Liquid Glass",
        bottomSheetGradientName = "Abyssal Glass"
    )

    val NebulaGlass = AppThemePreset(
        id = "nebula_glass",
        name = "Nebula Liquid Glass",
        subtitle = "Celestial midnight obsidian with vibrant amethyst & magenta glass",
        isDark = true,
        accentName = "Amethyst Quartz",
        accentShade = "500",
        accentColorHex = "#A855F7",
        secondaryAccentHex = "#EC4899",
        canvasColorName = "Nebula Void",
        canvasColorHex = "#070414",
        cardColorName = "Cosmic Violet Glass",
        cardColorHex = "#120A28",
        cardBorderHex = "#381F66",
        textPrimaryHex = "#FAF5FF",
        textSecondaryHex = "#D8B4FE",
        textMutedHex = "#9484A8",
        bgGradientColorsHex = listOf("#1A0B36", "#0B051D", "#03010A"),
        cardGradientColorsHex = listOf("#1E113B", "#100824"),
        bottomSheetGradientColorsHex = listOf("#17092E", "#070414"),
        bgGradientName = "Nebula Glow",
        cardGradientName = "Liquid Glass",
        bottomSheetGradientName = "Cosmic Glass"
    )

    val MatrixGlass = AppThemePreset(
        id = "matrix_glass",
        name = "Matrix Liquid Glass",
        subtitle = "Cybernetic deep obsidian with radiant emerald & acid lime accents",
        isDark = true,
        accentName = "Matrix Emerald",
        accentShade = "500",
        accentColorHex = "#10B981",
        secondaryAccentHex = "#84CC16",
        canvasColorName = "Matrix Black",
        canvasColorHex = "#020C06",
        cardColorName = "Dark Forest Glass",
        cardColorHex = "#081C10",
        cardBorderHex = "#1B4E2F",
        textPrimaryHex = "#ECFDF5",
        textSecondaryHex = "#6EE7B7",
        textMutedHex = "#5E8370",
        bgGradientColorsHex = listOf("#062615", "#031309", "#010603"),
        cardGradientColorsHex = listOf("#0D2A19", "#06180E"),
        bottomSheetGradientColorsHex = listOf("#092113", "#020C06"),
        bgGradientName = "Matrix Flow",
        cardGradientName = "Liquid Glass",
        bottomSheetGradientName = "Emerald Glass"
    )

    val SolarGlass = AppThemePreset(
        id = "solar_glass",
        name = "Solar Liquid Glass",
        subtitle = "Magma obsidian canvas with glowing amber & solar flare highlights",
        isDark = true,
        accentName = "Solar Amber",
        accentShade = "500",
        accentColorHex = "#F97316",
        secondaryAccentHex = "#FBBF24",
        canvasColorName = "Magma Canvas",
        canvasColorHex = "#0E0502",
        cardColorName = "Volcanic Glass",
        cardColorHex = "#1D0D07",
        cardBorderHex = "#4F2314",
        textPrimaryHex = "#FFF7ED",
        textSecondaryHex = "#FDBA74",
        textMutedHex = "#9A7265",
        bgGradientColorsHex = listOf("#2E0E05", "#140602", "#050100"),
        cardGradientColorsHex = listOf("#2B130A", "#170905"),
        bottomSheetGradientColorsHex = listOf("#220D06", "#0E0502"),
        bgGradientName = "Solar Flare",
        cardGradientName = "Liquid Glass",
        bottomSheetGradientName = "Volcanic Glass"
    )

    val GlacierGlass = AppThemePreset(
        id = "glacier_glass",
        name = "Glacier Liquid Glass",
        subtitle = "Crystalline frost light canvas with translucent ocean glass surfaces",
        isDark = false,
        accentName = "Glacier Azure",
        accentShade = "500",
        accentColorHex = "#0284C7",
        secondaryAccentHex = "#06B6D4",
        canvasColorName = "Frost Sky",
        canvasColorHex = "#F5F9FF",
        cardColorName = "Glacier Crystal White",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#BFD7F5",
        textPrimaryHex = "#0B192C",
        textSecondaryHex = "#0369A1",
        textMutedHex = "#64748B",
        bgGradientColorsHex = listOf("#FFFFFF", "#F0F7FF", "#E2EFFF"),
        cardGradientColorsHex = listOf("#FFFFFF", "#F8FBFF"),
        bottomSheetGradientColorsHex = listOf("#FFFFFF", "#EEF6FF"),
        bgGradientName = "Glacier Frost",
        cardGradientName = "Liquid Glass",
        bottomSheetGradientName = "Clean Crystal"
    )

    val SakuraGlass = AppThemePreset(
        id = "sakura_glass",
        name = "Sakura Liquid Glass",
        subtitle = "Blooming quartz light canvas with translucent rose glass surfaces",
        isDark = false,
        accentName = "Sakura Rose",
        accentShade = "500",
        accentColorHex = "#E11D48",
        secondaryAccentHex = "#F43F5E",
        canvasColorName = "Quartz Rose",
        canvasColorHex = "#FFF6F8",
        cardColorName = "Sakura Crystal White",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#FBC4CF",
        textPrimaryHex = "#1F0A11",
        textSecondaryHex = "#BE123C",
        textMutedHex = "#8C6873",
        bgGradientColorsHex = listOf("#FFFFFF", "#FFF1F4", "#FDE5EB"),
        cardGradientColorsHex = listOf("#FFFFFF", "#FFF7F9"),
        bottomSheetGradientColorsHex = listOf("#FFFFFF", "#FCE8EE"),
        bgGradientName = "Sakura Glow",
        cardGradientName = "Liquid Glass",
        bottomSheetGradientName = "Clean Crystal"
    )

    // Backward compatibility aliases
    val LastWaveOceanicGlass = OceanicGlass
    val LastWaveNebulaGlass = NebulaGlass
    val LastWaveMatrixGlass = MatrixGlass
    val LastWaveSolarGlass = SolarGlass
    val LastWaveGlacierGlass = GlacierGlass
    val LastWaveSakuraGlass = SakuraGlass

    val allPresets: List<AppThemePreset> = listOf(
        // Dark Presets
        StudioNight,
        AmoledPureBlack,
        CyberpunkNeon,
        EmeraldNebula,
        SunsetHorizon,
        OceanicGlass,
        NebulaGlass,
        MatrixGlass,
        SolarGlass,
        // Light Presets
        CleanFrost,
        MinimalIvory,
        CherryBlossom,
        ElectricMint,
        SunsetPeach,
        GlacierGlass,
        SakuraGlass
    )

    val darkPresets: List<AppThemePreset> = allPresets.filter { it.isDark }
    val lightPresets: List<AppThemePreset> = allPresets.filter { !it.isDark }

    fun findById(id: String): AppThemePreset? {
        val sanitized = id.removePrefix("lastwave_").trim()
        return allPresets.find {
            it.id.equals(id, ignoreCase = true) ||
                    it.id.equals(sanitized, ignoreCase = true) ||
                    it.id.equals("${sanitized}_glass", ignoreCase = true)
        }
    }

    fun findByName(name: String): AppThemePreset? {
        val sanitized = name.replace("LastWave", "", ignoreCase = true).trim()
        return allPresets.find {
            it.name.equals(name, ignoreCase = true) ||
                    it.name.equals(sanitized, ignoreCase = true) ||
                    it.name.startsWith(sanitized, ignoreCase = true)
        }
    }
}

/**
 * Full state representing user-customized colors, gradients, and theme settings.
 */
@Immutable
data class CustomThemeState(
    val presetId: String = "dark_studio_night",
    val currentThemeName: String = "Studio Night",
    val isPresetDark: Boolean = true,
    val isAmoled: Boolean = false,
    val accentName: String = "Electric Azure",
    val accentShade: String = "400",
    val accentColorHex: String = "#00D2FF",
    val secondaryAccentHex: String = "#1D4ED8",
    val canvasColorName: String = "Studio Void",
    val canvasColorHex: String = "#020610",
    val cardColorName: String = "Deep Studio Glass",
    val cardColorHex: String = "#050C1B",
    val cardBorderHex: String = "#0F2A4F",
    val textPrimaryHex: String = "#F8FAFC",
    val textSecondaryHex: String = "#7DD3FC",
    val textMutedHex: String = "#64748B",
    val bgGradientColorsHex: List<String> = listOf("#020610", "#010308", "#000103"),
    val cardGradientColorsHex: List<String> = listOf("#0B1E3B", "#040A17"),
    val bottomSheetGradientColorsHex: List<String> = listOf("#07152B", "#020610"),
    val bgGradientName: String = "Studio Void",
    val cardGradientName: String = "Studio Glass",
    val bottomSheetGradientName: String = "Deep Obsidian"
)

object ThemePreferences {
    private const val PREFS_NAME = "xtreme_theme_prefs"
    private const val KEY_THEME_MODE = "selected_theme_mode"
    
    private const val KEY_PRESET_ID = "custom_preset_id"
    private const val KEY_PRESET_DARK = "custom_preset_is_dark"
    private const val KEY_AMOLED = "custom_is_amoled"
    private const val KEY_ACCENT_NAME = "custom_accent_name"
    private const val KEY_ACCENT_SHADE = "custom_accent_shade"
    private const val KEY_ACCENT_HEX = "custom_accent_hex"
    private const val KEY_SECONDARY_ACCENT_HEX = "custom_secondary_accent_hex"
    private const val KEY_CANVAS_NAME = "custom_canvas_name"
    private const val KEY_CANVAS_HEX = "custom_canvas_hex"
    private const val KEY_CARD_NAME = "custom_card_name"
    private const val KEY_CARD_HEX = "custom_card_hex"
    private const val KEY_CARD_BORDER_HEX = "custom_card_border_hex"
    private const val KEY_TEXT_PRIMARY_HEX = "custom_text_primary_hex"
    private const val KEY_TEXT_SECONDARY_HEX = "custom_text_secondary_hex"
    private const val KEY_TEXT_MUTED_HEX = "custom_text_muted_hex"
    private const val KEY_BG_GRADIENT_COLORS = "custom_bg_gradient_colors"
    private const val KEY_CARD_GRADIENT_COLORS = "custom_card_gradient_colors"
    private const val KEY_BS_GRADIENT_COLORS = "custom_bs_gradient_colors"
    private const val KEY_BG_GRADIENT = "custom_bg_gradient"
    private const val KEY_CARD_GRADIENT = "custom_card_gradient"
    private const val KEY_BS_GRADIENT = "custom_bs_gradient"
    private const val KEY_CURRENT_THEME = "custom_theme_name"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getThemeMode(context: Context): AppThemeMode {
        val prefs = getPrefs(context)
        val key = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.storageKey)
        return AppThemeMode.fromKey(key)
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        val prefs = getPrefs(context)
        prefs.edit().putString(KEY_THEME_MODE, mode.storageKey).apply()
    }

    fun getCustomThemeState(context: Context): CustomThemeState {
        val prefs = getPrefs(context)
        val currentThemeName = prefs.getString(KEY_CURRENT_THEME, null)
        val presetId = prefs.getString(KEY_PRESET_ID, null)

        // If not customized yet, use default preset StudioNight
        val defaultPreset = ThemePresets.StudioNight

        val bgGradientColorsRaw = prefs.getString(KEY_BG_GRADIENT_COLORS, null)
        val bgGradientColors = if (!bgGradientColorsRaw.isNullOrBlank()) {
            bgGradientColorsRaw.split(",").filter { it.isNotBlank() }
        } else {
            defaultPreset.bgGradientColorsHex
        }

        val cardGradientColorsRaw = prefs.getString(KEY_CARD_GRADIENT_COLORS, null)
        val cardGradientColors = if (!cardGradientColorsRaw.isNullOrBlank()) {
            cardGradientColorsRaw.split(",").filter { it.isNotBlank() }
        } else {
            defaultPreset.cardGradientColorsHex
        }

        val bsGradientColorsRaw = prefs.getString(KEY_BS_GRADIENT_COLORS, null)
        val bsGradientColors = if (!bsGradientColorsRaw.isNullOrBlank()) {
            bsGradientColorsRaw.split(",").filter { it.isNotBlank() }
        } else {
            defaultPreset.bottomSheetGradientColorsHex
        }

        return CustomThemeState(
            presetId = presetId ?: defaultPreset.id,
            currentThemeName = currentThemeName ?: defaultPreset.name,
            isPresetDark = prefs.getBoolean(KEY_PRESET_DARK, defaultPreset.isDark),
            isAmoled = prefs.getBoolean(KEY_AMOLED, defaultPreset.isAmoled),
            accentName = prefs.getString(KEY_ACCENT_NAME, defaultPreset.accentName) ?: defaultPreset.accentName,
            accentShade = prefs.getString(KEY_ACCENT_SHADE, defaultPreset.accentShade) ?: defaultPreset.accentShade,
            accentColorHex = prefs.getString(KEY_ACCENT_HEX, defaultPreset.accentColorHex) ?: defaultPreset.accentColorHex,
            secondaryAccentHex = prefs.getString(KEY_SECONDARY_ACCENT_HEX, defaultPreset.secondaryAccentHex) ?: defaultPreset.secondaryAccentHex,
            canvasColorName = prefs.getString(KEY_CANVAS_NAME, defaultPreset.canvasColorName) ?: defaultPreset.canvasColorName,
            canvasColorHex = prefs.getString(KEY_CANVAS_HEX, defaultPreset.canvasColorHex) ?: defaultPreset.canvasColorHex,
            cardColorName = prefs.getString(KEY_CARD_NAME, defaultPreset.cardColorName) ?: defaultPreset.cardColorName,
            cardColorHex = prefs.getString(KEY_CARD_HEX, defaultPreset.cardColorHex) ?: defaultPreset.cardColorHex,
            cardBorderHex = prefs.getString(KEY_CARD_BORDER_HEX, defaultPreset.cardBorderHex) ?: defaultPreset.cardBorderHex,
            textPrimaryHex = prefs.getString(KEY_TEXT_PRIMARY_HEX, defaultPreset.textPrimaryHex) ?: defaultPreset.textPrimaryHex,
            textSecondaryHex = prefs.getString(KEY_TEXT_SECONDARY_HEX, defaultPreset.textSecondaryHex) ?: defaultPreset.textSecondaryHex,
            textMutedHex = prefs.getString(KEY_TEXT_MUTED_HEX, defaultPreset.textMutedHex) ?: defaultPreset.textMutedHex,
            bgGradientColorsHex = bgGradientColors,
            cardGradientColorsHex = cardGradientColors,
            bottomSheetGradientColorsHex = bsGradientColors,
            bgGradientName = prefs.getString(KEY_BG_GRADIENT, defaultPreset.bgGradientName) ?: defaultPreset.bgGradientName,
            cardGradientName = prefs.getString(KEY_CARD_GRADIENT, defaultPreset.cardGradientName) ?: defaultPreset.cardGradientName,
            bottomSheetGradientName = prefs.getString(KEY_BS_GRADIENT, defaultPreset.bottomSheetGradientName) ?: defaultPreset.bottomSheetGradientName
        )
    }

    fun saveCustomThemeState(context: Context, state: CustomThemeState) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putString(KEY_PRESET_ID, state.presetId)
            .putString(KEY_CURRENT_THEME, state.currentThemeName)
            .putBoolean(KEY_PRESET_DARK, state.isPresetDark)
            .putBoolean(KEY_AMOLED, state.isAmoled)
            .putString(KEY_ACCENT_NAME, state.accentName)
            .putString(KEY_ACCENT_SHADE, state.accentShade)
            .putString(KEY_ACCENT_HEX, state.accentColorHex)
            .putString(KEY_SECONDARY_ACCENT_HEX, state.secondaryAccentHex)
            .putString(KEY_CANVAS_NAME, state.canvasColorName)
            .putString(KEY_CANVAS_HEX, state.canvasColorHex)
            .putString(KEY_CARD_NAME, state.cardColorName)
            .putString(KEY_CARD_HEX, state.cardColorHex)
            .putString(KEY_CARD_BORDER_HEX, state.cardBorderHex)
            .putString(KEY_TEXT_PRIMARY_HEX, state.textPrimaryHex)
            .putString(KEY_TEXT_SECONDARY_HEX, state.textSecondaryHex)
            .putString(KEY_TEXT_MUTED_HEX, state.textMutedHex)
            .putString(KEY_BG_GRADIENT_COLORS, state.bgGradientColorsHex.joinToString(","))
            .putString(KEY_CARD_GRADIENT_COLORS, state.cardGradientColorsHex.joinToString(","))
            .putString(KEY_BS_GRADIENT_COLORS, state.bottomSheetGradientColorsHex.joinToString(","))
            .putString(KEY_BG_GRADIENT, state.bgGradientName)
            .putString(KEY_CARD_GRADIENT, state.cardGradientName)
            .putString(KEY_BS_GRADIENT, state.bottomSheetGradientName)
            .apply()
    }
}
