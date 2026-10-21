package com.example.playback

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** AudioTrack output precision, independent of the source codec and network quality. */
@Immutable
enum class OutputPrecision(val label: String) {
    PCM_16("16-bit PCM"), FLOAT_32("32-bit float");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: PCM_16
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object OutputPrecisionPolicy {
    fun allowsFloat(deviceType: Int?, encodings: IntArray, directFloatSupported: Boolean = false): Boolean = deviceType in setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE
    ) && (C.ENCODING_PCM_FLOAT in encodings || directFloatSupported)

    fun supportsFloat(device: AudioDeviceInfo, sampleRate: Int, channels: Int,
        attributes: android.media.AudioAttributes): Boolean {
        if (allowsFloat(device.type, device.encodings)) return true
        if (!allowsFloat(device.type, intArrayOf(), directFloatSupported = true) || Build.VERSION.SDK_INT < 33) return false
        return runCatching { DirectFloat.supported(sampleRate, channels, attributes) }.getOrDefault(false)
    }

    @RequiresApi(33)
    private object DirectFloat {
        fun supported(rate: Int, channels: Int, attributes: android.media.AudioAttributes): Boolean {
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(androidx.media3.common.util.Util.getAudioTrackChannelConfig(channels)).build()
            return AudioManager.getDirectPlaybackSupport(format, attributes) != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
        }
    }

    fun encoding(precision: OutputPrecision, floatSupported: Boolean, skipSilence: Boolean): Int =
        if (precision == OutputPrecision.FLOAT_32 && floatSupported && !skipSilence) C.ENCODING_PCM_FLOAT
        else C.ENCODING_PCM_16BIT
}

/** Reusable conversion at the DSP/output boundary. No sample-rate or channel changes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OutputPcmConverter {
    private var storage = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)

    fun convert(input: ByteBuffer, sourceEncoding: Int, targetEncoding: Int): ByteBuffer {
        if (sourceEncoding == targetEncoding) return input
        val sourceBytes = when (sourceEncoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
            else -> error("Unsupported PCM encoding: $sourceEncoding")
        }
        require(input.remaining() % sourceBytes == 0)
        val targetBytes = if (targetEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
        require(targetEncoding == C.ENCODING_PCM_FLOAT || targetEncoding == C.ENCODING_PCM_16BIT)
        val size = input.remaining() / sourceBytes * targetBytes
        if (storage.capacity() < size) storage = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        storage.clear(); storage.limit(size)
        val bigEndian = sourceEncoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN ||
            sourceEncoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN || sourceEncoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN
        val samples = input.duplicate().order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        while (samples.hasRemaining()) {
            // Double keeps integer PCM32 rounding at the boundary deterministic.
            val sample = when (sourceEncoding) {
                C.ENCODING_PCM_8BIT -> ((samples.get().toInt() and 255) - 128) / 128.0
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> samples.short / 32768.0
                C.ENCODING_PCM_24BIT -> {
                    val a = samples.get().toInt() and 255
                    val b = samples.get().toInt() and 255
                    val c = samples.get().toInt()
                    (a or (b shl 8) or (c shl 16)) / 8388608.0
                }
                C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                    val a = samples.get().toInt()
                    val b = samples.get().toInt() and 255
                    val c = samples.get().toInt() and 255
                    ((a shl 16) or (b shl 8) or c) / 8388608.0
                }
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> samples.int / 2147483648.0
                else -> samples.float.toDouble().let { if (it.isFinite()) it else 0.0 }
            }
            if (targetEncoding == C.ENCODING_PCM_FLOAT) storage.putFloat(sample.toFloat())
            else storage.putShort((sample.coerceIn(-1.0, 1.0) * 32768.0).roundToInt()
                .coerceIn(-32768, 32767).toShort())
        }
        input.position(input.limit())
        storage.flip()
        return storage
    }
}
