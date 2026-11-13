package com.example

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.playback.SoundOutputDevice
import com.example.ui.components.SoundOutputDeviceDialog
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OutputDevicePopupTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var originalVolume = 0
    private val phone = SoundOutputDevice(1, "This phone", "Built-in speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        true, isBuiltInSpeaker = true)

    @Before fun prepare() {
        originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT)
    }
    @After fun restore() { audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0) }

    @Test fun singleOutputIsCompactAndShowsOnlyItsNameWithAThinVolumeControl() {
        compose.setContent { MyApplicationTheme { SoundOutputDeviceDialog(listOf(phone), onSelectDevice = {}, onDismiss = {}) } }
        val dialog = compose.onNodeWithTag("sound_output_devices_dialog")
        dialog.assertIsDisplayed()
        compose.onNodeWithText("This phone").assertIsDisplayed()
        compose.onNodeWithText("Playing here", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Built-in speaker").assertDoesNotExist()
        compose.onNodeWithText("Choose where to listen").assertDoesNotExist()
        val bounds = dialog.fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("A single-device popup must stay compact: $bounds", bounds.height <= 224.dp.toPx())
            assertTrue(bounds.width <= 344.dp.toPx() + 1)
        }
        compose.onNodeWithTag("device_output_volume_track", useUnmergedTree = true).assertHeightIsEqualTo(4.dp)
        compose.onNodeWithTag("device_output_volume_thumb", useUnmergedTree = true).assertWidthIsEqualTo(12.dp)
        File(context.cacheDir, "output-popup-review.png").outputStream().use {
            dialog.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun selectionVolumeMuteAndCloseContinueWorking() {
        var devices by mutableStateOf(listOf(phone,
            SoundOutputDevice(2, "OnePlus Nord Buds 3", "Bluetooth", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, false, isBluetooth = true)))
        var selected = -1
        var dismissed = 0
        compose.setContent {
            MyApplicationTheme {
                SoundOutputDeviceDialog(devices, onSelectDevice = { id ->
                    selected = id; devices = devices.map { it.copy(isSelected = it.id == id) }
                }, onDismiss = { dismissed++ })
            }
        }
        compose.onNodeWithTag("device_row_2").performClick().assertIsSelected()
        assertEquals(2, selected)
        compose.onNodeWithText("OnePlus Nord Buds 3").assertIsDisplayed()
        compose.onNodeWithTag("device_output_volume_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.runOnIdle { assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
        compose.onNodeWithContentDescription("Mute").performClick()
        compose.runOnIdle { assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
        compose.onNodeWithTag("close_output_devices_dialog").performClick()
        compose.waitUntil(30_000) { dismissed == 1 }
    }

    @Test fun aLongDeviceListScrollsWhileVolumeAndCloseStayReachable() {
        val devices = (1..12).map { phone.copy(id = it, name = "Output $it", isSelected = it == 1) }
        compose.setContent { MyApplicationTheme { SoundOutputDeviceDialog(devices, onSelectDevice = {}, onDismiss = {}) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("device_row_12"))
        compose.onNodeWithTag("device_row_12").assertIsDisplayed()
        compose.onNodeWithTag("device_output_volume_slider").assertIsDisplayed()
        compose.onNodeWithTag("close_output_devices_dialog").assertIsDisplayed()
    }
}
