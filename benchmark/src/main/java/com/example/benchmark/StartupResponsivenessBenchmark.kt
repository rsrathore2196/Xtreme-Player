package com.example.benchmark

import android.os.Build
import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the first interactive window after the modal startup boundary has closed. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupResponsivenessBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private var originalIdleTimeout = 0L

    @Before fun measureWithoutImplicitUiAutomatorIdleWaits() {
        val configuration = Configurator.getInstance()
        originalIdleTimeout = configuration.waitForIdleTimeout
        configuration.waitForIdleTimeout = 0L
    }

    @After fun restoreUiAutomatorConfiguration() {
        Configurator.getInstance().waitForIdleTimeout = originalIdleTimeout
    }

    @Test fun coldOpening() = opening(StartupMode.COLD)
    @Test fun warmReopening() = opening(StartupMode.WARM)
    @Test fun retainedActivityReopening() = opening(StartupMode.HOT)
    @Test fun rapidTabRetargeting() = opening(StartupMode.WARM, rapidRetargeting = true)
    @Test fun optionalWorkWakeupAndInteraction() = opening(StartupMode.WARM, observeOptionalWakeup = true)

    private fun opening(mode: StartupMode, observeOptionalWakeup: Boolean = false, rapidRetargeting: Boolean = false) = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = mode,
        iterations = 5,
        setupBlock = {
            if (Build.VERSION.SDK_INT >= 33)
                device.executeShellCommand("pm grant $TARGET android.permission.POST_NOTIFICATIONS")
            pressHome()
        }
    ) {
        startActivityAndWait()
        // A prepared profiling installation is required; never delete user data or
        // include onboarding/API latency in a frame-responsiveness measurement.
        check(device.wait(Until.hasObject(By.res("nav_tab_home_active")), 90_000L)) {
            "Home did not become interactive. Check onboarding. See benchmark/README.md."
        }
        check(device.wait(Until.gone(By.res("startup_initialization_overlay")), 90_000L)) {
            "Startup presentation did not finish; cannot measure interactive navigation."
        }
        val interactiveStart = SystemClock.elapsedRealtime()
        do {
            // Deliberately no waitForIdle(): that would skip the reported loading window.
            device.swipe(device.displayWidth / 2, device.displayHeight * 2 / 3,
                device.displayWidth / 2, device.displayHeight / 3, 50)
            tab("search", waitForDestination = !rapidRetargeting)
            tab("library", waitForDestination = !rapidRetargeting)
            tab("search", waitForDestination = !rapidRetargeting)
            tab("home", waitForDestination = !rapidRetargeting)
            tab("library", waitForDestination = !rapidRetargeting)
            tab("home", waitForDestination = !rapidRetargeting)
        } while (SystemClock.elapsedRealtime() - interactiveStart < 6_000L)
        check(device.wait(Until.hasObject(By.res("main_tab_0_settled")), 3_000L))
        if (observeOptionalWakeup) {
            // Measurement only: trace across optional admission, then interrupt
            // it. This is not an application startup delay or a UI-idle wait.
            SystemClock.sleep(17_000L)
            tab("search")
            check(device.wait(Until.hasObject(By.res("search_screen")), 2_000L))
            tab("library")
            check(device.wait(Until.hasObject(By.res("library_screen")), 2_000L))
            tab("home")
        }
        // Include the existing player expansion path when a paused real session is prepared.
        device.findObject(By.res("mini_player_container"))?.let { mini ->
            mini.click()
            check(device.wait(Until.hasObject(By.res("player_minimize_button")), 3_000L))
            device.pressBack()
        }
    }

    private fun MacrobenchmarkScope.tab(name: String, waitForDestination: Boolean = true) {
        val selector = By.res(Pattern.compile("nav_tab_${name}_(active|inactive)"))
        checkNotNull(device.wait(Until.findObject(selector), 2_000L)).click()
        check(device.wait(Until.hasObject(By.res("nav_tab_${name}_active")), 2_000L))
        if (waitForDestination) {
            val page = listOf("home", "search", "library").indexOf(name)
            check(device.wait(Until.hasObject(By.res("main_tab_${page}_settled")), 3_000L)) {
                "Dock selected $name but its destination did not settle"
            }
        }
    }

    private companion object { const val TARGET = "com.aistudio.xtremeplayer.vkmz" }
}
