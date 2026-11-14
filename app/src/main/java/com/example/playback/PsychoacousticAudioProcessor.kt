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
        @Volatile internal var spatialEnabled = false
    }
    private var last: AudioEffectsState? = null
    private var filters = emptyArray<Biquad>()
    private var gain = 1f
    private var targetGain = 1f
    private var width = 1f
    private var targetWidth = 1f
    private var equalizing = false
    private var lastSpatialEnabled = false
    private var rampFrames = 512
    private var settlingFrames = 0
    private var attack = 1f / 48
    private var release = 1f / 512
    private var response = emptyArray<DoubleArray>()
    private val spatial = StereoSpatializer()
    // The sink may skip queueInput entirely during bypass. Clear EQ history here
    // as well, so re-enabling the same preset cannot resurrect old samples.
    internal val isProcessing: Boolean get() = synchronize(settings.get())

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in intArrayOf(C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT,
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT,
                C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN))
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush() {
        filters = Array(7) { Biquad(inputAudioFormat.channelCount.coerceAtLeast(1)) }
        last = null; equalizing = false; gain = 1f; width = 1f; settlingFrames = 0
        rampFrames = (inputAudioFormat.sampleRate * .010).roundToInt().coerceAtLeast(1)
        attack = (1.0 - exp(-1.0 / (inputAudioFormat.sampleRate * .001))).toFloat()
        release = (1.0 - exp(-1.0 / (inputAudioFormat.sampleRate * .020))).toFloat()
        // Trigonometry is shared by all response checks and never repeated per sample.
        response = Array(258) { i ->
            val omega = when (i) {
                0 -> 0.0
                257 -> PI
                else -> 2.0 * PI * 10.0 * (inputAudioFormat.sampleRate * .05).pow((i - 1) / 255.0) / inputAudioFormat.sampleRate
            }
            doubleArrayOf(cos(omega), sin(omega), cos(2 * omega), sin(2 * omega))
        }
        spatial.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
    }

    private fun synchronize(state: AudioEffectsState): Boolean {
        val spatialRequested = spatialEnabled
        spatial.setEnabled(spatialRequested)
        val active = state.isEnabled && (state.bands.any { it.levelMb.toInt() != 0 &&
            it.centerFreqHz > 0 && it.centerFreqHz < inputAudioFormat.sampleRate * .5 } ||
            state.crystalClarityEnabled || state.bassBoostStrength > 0 || state.loudnessGainMb > 0 ||
            state.virtualizerStrength > 0 && !spatialRequested && inputAudioFormat.channelCount == 2)
        if (!active) {
            if (equalizing) {
                filters.forEach { it.reset() }
                gain = 1f; targetGain = 1f; width = 1f; targetWidth = 1f; settlingFrames = 0
            }
        } else if (!equalizing || last != state || lastSpatialEnabled != spatialRequested) prepare(state, spatialRequested)
        equalizing = active
        last = state
        lastSpatialEnabled = spatialRequested
        return active || spatial.active
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val state = settings.get()
        val processing = synchronize(state)
        val frameBytes = inputAudioFormat.bytesPerFrame
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) return
        val bytes = frames * frameBytes
        val bigEndian = inputAudioFormat.encoding in intArrayOf(C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN)
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val output = replaceOutputBuffer(bytes).order(order)
        if (!processing) {
            val limit = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + bytes)
            output.put(inputBuffer)
            inputBuffer.limit(limit)
            output.flip()
            return
        }
        inputBuffer.order(order)
        val channels = inputAudioFormat.channelCount
        repeat(frames) {
            if (gain > targetGain) gain += (targetGain - gain) * attack
            else if (settlingFrames == 0) gain += (targetGain - gain) * release
            if (settlingFrames > 0) settlingFrames--
            width += (targetWidth - width) / rampFrames
            var left = 0f
            var right = 0f
            if (channels == 2) {
                spatial.process(read(inputBuffer), read(inputBuffer))
                left = spatial.left; right = spatial.right
            }
            for (channel in 0 until channels) {
                var sample = if (channels == 2) { if (channel == 0) left else right } else read(inputBuffer)
                if (equalizing) {
                    sample *= gain
                    for (filter in filters) sample = filter.process(sample, channel)
                }
                if (channels == 2) {
                    if (channel == 0) left = sample else right = sample
                } else write(output, limit(sample))
            }
            if (channels == 2) {
                val mid = (left + right) * 0.5f
                val side = (left - right) * 0.5f * if (equalizing) width else 1f
                write(output, limit(mid + side)); write(output, limit(mid - side))
            }
        }
        output.flip()
    }

    private fun prepare(state: AudioEffectsState, spatialRequested: Boolean) {
        val rate = inputAudioFormat.sampleRate.toDouble()
        val targets = Array(7) { doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0) }
        for (i in 0 until 5) {
            val band = state.bands.getOrNull(i)
            val db = if (state.isEnabled) (band?.levelMb ?: 0) / 100.0 else 0.0
            targets[i] = coefficients(rate, band?.centerFreqHz?.toDouble() ?: 1000.0, db, false)
        }
        val enhance = state.crystalClarityEnabled
        val bass = (if (state.isEnabled) state.bassBoostStrength / 1000.0 * 4.0 else 0.0) + if (enhance) 0.8 else 0.0
        val air = if (enhance) 1.5 else 0.0
        targets[5] = coefficients(rate, 60.0, bass, false)
        targets[6] = coefficients(rate, 14000.0, air, true)
        targets.forEachIndexed { i, values -> filters[i].target(values, rampFrames) }
        // Independent spatial audio already supplies widening. Preserve multichannel
        // PCM channel order and avoid widening stereo twice when it is selected.
        targetWidth = 1f + if (!spatialRequested && inputAudioFormat.channelCount == 2)
            state.virtualizerStrength.coerceIn(0, 1000) / 1000f * .30f else 0f
        val peakDb = peakGainDb(targets)
        val headroomDb = max(0.0, peakDb) + 20.0 * log10(targetWidth.toDouble()) + if (peakDb > .001) .25 else 0.0
        targetGain = 10.0.pow((state.loudnessGainMb / 100.0 - headroomDb) / 20.0).toFloat()
        // A 1 ms attack precedes the slower filter morph without a hard volume step;
        // the safety limiter catches transients. Release waits for filters to settle.
        settlingFrames = rampFrames
    }

    private fun peakGainDb(targets: Array<DoubleArray>): Double {
        var peak = 1.0
        for (point in response) {
            var power = 1.0
            for (c in targets) {
                val real = c[0] + c[1] * point[0] + c[2] * point[2]
                val imaginary = c[1] * point[1] + c[2] * point[3]
                val denominatorReal = 1.0 + c[3] * point[0] + c[4] * point[2]
                val denominatorImaginary = c[3] * point[1] + c[4] * point[3]
                power *= (real * real + imaginary * imaginary) /
                    (denominatorReal * denominatorReal + denominatorImaginary * denominatorImaginary).coerceAtLeast(1e-30)
            }
            peak = max(peak, power)
        }
        return 10.0 * log10(peak)
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
        return if (a <= 0.95f) sample else sign(sample) * (0.95f + 0.05f * tanh((a - 0.95f) / 0.05f))
    }

    private class Biquad(channels: Int) {
        private val z1 = DoubleArray(channels)
        private val z2 = DoubleArray(channels)
        private val current = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
        private var next = current.copyOf()
        private var ramp = 0
        fun target(value: DoubleArray, frames: Int) { next = value; ramp = frames }
        fun reset() {
            z1.fill(0.0); z2.fill(0.0)
            current.fill(0.0); current[0] = 1.0; next = current.copyOf(); ramp = 0
        }
        fun process(input: Float, channel: Int): Float {
            if (channel == 0 && ramp > 0) {
                for (i in current.indices) current[i] += (next[i] - current[i]) / ramp
                ramp--
            }
            val out = current[0] * input + z1[channel]
            z1[channel] = current[1] * input - current[3] * out + z2[channel]
            z2[channel] = current[2] * input - current[4] * out
            if (!out.isFinite()) { z1[channel] = 0.0; z2[channel] = 0.0; return 0f }
            return out.toFloat()
        }
    }
    private fun coefficients(rate: Double, frequency: Double, db: Double, shelf: Boolean): DoubleArray {
        // A band above Nyquist cannot be rendered; don't shift it into the audible mids.
        if (abs(db) < 0.001 || frequency >= rate * .5) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
        val omega = 2.0 * PI * frequency.coerceIn(10.0, rate * 0.49) / rate
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
        return doubleArrayOf(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }
}
