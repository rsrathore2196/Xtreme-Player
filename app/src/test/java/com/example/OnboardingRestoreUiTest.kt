package com.example

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.Color
import com.example.data.local.UserProfile
import com.example.ui.screens.OnboardingScreen
import com.example.ui.viewmodel.OnboardingRestoreState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingRestoreUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(sdk = [28])
    fun legacyStoragePermissionIsRequestedBeforeTheFilePickerAndDenialLeavesWelcomeUsable() {
        compose.setContent { MaterialTheme { OnboardingScreen(onComplete = { _, _, _, _, _ -> }) } }
        compose.onNodeWithTag("onboarding_restore_button").performScrollTo().performClick()
        val activity = shadowOf(compose.activity)
        val request = activity.lastRequestedPermission
        assertNotNull(request)
        assertTrue(request.requestedPermissions.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        assertNotEquals(Intent.ACTION_OPEN_DOCUMENT, activity.nextStartedActivityForResult?.intent?.action)
        compose.runOnUiThread {
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                IntArray(request.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_DENIED })
        }
        compose.onNodeWithTag("onboarding_restore_status").assertExists()
        compose.onNodeWithTag("onboarding_restore_button").assertIsEnabled()
        compose.onNodeWithTag("start_listening_button").assertIsEnabled()
        compose.onNodeWithTag("onboarding_name_input").assertDoesNotExist()
    }

    @Test @Config(sdk = [28])
    fun grantingLegacyPermissionOpensTheBackupPicker() {
        compose.setContent { MaterialTheme { OnboardingScreen(onComplete = { _, _, _, _, _ -> }) } }
        compose.onNodeWithTag("onboarding_restore_button").performScrollTo().performClick()
        val activity = shadowOf(compose.activity)
        val request = activity.lastRequestedPermission
        assertNotNull(request)
        compose.runOnUiThread {
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                IntArray(request.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_GRANTED })
        }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, activity.nextStartedActivityForResult.intent.action)
        compose.onNodeWithTag("onboarding_name_input").assertDoesNotExist()
    }

    @Test fun welcomeActionsShareTheRowAndRestoreRequestsDocumentAccessWithoutOpeningDetails() {
        var restoreCalls = 0
        compose.setContent { MaterialTheme {
            OnboardingScreen(onComplete = { _, _, _, _, _ -> error("Unexpected profile submission") },
                onRestoreBackup = { restoreCalls++ })
        } }
        val newUser = compose.onNodeWithTag("start_listening_button")
        val restore = compose.onNodeWithTag("onboarding_restore_button")
        newUser.performScrollTo().assertTextContains("New User")
        restore.assertIsDisplayed().assertTextContains("Restore")
        val first = newUser.fetchSemanticsNode().boundsInRoot
        val second = restore.fetchSemanticsNode().boundsInRoot
        assertEquals(first.width, second.width, 1f)
        assertEquals(first.top, second.top, 1f)
        assertTrue(first.right < second.left)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Restore", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color.Black, layouts.single().layoutInput.style.color)
        restore.performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull(request)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        assertEquals("*/*", request.intent.type)
        assertTrue(request.intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals(0, restoreCalls)
        compose.onNodeWithTag("onboarding_name_input").assertDoesNotExist()
        // Cancellation returns to the same welcome actions, without a restore or profile submission.
        compose.runOnUiThread {
            shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
        }
        newUser.assertIsEnabled()
        restore.assertIsEnabled()
        assertEquals(0, restoreCalls)
    }

    @Test fun selectedDocumentInvokesRestoreOnceAndRestoringPreventsDuplicateActions() {
        var state by mutableStateOf(OnboardingRestoreState())
        val selected = mutableListOf<android.net.Uri>()
        compose.setContent { MaterialTheme {
            OnboardingScreen(onComplete = { _, _, _, _, _ -> }, restoreState = state,
                onRestoreBackup = { selected.add(it); state = OnboardingRestoreState(restoring = true) })
        } }
        compose.onNodeWithTag("onboarding_restore_button").performScrollTo().performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        val uri = android.net.Uri.parse("content://documents/backup.json")
        compose.runOnUiThread {
            shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
        }
        compose.onNodeWithTag("onboarding_restore_button").assertIsNotEnabled()
        compose.onNodeWithTag("start_listening_button").assertIsNotEnabled()
        compose.onNodeWithText("Restoring backup…").assertExists()
        assertEquals(listOf(uri), selected)
        compose.runOnIdle { state = OnboardingRestoreState(error = "Invalid backup") }
        compose.onNodeWithText("Invalid backup").assertExists()
        compose.onNodeWithTag("onboarding_restore_button").assertIsEnabled()
        compose.onNodeWithTag("onboarding_name_input").assertDoesNotExist()
    }

    @Test fun incompleteRestoredProfileOpensDetailsAndRetainsRestoredCountryAndLanguages() {
        var submitted: List<Any>? = null
        compose.setContent { MaterialTheme {
            OnboardingScreen(onComplete = { name, languages, country, code, flag ->
                submitted = listOf(name, languages, country, code, flag)
            }, restoreState = OnboardingRestoreState(profileForDetails =
                UserProfile("", listOf("Spanish", "Korean"), "Spain", "ES", "🇪🇸")))
        } }
        compose.onNodeWithTag("onboarding_name_input").performScrollTo().performTextInput("Alex")
        compose.onNodeWithTag("onboarding_submit_button").performScrollTo().performClick()
        assertEquals(listOf("Alex", listOf("Spanish", "Korean"), "Spain", "ES", "🇪🇸"), submitted)
    }
}
