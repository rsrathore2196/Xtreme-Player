package com.example

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AnimatedBottomBar
import com.example.ui.components.AnimatedBottomBarItem
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.theme.DarkAppColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockSelectionMotionParityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun layeredDockMatchesIndependentPaddingAndLabelSprings() {
        val selection = mutableIntStateOf(0)
        val titles = listOf("Home", "Search", "Library")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDynamicGlassEnabled provides false) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedBottomBar(selection.intValue, { selection.intValue = it }, remember { listOf(
                            AnimatedBottomBarItem(0, "Home", Icons.Filled.Home, Icons.Filled.Home),
                            AnimatedBottomBarItem(1, "Search", Icons.Filled.Search, Icons.Filled.Search),
                            AnimatedBottomBarItem(2, "Library", Icons.Filled.LibraryMusic, Icons.Filled.LibraryMusic)
                        ) }, appColors = DarkAppColors.copy(dynamicGlassEnabled = false))
                        // An ordinary measured Row is an independent oracle for the
                        // layer-only implementation's padding and label-size physics.
                        Row(Modifier.testTag("measured_dock").height(64.dp).background(Color.Black)
                            .padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            titles.forEachIndexed { index, title ->
                                val selected = selection.intValue == index
                                val padding by animateDpAsState(if (selected) 16.dp else 14.dp,
                                    spring(dampingRatio = .8f, stiffness = 650f), label = "measured_padding_$index")
                                Row(Modifier.testTag("measured_$index").height(52.dp)
                                    .padding(horizontal = padding), verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.size(24.dp))
                                    AnimatedVisibility(selected,
                                        enter = fadeIn(spring(dampingRatio = .8f, stiffness = 650f)) +
                                            expandHorizontally(spring(dampingRatio = .8f, stiffness = 650f),
                                                expandFrom = Alignment.Start, clip = false),
                                        exit = fadeOut(tween(90)) +
                                            shrinkHorizontally(spring(dampingRatio = .8f, stiffness = 650f),
                                                shrinkTowards = Alignment.Start, clip = false)) {
                                        Text(title, Modifier.padding(start = 6.dp), fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp, letterSpacing = .2.sp, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        var maximumDifference = 0f
        for (target in listOf(1, 2, 0)) {
            compose.runOnIdle { selection.intValue = target }
            repeat(40) {
                compose.mainClock.advanceTimeByFrame()
                val actual = compose.onNodeWithTag("floating_bottom_navigation_bar").fetchSemanticsNode().boundsInRoot.width
                val expected = compose.onNodeWithTag("measured_dock").fetchSemanticsNode().boundsInRoot.width
                val pill = compose.onNode(hasTestTag("nav_tab_${titles[target].lowercase()}_active") or
                    hasTestTag("nav_tab_${titles[target].lowercase()}_inactive")).fetchSemanticsNode().boundsInRoot.width
                val expectedPill = compose.onNodeWithTag("measured_$target").fetchSemanticsNode().boundsInRoot.width
                maximumDifference = maxOf(maximumDifference, kotlin.math.abs(actual - expected), kotlin.math.abs(pill - expectedPill))
            }
            compose.mainClock.advanceTimeBy(1000)
        }
        assertTrue("Layer geometry must preserve the measured padding/label spring (difference=$maximumDifference px)",
            maximumDifference < 2f)
    }
}
