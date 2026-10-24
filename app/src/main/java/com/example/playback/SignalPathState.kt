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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
data class StreamQualityBadge(
    val codec: String = "", val bitrateKbps: Int = 0, val sampleRateHz: Int = 0,
    val bitDepth: Int = 0, val lossless: Boolean = false, val verified: Boolean = false
) {
    /** Compact source-quality classification; never infer depth from decoder/output precision. */
    val badgeLabel: String get() = when {
        verified && lossless && bitDepth >= 24 && sampleRateHz >= 48_000 -> "Hi-Res Lossless"
        verified && lossless && bitDepth >= 16 && sampleRateHz >= 44_100 -> "Lossless"
        verified && codec == "E-AC-3 JOC" -> "Dolby Atmos"
        else -> label
    }
    val label: String get() {
        if (codec.isBlank()) return "Resolving audio"
        val rate = if (sampleRateHz > 0) String.format(Locale.ROOT, "%.1f kHz", sampleRateHz / 1000.0) else ""
        return if (lossless) {
            val bitRate = listOfNotNull(bitDepth.takeIf { it > 0 }?.let { "$it-bit" }, rate.takeIf(String::isNotEmpty)).joinToString("/")
            listOfNotNull(codec, bitRate.takeIf(String::isNotEmpty)).joinToString(" • ")
        } else {
            val details = bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" } ?: rate
            listOfNotNull(codec, details.takeIf(String::isNotEmpty)).joinToString(" • ")
        }
    }
    companion object {
        fun from(format: Format, fallbackBitrate: Int = 0): StreamQualityBadge {
            val mime = format.sampleMimeType
            val codecs = format.codecs
            val codec = when {
                mime == MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3 JOC"
                mime == MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
                mime == MimeTypes.AUDIO_FLAC -> "FLAC"
                mime == MimeTypes.AUDIO_ALAC -> "ALAC"
                mime == MimeTypes.AUDIO_RAW -> "PCM"
                mime == MimeTypes.AUDIO_OPUS || codecs?.contains("opus", ignoreCase = true) == true -> "OPUS"
                mime == MimeTypes.AUDIO_AAC || codecs?.contains("mp4a", ignoreCase = true) == true -> "AAC"
                mime == MimeTypes.AUDIO_MPEG -> "MP3"
                mime == MimeTypes.AUDIO_VORBIS || codecs?.contains("vorbis", ignoreCase = true) == true -> "VORBIS"
                !codecs.isNullOrBlank() -> codecs.uppercase(Locale.ROOT)
                !mime.isNullOrBlank() -> mime.substringAfterLast('/').substringAfterLast('-').uppercase(Locale.ROOT)
                else -> ""
            }
            val lossless = mime in setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW)
            val bits = when (format.pcmEncoding) {
                C.ENCODING_PCM_8BIT -> 8; C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
                else -> 0 // Decoder float precision is not evidence of the source's bit depth.
            }
            val actualBitrate = format.averageBitrate.takeIf { it > 0 }?.div(1000)
                ?: format.peakBitrate.takeIf { it > 0 }?.div(1000) ?: fallbackBitrate.takeIf { it > 0 } ?: 0
            return StreamQualityBadge(codec, actualBitrate,
                format.sampleRate.coerceAtLeast(0), if (lossless) bits else 0, lossless, true)
        }
    }
}

@Immutable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
data class SignalPathState(
    val trackId: String = "", val source: String = "Unknown", val quality: StreamQualityBadge = StreamQualityBadge(),
    val inputRateHz: Int = 0, val outputRateHz: Int = 0, val appResampling: Boolean = false, val bitPerfect: Boolean = false,
    val directApproved: Boolean = false, val outputDevice: String = "System output", val outputType: String = "Unknown",
    val dspActive: Boolean = false, val clarityEnabled: Boolean = false,
    val eqActive: Boolean = false,
    val spatialActive: Boolean = false, val skipSilenceActive: Boolean = false,
    val appVolume: Float = 1f, val systemVolume: Int = 0, val systemVolumeMax: Int = 0,
    val mode: String = "Shared", val buffering: Boolean = false, val glitches: Int = 0,
    val outputEncoding: Int = C.ENCODING_INVALID, val outputChannels: Int = 0,
    val outputRouteVerified: Boolean = false, val playbackSpeed: Float = 1f, val pitch: Float = 1f,
    val playbackStatus: String = "Idle", val sourceId: String = "", val enhancerOriginalSourceId: String = "",
    val enhancerOriginalUri: String = "", val sourceTransitionInProgress: Boolean = false,
    internal val sourceTransitionOwner: String = "",
    val streamUri: String = "", val sourceChannels: Int = 0,
    val decoderName: String = "", val decoderEncoding: Int = C.ENCODING_INVALID,
    val dspEncoding: Int = C.ENCODING_INVALID, val dspRateHz: Int = 0,
    val bufferSizeFrames: Int = 0, val bufferCapacityFrames: Int = 0,
    val enhancerPolicyKey: String = ""
) {
    val status: String get() = when { bitPerfect -> "Bit-Perfect"; dspActive -> "Enhanced"; else -> "High Quality" }
    val outputFormat: String get() {
        val encoding = pcmEncodingLabel(outputEncoding)
        val channels = channelLabel(outputChannels).takeUnless { it == "—" }.orEmpty()
        val rate = outputRateHz.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1f kHz", it / 1000.0) }.orEmpty()
        return listOf(encoding, rate, channels).filter(String::isNotEmpty).joinToString(" • ")
    }
    val sourcePcmDataRateKbps: Long? get() = if (quality.verified && quality.lossless && quality.bitDepth > 0 &&
        quality.sampleRateHz > 0 && sourceChannels > 0) quality.sampleRateHz.toLong() * quality.bitDepth * sourceChannels / 1000 else null

    val sourcePrecisionReduced: Boolean get() {
        if (!quality.verified || !quality.lossless || quality.bitDepth <= 0) return false
        val decoderBits = pcmPrecisionBits(decoderEncoding).takeIf { it > 0 } ?: Int.MAX_VALUE
        val outputBits = pcmPrecisionBits(outputEncoding).takeIf { it > 0 } ?: Int.MAX_VALUE
        return minOf(decoderBits, outputBits) < quality.bitDepth
    }

    companion object {
        // IEEE float32 carries every 24-bit integer sample exactly, not every 32-bit one.
        private fun pcmPrecisionBits(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
            else -> 0
        }
        fun channelLabel(channels: Int): String = when (channels) { 1 -> "Mono"; 2 -> "Stereo"; in 3..32 -> "$channels (Surround)"; else -> "—" }
        fun pcmEncodingLabel(encoding: Int): String = when (encoding) {
            C.ENCODING_PCM_FLOAT -> "32-bit float PCM"
            C.ENCODING_PCM_8BIT -> "8-bit PCM"
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "16-bit PCM"
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "24-bit PCM"
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "32-bit PCM"
            else -> "Format not reported"
        }
    }
}

