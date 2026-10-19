package com.example

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ui.components.AnimatedBottomBar
import com.example.ui.components.AnimatedBottomBarItem
import com.example.ui.components.LocalLiquidGlassBackdrop
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.rememberLiquidGlassBackdrop
import com.example.ui.components.xtremeSharedBackdropSource
import com.example.ui.theme.DarkAppColors
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockGlassMotionTest {
    @get:Rule val compose = createComposeRule()
    private var selected = 0
    private var clicks = 0
    private var compositions = 0
    private var measures = 0
    private val dock get() = compose.onNodeWithTag("floating_bottom_navigation_bar")

    private fun showDock(glass: Boolean = true, withBackdrop: Boolean = false, owner: LifecycleOwner? = null,
        direction: LayoutDirection = LayoutDirection.Ltr, initialSelection: Int = 0) {
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            val tab = remember { mutableIntStateOf(initialSelection) }
            val backdrop = if (withBackdrop) rememberLiquidGlassBackdrop() else null
            SideEffect { compositions++ }
            CompositionLocalProvider(LocalLiquidGlassBackdrop provides backdrop,
                LocalDynamicGlassEnabled provides glass,
                LocalLayoutDirection provides direction,
                LocalLifecycleOwner provides (owner ?: LocalLifecycleOwner.current)) {
            Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                if (withBackdrop) Box(Modifier.fillMaxSize().xtremeSharedBackdropSource()
                    .background(Color(0xFF254963)))
                AnimatedBottomBar(tab.intValue, {
                    selected = it
                    clicks++
                    tab.intValue = it
                }, remember { listOf(
                    AnimatedBottomBarItem(0, "Home", Icons.Filled.Home, Icons.Filled.Home),
                    AnimatedBottomBarItem(1, "Search", Icons.Filled.Search, Icons.Filled.Search),
                    AnimatedBottomBarItem(2, "Library", Icons.Filled.LibraryMusic, Icons.Filled.LibraryMusic)
                ) }, modifier = Modifier.layout { measurable, constraints ->
                    measures++
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }, appColors = DarkAppColors.copy(dynamicGlassEnabled = glass))
            }
            }
        } }
        compose.waitForIdle()
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test fun outerCapsuleStaysCircularAfterEveryTabChangesTheHardwareLayerWidth() {
        showDock(glass = false, initialSelection = -1)
        val directory = java.io.File("build/reports/dock-shape").apply { mkdirs() }
        fun assertCapsule(name: String) {
            val file = java.io.File(directory, "$name.png")
            dock.captureRoboImage(file.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            val pixels = javax.imageio.ImageIO.read(file)
            val radius = pixels.height / 2f
            for (fraction in listOf(.125f, .1875f, .25f)) {
                val y = (pixels.height * fraction).toInt()
                val centerY = y + .5f
                val expected = radius - sqrt(radius * radius - (radius - centerY) * (radius - centerY))
                fun filled(x: Int): Boolean {
                    val pixel = java.awt.Color(pixels.getRGB(x, y), true)
                    return pixel.red < 100 && pixel.green < 100 && pixel.blue < 100
                }
                val left = (0 until pixels.width / 3).firstOrNull(::filled)
                val right = (0 until pixels.width / 3).firstOrNull { filled(pixels.width - 1 - it) }
                assertNotNull("$name: missing left capsule edge", left)
                assertNotNull("$name: missing right capsule edge", right)
                assertEquals("$name: left cap must stay circular at row $y", expected, left!!.toFloat(), 1.5f)
                assertEquals("$name: right cap must stay circular at row $y", expected, right!!.toFloat(), 1.5f)
            }
        }
        assertCapsule("inactive")
        for (title in listOf("home", "search", "library", "home", "library", "search")) {
            compose.onNodeWithTag("nav_tab_${title}_inactive").performClick()
            compose.mainClock.advanceTimeBy(1600)
            assertCapsule(title)
        }
    }

    @Test fun tabSpringsChangeLayersWithoutRemeasuringTheDock() {
        showDock()
        val layoutSize = dock.fetchSemanticsNode().size
        for (title in listOf("search", "library", "home")) {
            compose.onNodeWithTag("nav_tab_${title}_inactive").performClick()
            compose.mainClock.advanceTimeByFrame()
            val measured = measures
            repeat(35) {
                compose.mainClock.advanceTimeByFrame()
                assertEquals("Selection frames must not remeasure ancestors", measured, measures)
                assertEquals(layoutSize, dock.fetchSemanticsNode().size)
            }
            compose.mainClock.advanceTimeBy(1200)
        }
    }

    @Test fun retainedEndpointsAndTouchTargetsAlsoWorkInRtl() {
        showDock(direction = LayoutDirection.Rtl)
        val home = compose.onNodeWithTag("nav_tab_home_active").fetchSemanticsNode().boundsInRoot
        val library = compose.onNodeWithTag("nav_tab_library_inactive").fetchSemanticsNode().boundsInRoot
        assertTrue(home.center.x > library.center.x)
        compose.onNodeWithTag("nav_tab_library_inactive").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(1600)
        assertEquals(2, selected)
        compose.onNodeWithTag("nav_tab_library_active").assertIsSelected()
        // The label/icon inverse layer must not leave the icon stretched by tab expansion.
        val icon = compose.onNodeWithContentDescription("Library", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(icon.width, icon.height, 0.6f)
        compose.onNodeWithTag("nav_tab_home_inactive").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(1600)
        assertEquals(0, selected)
    }

    @Test fun holdingTabMovesWholeOuterDockWithoutResizingLayoutOrRecomposingItsHost() {
        showDock()
        val initialLayoutSize = dock.fetchSemanticsNode().size
        val initialDock = dock.fetchSemanticsNode().boundsInRoot
        val initialPill = compose.onNodeWithTag("nav_tab_home_active").fetchSemanticsNode().boundsInRoot
        val initialCompositions = compositions
        compose.onNodeWithTag("nav_tab_home_active").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(180)
        assertEquals("Pressing must not remeasure the dock", initialLayoutSize, dock.fetchSemanticsNode().size)
        val heldDock = dock.fetchSemanticsNode().boundsInRoot
        val heldPill = compose.onNodeWithTag("nav_tab_home_active").fetchSemanticsNode().boundsInRoot
        assertTrue("The outer capsule expands, rather than only shrinking the tapped tab",
            heldDock.width > initialDock.width * 1.08f)
        assertTrue(heldDock.height > initialDock.height * 1.08f)
        assertEquals("The foreground follows the outer capsule's hardware layer",
            heldDock.width / initialDock.width,
            heldPill.width / initialPill.width, 0.015f)
        assertEquals(initialDock.center.x, heldDock.center.x, 0.5f)
        assertEquals(initialDock.center.y, heldDock.center.y, 0.5f)
        assertEquals("Touch frames must not recompose the navigation host", initialCompositions, compositions)
        assertEquals("A down event must not navigate", 0, clicks)

        compose.onNodeWithTag("nav_tab_home_active").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1600)
        val settled = dock.fetchSemanticsNode().boundsInRoot
        assertEquals(initialDock.width, settled.width, 0.5f)
        assertEquals(initialDock.height, settled.height, 0.5f)
        assertEquals(1, clicks)
        assertEquals(0, selected)
    }

    @Test fun cancelledTouchSettlesWithoutSelectingAndAllSixTabRoutesCanRetargetDuringMotion() {
        showDock()
        val initial = dock.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("nav_tab_search_inactive").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(120)
        compose.onNodeWithTag("nav_tab_search_inactive").performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(1600)
        assertEquals(0, clicks)
        assertEquals(initial.width, dock.fetchSemanticsNode().boundsInRoot.width, 0.5f)
        val titles = listOf("home", "search", "library")
        for (target in listOf(1, 2, 0, 2, 1, 0)) {
            compose.onNodeWithTag("nav_tab_${titles[target]}_inactive").performTouchInput { click() }
            compose.mainClock.advanceTimeBy(120)
            compose.onNodeWithTag("nav_tab_${titles[target]}_active").assertIsSelected()
            assertEquals(target, selected)
        }
        compose.mainClock.advanceTimeBy(1800)
        assertEquals(6, clicks)
        assertEquals(initial.width, dock.fetchSemanticsNode().boundsInRoot.width, 0.5f)
    }

    @Test fun movingOutOfTabDoesNotStealClickAndGlassDisabledKeepsExistingOuterGeometry() {
        showDock(glass = false)
        val initial = dock.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("nav_tab_home_active").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(180)
        assertEquals(initial.width, dock.fetchSemanticsNode().boundsInRoot.width, 0.5f)
        assertEquals(initial.height, dock.fetchSemanticsNode().boundsInRoot.height, 0.5f)
        compose.onNodeWithTag("nav_tab_home_active").performTouchInput {
            moveTo(Offset(center.x, -100f))
            up()
        }
        compose.mainClock.advanceTimeBy(1600)
        assertEquals(0, clicks)
        compose.onNodeWithTag("nav_tab_search_inactive").performClick()
        compose.mainClock.advanceTimeBy(1600)
        compose.onNodeWithTag("nav_tab_search_active").assertIsSelected()
        assertEquals(1, selected)
    }

    @Test fun liveBackdropRemainsUsableAndPausingMidPressResetsTheDockBeforeResume() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        showDock(withBackdrop = true, owner = owner)
        val initial = dock.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("nav_tab_home_active").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        assertTrue(dock.fetchSemanticsNode().boundsInRoot.width > initial.width * 1.08f)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(initial.width, dock.fetchSemanticsNode().boundsInRoot.width, 0.5f)
        compose.onNodeWithTag("nav_tab_home_active").performTouchInput { cancel() }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(1600)
        assertEquals(0, clicks)
        compose.onNodeWithTag("nav_tab_search_inactive").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(1600)
        compose.onNodeWithTag("nav_tab_search_active").assertIsSelected()
        assertEquals(1, selected)
    }
}
