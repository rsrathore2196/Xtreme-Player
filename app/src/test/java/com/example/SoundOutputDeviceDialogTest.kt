package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.example.playback.SoundOutputDevice
import com.example.ui.components.LocalPlayerPopupColors
import com.example.ui.components.SoundOutputDeviceDialog
import com.example.ui.theme.*
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
import kotlin.math.roundToInt

@OptIn(ExperimentalRoborazziApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoundOutputDeviceDialogTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun permissions() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
            (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.6f).roundToInt(), 0)
    }

    @Test fun darkPickerShowsCompleteDeviceNamesCategoryIconsAndWorkingControls() = checkPicker(DarkAppColors)
    @Test fun lightPickerShowsCompleteDeviceNamesCategoryIconsAndWorkingControls() = checkPicker(LightAppColors)

    private fun checkPicker(colors: AppThemeColors) {
        var devices by mutableStateOf(listOf(
            SoundOutputDevice(2, "OnePlus Nord Buds 3", "Bluetooth", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, true, isBluetooth = true),
            SoundOutputDevice(1, "This phone", "Built-in speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, false, isBuiltInSpeaker = true)
        ))
        var selected = -1
        var dismissed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppColors provides colors, LocalPlayerPopupColors provides colors) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF5C5268))) {
                        SoundOutputDeviceDialog(devices, onSelectDevice = { id ->
                            selected = id
                            devices = devices.map { it.copy(isSelected = it.id == id) }
                        }, onDismiss = { dismissed++ })
                    }
                }
            }
        }
        settle()
        compose.onNodeWithTag("output_device_count").assertTextEquals("2")
        compose.onNodeWithText("Playing here", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Built-in speaker").assertDoesNotExist()
        compose.onNodeWithText("Choose where to listen").assertDoesNotExist()
        compose.onNodeWithTag("device_output_volume_track", useUnmergedTree = true).assertHeightIsEqualTo(4.dp)
        compose.onNodeWithTag("device_output_volume_thumb", useUnmergedTree = true).assertWidthIsEqualTo(12.dp)
        compose.onNodeWithTag("device_row_2").assertIsSelected()
        compose.onNodeWithContentDescription("Earbuds", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Phone speaker", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("OnePlus Nord Buds 3", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        capture(if (colors.isDark) "dark" else "light")
        val layout = layouts.single()
        // Integer layout width can round below the paragraph's fractional width.
        // Check actual truncation rather than treating that subpixel difference as clipping.
        assertFalse("The device name must not be vertically clipped", layout.didOverflowHeight)
        assertFalse("The full earbuds name must fit", layout.isLineEllipsized(layout.lineCount - 1))
        assertEquals("OnePlus Nord Buds 3".length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        compose.onNodeWithTag("device_row_1").performClick()
        settle()
        assertEquals(1, selected)
        compose.onNodeWithTag("device_row_1").assertIsSelected()
        compose.onNodeWithTag("device_row_2").assertIsNotSelected()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        compose.onNodeWithTag("device_output_volume_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        settle()
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        compose.onNodeWithContentDescription("Mute").performClick()
        settle()
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        compose.onNodeWithTag("close_output_devices_dialog").performClick()
        settle()
        assertEquals(1, dismissed)
    }

    @Test @Config(qualifiers = "w320dp-h480dp-port")
    fun compactScreenKeepsVolumeAndCloseReachableWhileDevicesScroll() {
        val devices = (1..6).map {
            SoundOutputDevice(it, "Headphones $it", "Bluetooth", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, it == 1, isBluetooth = true)
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppColors provides DarkAppColors) {
                    SoundOutputDeviceDialog(devices, onSelectDevice = {}, onDismiss = {})
                }
            }
        }
        settle()
        compose.onNodeWithTag("close_output_devices_dialog").assertIsDisplayed()
        compose.onNodeWithTag("device_output_volume_slider").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("device_row_6"))
        compose.onNodeWithTag("device_row_6").assertIsDisplayed()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun capture(mode: String) {
        val node = compose.onNodeWithTag("sound_output_devices_dialog").fetchSemanticsNode()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = (node.root as ViewRootForTest).view
            val root = view.rootView
            val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(full))
            val bounds = node.boundsInRoot
            val location = IntArray(2).also(view::getLocationInWindow)
            val origin = IntArray(2).also(root::getLocationInWindow)
            bitmap = Bitmap.createBitmap(full,
                bounds.left.roundToInt() + location[0] - origin[0],
                bounds.top.roundToInt() + location[1] - origin[1],
                bounds.width.roundToInt(), bounds.height.roundToInt())
        }
        val file = File("build/reports/output-device-review/$mode.png").apply { parentFile?.mkdirs() }
        bitmap.captureRoboImage(file.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
