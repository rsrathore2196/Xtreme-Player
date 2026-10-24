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
 * Curated list of 10 Dark and 10 Light modern, aesthetic, eye-catching presets.
 * Each preset has distinct visual identities, colors, contrasts, and atmosphere.
 */
object ThemePresets {

    // ==========================================
    // 10 DISTINCT DARK MODE PRESETS
    // ==========================================

    val StudioNight = AppThemePreset(
        id = "dark_studio_night",
        name = "Studio Night",
        subtitle = "Signature studio obsidian night with luminous electric azure accents",
        isDark = true,
        accentName = "Electric Azure",
        accentShade = "400",
        accentColorHex = "#66D5FF",
        secondaryAccentHex = "#A6BCFF",
        canvasColorName = "Studio Void",
        canvasColorHex = "#080E19",
        cardColorName = "Deep Studio Glass",
        cardColorHex = "#111C2C",
        cardBorderHex = "#2D425E",
        textPrimaryHex = "#F1F6FF",
        textSecondaryHex = "#C0CEE2",
        textMutedHex = "#9CADC5",
        bgGradientColorsHex = listOf("#0A131F", "#080E19", "#050910"),
        cardGradientColorsHex = listOf("#142334", "#111C2C"),
        bottomSheetGradientColorsHex = listOf("#132131", "#080E19"),
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
        secondaryAccentHex = "#D4D4E0",
        canvasColorName = "Pitch Black",
        canvasColorHex = "#000000",
        cardColorName = "Pitch Black",
        cardColorHex = "#000000",
        cardBorderHex = "#2D2D35",
        textPrimaryHex = "#FFFFFF",
        textSecondaryHex = "#E1E1E9",
        textMutedHex = "#A1A1B0",
        bgGradientColorsHex = listOf("#000000", "#000000", "#000000"),
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
        accentColorHex = "#D6A1FF",
        secondaryAccentHex = "#FF9ACB",
        canvasColorName = "Abyssal Violet",
        canvasColorHex = "#100A1C",
        cardColorName = "Dark Violet",
        cardColorHex = "#20152F",
        cardBorderHex = "#4B355F",
        textPrimaryHex = "#FBF4FF",
        textSecondaryHex = "#E2C9EE",
        textMutedHex = "#BBA1C8",
        bgGradientColorsHex = listOf("#150E22", "#100A1C", "#0A0612"),
        cardGradientColorsHex = listOf("#271B37", "#20152F"),
        bottomSheetGradientColorsHex = listOf("#251834", "#100A1C"),
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
        accentColorHex = "#64DDB3",
        secondaryAccentHex = "#9ACFC7",
        canvasColorName = "Deep Jade",
        canvasColorHex = "#071410",
        cardColorName = "Dark Forest",
        cardColorHex = "#10271F",
        cardBorderHex = "#305246",
        textPrimaryHex = "#F0FFF8",
        textSecondaryHex = "#C1E4D5",
        textMutedHex = "#9CBFB0",
        bgGradientColorsHex = listOf("#091914", "#071410", "#050D0A"),
        cardGradientColorsHex = listOf("#132E25", "#10271F"),
        bottomSheetGradientColorsHex = listOf("#122C23", "#071410"),
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
        accentColorHex = "#FFB18F",
        secondaryAccentHex = "#EAB8C9",
        canvasColorName = "Dark Ember",
        canvasColorHex = "#180F13",
        cardColorName = "Burnt Slate",
        cardColorHex = "#2B1C23",
        cardBorderHex = "#5A3D49",
        textPrimaryHex = "#FFF7F2",
        textSecondaryHex = "#EBCDC4",
        textMutedHex = "#C6A9A6",
        bgGradientColorsHex = listOf("#1E1316", "#180F13", "#100A0C"),
        cardGradientColorsHex = listOf("#332227", "#2B1C23"),
        bottomSheetGradientColorsHex = listOf("#302026", "#180F13"),
        bgGradientName = "Sunset Ember",
        cardGradientName = "Subtle Sheen",
        bottomSheetGradientName = "Studio Midnight"
    )

    // ==========================================
    // 10 DISTINCT LIGHT MODE PRESETS
    // ==========================================

