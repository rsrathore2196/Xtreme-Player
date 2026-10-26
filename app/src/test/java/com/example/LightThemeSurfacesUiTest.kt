package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.imageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.SuccessResult
import com.example.data.local.AppThemeMode
import com.example.data.local.ThemePresets
import com.example.data.model.MusicTrack
import com.example.playback.AudioEffectsState
import com.example.playback.PlayerUiState
import com.example.ui.components.*
import com.example.ui.screens.settings.SettingsCategory
import com.example.ui.screens.settings.SettingsCategoryCardGroup
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LightThemeSurfacesUiTest {
    @get:Rule val compose = createComposeRule()
    private val song = MusicTrack("surface", "Song title", "Artist", "Album", 180_000, "", "")
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var previousLoader: ImageLoader? = null
    private var testLoader: ImageLoader? = null

    @After fun restoreLoader() { previousLoader?.let(Coil::setImageLoader); testLoader?.shutdown() }

    private fun capture(tag: String, name: String): java.awt.image.BufferedImage {
        val file = java.io.File("build/reports/light-theme-review/$name.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return javax.imageio.ImageIO.read(file)
    }

    @Test fun darkAndAmoledReferenceSurfaces() {
        var colors by mutableStateOf(DarkAppColors)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors, LocalDynamicGlassEnabled provides true,
                LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                Column(Modifier.fillMaxSize().background(colors.scaffoldBackground).testTag("surface_reference")) {
                    InfiniteRadioHeroCard(null, null, false, onPlayInfiniteRadio = {})
                    SettingsCategoryCardGroup(listOf(SettingsCategory.THEMES_APP_UI), colors.isDark, {},
                        Modifier.padding(16.dp))
                    Box(Modifier.padding(16.dp).fillMaxWidth().height(64.dp)
                        .liquidGlassCard(colors, RoundedCornerShape(22.dp), tintAccent = true)) {
                        Text("Settings content", color = colors.textPrimary, modifier = Modifier.padding(16.dp))
                    }
                    MiniPlayer(PlayerUiState(currentTrack = song), {}, {}, {}, {}, modifier = Modifier.padding(16.dp))
                    FloatingPillBottomBar(0, {})
                }
            }
        } }
        compose.mainClock.advanceTimeBy(1600)
        capture("surface_reference", "dark-reference")
        compose.runOnIdle { colors = DarkAppColors.copy(isAmoled = true, scaffoldBackground = Color.Black) }
        compose.mainClock.advanceTimeBy(1600)
        capture("surface_reference", "amoled-reference")
        compose.onNodeWithText("Settings content").assertIsDisplayed()
        compose.onNodeWithTag("mini_player_play_pause").assertHasClickAction()
        compose.onNodeWithTag("nav_tab_home_active").assertIsSelected()
    }

    private fun assertUnmodifiedForeground() {
        val foreground = Color(0xff142136)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides LightAppColors, LocalDynamicGlassEnabled provides true) {
                Column(Modifier.fillMaxSize().background(LightAppColors.scaffoldBackground)) {
                    val decorations = listOf(
                        "card" to Modifier.liquidGlassCard(LightAppColors, tintAccent = true),
                        "surface" to Modifier.liquidGlass(LightAppColors),
                        "button" to Modifier.liquidGlassButton(LightAppColors),
                        "active_button" to Modifier.liquidGlassButton(LightAppColors, isActive = true),
                        "primary" to Modifier.primaryLiquidGlassButton(LightAppColors),
                        "pill" to Modifier.liquidGlassPill(LightAppColors),
                        "active_pill" to Modifier.liquidGlassPill(LightAppColors, isActive = true))
                    decorations.forEach { (name, decoration) ->
                        Box(Modifier.padding(8.dp).size(160.dp, 64.dp).then(decoration).testTag("foreground_$name")) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp).background(foreground))
                        }
                    }
                }
            }
        } }
        for (name in listOf("card", "surface", "button", "active_button", "primary", "pill", "active_pill")) {
            val pixels = capture("foreground_$name", "light-foreground-$name")
            for (fraction in listOf(.1f, .25f, .5f, .8f)) {
                assertEquals("$name: glass must never paint over foreground content", foreground.toArgb(),
                    pixels.getRGB(pixels.width / 2, (pixels.height * fraction).toInt()))
            }
        }
    }

    @Test fun lightGlassDoesNotWashOutForegroundContent() = assertUnmodifiedForeground()

    @Test @Config(sdk = [31])
    fun lightGlassForegroundAlsoRemainsIntactOnAndroid12() = assertUnmodifiedForeground()

    @Test fun heroKeepsReadableWhiteTextOverBrightDarkAndMissingArtwork() {
        previousLoader = context.imageLoader
        val requests = java.util.concurrent.atomic.AtomicInteger()
        testLoader = ImageLoader.Builder(context).components {
            add(Interceptor { chain ->
                val bright = chain.request.data.toString().contains("bright")
                val bitmap = Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(if (bright) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                }
                requests.incrementAndGet()
                SuccessResult(BitmapDrawable(context.resources, bitmap), chain.request, DataSource.MEMORY_CACHE)
            })
        }.build().also(Coil::setImageLoader)
        var track by mutableStateOf<MusicTrack?>(song.copy(id = "bright", coverUrl = "https://lh3.googleusercontent.com/bright=w120-h120"))
        var glass by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides LightAppColors.copy(dynamicGlassEnabled = glass),
                LocalDynamicGlassEnabled provides glass) {
                InfiniteRadioHeroCard(track, null, false, onPlayInfiniteRadio = {})
            }
        } }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.waitUntil(3_000) { requests.get() > 0 }
        for (state in listOf("bright", "dark", "missing", "matte")) {
            compose.runOnIdle {
                track = if (state == "missing" || state == "matte") null
                    else song.copy(id = state, coverUrl = "https://lh3.googleusercontent.com/$state=w120-h120")
                glass = state != "matte"
            }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            for (label in listOf("Infinite Radio", "A fresh random mix · Endless discoveries")) {
                val layouts = mutableListOf<TextLayoutResult>()
                val text = compose.onNodeWithText(label, useUnmergedTree = true)
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(Color.White, layouts.single().layoutInput.style.color)
                val bounds = text.fetchSemanticsNode().boundsInRoot
                val hero = compose.onNodeWithTag("infinite_radio_hero_card").fetchSemanticsNode().boundsInRoot
                val image = capture("infinite_radio_hero_card", "light-hero-$state")
                val backdrop = Color(image.getRGB(image.width - 28,
                    (bounds.center.y - hero.top).toInt().coerceIn(0, image.height - 1)))
                assertTrue("$state: hero text needs a supporting scrim", 1.05f / (backdrop.luminance() + .05f) >= 4.5f)
            }
            compose.onNodeWithTag("hero_play_infinite_radio_button").assertHasClickAction()
        }
    }

    @Test @Config(sdk = [31])
    fun lightFloatingControlsStayTranslucentAndWorkAcrossTabsAndGlassModes() {
        var colors by mutableStateOf(LightAppColors)
        var background by mutableStateOf(Color.Black)
        var selected by mutableIntStateOf(0)
        var playing by mutableStateOf(false)
        var playTaps = 0
        var nextTaps = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            val backdrop = rememberLiquidGlassBackdrop()
            CompositionLocalProvider(LocalAppColors provides colors, LocalDynamicGlassEnabled provides colors.dynamicGlassEnabled,
                LocalLiquidGlassBackdrop provides backdrop, LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().xtremeSharedBackdropSource().background(background))
                    Column(Modifier.fillMaxSize()) {
                        MiniPlayer(PlayerUiState(currentTrack = song, isPlaying = playing), { playTaps++; playing = !playing },
                            { nextTaps++ }, {}, {}, modifier = Modifier.padding(16.dp))
                        FloatingPillBottomBar(selected, { selected = it })
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(1000)
        for (glass in listOf(true, false)) {
            compose.runOnIdle { colors = LightAppColors.copy(dynamicGlassEnabled = glass) }
            for (canvas in listOf(Color.Black, Color.White, Color(0xffe85080))) {
                compose.runOnIdle { background = canvas }
                // Publish the state write before settling the frozen test clock;
                // otherwise a capture can still contain the previous canvas.
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(1600)
                compose.waitForIdle()
                val mini = capture("mini_player_container", "light-mini-$glass-${canvas.toArgb()}")
                val fill = java.awt.Color(mini.getRGB(mini.width / 2, mini.height - 5))
                if (glass) {
                    val shell = compose.onNodeWithTag("mini_player_container").fetchSemanticsNode().boundsInRoot
                    for (label in listOf("Song title", "Artist")) {
                        val bounds = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                        val left = ((bounds.left - shell.left) * mini.width / shell.width).toInt()
                        val top = ((bounds.top - shell.top) * mini.height / shell.height).toInt()
                        val right = ((bounds.right - shell.left) * mini.width / shell.width).toInt().coerceAtMost(mini.width - 1)
                        val bottom = ((bounds.bottom - shell.top) * mini.height / shell.height).toInt().coerceAtMost(mini.height - 1)
                        val behind = Color(mini.getRGB((left - 3).coerceAtLeast(0), (top + bottom) / 2)).luminance()
                        var readable = 0
                        for (y in top.coerceAtLeast(0)..bottom) for (x in left.coerceAtLeast(0)..right) {
                            val luminance = Color(mini.getRGB(x, y)).luminance()
                            val ratio = (maxOf(luminance, behind) + .05f) / (minOf(luminance, behind) + .05f)
                            if (ratio >= 3f) readable++
                        }
                        assertTrue("$label needs plain contrasting ink over $canvas; found $readable", readable >= 12)
                        val layouts = mutableListOf<TextLayoutResult>()
                        compose.onNodeWithText(label, useUnmergedTree = true)
                            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, layouts.single().layoutInput.style.fontWeight)
                        assertNull("Labels must not have a glow or text shadow", layouts.single().layoutInput.style.shadow)
                    }
                }

                val dock = capture("floating_bottom_navigation_bar", "light-dock-$glass-${canvas.toArgb()}")
                val dockFill = java.awt.Color(dock.getRGB(dock.width / 2, dock.height - 5))
                for (sample in listOf(fill, dockFill)) {
                    when {
                        !glass -> assertTrue("Glass-disabled surfaces keep their theme fill",
                            minOf(sample.red, sample.green, sample.blue) > 195)
                        canvas == Color.Black -> assertTrue("Clear glass must not add an opaque white wash",
                            maxOf(sample.red, sample.green, sample.blue) < 110)
                        canvas == Color.White -> assertTrue("Clear glass must still reveal a bright canvas",
                            minOf(sample.red, sample.green, sample.blue) > 140)
                        else -> assertTrue("Artwork color must show through both floating surfaces",
                            sample.red - sample.green > 30)
                    }
                }
                for ((tab, index) in listOf("search" to 1, "library" to 2, "home" to 0)) {
                    compose.onNodeWithTag("nav_tab_${tab}_inactive").performClick()
                    compose.mainClock.advanceTimeBy(1600)
                    compose.onNodeWithTag("nav_tab_${tab}_active").assertIsSelected()
                    compose.runOnIdle { assertEquals(index, selected) }
                }
            }
            compose.onNodeWithTag("mini_player_play_pause").performClick()
            compose.onNodeWithTag("mini_player_next").performClick()
        }
        assertEquals(2, playTaps); assertEquals(2, nextTaps)
    }

    @Test fun settingsAndAppearanceKeepTheirThemeAndNavigation() {
        var colors by mutableStateOf(LightAppColors)
        var homeBacks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors, LocalDynamicGlassEnabled provides true) {
                SettingsScreen(AudioEffectsState(), false, AppThemeMode.LIGHT, null,
                    onSelectThemeMode = {}, onToggleDarkMode = {}, onCrystalClarityToggle = {},
                    onToggleEqualizer = {}, onOpenEqualizer = {}, onSelectPreset = {},
                    customThemeState = ThemePresets.CyberpunkNeon.toCustomThemeState(),
                    onNavigateBackToHome = { homeBacks++ })
            }
        } }
        compose.mainClock.advanceTimeBy(600)
        capture("settings_screen", "light-settings-overview")
        compose.onNodeWithTag("settings_cat_themes").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("theme_button_light").assertIsDisplayed()
        fun assertModeIconColors() {
            for (name in listOf("System", "Dark", "Light")) {
                val file = java.io.File.createTempFile("theme-mode-icon-", ".png")
                try {
                    compose.onNodeWithContentDescription(name, useUnmergedTree = true).captureRoboImage(file.absolutePath,
                        roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                    val icon = javax.imageio.ImageIO.read(file)
                    val expected = if (name == "Light") colors.primaryAccent.toArgb() else Color(0xff6b7280).toArgb()
                    val coloredPixels = (0 until icon.height).sumOf { y ->
                        (0 until icon.width).count { x ->
                            val actual = java.awt.Color(icon.getRGB(x, y))
                            val wanted = java.awt.Color(expected)
                            kotlin.math.abs(actual.red - wanted.red) <= 2 &&
                                kotlin.math.abs(actual.green - wanted.green) <= 2 &&
                                kotlin.math.abs(actual.blue - wanted.blue) <= 2
                        }
                    }
                    assertTrue("$name: only the active mode icon should use the accent; inactive icons stay grey", coloredPixels > 10)
                } finally { file.delete() }
            }
        }
        assertModeIconColors()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Light", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(LiquidGlass.lightContentColor(LiquidGlass.settingsColors(colors), colors.primaryAccent),
            layouts.single().layoutInput.style.color)
        capture("settings_screen", "light-settings-appearance")
        compose.runOnIdle { colors = resolveAppThemeColors(false, ThemePresets.SageMist.toCustomThemeState()) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("theme_button_light").assertIsDisplayed()
        capture("settings_screen", "light-settings-sage")
        assertModeIconColors()
        compose.runOnIdle { colors = LightAppColors.copy(primaryAccent = Color.White) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        assertModeIconColors()
        compose.onNodeWithTag("settings_back_button").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("settings_cat_themes").assertIsDisplayed()
        compose.onNodeWithTag("settings_back_button").performClick()
        assertEquals(1, homeBacks)
    }
}
