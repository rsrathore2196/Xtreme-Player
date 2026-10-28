package com.example

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.core.graphics.ColorUtils
import com.example.data.local.ListeningPeriod
import com.example.data.local.ListeningTotal
import com.example.data.local.UserProfile
import com.example.data.local.UserProfilePreferences
import com.example.data.repository.ListeningSummary
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.ListeningStatsContent
import com.example.ui.screens.ListeningStatsShareDialog
import com.example.ui.theme.*
import com.example.ui.util.*
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningStatsSharingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val song = ListeningTotal("o", "2026-10", "song", "song", "one", "Favorite Song", "Main Artist",
        "UC_exact", "", 120_000, "", 90_000, 2)
    private val summary = ListeningSummary(90_000, 2, listOf(song), listOf(song.copy(kind = "artist")))

    @Before fun resetProviderRootsForTheFreshTestApplication() {
        val roots = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (roots.get(null) as MutableMap<*, *>).clear()
    }

    @Test fun shareActionIsLastAndFreezesTheCurrentPeriodAndActualSummary() {
        var selected by mutableStateOf(ListeningPeriod.WEEKLY)
        var shared: Pair<ListeningSummary, ListeningPeriod>? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                ListeningStatsContent(summary, selected, { selected = it }, {}, { _, _ -> }, {},
                    onShare = { snapshot, period -> shared = snapshot to period })
            }
        } }
        compose.onNodeWithText("Monthly").performClick()
        compose.onNodeWithTag("listening_stats_page").performScrollToNode(hasTestTag("share_listening_stats"))
        compose.onNodeWithTag("share_listening_stats").assertIsDisplayed().performClick()
        assertEquals(summary to ListeningPeriod.MONTHLY, shared)
    }

    @Test fun squareCardInteriorsFollowTheAccentAndRemainReadableInDarkLightAndCustomThemes() {
        var palette by mutableStateOf(DarkAppColors.copy(primaryAccent = Color(0xFF209BFF)))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides palette, LocalDynamicGlassEnabled provides false,
                LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                ListeningStatsContent(summary, ListeningPeriod.WEEKLY, {}, {}, { _, _ -> }, {})
            }
        } }
        fun capture(label: String): Int {
            val tags = listOf("Minutes Played", "Top Song", "Top Artist", "Songs Played")
            tags.forEach { tag ->
                val bounds = compose.onNodeWithTag("stats_card_$tag").fetchSemanticsNode().boundsInRoot
                assertEquals(bounds.width, bounds.height, 1f)
                compose.onNodeWithTag("stats_logo_$tag").assertIsDisplayed()
            }
            val file = File("/tmp/listening-stats-cards-$label.png")
            compose.onNodeWithTag("listening_stats_page").captureRoboImage(file.absolutePath,
                RoborazziOptions(taskType = RoborazziTaskType.Record))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("1.5").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val foreground = layouts.single().layoutInput.style.color.toArgb()
            val saved = if (palette.neutralSurfaces) palette.cardBackgroundElevated else palette.cardBackground
            val base = statsCardBase(palette.primaryAccent, saved)
            for (surface in listOf(base, statsCardHighlight(palette.primaryAccent, base))) {
                assertTrue("Card text must retain at least normal text contrast", ColorUtils.calculateContrast(foreground, surface.toArgb()) >= 4.5)
            }
            val bounds = compose.onNodeWithTag("stats_card_Minutes Played").fetchSemanticsNode().boundsInRoot
            return ImageIO.read(file).getRGB((bounds.left + bounds.width * .30f).toInt(), (bounds.top + bounds.height * .4f).toInt())
        }
        val blue = capture("blue")
        compose.runOnIdle { palette = palette.copy(primaryAccent = Color(0xFFE745C2)) }
        val pink = capture("pink")
        assertNotEquals("The interior must change with the accent, not just its border", blue, pink)
        compose.runOnIdle { palette = LightAppColors.copy(primaryAccent = Color(0xFFFFDE75)) }
        capture("light")
        compose.runOnIdle { palette = palette.copy(neutralSurfaces = false, cardBackground = Color(0xFF63393E)) }
        capture("custom")
    }

    @Test fun amoledStatsCardsUseNearBlackNeutralSurfacesForWhiteAndColouredAccents() {
        var palette by mutableStateOf(DarkAppColors.copy(isAmoled = true, scaffoldBackground = Color.Black,
            cardBackground = Color.Black, primaryAccent = Color.White))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides palette, LocalDynamicGlassEnabled provides false,
                LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                ListeningStatsContent(summary, ListeningPeriod.WEEKLY, {}, {}, { _, _ -> }, {})
            }
        } }
        for ((name, accent) in listOf("white" to Color.White, "cyan" to Color.Cyan)) {
            compose.runOnIdle { palette = palette.copy(primaryAccent = accent) }
            for (label in listOf("Minutes Played", "Top Song", "Top Artist", "Songs Played")) {
                val file = File("/tmp/listening-stats-amoled-$name-$label.png")
                compose.onNodeWithTag("stats_card_$label").captureRoboImage(file.absolutePath,
                    RoborazziOptions(taskType = RoborazziTaskType.Record))
                val pixels = ImageIO.read(file)
                val pixel = java.awt.Color(pixels.getRGB((pixels.width * .85f).toInt(), (pixels.height * .4f).toInt()), true)
                assertTrue("$label: AMOLED interiors must stay near black", maxOf(pixel.red, pixel.green, pixel.blue) <= 48)
                assertTrue("$label: AMOLED interiors must be neutral grey", maxOf(pixel.red, pixel.green, pixel.blue) -
                    minOf(pixel.red, pixel.green, pixel.blue) <= 3)
                compose.onNodeWithTag("stats_logo_$label").assertIsDisplayed()
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("1.5").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Neutral AMOLED cards retain readable text", ColorUtils.calculateContrast(
            layouts.single().layoutInput.style.color.toArgb(), android.graphics.Color.rgb(48, 48, 48)) >= 7.0)
    }

    @Test fun popupSharesThePngAndSaveOpensTheAndroidImageDocumentPicker() {
        UserProfilePreferences.saveUserProfile(compose.activity, UserProfile(name = "Maya"))
        val request = ListeningStatsImageRequest(summary.copy(songs = emptyList(), artists = emptyList()),
            ListeningPeriod.MONTHLY, Color.Cyan.toArgb())
        compose.setContent { MaterialTheme { ListeningStatsShareDialog(request, {}) } }
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val node = compose.onAllNodesWithTag("share_stats_image").fetchSemanticsNodes().singleOrNull()
            node != null && !node.config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithTag("listening_stats_share_popup").assertIsDisplayed()
        compose.onNodeWithTag("share_stats_image").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No app is available to share the image.").assertDoesNotExist()
        var chooser: Intent? = null
        compose.waitUntil(3_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            chooser = shadowOf(compose.activity).nextStartedActivity
            chooser != null
        }
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        val share = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("image/png", share.type)
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        compose.waitUntil(3_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            !compose.onNodeWithTag("save_stats_image").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithTag("save_stats_image").performClick()
        var picker: Intent? = null
        compose.waitUntil(3_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            // Robolectric also records startActivity's chooser in this separate result queue.
            val next = shadowOf(compose.activity).nextStartedActivityForResult?.intent
            if (next?.action == Intent.ACTION_CREATE_DOCUMENT) picker = next
            picker != null
        }
        assertEquals("image/png", picker!!.type)
        assertEquals("Xtreme-Player-Monthly-Stats.png", picker!!.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test fun fullFeaturedNamesFitBelowTheirArtworkInTheSameSquareCards() = verifyFullNames(false)

    @Test @Config(qualifiers = "w360dp-h800dp-port")
    fun fullFeaturedNamesRemainReadableInNarrowWindowsWithLargerFonts() = verifyFullNames(true)

    private fun verifyFullNames(largeFont: Boolean) {
        val title = "Phir Aur Kya Chahiye (From Zara Hatke Zara Bachke)"
        val artist = "Arijit Singh and Sachin-Jigar"
        val row = song.copy(title = title, artist = artist)
        val data = summary.copy(songs = listOf(row), artists = listOf(row.copy(kind = "artist")))
        compose.setContent { MaterialTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAppColors provides if (largeFont) LightAppColors else DarkAppColors,
                LocalDensity provides Density(density.density, if (largeFont) 1.3f else density.fontScale),
                LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                ListeningStatsContent(data, ListeningPeriod.WEEKLY, {}, {}, { _, _ -> }, {})
            }
        } }
        for ((label, name) in listOf("Top Song" to title, "Top Artist" to artist)) {
            val card = compose.onNodeWithTag("stats_card_$label").fetchSemanticsNode().boundsInRoot
            val art = compose.onNodeWithTag("stats_artwork_$label").fetchSemanticsNode().boundsInRoot
            val text = compose.onNodeWithTag("stats_name_$label")
            val bounds = text.fetchSemanticsNode().boundsInRoot
            assertEquals(card.width, card.height, 1f)
            assertTrue("Artwork must be above the full name", art.bottom <= bounds.top + 1f)
            assertEquals("The name must start directly under the upper-left artwork", art.left, bounds.left, 1f)
            assertTrue("The complete name must stay inside the square card", bounds.bottom <= card.bottom - 2f)
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(name, layout.layoutInput.text.text)
            assertFalse(layout.hasVisualOverflow)
            assertTrue("Every line must be visible, not clipped by the card viewport", bounds.height >= layout.size.height - 1f)
            assertEquals(name.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
        }
        compose.onNodeWithTag("listening_stats_page").captureRoboImage("/tmp/listening-stats-full-names-" + largeFont + ".png",
            RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