    val CleanDay = AppThemePreset(
        id = "light_clean_day",
        name = "Clean Day Light",
        subtitle = "Pure daylight canvas with vibrant sapphire blue accents",
        isDark = false,
        accentName = "Ocean Blue",
        accentShade = "500",
        accentColorHex = "#1762AA",
        secondaryAccentHex = "#5663AD",
        canvasColorName = "Pure White",
        canvasColorHex = "#F2F7FD",
        cardColorName = "White",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#CDDAE9",
        textPrimaryHex = "#162C45",
        textSecondaryHex = "#405A76",
        textMutedHex = "#596D83",
        bgGradientColorsHex = listOf("#EFF5FC", "#F2F7FD", "#E8F0F9"),
        cardGradientColorsHex = listOf("#FCFDFE", "#FFFFFF"),
        bottomSheetGradientColorsHex = listOf("#F9FBFD", "#F2F7FD"),
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
        accentName = "Antique Bronze",
        accentShade = "700",
        accentColorHex = "#845725",
        secondaryAccentHex = "#8A644A",
        canvasColorName = "Linen Ivory",
        canvasColorHex = "#FAF7F0",
        cardColorName = "Warm White",
        cardColorHex = "#FFFEFA",
        cardBorderHex = "#DDD3C3",
        textPrimaryHex = "#30291E",
        textSecondaryHex = "#635642",
        textMutedHex = "#74664F",
        bgGradientColorsHex = listOf("#F8F5ED", "#FAF7F0", "#F5F0E7"),
        cardGradientColorsHex = listOf("#FEFCF7", "#FFFEFA"),
        bottomSheetGradientColorsHex = listOf("#FCFAF5", "#FAF7F0"),
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
        accentColorHex = "#A73567",
        secondaryAccentHex = "#824E88",
        canvasColorName = "Sakura Blush",
        canvasColorHex = "#FFF4F8",
        cardColorName = "White Rose",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#EBD0DC",
        textPrimaryHex = "#3D2333",
        textSecondaryHex = "#795368",
        textMutedHex = "#856176",
        bgGradientColorsHex = listOf("#FEF1F6", "#FFF4F8", "#FBEBF1"),
        cardGradientColorsHex = listOf("#FEFDFD", "#FFFFFF"),
        bottomSheetGradientColorsHex = listOf("#FDFAFB", "#FFF4F8"),
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
        accentColorHex = "#0D7165",
        secondaryAccentHex = "#286F7A",
        canvasColorName = "Fresh Mint",
        canvasColorHex = "#EFFAF5",
        cardColorName = "White Sage",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#C4DDD3",
        textPrimaryHex = "#18372D",
        textSecondaryHex = "#426B5E",
        textMutedHex = "#4F6D60",
        bgGradientColorsHex = listOf("#ECF8F3", "#EFFAF5", "#E5F4EF"),
        cardGradientColorsHex = listOf("#FCFDFD", "#FFFFFF"),
        bottomSheetGradientColorsHex = listOf("#F9FBFB", "#EFFAF5"),
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
        accentColorHex = "#A04E2E",
        secondaryAccentHex = "#99633D",
        canvasColorName = "Sunlit Cream",
        canvasColorHex = "#FFF5EE",
        cardColorName = "Sunlit White",
        cardColorHex = "#FFFEFC",
        cardBorderHex = "#E9D0BE",
        textPrimaryHex = "#412A21",
        textSecondaryHex = "#785846",
        textMutedHex = "#7A5F4E",
        bgGradientColorsHex = listOf("#FEF2EB", "#FFF5EE", "#FBEDE5"),
        cardGradientColorsHex = listOf("#FEFCFA", "#FFFEFC"),
        bottomSheetGradientColorsHex = listOf("#FDFAF7", "#FFF5EE"),
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
        accentColorHex = "#67D9F0",
        secondaryAccentHex = "#8DAEFF",
        canvasColorName = "Oceanic Void",
        canvasColorHex = "#08131D",
        cardColorName = "Abyssal Navy Glass",
        cardColorHex = "#112635",
        cardBorderHex = "#2D4D63",
        textPrimaryHex = "#F1FBFF",
        textSecondaryHex = "#BDDBEA",
        textMutedHex = "#99B9CD",
        bgGradientColorsHex = listOf("#0A1822", "#08131D", "#050C13"),
        cardGradientColorsHex = listOf("#142D3C", "#112635"),
        bottomSheetGradientColorsHex = listOf("#132A3A", "#08131D"),
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
        accentColorHex = "#C5B2FF",
        secondaryAccentHex = "#E9ADE5",
        canvasColorName = "Nebula Void",
        canvasColorHex = "#100D20",
        cardColorName = "Cosmic Violet Glass",
        cardColorHex = "#211A35",
        cardBorderHex = "#4B3D69",
        textPrimaryHex = "#F8F4FF",
        textSecondaryHex = "#D9CDEF",
        textMutedHex = "#B2A5CC",
        bgGradientColorsHex = listOf("#151126", "#100D20", "#0A0815"),
        cardGradientColorsHex = listOf("#28203D", "#211A35"),
        bottomSheetGradientColorsHex = listOf("#251E3A", "#100D20"),
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
        accentColorHex = "#8CE2A0",
        secondaryAccentHex = "#CCDE8B",
        canvasColorName = "Matrix Black",
        canvasColorHex = "#09150E",
        cardColorName = "Dark Forest Glass",
        cardColorHex = "#16291C",
        cardBorderHex = "#37543F",
        textPrimaryHex = "#F1FFF3",
        textSecondaryHex = "#C7E6C8",
        textMutedHex = "#A0BDA3",
        bgGradientColorsHex = listOf("#0C1A12", "#09150E", "#060E09"),
        cardGradientColorsHex = listOf("#1B3021", "#16291C"),
        bottomSheetGradientColorsHex = listOf("#192E1F", "#09150E"),
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
        accentColorHex = "#F8C781",
        secondaryAccentHex = "#FFA78A",
        canvasColorName = "Magma Canvas",
        canvasColorHex = "#19120A",
        cardColorName = "Volcanic Glass",
        cardColorHex = "#2D2316",
        cardBorderHex = "#5B4931",
        textPrimaryHex = "#FFFAEE",
        textSecondaryHex = "#EFDBB9",
        textMutedHex = "#C7B598",
        bgGradientColorsHex = listOf("#1F170D", "#19120A", "#100C06"),
        cardGradientColorsHex = listOf("#352A1A", "#2D2316"),
        bottomSheetGradientColorsHex = listOf("#322719", "#19120A"),
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
        accentColorHex = "#086B9B",
        secondaryAccentHex = "#425FA1",
        canvasColorName = "Frost Sky",
        canvasColorHex = "#F1F8FC",
        cardColorName = "Glacier Crystal White",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#C5DCE9",
        textPrimaryHex = "#153146",
        textSecondaryHex = "#45677D",
        textMutedHex = "#566F81",
        bgGradientColorsHex = listOf("#EEF6FB", "#F1F8FC", "#E7F2F8"),
        cardGradientColorsHex = listOf("#FCFDFE", "#FFFFFF"),
        bottomSheetGradientColorsHex = listOf("#F9FBFC", "#F1F8FC"),
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
        accentColorHex = "#AD3656",
        secondaryAccentHex = "#8C5481",
        canvasColorName = "Quartz Rose",
        canvasColorHex = "#FFF3F5",
        cardColorName = "Sakura Crystal White",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#EBCBD4",
        textPrimaryHex = "#42242F",
        textSecondaryHex = "#7C5062",
        textMutedHex = "#7E5A69",
        bgGradientColorsHex = listOf("#FEF0F3", "#FFF3F5", "#FBEAEE"),
        cardGradientColorsHex = listOf("#FEFDFD", "#FFFFFF"),
        bottomSheetGradientColorsHex = listOf("#FDFAFB", "#FFF3F5"),
        bgGradientName = "Sakura Glow",
        cardGradientName = "Liquid Glass",
        bottomSheetGradientName = "Clean Crystal"
    )