/** One service-owned presentation channel; no polling, disk reads, or UI dependencies. */
object AudioSignalPath {
    private val mutable = MutableStateFlow(SignalPathState())
    val state = mutable.asStateFlow()
    internal fun update(block: (SignalPathState) -> SignalPathState) { mutable.update(block) }
    internal fun reset() { mutable.value = SignalPathState() }
    internal fun source(id: String, provider: String, sourceId: String = "", originalSourceId: String = "", uri: String = "", originalUri: String = "", originalPolicyKey: String = "") = update { old ->
        if (old.trackId == id && old.source == provider && old.sourceId == sourceId && old.streamUri == uri) {
            if (old.enhancerOriginalSourceId == originalSourceId && old.enhancerOriginalUri == originalUri && old.enhancerPolicyKey == originalPolicyKey) old
            else old.copy(enhancerOriginalSourceId = originalSourceId, enhancerOriginalUri = originalUri, enhancerPolicyKey = originalPolicyKey)
        } else old.copy(trackId = id, source = provider, sourceId = sourceId, enhancerOriginalSourceId = originalSourceId,
            enhancerOriginalUri = originalUri, sourceTransitionInProgress = old.trackId == id && old.sourceTransitionInProgress,
            sourceTransitionOwner = old.sourceTransitionOwner.takeIf { old.trackId == id }.orEmpty(),
            streamUri = uri, quality = StreamQualityBadge(), glitches = 0, enhancerPolicyKey = originalPolicyKey,
            sourceChannels = 0, decoderName = "")
    }
    internal fun format(id: String, value: Format, bitrate: Int) = update { old ->
        if (old.trackId != id) old else old.copy(quality = StreamQualityBadge.from(value, bitrate), sourceChannels = value.channelCount.coerceAtLeast(0))
    }
    internal fun decoder(id: String, uri: String, name: String) = update { old ->
        if (old.trackId == id && old.streamUri == uri) old.copy(decoderName = name) else old
    }
    internal fun verifiedSource(id: String, provider: String, value: Format, bitrate: Int, sourceId: String = "", originalSourceId: String = "", uri: String = "", decoderNameOverride: String? = null, originalUri: String = "", originalPolicyKey: String = "") = update { old ->
        old.copy(trackId = id, source = provider, sourceId = sourceId, enhancerOriginalSourceId = originalSourceId, quality = StreamQualityBadge.from(value, bitrate), streamUri = uri,
            enhancerOriginalUri = originalUri, enhancerPolicyKey = originalPolicyKey, sourceTransitionInProgress = old.trackId == id && old.sourceTransitionInProgress,
            sourceTransitionOwner = old.sourceTransitionOwner.takeIf { old.trackId == id }.orEmpty(),
            sourceChannels = value.channelCount.coerceAtLeast(0),
            decoderName = decoderNameOverride ?: old.decoderName.takeIf { old.trackId == id && old.streamUri == uri }.orEmpty(),
            glitches = if (old.trackId == id) old.glitches else 0)
    }
    internal fun sourceRequest(id: String, pending: Boolean, owner: String = "") = update { old ->
        if (old.trackId == id && (pending || owner.isBlank() || old.sourceTransitionOwner == owner))
            old.copy(sourceTransitionInProgress = pending, sourceTransitionOwner = owner.takeIf { pending }.orEmpty()) else old
    }
    internal fun sourceBookmark(id: String, uri: String, origin: SourceTransitionOrigin?) = update { old ->
        if (old.trackId == id && old.streamUri == uri) old.copy(
            enhancerOriginalSourceId = origin?.sourceId.orEmpty(), enhancerOriginalUri = origin?.track?.audioUrl.orEmpty(),
            enhancerPolicyKey = origin?.policyKey.orEmpty()) else old
    }
}
