package com.example.performance

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Display

/**
 * Manages evidence-based display refresh-rate policy and Compose adaptive refresh-rate configuration.
 *
 * Reduces unnecessary display refresh-rate management overhead and improves frame consistency
 * across older Android devices, 60Hz/high-refresh displays, and affected Samsung devices.
 */
object DisplayRefreshRatePolicy {
    private const val TAG = "RefreshRatePolicy"

    /**
     * Determines whether the current device is a Samsung device susceptible to the documented
     * adaptive refresh-rate voting bug where repeated frame-rate voting on the main thread causes
     * micro-stuttering and verbose main-thread stack-trace generation.
     *
     * Targeted primarily at Samsung Galaxy S22 series (SM-S901x, SM-S906x, SM-S908x) and Samsung
     * devices on Android 12 through Android 14 (One UI 4 - One UI 6) where SurfaceFlinger/SecDisplay
     * exhibits this behavior.
     */
    fun isAffectedSamsungDevice(
        manufacturer: String? = try { Build.MANUFACTURER } catch (_: Throwable) { null },
        model: String? = try { Build.MODEL } catch (_: Throwable) { null },
        sdkInt: Int = try { Build.VERSION.SDK_INT } catch (_: Throwable) { 0 }
    ): Boolean {
        if (manufacturer == null || !manufacturer.equals("samsung", ignoreCase = true)) {
            return false
        }
        val upperModel = model?.uppercase().orEmpty()
        val isS22Series = upperModel.startsWith("SM-S901") ||
            upperModel.startsWith("SM-S906") ||
            upperModel.startsWith("SM-S908")

        val isAffectedAndroidVersion = sdkInt in Build.VERSION_CODES.S..Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        return isS22Series || isAffectedAndroidVersion
    }

    /**
     * Configures Jetpack Compose UI's adaptive refresh rate flag if available on the classpath.
     *
     * If the current device is an affected Samsung device, disables the flag to eliminate repeated
     * main-thread voting cycles and stack-trace logs. On all other devices (Pixel, OnePlus, Xiaomi,
     * Motorola, etc.) and unaffected platforms, leaves Compose's native behavior intact.
     */
    fun applyAdaptiveRefreshRatePolicy() {
        if (!isAffectedSamsungDevice()) {
            return
        }

        try {
            val flagsClass = Class.forName("androidx.compose.ui.ComposeUiFlags")
            val field = flagsClass.getDeclaredField("isAdaptiveRefreshRateEnabled")
            field.isAccessible = true
            field.setBoolean(null, false)
            Log.i(TAG, "Adaptive refresh rate flag safely adjusted for affected device")
        } catch (_: ClassNotFoundException) {
            // Flag not present in this Compose runtime; native platform behavior preserved
        } catch (_: NoSuchFieldException) {
            // Field not present in this Compose build; native platform behavior preserved
        } catch (e: Throwable) {
            Log.w(TAG, "Could not adjust ComposeUiFlags: ${e.message}")
        }
    }

    /**
     * Evaluates a display's mode list and determines whether it supports high/variable refresh rates.
     * Returns a pair of (supportsHighRefresh: Boolean, maxSupportedRate: Float).
     */
    fun resolveDisplayRefreshCapabilities(display: Display?): Pair<Boolean, Float> {
        if (display == null) return false to 60f
        val currentMode = display.mode ?: return false to 60f
        val modes = display.supportedModes ?: return false to 60f

        var maxRate = 60f
        var hasHighRefresh = false

        for (mode in modes) {
            if (mode.physicalWidth == currentMode.physicalWidth && mode.physicalHeight == currentMode.physicalHeight) {
                val rate = mode.refreshRate
                if (rate.isFinite() && rate > maxRate) {
                    maxRate = rate
                    if (rate > 60.5f) {
                        hasHighRefresh = true
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    for (altRate in mode.alternativeRefreshRates) {
                        if (altRate.isFinite() && altRate > maxRate) {
                            maxRate = altRate
                            if (altRate > 60.5f) {
                                hasHighRefresh = true
                            }
                        }
                    }
                }
            }
        }

        return hasHighRefresh to maxRate
    }
}