    val VelvetNoir = AppThemePreset(
        id = "dark_velvet_noir",
        name = "Velvet Noir",
        subtitle = "Dusty rose on deep aubergine with soft champagne highlights",
        isDark = true,
        accentName = "Velvet Noir Accent",
        accentShade = "500",
        accentColorHex = "#EBAFC1",
        secondaryAccentHex = "#E3C49C",
        canvasColorName = "Velvet Noir Canvas",
        canvasColorHex = "#160F18",
        cardColorName = "Velvet Noir Surface",
        cardColorHex = "#281D2C",
        cardBorderHex = "#523C55",
        textPrimaryHex = "#FFF4F8",
        textSecondaryHex = "#E6C9D6",
        textMutedHex = "#BEA0B2",
        bgGradientColorsHex = listOf("#1B131C", "#160F18", "#0E0A10"),
        cardGradientColorsHex = listOf("#302332", "#281D2C"),
        bottomSheetGradientColorsHex = listOf("#2D2130", "#160F18"),
        bgGradientName = "Velvet Noir Atmosphere",
        cardGradientName = "Velvet Noir Glass",
        bottomSheetGradientName = "Velvet Noir Surface"
    )

    val PorcelainBlue = AppThemePreset(
        id = "light_porcelain_blue",
        name = "Porcelain Blue",
        subtitle = "Porcelain surfaces with refined ink-blue and periwinkle accents",
        isDark = false,
        accentName = "Porcelain Blue Accent",
        accentShade = "500",
        accentColorHex = "#384FB0",
        secondaryAccentHex = "#5365A0",
        canvasColorName = "Porcelain Blue Canvas",
        canvasColorHex = "#F4F5FC",
        cardColorName = "Porcelain Blue Surface",
        cardColorHex = "#FFFFFF",
        cardBorderHex = "#D2D7ED",
        textPrimaryHex = "#242A48",
        textSecondaryHex = "#505D82",
        textMutedHex = "#5C6785",
        bgGradientColorsHex = listOf("#F1F3FB", "#F4F5FC", "#ECEEF9"),
        cardGradientColorsHex = listOf("#FDFDFE", "#FFFFFF"),
        bottomSheetGradientColorsHex = listOf("#FAFBFD", "#F4F5FC"),
        bgGradientName = "Porcelain Blue Atmosphere",
        cardGradientName = "Porcelain Blue Glass",
        bottomSheetGradientName = "Porcelain Blue Surface"
    )

