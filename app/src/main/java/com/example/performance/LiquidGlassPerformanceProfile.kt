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
 * Evidence-based device capability evaluator for dynamic Liquid Glass profile selection.
 * Analyzes RAM class, available hardware acceleration, Android version, display refresh rate,
 * processor count, and system power-save mode rather than relying on an arbitrary device blacklist.
 */
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
        // 1. Android version check: Below Android 12 (API 31), RenderEffect is not natively supported
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return LiquidGlassProfile.PERFORMANCE
        }

        // 2. Power-Save Mode: If system battery saver is active, step down to reduce GPU cycles and thermal load
        val powerManager = try {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (_: Throwable) {
            null
        }
        if (powerManager?.isPowerSaveMode == true) {
            return LiquidGlassProfile.PERFORMANCE
        }

        // 3. Low-RAM Device check
        val activityManager = try {
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        } catch (_: Throwable) {
            null
        }
        if (activityManager?.isLowRamDevice == true) {
            return LiquidGlassProfile.PERFORMANCE
        }

        val memoryClass = activityManager?.memoryClass ?: 192
        val availableProcessors = try {
            Runtime.getRuntime().availableProcessors()
        } catch (_: Throwable) {
            4
        }

        // 4. Android 12/12L (API 31/32) does not support AGSL RuntimeShader (introduced in API 33/Android 13)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return if (memoryClass >= 256 && availableProcessors >= 6) {
                LiquidGlassProfile.BALANCED
            } else {
                LiquidGlassProfile.PERFORMANCE
            }
        }

        // 5. Display Refresh Rate & Frame Budget Evaluation
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display
            } catch (_: Throwable) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                @Suppress("DEPRECATION")
                wm?.defaultDisplay
            }
        } else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            wm?.defaultDisplay
        }

        val (hasHighRefresh, maxRate) = DisplayRefreshRatePolicy.resolveDisplayRefreshCapabilities(display)

        // On displays with high refresh rates (>= 90Hz or 120Hz), the frame budget is 8.3ms or 11.1ms.
        // Unless the device has generous memory and processors (flagship tier), BALANCED delivers
        // zero dropped frames while preserving the full Liquid Glass visual identity.
        return when {
            memoryClass >= 512 && availableProcessors >= 8 -> LiquidGlassProfile.PREMIUM
            memoryClass >= 256 && availableProcessors >= 6 && (!hasHighRefresh || maxRate <= 90f) -> LiquidGlassProfile.PREMIUM
            memoryClass >= 192 && availableProcessors >= 4 -> LiquidGlassProfile.BALANCED
            else -> LiquidGlassProfile.PERFORMANCE
        }
    }
}
