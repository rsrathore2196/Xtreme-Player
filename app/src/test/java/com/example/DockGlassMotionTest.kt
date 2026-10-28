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
import com.example.ui.theme.LightAppColors
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
    private val outerGlass get() = compose.onNodeWithTag("bottom_bar_outer_glass", useUnmergedTree = true)

    private fun showDock(glass: Boolean = true, withBackdrop: Boolean = false, owner: LifecycleOwner? = null,
        direction: LayoutDirection = LayoutDirection.Ltr, initialSelection: Int = 0, light: Boolean = false,
        compact: Boolean = false) {
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
                }, appColors = (if (light) LightAppColors else DarkAppColors).copy(dynamicGlassEnabled = glass),
                    compactProgress = if (compact) remember { androidx.compose.runtime.mutableFloatStateOf(1f) } else null,
                    compact = compact, compactTabIndex = 0, compactCompanionTabIndex = 1)
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

    @Test fun selectionUsesARestrainedFastSpringWithoutAnExtraOuterPulse() = checkSelectionMotion()

    @Test fun aTabTapSpringScalesTheOuterCapsuleAndItsContentTogetherThenSettles() = checkTouchSpring()

    @Test fun lightThemeKeepsTheSameTouchSpring() = checkTouchSpring(light = true)

    @Test fun disablingGlassDoesNotDisableTheSpatialTouchSpring() = checkTouchSpring(glass = false)

    private fun checkTouchSpring(light: Boolean = false, glass: Boolean = true) {
        showDock(light = light, glass = glass)
        val layout = dock.fetchSemanticsNode().size
        val restingHeight = dock.fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("nav_tab_search_inactive").performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        val measureCount = measures
        val compositionCount = compositions
        val scales = mutableListOf<Float>()
        fun sample() {
            val bounds = dock.fetchSemanticsNode().boundsInRoot
            val outer = outerGlass.fetchSemanticsNode().boundsInRoot
            scales += bounds.height / restingHeight
            assertEquals("The glass and foreground share the same spring", bounds.height, outer.height, .1f)
            assertEquals("Touch feedback cannot remeasure ancestors", measureCount, measures)
            assertEquals("Touch feedback cannot recompose the host", compositionCount, compositions)
            assertEquals(layout, dock.fetchSemanticsNode().size)
            val icon = compose.onNodeWithContentDescription("Search", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertEquals("Uniform spring scaling must not stretch icons", icon.width, icon.height, .6f)
        }
        repeat(5) { compose.mainClock.advanceTimeByFrame(); sample() }
        compose.onRoot().performTouchInput { up() }
        repeat(65) { compose.mainClock.advanceTimeByFrame(); sample() }
        assertTrue("The capsule must visibly respond to a tab tap", scales.max() > 1.015f)
        assertTrue("The touch spring must stay restrained", scales.max() < 1.05f)
        assertTrue("Release must retain the small spring rebound", scales.min() < .9995f)
        assertEquals("The original resting height is preserved", 1f, scales.last(), .0001f)
        assertEquals(1, selected)
        assertEquals("One pointer tap must select once", 1, clicks)
    }

    @Test fun lightThemeAndGlassDisabledKeepTheSameSelectionMotion() = checkSelectionMotion(light = true, glass = false)

    private fun checkSelectionMotion(light: Boolean = false, glass: Boolean = true) {
        showDock(light = light, glass = glass)
        val originalSize = dock.fetchSemanticsNode().size
        for (title in listOf("search", "library", "home")) {
            val initialWidth = compose.onNodeWithTag("nav_tab_${title}_inactive").fetchSemanticsNode().boundsInRoot.width
            compose.onNodeWithTag("nav_tab_${title}_inactive").performClick()
            compose.mainClock.advanceTimeByFrame()
            val measureCount = measures
            val compositionCount = compositions
            val widths = mutableListOf<Float>()
            repeat(40) {
                compose.mainClock.advanceTimeByFrame()
                val parent = dock.fetchSemanticsNode().boundsInRoot
                val outer = outerGlass.fetchSemanticsNode().boundsInRoot
                val scale = outer.height / parent.height
                widths += compose.onNodeWithTag("nav_tab_${title}_active").fetchSemanticsNode().boundsInRoot.width
                assertEquals("No extra outer inflation or press bounce", 1f, scale, .001f)
                assertEquals("The whole outer glass scales uniformly", scale, outer.width / parent.width, .001f)
                assertEquals("The capsule stays centered horizontally", parent.center.x, outer.center.x, .5f)
                assertEquals("The capsule stays centered vertically", parent.center.y, outer.center.y, .5f)
                assertEquals("Outer selection motion must not remeasure the dock", measureCount, measures)
                assertEquals("Outer selection motion must not recompose its host", compositionCount, compositions)
                assertEquals(originalSize, dock.fetchSemanticsNode().size)
            }
            val finalWidth = widths.last()
            val journey = finalWidth - initialWidth
            assertTrue("The selected pill should expand", journey > 10f)
            assertTrue("The width change should be mostly complete after about 120ms", (widths[6] - initialWidth) / journey > .8f)
            assertTrue("The width spring should have only a restrained overshoot", widths.max() <= finalWidth + journey * .025f)
            assertEquals("The pill should finish its settle without a long tail", finalWidth, widths[34], .1f)
            val parent = dock.fetchSemanticsNode().boundsInRoot
            val outer = outerGlass.fetchSemanticsNode().boundsInRoot
            assertEquals("Selection returns to the existing resting geometry", parent.height, outer.height, .1f)
            compose.onNodeWithTag("nav_tab_${title}_active").assertIsSelected()
        }
        assertEquals(3, clicks)
    }

    @Test fun rapidSelectionsRetargetTheOuterGlassContinuouslyWithoutStretchingContent() {
        showDock()
        val titles = listOf("home", "search", "library")
        var previousScale = 1f
        for (target in listOf(1, 2, 0, 2, 1, 0)) {
            compose.onNodeWithTag("nav_tab_${titles[target]}_inactive").performClick()
            repeat(7) {
                compose.mainClock.advanceTimeByFrame()
                val outer = outerGlass.fetchSemanticsNode().boundsInRoot
                val parent = dock.fetchSemanticsNode().boundsInRoot
                val scale = outer.height / parent.height
                assertTrue("Retargeting must not jump the outer surface", kotlin.math.abs(scale - previousScale) < .015f)
                previousScale = scale
                for (title in titles) {
                    val icon = compose.onNodeWithContentDescription(title.replaceFirstChar { it.uppercase() },
                        useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    assertEquals("Only the outer glass gets the new motion", icon.width, icon.height, .6f)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1600)
        val parent = dock.fetchSemanticsNode().boundsInRoot
        assertEquals(parent.height, outerGlass.fetchSemanticsNode().boundsInRoot.height, .1f)
        assertEquals(6, clicks)
        assertEquals(0, selected)
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
        assertEquals("A pointer tap must navigate once", 1, clicks)
        // The label/icon inverse layer must not leave the icon stretched by tab expansion.
        val icon = compose.onNodeWithContentDescription("Library", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(icon.width, icon.height, 0.6f)
        compose.onNodeWithTag("nav_tab_home_inactive").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(1600)
        assertEquals(0, selected)
    }

    @Test fun holdingTheActiveTabUsesOneSmallUniformSpringWithoutChangingTheLayout() {
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
        val scale = heldDock.height / initialDock.height
        assertTrue("The outer capsule follows the restrained touch spring", scale in 1.02f..1.05f)
        assertEquals("Touch feedback is uniform", scale, heldDock.width / initialDock.width, .001f)
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
        compose.onNodeWithTag("nav_tab_home_active").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(120)
        compose.onNodeWithTag("nav_tab_home_active").performTouchInput { cancel() }
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

    @Test fun movingOutOfTabDoesNotStealClickAndGlassDisabledKeepsItsRestingGeometry() {
        showDock(glass = false)
        val initial = dock.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("nav_tab_home_active").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(180)
        val pressed = dock.fetchSemanticsNode().boundsInRoot
        assertTrue(pressed.width / initial.width in 1.02f..1.05f)
        assertEquals(pressed.width / initial.width, pressed.height / initial.height, .001f)
        compose.onNodeWithTag("nav_tab_home_active").performTouchInput {
            moveTo(Offset(center.x, -100f))
            up()
        }
        compose.mainClock.advanceTimeBy(1600)
        assertEquals(0, clicks)
        assertEquals(initial.width, dock.fetchSemanticsNode().boundsInRoot.width, .1f)
        assertEquals(initial.height, dock.fetchSemanticsNode().boundsInRoot.height, .1f)
        compose.onNodeWithTag("nav_tab_search_inactive").performClick()
        compose.mainClock.advanceTimeBy(1600)
        compose.onNodeWithTag("nav_tab_search_active").assertIsSelected()
        assertEquals(1, selected)
    }

    @Test fun touchDownSelectsImmediatelyAndDraggingRetargetsWithoutDuplicateClicks() {
        showDock()
        compose.onNodeWithTag("nav_tab_search_inactive").performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("Selection reacts to down, without waiting for finger-up", 1, selected)
        assertEquals(1, clicks)
        val target = compose.onNodeWithTag("nav_tab_library_inactive").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { moveTo(target) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(2, selected)
        assertEquals(2, clicks)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(800)
        assertEquals("Releasing must not repeat the original tab's click", 2, clicks)
        compose.onNodeWithTag("nav_tab_home_inactive").performClick()
        compose.mainClock.advanceTimeBy(800)
        assertEquals("Accessibility/semantics clicks keep working after a drag", 0, selected)
        assertEquals(3, clicks)
        compose.onNodeWithTag("nav_tab_search_inactive").performTouchInput { click() }
        assertEquals(1, selected)
        assertEquals("A quick touch selects exactly once", 4, clicks)
    }

    @Test fun compactTouchSelectionKeepsHiddenTabsInactive() {
        showDock(compact = true)
        compose.onNodeWithTag("nav_tab_library_inactive").assertDoesNotExist()
        compose.onNodeWithTag("nav_tab_search_inactive").performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, selected)
        assertEquals(1, clicks)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(800)
        assertEquals(1, clicks)
        compose.onNodeWithTag("nav_tab_home_inactive").performTouchInput { click() }
        assertEquals(0, selected)
        assertEquals(2, clicks)
    }

    @Test fun cancellingANewTabPressDoesNotUndoOrRepeatItsImmediateSelection() {
        showDock(glass = false)
        compose.onNodeWithTag("nav_tab_search_inactive").performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, selected)
        compose.onRoot().performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(800)
        assertEquals(1, clicks)
        compose.onNodeWithTag("nav_tab_home_inactive").performClick()
        assertEquals(0, selected)
        assertEquals(2, clicks)
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
        assertTrue("Live glass follows the same bounded touch spring",
            dock.fetchSemanticsNode().boundsInRoot.width / initial.width in 1.02f..1.05f)
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
