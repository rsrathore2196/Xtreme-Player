package com.example.benchmark

import android.os.Build
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit device-only profile collection. Normal app/release builds never execute this class. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule val profiles = BaselineProfileRule()

    @Test
    fun startup() = profiles.collect(packageName = TARGET, includeInStartupProfile = true) {
        grantNotificationPermission()
        pressHome()
        startActivityAndWait()
        finishOnboardingIfNeeded()
        requiredTag("home_screen")
    }

    @Test
    fun tabsAndPlayer() = profiles.collect(packageName = TARGET) {
        grantNotificationPermission()
        pressHome()
        startActivityAndWait()
        finishOnboardingIfNeeded()
        for (tab in listOf("home", "search", "library", "home")) {
            val selector = By.res(Pattern.compile("nav_tab_${tab}_(active|inactive)"))
            checkNotNull(device.wait(Until.findObject(selector), TIMEOUT)) { "Missing navigation tab: $tab" }.click()
            check(device.wait(Until.hasObject(By.res("nav_tab_${tab}_active")), TIMEOUT))
            device.waitForIdle(2_000L)
        }
        // Use a real, previously played/paused recording. No network search, demo track or test hook.
        val miniPlayer = device.wait(Until.findObject(By.res("mini_player_container")), TIMEOUT)
        checkNotNull(miniPlayer) {
            "Prepare this profiling installation first: finish onboarding, play a real song, then pause it. See benchmark/README.md."
        }.click()
        requiredTag("player_minimize_button")
        device.waitForIdle(2_000L)
        device.pressBack()
        check(device.wait(Until.gone(By.res("player_minimize_button")), TIMEOUT))
    }

    private fun MacrobenchmarkScope.requiredTag(tag: String): UiObject2 =
        checkNotNull(device.wait(Until.findObject(By.res(tag)), TIMEOUT)) { "Missing profile UI node: $tag" }

    private fun MacrobenchmarkScope.grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33)
            device.executeShellCommand("pm grant $TARGET android.permission.POST_NOTIFICATIONS")
    }

    private fun MacrobenchmarkScope.finishOnboardingIfNeeded() {
        device.wait(Until.hasObject(By.pkg(TARGET).depth(0)), TIMEOUT)
        device.wait(Until.findObject(By.res("start_listening_button")), 2_000L)?.click()
        device.wait(Until.findObject(By.res("onboarding_name_input")), 2_000L)?.let { name ->
            name.text = "Profile user"
            device.pressBack()
            var submit = device.findObject(By.res("onboarding_submit_button"))
            repeat(3) {
                if (submit == null) {
                    device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                        device.displayWidth / 2, device.displayHeight / 3, 24)
                    submit = device.wait(Until.findObject(By.res("onboarding_submit_button")), 1_000L)
                }
            }
            checkNotNull(submit) { "Could not complete profiling onboarding" }.click()
        }
        requiredTag("home_screen")
    }

    private companion object {
        const val TARGET = "com.aistudio.xtremeplayer.vkmz"
        const val TIMEOUT = 10_000L
    }
}
