package com.example.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.WindowManager
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * User-selectable liquid glass rendering performance profile setting.
 */
enum class LiquidGlassProfileSetting {
    AUTO,
    PREMIUM,
    BALANCED,
    PERFORMANCE
}

/**
 * Concrete rendering profile configuration governing blur radius, backdrop resolution,
 * shader complexity (AGSL lens refraction and dispersion), reflections, and shadows.
 */
@Immutable
data class LiquidGlassProfile(
    val name: String,
    val blurRadiusMultiplier: Float,
    val floatingDockBlurRadius: Dp,
    val backdropSampleScale: Float,
    val enableLens: Boolean,
    val enableDepthEffect: Boolean,
    val enableChromaticAberration: Boolean,
    val enableRadialReflections: Boolean,
    val enableShadows: Boolean,
    val shadowElevationMultiplier: Float,
    val baseFillAlphaOverride: Float? = null,
    val allowSecondaryBackdropSampling: Boolean = true
) {
    /** Dock radii are already tuned per profile; do not multiply them twice. */
    internal fun blurRadius(requested: Dp, floatingDock: Boolean): Dp =
        if (floatingDock) floatingDockBlurRadius else requested * blurRadiusMultiplier

    internal fun surfaceFillAlpha(requested: Float?, fallback: Float, floatingDock: Boolean): Float =
        ((if (floatingDock) baseFillAlphaOverride ?: requested else requested ?: baseFillAlphaOverride)
            ?: fallback).coerceIn(0f, 1f)

    companion object {
        /**
         * Full-fidelity Liquid Glass rendering:
         * - Original blur radius (20dp default, 8dp floating dock)
         * - 0.33f backdrop sample scale
         * - Full AGSL lens refraction with 3-channel chromatic dispersion
         * - Multi-pass radial reflections, specular sheen, and full shadows
         */
        val PREMIUM = LiquidGlassProfile(
            name = "Premium",
            blurRadiusMultiplier = 1.0f,
            floatingDockBlurRadius = 8.dp,
            backdropSampleScale = 0.33f,
            enableLens = true,
            enableDepthEffect = true,
            enableChromaticAberration = true,
            enableRadialReflections = true,
            enableShadows = true,
            shadowElevationMultiplier = 1.0f,
            baseFillAlphaOverride = null,
            allowSecondaryBackdropSampling = true
        )

        /**
         * Balanced Liquid Glass rendering:
         * - 40% reduced blur cost (0.60x radius)
         * - 0.28f backdrop sample scale
         * - Single-texture lens refraction (chromatic aberration disabled, cutting texture lookups in half)
         * - Sleek linear reflections (avoiding expensive fragment Euclidean distance math)
         * - Reduced shadow processing (0.60x elevation, no ambient shadow)
         * - Preserves overall glass appearance and Liquid Glass aesthetic faithfully
         */
        val BALANCED = LiquidGlassProfile(
            name = "Balanced",
            blurRadiusMultiplier = 0.60f,
            floatingDockBlurRadius = 5.dp,
            backdropSampleScale = 0.28f,
            enableLens = true,
            enableDepthEffect = false,
            enableChromaticAberration = false,
            enableRadialReflections = false,
            enableShadows = true,
            shadowElevationMultiplier = 0.6f,
            baseFillAlphaOverride = null,
            allowSecondaryBackdropSampling = true
        )

        /**
         * High-performance Liquid Glass rendering:
         * - Lower-resolution backdrop processing (0.20f sample scale, ~60% fewer pixels)
         * - Minimal blur (0.25x radius, 3dp dock)
         * - Lens refraction and chromatic aberration disabled (no AGSL RuntimeShader pass)
         * - Radial reflections disabled
         * - Redundant button backdrop captures bypassed in favor of clean translucent fills
         * - Lightweight translucent surface fills for smooth 60fps/120fps on budget hardware
         */
        val PERFORMANCE = LiquidGlassProfile(
            name = "Performance",
            blurRadiusMultiplier = 0.25f,
            floatingDockBlurRadius = 3.dp,
            backdropSampleScale = 0.20f,
            enableLens = false,
            enableDepthEffect = false,
            enableChromaticAberration = false,
            enableRadialReflections = false,
            enableShadows = false,
            shadowElevationMultiplier = 0.0f,
            baseFillAlphaOverride = 0.78f,
            allowSecondaryBackdropSampling = false
        )
    }
}

