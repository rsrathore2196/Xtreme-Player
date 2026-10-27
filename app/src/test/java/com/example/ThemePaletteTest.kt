package com.example

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.unit.dp
import com.example.data.local.ThemePresets
import com.example.ui.theme.*
import com.example.ui.util.playerAppearanceColors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class ThemePaletteTest {
    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

    private fun neutral(color: Color) {
        assertEquals(color.red, color.green, .001f)
        assertEquals(color.green, color.blue, .001f)
    }

    @Test fun everyPresetHasNeutralSurfacesAndCompleteMaterialRolesWithReadableContent() {
        ThemePresets.allPresets.forEach { preset ->
            val colors = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState())
            assertTrue(colors.neutralSurfaces)
            assertEquals(if (preset.isDark) {
                if (preset.isAmoled) Color.Black else Color(0xff0b0b0b)
            } else Color.White, colors.scaffoldBackground)
            val scheme = resolveColorScheme(preset.isDark, colors)
            val surfaces = listOf(scheme.background, scheme.surface, scheme.surfaceVariant,
                scheme.surfaceDim, scheme.surfaceBright, scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest, colors.chipBackground, colors.inputBackground)
            surfaces.forEach { surface ->
                neutral(surface)
                assertEquals(1f, surface.alpha, 0f)
                listOf(colors.textPrimary, colors.textSecondary, colors.textMuted).forEach { text ->
                    assertTrue("${preset.name}: $text on $surface", contrast(text, surface) >= 4.5f)
                }
            }
            for (elevation in listOf(0, 1, 6, 24)) neutral(scheme.surfaceColorAtElevation(elevation.dp))
            for ((background, content) in listOf(scheme.primary to scheme.onPrimary,
                scheme.secondary to scheme.onSecondary, scheme.primaryContainer to scheme.onPrimaryContainer,
                scheme.secondaryContainer to scheme.onSecondaryContainer)) {
                assertEquals(1f, background.alpha, 0f)
                assertTrue("${preset.name}: action contrast", contrast(content, background) >= 4.5f)
            }
            assertTrue(contrast(scheme.outline, scheme.surface) >= 3f)
            assertTrue(contrast(colors.primaryAccent, colors.scaffoldBackground) >= 4.5f)
        }
    }

    @Test fun highContrastIncludesElevatedSurfacesAndCustomColorsAreNotNeutralized() {
        ThemePresets.allPresets.forEach { preset ->
            val colors = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState(), true)
            val scheme = resolveColorScheme(preset.isDark, colors)
            for (surface in listOf(scheme.background, scheme.surface, scheme.surfaceContainerHighest)) {
                for (text in listOf(colors.textPrimary, colors.textSecondary, colors.textMuted)) {
                    assertTrue("${preset.name}: enhanced text", contrast(text, surface) >= 7f)
                }
            }
        }
        val custom = ThemePresets.CherryBlossom.toCustomThemeState().copy(presetId = "", currentThemeName = "Custom",
            canvasColorHex = "#FFF0F8", cardColorHex = "#FFF7F2",
            bgGradientColorsHex = listOf("#FFF0F8", "#FFF0F8"))
        val colors = resolveAppThemeColors(false, custom)
        assertFalse(colors.neutralSurfaces)
        assertEquals(Color(0xfffff0f8), colors.scaffoldBackground)
        assertEquals(Color(0xfffff7f2), colors.cardBackground)
        assertEquals(Color(0xfffff0f8), LiquidGlass.settingsColors(colors).scaffoldBackground)
    }

    @Test fun settingsRetainsAccentActionsButDoesNotPropagateItsFinishIntoThePlayer() {
        ThemePresets.allPresets.forEach { preset ->
            val original = resolveAppThemeColors(preset.isDark, preset.toCustomThemeState())
            val settings = LiquidGlass.settingsColors(original)
            assertTrue(settings.settingsSurface)
            assertEquals(original.primaryAccent, settings.primaryAccent)
            assertEquals(original.cardBackground, settings.cardBackground)
            assertFalse(original.settingsSurface)
            val player = playerAppearanceColors(settings)
            assertFalse(player.settingsSurface)
            assertFalse(player.neutralSurfaces)
            assertEquals(Color.White, player.primaryAccent)
        }
    }
}
