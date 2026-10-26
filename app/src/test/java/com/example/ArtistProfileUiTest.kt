package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.imageLoader
import coil.intercept.Interceptor
import coil.request.SuccessResult
import coil.decode.DataSource
import com.example.data.repository.*
import com.example.playback.PlayerUiState
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.screens.CatalogDetailScreen
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.viewmodel.CatalogDetailState
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
class ArtistProfileUiTest {
    @get:Rule val compose = createComposeRule()
    private var previousLoader: ImageLoader? = null
    private var testLoader: ImageLoader? = null
    @After fun restoreLoader() { previousLoader?.let(Coil::setImageLoader); testLoader?.shutdown() }

    @Test fun realArtistDetailsAndReleaseNavigationAreDisplayedWithoutChangingOtherPages() = details()

    @Test @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeDetailsKeepWorkingWithTheFullWidthPortrait() = details()

    private fun details() {
        val artist = CatalogArtist("UCmain", "Main Artist")
        val release = CatalogAlbum("MPRErelease", "Release", listOf(artist), "", "2026", CatalogProvider.YOUTUBE_MUSIC)
        val profile = CatalogArtistProfile(artist, "Official biography", "1.2M subscribers", "3.4M monthly listeners",
            releases = listOf(ArtistReleaseSection("Albums", listOf(release))))
        var opened: CatalogAlbum? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors, LocalArtworkRequestsActive provides false,
                LocalVisualEffectsActive provides false) {
                CatalogDetailScreen(CatalogDetailState(CatalogDestination.Artist(artist), artistProfile = profile),
                    PlayerUiState(), {}, {}, { _, _ -> }, {}, { _, _ -> }, onOpenAlbum = { opened = it })
            }
        } }
        compose.onAllNodesWithText("Main Artist").assertCountEquals(1)
        compose.onNodeWithTag("artist_profile_name").assertTextEquals("Main Artist").assertIsDisplayed()
        compose.onNodeWithText("Subscribers").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1.2M").assertIsDisplayed()
        compose.onNodeWithText("Monthly listeners").assertIsDisplayed()
        compose.onNodeWithText("3.4M").assertIsDisplayed()
        compose.onNodeWithText("Official biography").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Read more").performScrollTo().performClick()
        compose.onNodeWithText("Show less").assertIsDisplayed()
        // Scroll the vertical collection first. A prefetched LazyRow child can
        // exist before it is placed; scrolling that child only moves its row.
        compose.onNodeWithTag("catalog_detail_list").performScrollToIndex(3)
        compose.onNodeWithTag("artist_release_MPRErelease").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(release, opened) }
    }

    @Test @Config(qualifiers = "w1440dp-h900dp-land")
    fun largeLandscapeArtistHeaderUsesTheWholeAvailableWidth() {
        val artist = CatalogArtist("UCwide", "Wide Artist")
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors, LocalArtworkRequestsActive provides false,
                LocalVisualEffectsActive provides false) {
                CatalogDetailScreen(CatalogDetailState(CatalogDestination.Artist(artist)), PlayerUiState(), {}, {},
                    { _, _ -> }, {}, { _, _ -> })
            }
        } }
        assertEquals(compose.onRoot().fetchSemanticsNode().boundsInRoot.width,
            compose.onNodeWithTag("artist_profile_header").fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test fun officialPortraitFillsTheHeaderAndBlendsBothEdgesIntoThePage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        previousLoader = context.imageLoader
        testLoader = ImageLoader.Builder(context).components { add(Interceptor { chain ->
            SuccessResult(BitmapDrawable(context.resources, Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(android.graphics.Color.WHITE) }), chain.request, DataSource.MEMORY_CACHE)
        }) }.build().also(Coil::setImageLoader)
        val artist = CatalogArtist("UCportrait", "Portrait Artist", image = "https://yt3.googleusercontent.com/portrait=w1200-h1200")
        val canvas = Color(0xff183347)
        var backs = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(scaffoldBackground = canvas, dynamicGlassEnabled = false),
                LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false, LocalDynamicGlassEnabled provides false) {
                CatalogDetailScreen(CatalogDetailState(CatalogDestination.Artist(artist), artistProfile = CatalogArtistProfile(artist)),
                    PlayerUiState(), { backs++ }, {}, { _, _ -> }, {}, { _, _ -> })
            }
        } }
        fun capture(): java.awt.image.BufferedImage {
            val file = java.io.File.createTempFile("artist-header-", ".png")
            return try {
                compose.onNodeWithTag("artist_profile_header").captureRoboImage(file.absolutePath,
                    roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                javax.imageio.ImageIO.read(file)
            } finally { file.delete() }
        }
        compose.waitUntil(5_000) { val image = capture(); image.getRGB(image.width / 2, image.height / 2) == Color.White.toArgb() }
        val header = compose.onNodeWithTag("artist_profile_header").fetchSemanticsNode().boundsInRoot
        val page = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(page.width, header.width, 1f)
        val image = capture()
        assertEquals("Portrait must extend to both horizontal sides, not a circle", Color.White.toArgb(), image.getRGB(1, image.height / 2))
        assertEquals(Color.White.toArgb(), image.getRGB(image.width - 2, image.height / 2))
        for (y in listOf(1, image.height - 2)) assertEquals(canvas.toArgb(), image.getRGB(image.width / 2, y))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }
}
