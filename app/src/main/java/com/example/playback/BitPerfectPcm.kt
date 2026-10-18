package com.example.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** IEEE float can represent every signed 24-bit PCM value exactly. Repack, never dither/resample.
 * 32-bit integer sources cannot use this path: float has only 24 significant bits.
 */
internal object BitPerfectPcm {
    fun repack24(input: ByteBuffer, output: ByteBuffer) {
        require(input.remaining() % 4 == 0 && output.remaining() >= input.remaining() / 4 * 3)
        val source = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (source.hasRemaining()) {
            val sample = source.float
            require(sample.isFinite() && sample >= -1f && sample < 1f) { "Invalid native PCM sample" }
            val value = (sample * 8388608f).roundToInt()
            require(value in -8388608..8388607 && value / 8388608f == sample) { "Float PCM is not an exact 24-bit recording" }
            output.put(value.toByte()).put((value shr 8).toByte()).put((value shr 16).toByte())
        }
        input.position(input.limit())
    }
}
