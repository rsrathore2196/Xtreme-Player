package com.example

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.example.data.local.ThemePresets
import com.example.ui.screens.settings.*
import com.example.ui.theme.*
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemePresetUiTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(tag: String, name: String): java.awt.image.BufferedImage {
        val file = File("build/reports/theme-review/$name.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag(tag, useUnmergedTree = true).captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return ImageIO.read(file)
    }

    @Test fun presetSelectionUpdatesTheRenderedPreviewWithoutChangingTheGridNavigation() {
        var selected by mutableStateOf(ThemePresets.StudioNight)
        compose.setContent {
            val colors = resolveAppThemeColors(selected.isDark, selected.toCustomThemeState())
            MaterialTheme(colorScheme = resolveColorScheme(colors.isDark, colors)) {
                CompositionLocalProvider(LocalAppColors provides LiquidGlass.settingsColors(colors)) {
                    Box(Modifier.fillMaxSize().background(colors.scaffoldBackground)) {
                        ThemePresetsPage(selected.toCustomThemeState(), { selected = it })
                    }
                }
            }
        }
        compose.onNodeWithText("Light", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Cherry Blossom").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ThemePresets.CherryBlossom, selected)
        val image = capture("theme_preview_${selected.id}", "light-preset")
        assertEquals(java.awt.Color.WHITE.rgb, image.getRGB(image.width / 2, 6))
        compose.onNodeWithText("Dark", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Studio Night").performScrollTo().performClick()
        assertEquals(ThemePresets.StudioNight, selected)
    }

    @Test fun settingsUsesNeutralGlassWithReadableLabelsAndFunctionalAccentControls() {
        var preset by mutableStateOf(ThemePresets.EmeraldNebula)
        var checked by mutableStateOf(true)
        var opens = 0
        compose.setContent {
            val colors = LiquidGlass.settingsColors(resolveAppThemeColors(preset.isDark, preset.toCustomThemeState()))
            MaterialTheme(colorScheme = resolveColorScheme(colors.isDark, colors)) {
                CompositionLocalProvider(LocalAppColors provides colors) {
                    Column(Modifier.fillMaxWidth().background(colors.scaffoldBackground)
                        .padding(16.dp).testTag("settings_theme_surface")) {
                        SettingsCategoryCardGroup(listOf(SettingsCategory.THEMES_APP_UI,
                            SettingsCategory.MUSIC_PLAYBACK), colors.isDark, { opens++ })
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp)
                            .liquidGlassCard(colors, tintAccent = true).padding(16.dp)) {
                            Text("Example control", color = colors.textPrimary, modifier = Modifier.weight(1f))
                            Switch(checked, { checked = it }, colors = liquidGlassSwitchColors(colors),
                                modifier = Modifier.testTag("example_theme_switch"))
                        }
                    }
                }
            }
        }
        for (theme in listOf(ThemePresets.EmeraldNebula, ThemePresets.CherryBlossom)) {
            compose.runOnIdle { preset = theme }
            val colors = resolveAppThemeColors(theme.isDark, theme.toCustomThemeState())
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Example control").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(colors.textPrimary, layouts.single().layoutInput.style.color)
            val image = capture("settings_theme_surface", if (theme.isDark) "settings-dark" else "settings-light")
            // Sample an unoccupied section of the category card: its glass must not pick up the accent hue.
            val pixel = java.awt.Color(image.getRGB(image.width / 2, 12))
            assertTrue(maxOf(pixel.red, pixel.green, pixel.blue) - minOf(pixel.red, pixel.green, pixel.blue) <= 2)
        }
        compose.onNodeWithText("Appearance").performClick()
        assertEquals(1, opens)
        compose.onNodeWithTag("example_theme_switch").performClick()
        assertFalse(checked)
    }
}
