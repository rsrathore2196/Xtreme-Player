package com.example

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ui.components.MainTabPager
import kotlinx.coroutines.launch
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
class PreparedTabArtworkUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun formerPreparationHostKeepsUnopenedTabsLazyWhileHomeIsLoading() {
        val mounts = IntArray(3)
        compose.setContent { MaterialTheme {
            val pager = rememberPagerState(pageCount = { 3 })
            val scope = rememberCoroutineScope()
            Column(Modifier.fillMaxSize()) {
                Text("Search", Modifier.testTag("search").clickable { scope.launch { pager.scrollToPage(1) } })
                Box(Modifier.weight(1f)) {
                    MainTabPager(pager, pageCovered = false) { page ->
                        DisposableEffect(page) { mounts[page]++; onDispose { } }
                        Text(if (page == 0) "Home loading" else "Screen $page")
                    }
                }
            }
        } }
        compose.onNodeWithText("Home loading").assertIsDisplayed()
        compose.runOnIdle { assertArrayEquals(intArrayOf(1, 0, 0), mounts) }
        compose.onNodeWithTag("search").performClick()
        compose.onNodeWithText("Screen 1").assertIsDisplayed()
        compose.runOnIdle { assertArrayEquals(intArrayOf(1, 1, 0), mounts) }
    }
}
