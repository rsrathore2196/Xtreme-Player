package com.example

import android.app.Application
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.components.KeepLyricsScreenAwake
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeepLyricsAwakeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun flagIsOwnedOnlyByVisibleEnabledResumedLyricsAndClearsOnClose() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        var enabled by mutableStateOf(false)
        var visible by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var target: View? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val view = LocalView.current
                SideEffect { target = view }
                if (mounted) KeepLyricsScreenAwake(visible, enabled)
            }
        } }
        compose.runOnIdle { assertFalse(target!!.keepScreenOn); enabled = true }
        compose.runOnIdle { assertTrue(target!!.keepScreenOn); visible = false }
        compose.runOnIdle { assertFalse(target!!.keepScreenOn); visible = true }
        compose.runOnIdle { assertTrue(target!!.keepScreenOn); owner.lifecycle.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertFalse(target!!.keepScreenOn); owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertTrue(target!!.keepScreenOn); mounted = false }
        compose.runOnIdle { assertFalse(target!!.keepScreenOn) }
    }
}
