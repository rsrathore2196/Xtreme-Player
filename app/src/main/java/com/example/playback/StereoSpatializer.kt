/*
 * Stereo spatial audio processor; GPL-3.0.
 */
package com.example.playback

import kotlin.math.roundToInt

/** Stereo mid/side widening and 15 ms low-pass crossfeed.
 * Owned by one renderer, entirely on its audio thread. No per-frame allocations.
 * Mono/multichannel are untouched. This is stereo virtualization, not Atmos.
 */
internal class StereoSpatializer {
    private var delayLeft = FloatArray(0)
    private var delayRight = FloatArray(0)
    private var index = 0
    private var lowLeft = 0f
    private var lowRight = 0f
    private var wet = 0f
    private var target = 0f
    private var ramp = 0
    var left = 0f
        private set
    var right = 0f
        private set
    val active: Boolean get() = wet > 0f || target > 0f

    fun configure(rate: Int, channels: Int) {
        val size = if (channels == 2 && rate > 0) (rate * .015f).roundToInt().coerceAtLeast(1) else 0
        if (delayLeft.size != size) { delayLeft = FloatArray(size); delayRight = FloatArray(size) }
        flush()
    }
    fun setEnabled(enabled: Boolean) {
        val value = if (enabled && delayLeft.isNotEmpty()) 1f else 0f
        if (target == value) return
        target = value
        ramp = 512
    }
    fun process(inputLeft: Float, inputRight: Float) {
        if (delayLeft.isEmpty() || !active) { left = inputLeft; right = inputRight; return }
        if (ramp > 0) { wet += (target - wet) / ramp; ramp-- }
        val mid = (inputLeft + inputRight) * .5f
        val side = (inputLeft - inputRight) * .5f * 2.5f
        lowLeft += .3f * (delayRight[index] - lowLeft)
        lowRight += .3f * (delayLeft[index] - lowRight)
        delayLeft[index] = inputLeft
        delayRight[index] = inputRight
        if (++index == delayLeft.size) index = 0
        val widenedLeft = (mid + side + lowLeft * .2f) * .82f
        val widenedRight = (mid - side + lowRight * .2f) * .82f
        left = inputLeft + (widenedLeft - inputLeft) * wet
        right = inputRight + (widenedRight - inputRight) * wet
        if (!active) clearDelay()
    }
    private fun clearDelay() {
        delayLeft.fill(0f); delayRight.fill(0f); index = 0; lowLeft = 0f; lowRight = 0f
    }
    fun flush() { clearDelay(); wet = 0f; target = 0f; ramp = 0 }
}