    val LavenderLinen = AppThemePreset(
        id = "light_lavender_linen",
        name = "Lavender Linen",
        subtitle = "Soft lavender linen with elegant plum and lilac detailing",
        isDark = false,
        accentName = "Lavender Linen Accent",
        accentShade = "500",
        accentColorHex = "#78449A",
        secondaryAccentHex = "#795688",
        canvasColorName = "Lavender Linen Canvas",
        canvasColorHex = "#F9F4FC",
        cardColorName = "Lavender Linen Surface",
        cardColorHex = "#FFFDFF",
        cardBorderHex = "#E0D2EA",
        textPrimaryHex = "#35273F",
        textSecondaryHex = "#6F567D",
        textMutedHex = "#745E7D",
        bgGradientColorsHex = listOf("#F7F1FB", "#F9F4FC", "#F3ECF8"),
        cardGradientColorsHex = listOf("#FDFBFE", "#FFFDFF"),
        bottomSheetGradientColorsHex = listOf("#FCF8FC", "#F9F4FC"),
        bgGradientName = "Lavender Linen Atmosphere",
        cardGradientName = "Lavender Linen Glass",
        bottomSheetGradientName = "Lavender Linen Surface"
    )

    val SageMist = AppThemePreset(
        id = "light_sage_mist",
        name = "Sage Mist",
        subtitle = "Calm mineral sage with porcelain cards and deep botanical accents",
        isDark = false,
        accentName = "Sage Mist Accent",
        accentShade = "500",
        accentColorHex = "#346D55",
        secondaryAccentHex = "#557460",
        canvasColorName = "Sage Mist Canvas",
        canvasColorHex = "#F3F8F1",
        cardColorName = "Sage Mist Surface",
        cardColorHex = "#FDFFFC",
        cardBorderHex = "#CEDDCB",
        textPrimaryHex = "#25382C",
        textSecondaryHex = "#526C58",
        textMutedHex = "#5D715F",
        bgGradientColorsHex = listOf("#F0F6EF", "#F3F8F1", "#EAF2EA"),
        cardGradientColorsHex = listOf("#FBFDFA", "#FDFFFC"),
        bottomSheetGradientColorsHex = listOf("#F8FBF8", "#F3F8F1"),
        bgGradientName = "Sage Mist Atmosphere",
        cardGradientName = "Sage Mist Glass",
        bottomSheetGradientName = "Sage Mist Surface"
    )

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
        VelvetNoir,
        // Light Presets
        CleanFrost,
        MinimalIvory,
        CherryBlossom,
        ElectricMint,
        SunsetPeach,
        GlacierGlass,
        SakuraGlass,
        PorcelainBlue,
        LavenderLinen,
        SageMist
    )

    val darkPresets: List<AppThemePreset> = allPresets.filter { it.isDark }
    val lightPresets: List<AppThemePreset> = allPresets.filter { !it.isDark }

    fun findById(id: String): AppThemePreset? {
        val sanitized = id.removePrefix("glass_").trim()
        return allPresets.find {
            it.id.equals(id, ignoreCase = true) ||
                    it.id.equals(sanitized, ignoreCase = true) ||
                    it.id.equals("${sanitized}_glass", ignoreCase = true)
        }
    }

    fun findByName(name: String): AppThemePreset? {
        val sanitized = name.replace("Glass", "", ignoreCase = true).trim()
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
        return ReactiveSettingsRepository.preferences(context, PREFS_NAME)
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

        // A new installation follows the effective mode before onboarding is composed.
        // Existing saved presets/custom colors remain authoritative.
        val dark = when (getThemeMode(context)) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.SYSTEM -> context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val defaultPreset = ThemePresets.findById(presetId.orEmpty())
            ?: ThemePresets.findByName(currentThemeName.orEmpty())
            ?: if (dark) ThemePresets.StudioNight else ThemePresets.CleanDay

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
