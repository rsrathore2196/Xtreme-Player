/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 * Xtreme integration: package renamed; shader editor annotations removed.
 */
package com.example.ui.components.backdrop.effects

import androidx.annotation.FloatRange
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import com.example.ui.components.backdrop.BackdropEffectScope
import com.example.ui.components.backdrop.isRenderEffectSupported

fun BackdropEffectScope.blur(
    @FloatRange(from = 0.0) radius: Float,
    edgeTreatment: TileMode = TileMode.Clamp
) {
    if (!isRenderEffectSupported()) return
    if (radius <= 0f) return

    // Sample the real neighboring page pixels before clipping the glass. Clamping
    // to a resized, rounded capture rect makes the blur's edge move in texel-sized
    // steps during the last frames of a dock/mini-player spring.
    padding = maxOf(padding, radius * 3f)

    renderEffect =
        BlurEffect(
            renderEffect,
            radius,
            radius,
            edgeTreatment
        )
}
