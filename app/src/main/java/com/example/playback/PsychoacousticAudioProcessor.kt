package com.example.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

/** Float biquad arithmetic for every PCM path, including DefaultAudioSink's float path.
 * Lives BEFORE the sink: Media3 1.5 skips its custom processor chain for float output.
 * Disabled/bypassed audio is copied byte-for-byte; settings change without a sink flush.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PsychoacousticAudioProcessor : BaseAudioProcessor() {
    companion object {
        internal val settings = AtomicReference(AudioEffectsState())
        private const val RAMP_FRAMES = 512
    }
    private var last: AudioEffectsState? = null
    private var filters = emptyArray<Biquad>()
    private var gain = 1f
    private var targetGain = 1f
    private var width = 1f
    private var targetWidth = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in intArrayOf(C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT,
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT,
                C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN))
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush() {
        filters = Array(7) { Biquad(inputAudioFormat.channelCount.coerceAtLeast(1)) }
        last = null; gain = 1f; width = 1f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val state = settings.get()
        val frameBytes = inputAudioFormat.bytesPerFrame
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) return
        val bytes = frames * frameBytes
        val bigEndian = inputAudioFormat.encoding in intArrayOf(C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN)
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val output = replaceOutputBuffer(bytes).order(order)
        if (!state.isEnabled) {
            val limit = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + bytes)
            output.put(inputBuffer)
            inputBuffer.limit(limit)
            output.flip()
            last = null
            return
        }
        if (last != state) {
            prepare(state)
            last = state
        }
        inputBuffer.order(order)
        val channels = inputAudioFormat.channelCount
        repeat(frames) {
            gain += (targetGain - gain) / RAMP_FRAMES
            width += (targetWidth - width) / RAMP_FRAMES
            var left = 0f
            var right = 0f
            for (channel in 0 until channels) {
                var sample = read(inputBuffer) * gain
                for (filter in filters) sample = filter.process(sample, channel)
                if (channels == 2) {
                    if (channel == 0) left = sample else right = sample
                } else write(output, limit(sample))
            }
            if (channels == 2) {
                val mid = (left + right) * 0.5f
                val side = (left - right) * 0.5f * width
                write(output, limit(mid + side)); write(output, limit(mid - side))
            }
        }
        output.flip()
    }

    private fun prepare(state: AudioEffectsState) {
        val rate = inputAudioFormat.sampleRate.toDouble()
        var boosts = 0.0
        for (i in 0 until 5) {
            val band = state.bands.getOrNull(i)
            val db = if (state.isEnabled) (band?.levelMb ?: 0) / 100.0 else 0.0
            boosts += max(0.0, db)
            filters[i].target(coefficients(rate, band?.centerFreqHz?.toDouble() ?: 1000.0, db, false))
        }
        val enhance = state.crystalClarityEnabled
        val bass = (if (state.isEnabled) state.bassBoostStrength / 1000.0 * 4.0 else 0.0) + if (enhance) 0.8 else 0.0
        val air = if (enhance) 1.5 else 0.0
        filters[5].target(coefficients(rate, 60.0, bass, false))
        filters[6].target(coefficients(rate, 14000.0, air, true))
        boosts += bass + air + if (state.isEnabled && state.virtualizerStrength > 0) 2.5 else 0.0
        // Reserve filter headroom BEFORE boosting. Loudness gain stays controlled by the limiter.
        targetGain = 10.0.pow((-boosts + if (state.isEnabled) state.loudnessGainMb / 100.0 else 0.0) / 20.0).toFloat()
        targetWidth = 1f + if (state.isEnabled) state.virtualizerStrength.coerceIn(0, 1000) / 1000f * 0.30f else 0f
    }

    private fun read(buffer: ByteBuffer): Float = when (inputAudioFormat.encoding) {
        C.ENCODING_PCM_FLOAT -> buffer.float.let { if (it.isFinite()) it else 0f }
        C.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128f
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> buffer.short / 32768f
        C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
            val a = buffer.get().toInt(); val b = buffer.get().toInt() and 255; val c = buffer.get().toInt() and 255
            ((a shl 16) or (b shl 8) or c) / 8388608f
        }
        C.ENCODING_PCM_24BIT -> {
            val a = buffer.get().toInt() and 255; val b = buffer.get().toInt() and 255
            val c = buffer.get().toInt()
            (a or (b shl 8) or (c shl 16)) / 8388608f
        }
        else -> (buffer.int / 2147483648.0).toFloat()
    }
    private fun write(buffer: ByteBuffer, sample: Float) {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> buffer.putFloat(sample)
            C.ENCODING_PCM_8BIT -> buffer.put((sample * 127f + 128f).roundToInt().coerceIn(0, 255).toByte())
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> buffer.putShort((sample * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                val value = (sample * 8388607f).roundToInt().coerceIn(-8388608, 8388607)
                buffer.put((value shr 16).toByte()); buffer.put((value shr 8).toByte()); buffer.put(value.toByte())
            }
            C.ENCODING_PCM_24BIT -> {
                val value = (sample * 8388607f).roundToInt().coerceIn(-8388608, 8388607)
                buffer.put(value.toByte()); buffer.put((value shr 8).toByte()); buffer.put((value shr 16).toByte())
            }
            else -> buffer.putInt((sample.toDouble() * 2147483647.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
        }
    }
    private fun limit(sample: Float): Float {
        if (!sample.isFinite()) return 0f
        val a = abs(sample)
        return if (a <= 0.90f) sample else sign(sample) * (0.90f + 0.10f * tanh((a - 0.90f) / 0.10f))
    }

    private class Biquad(channels: Int) {
        private val z1 = FloatArray(channels)
        private val z2 = FloatArray(channels)
        private val current = floatArrayOf(1f, 0f, 0f, 0f, 0f)
        private var next = current.copyOf()
        private var ramp = 0
        fun target(value: FloatArray) { next = value; ramp = RAMP_FRAMES }
        fun process(input: Float, channel: Int): Float {
            if (channel == 0 && ramp > 0) {
                for (i in current.indices) current[i] += (next[i] - current[i]) / ramp
                ramp--
            }
            val out = current[0] * input + z1[channel]
            z1[channel] = current[1] * input - current[3] * out + z2[channel]
            z2[channel] = current[2] * input - current[4] * out
            if (!out.isFinite()) { z1[channel] = 0f; z2[channel] = 0f; return 0f }
            return out
        }
    }
    private fun coefficients(rate: Double, frequency: Double, db: Double, shelf: Boolean): FloatArray {
        if (abs(db) < 0.001) return floatArrayOf(1f, 0f, 0f, 0f, 0f)
        val omega = 2.0 * PI * frequency.coerceIn(10.0, rate * 0.45) / rate
        val a = 10.0.pow(db / 40.0); val c = cos(omega); val s = sin(omega)
        val alpha = s / (2.0 * 0.8)
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        if (shelf) {
            val k = 2.0 * sqrt(a) * s / sqrt(2.0)
            b0 = a * ((a + 1) + (a - 1) * c + k); b1 = -2 * a * ((a - 1) + (a + 1) * c)
            b2 = a * ((a + 1) + (a - 1) * c - k); a0 = (a + 1) - (a - 1) * c + k
            a1 = 2 * ((a - 1) - (a + 1) * c); a2 = (a + 1) - (a - 1) * c - k
        } else {
            b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a
            a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a
        }
        return floatArrayOf((b0/a0).toFloat(), (b1/a0).toFloat(), (b2/a0).toFloat(), (a1/a0).toFloat(), (a2/a0).toFloat())
    }
}
