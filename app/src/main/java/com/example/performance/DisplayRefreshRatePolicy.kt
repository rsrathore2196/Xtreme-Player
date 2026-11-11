package com.example.performance

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

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
     * This workaround is deliberately limited to the Samsung Galaxy S22 family on Android 12–14,
     * the combination for which the issue was identified during the BitChord comparison. Do not
     * generalize it to every Samsung model without device-specific reproduction evidence.
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
        return isS22Series && isAffectedAndroidVersion
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
     * Mirrors BitChord's supported-rate discovery: expose rates the active display reports
     * across its available modes, including API 31+ alternative refresh rates.
     */
    fun supportedPerformanceRefreshRates(display: Display?): List<Int> {
        val rates = linkedSetOf<Int>()
        display?.supportedModes?.forEach { mode ->
            val rate = mode.refreshRate
            if (rate.isFinite()) {
                val rounded = rate.roundToInt()
                if (rounded in 50..240) rates += rounded
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mode.alternativeRefreshRates.forEach { alternative ->
                    if (alternative.isFinite()) {
                        val rounded = alternative.roundToInt()
                        if (rounded in 50..240) rates += rounded
                    }
                }
            }
        }
        if (rates.isEmpty()) {
            val fallback = display?.refreshRate?.takeIf { it.isFinite() && it > 0f }?.roundToInt() ?: 60
            rates += fallback.coerceIn(50, 240)
        }
        return rates.sorted()
    }

    /**
     * Resolve a saved fixed-rate choice against the current display. AUTO (0) deliberately
     * remains automatic. If a setting is restored on another device, choose the nearest
     * supported rate; on an exact tie prefer the higher rate, matching BitChord's policy.
     */
    fun resolvePreferredRefreshRate(preferredHz: Int, supportedRates: List<Int>): Int {
        if (preferredHz <= 0) return 0
        val usable = supportedRates.filter { it in 50..240 }.distinct()
        if (usable.isEmpty()) return preferredHz.coerceIn(50, 240)
        return usable.minWithOrNull(
            compareBy<Int> { abs(it - preferredHz) }.thenByDescending { it }
        ) ?: preferredHz.coerceIn(50, 240)
    }

    fun resolvePreferredRefreshRate(display: Display?, preferredHz: Int): Int =
        resolvePreferredRefreshRate(preferredHz, supportedPerformanceRefreshRates(display))

    /**
     * Returns whether the display has a supported rate above 60Hz and its highest reported rate.
     * Used by the Liquid Glass AUTO profile; refresh selection itself is handled separately.
     */
    fun resolveDisplayRefreshCapabilities(display: Display?): Pair<Boolean, Float> {
        val maximum = supportedPerformanceRefreshRates(display).maxOrNull()?.toFloat() ?: 60f
        return (maximum > 60.5f) to maximum
    }
}
