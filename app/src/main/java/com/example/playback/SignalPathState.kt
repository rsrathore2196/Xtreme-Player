package com.example.playback

import androidx.compose.runtime.Immutable
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** The saved per-network profile remains the single source of the user's preference. */
@Immutable
enum class AudioQualityPreference(val legacy: AudioQuality) {
    MAX_LOSSLESS(AudioQuality.HI_RES_LOSSLESS), HIGH(AudioQuality.ULTRA_HD_320),
    MEDIUM(AudioQuality.HIGH_160), DATA_SAVER(AudioQuality.MEDIUM_96);
    companion object {
        fun from(quality: AudioQuality) = entries.first { it.legacy == quality }
    }
}

@Immutable
data class StreamQualityBadge(
    val codec: String = "", val bitrateKbps: Int = 0, val sampleRateHz: Int = 0,
    val bitDepth: Int = 0, val lossless: Boolean = false, val verified: Boolean = false
) {
    val label: String get() {
        if (codec.isBlank()) return "Resolving audio"
        val rate = if (sampleRateHz > 0) String.format(Locale.ROOT, "%.1f kHz", sampleRateHz / 1000.0) else ""
        return if (lossless) listOf(codec, listOfNotNull(bitDepth.takeIf { it > 0 }?.let { "$it-bit" },
            rate.takeIf(String::isNotEmpty)).joinToString("/")).filter(String::isNotEmpty).joinToString(" • ")
        else listOf(codec, bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" } ?: rate)
            .filter(String::isNotEmpty).joinToString(" • ")
    }
    companion object {
        fun from(format: Format, fallbackBitrate: Int = 0): StreamQualityBadge {
            val codec = when (format.sampleMimeType) {
                MimeTypes.AUDIO_FLAC -> "FLAC"; MimeTypes.AUDIO_ALAC -> "ALAC"; MimeTypes.AUDIO_RAW -> "PCM"
                MimeTypes.AUDIO_OPUS -> "OPUS"; MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_MPEG -> "MP3"
                MimeTypes.AUDIO_VORBIS -> "VORBIS"; else -> format.codecs.orEmpty().uppercase(Locale.ROOT)
            }
            val lossless = format.sampleMimeType in setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW)
            val bits = when (format.pcmEncoding) {
                C.ENCODING_PCM_8BIT -> 8; C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
                else -> 0 // Decoder float precision is not evidence of the source's bit depth.
            }
            return StreamQualityBadge(codec, format.averageBitrate.takeIf { it > 0 }?.div(1000)
                ?: format.peakBitrate.takeIf { it > 0 }?.div(1000) ?: fallbackBitrate,
                format.sampleRate.coerceAtLeast(0), if (lossless) bits else 0, lossless, true)
        }
    }
}

@Immutable
data class SignalPathState(
    val trackId: String = "", val source: String = "Unknown", val quality: StreamQualityBadge = StreamQualityBadge(),
    val inputRateHz: Int = 0, val outputRateHz: Int = 0, val appResampling: Boolean = false, val bitPerfect: Boolean = false,
    val directApproved: Boolean = false, val outputDevice: String = "System output", val outputType: String = "Unknown",
    val dspActive: Boolean = false, val clarityEnabled: Boolean = false,
    val enhancerEnabled: Boolean = false, val eqActive: Boolean = false,
    val appVolume: Float = 1f, val systemVolume: Int = 0, val systemVolumeMax: Int = 0,
    val mode: String = "Shared", val buffering: Boolean = false, val glitches: Int = 0,
    val outputEncoding: Int = C.ENCODING_INVALID, val outputChannels: Int = 0,
    val outputRouteVerified: Boolean = false, val playbackSpeed: Float = 1f, val pitch: Float = 1f,
    val playbackStatus: String = "Idle"
) {
    val status: String get() = when { bitPerfect -> "Bit-Perfect"; dspActive -> "Enhanced"; else -> "High Quality" }
    val outputFormat: String get() {
        val encoding = when (outputEncoding) {
            C.ENCODING_PCM_FLOAT -> "32-bit float PCM"
            C.ENCODING_PCM_8BIT -> "8-bit PCM"
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "16-bit PCM"
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "24-bit PCM"
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "32-bit PCM"
            else -> "Format not reported"
        }
        val channels = when (outputChannels) { 1 -> "Mono"; 2 -> "Stereo"; in 3..32 -> "$outputChannels channels"; else -> "" }
        val rate = outputRateHz.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1f kHz", it / 1000.0) }.orEmpty()
        return listOf(encoding, rate, channels).filter(String::isNotEmpty).joinToString(" • ")
    }
}

/** One service-owned presentation channel; no polling, disk reads, or UI dependencies. */
object AudioSignalPath {
    private val mutable = MutableStateFlow(SignalPathState())
    val state = mutable.asStateFlow()
    internal fun update(block: (SignalPathState) -> SignalPathState) { mutable.update(block) }
    internal fun reset() { mutable.value = SignalPathState() }
    internal fun source(id: String, provider: String) = update { old ->
        if (old.trackId == id && old.source == provider) old else old.copy(trackId = id, source = provider,
            quality = StreamQualityBadge(), glitches = 0)
    }
    internal fun format(id: String, value: Format, bitrate: Int) = update { old ->
        if (old.trackId != id) old else old.copy(quality = StreamQualityBadge.from(value, bitrate))
    }
}