/**
 * Conservative heuristic for dynamic Liquid Glass profile selection.
 * Uses Android version, low-RAM status, memory class, processor count, display refresh rate,
 * and power-save state. Android exposes no portable GPU-performance score, so AUTO is a starting
 * point rather than a guarantee of a particular frame rate; validate it with device benchmarks.
 */
/**
 * Snapshot of coarse device/runtime capabilities used by the AUTO glass profile.
 *
 * Android does not expose a portable GPU-performance score. These values are conservative
 * heuristics only; actual profile quality must be validated with frame/GPU measurements.
 */
internal data class LiquidGlassDeviceCapabilities(
    val sdkInt: Int,
    val powerSaveMode: Boolean,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
    val availableProcessors: Int,
    val hasHighRefresh: Boolean,
    val maxRefreshRateHz: Float
)

object LiquidGlassDeviceCapability {

    fun resolveEffectiveProfile(
        context: Context,
        setting: LiquidGlassProfileSetting
    ): LiquidGlassProfile {
        return when (setting) {
            LiquidGlassProfileSetting.PREMIUM -> LiquidGlassProfile.PREMIUM
            LiquidGlassProfileSetting.BALANCED -> LiquidGlassProfile.BALANCED
            LiquidGlassProfileSetting.PERFORMANCE -> LiquidGlassProfile.PERFORMANCE
            LiquidGlassProfileSetting.AUTO -> evaluateAutomaticProfile(context)
        }
    }

    fun evaluateAutomaticProfile(context: Context): LiquidGlassProfile {
        val powerManager = try {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (_: RuntimeException) {
            null
        }
        val activityManager = try {
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        } catch (_: RuntimeException) {
            null
        }

        val display = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay
            }
        } catch (_: RuntimeException) {
            null
        }
        val (hasHighRefresh, maxRefreshRate) =
            DisplayRefreshRatePolicy.resolveDisplayRefreshCapabilities(display)

        return selectAutomaticProfile(
            LiquidGlassDeviceCapabilities(
                sdkInt = Build.VERSION.SDK_INT,
                // Missing service information is treated conservatively rather than assuming
                // that battery saver is off or the device has a large memory budget.
                powerSaveMode = powerManager?.isPowerSaveMode ?: true,
                lowRamDevice = activityManager?.isLowRamDevice ?: true,
                memoryClassMb = activityManager?.memoryClass ?: 0,
                availableProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                hasHighRefresh = hasHighRefresh,
                maxRefreshRateHz = maxRefreshRate
            )
        )
    }

    /** Pure policy function so AUTO selection is covered by deterministic unit tests. */
    internal fun selectAutomaticProfile(capabilities: LiquidGlassDeviceCapabilities): LiquidGlassProfile {
        if (capabilities.sdkInt < Build.VERSION_CODES.S) {
            return LiquidGlassProfile.PERFORMANCE
        }
        if (capabilities.powerSaveMode || capabilities.lowRamDevice) {
            return LiquidGlassProfile.PERFORMANCE
        }

        // Android 12/12L supports platform blur but not AGSL RuntimeShader. Balanced
        // keeps the lens path gracefully disabled by the API guard in XtremeLiquidGlass.
        if (capabilities.sdkInt < Build.VERSION_CODES.TIRAMISU) {
            return if (capabilities.memoryClassMb >= 256 && capabilities.availableProcessors >= 6) {
                LiquidGlassProfile.BALANCED
            } else {
                LiquidGlassProfile.PERFORMANCE
            }
        }

        return when {
            capabilities.memoryClassMb >= 512 && capabilities.availableProcessors >= 8 ->
                LiquidGlassProfile.PREMIUM

            capabilities.memoryClassMb >= 256 && capabilities.availableProcessors >= 6 &&
                (!capabilities.hasHighRefresh || capabilities.maxRefreshRateHz <= 90f) ->
                LiquidGlassProfile.PREMIUM

            capabilities.memoryClassMb >= 192 && capabilities.availableProcessors >= 4 ->
                LiquidGlassProfile.BALANCED

            else -> LiquidGlassProfile.PERFORMANCE
        }
    }
}
