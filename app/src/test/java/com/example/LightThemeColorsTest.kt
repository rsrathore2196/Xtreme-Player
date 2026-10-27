package com.example

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.example.data.local.ThemePresets
import com.example.ui.theme.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LightThemeColorsTest {
    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

    @Test fun allLightPresetsKeepTextAndStateOutlinesReadableOnTheirActualSurfaces() {
        ThemePresets.lightPresets.forEach { preset ->
            val original = resolveAppThemeColors(false, preset.toCustomThemeState())
            val colors = LiquidGlass.settingsColors(original)
            val surfaces = listOf(colors.cardBackground, colors.scaffoldBackground) +
                LiquidGlass.lightSurfaceColors(colors, true).map { it.compositeOver(colors.scaffoldBackground) }
            for (text in listOf(colors.textPrimary, colors.textSecondary, colors.textMuted,
                LiquidGlass.lightContentColor(colors, colors.primaryAccent))) {
                surfaces.forEach { surface -> assertTrue("${preset.name}: text must remain readable", contrast(text, surface) >= 4.5f) }
            }
            for (selected in listOf(false, true)) {
                val outline = LiquidGlass.lightOutlineColor(colors, selected)
                for (surface in listOf(colors.cardBackground, colors.scaffoldBackground)) {
                    assertTrue("${preset.name}: continuous outline", contrast(outline, surface) >= if (selected) 3f else 1.5f)
                }
            }
            assertEquals(original.primaryAccent, colors.primaryAccent)
            assertEquals(original.cardBackground, colors.cardBackground)
        }
    }

    @Test fun customPaleAccentsAndInvisibleBordersAreDerivedWithoutOverwritingThePalette() {
        val original = LightAppColors.copy(primaryAccent = Color.White, cardBorder = Color.White,
            textMuted = Color(0xffdddddd), cardBackground = Color(0xfffff5dd), scaffoldBackground = Color(0xfffffaef))
        val prepared = LiquidGlass.settingsColors(original)
        assertNotEquals(original.cardBorder, prepared.cardBorder)
        assertNotEquals(original.textMuted, prepared.textMuted)
        assertEquals(Color.White, prepared.primaryAccent)
        assertEquals(original.cardBackground, prepared.cardBackground)
        assertTrue(contrast(LiquidGlass.lightOutlineColor(prepared, true), prepared.cardBackground) >= 3f)
        assertTrue(contrast(prepared.textMuted, prepared.cardBackground) >= 4.5f)
        assertTrue(LiquidGlass.lightSurfaceColors(prepared, false).all { it.red > it.blue })
    }

    @Test fun settingsFinishDoesNotChangeDarkAndAmoledPaletteValues() {
        ThemePresets.darkPresets.forEach { preset ->
            val colors = resolveAppThemeColors(true, preset.toCustomThemeState())
            assertEquals(colors.copy(settingsSurface = true), LiquidGlass.settingsColors(colors))
            assertEquals(colors.primaryAccent, LiquidGlass.lightContentColor(colors, colors.primaryAccent))
        }
    }
}
