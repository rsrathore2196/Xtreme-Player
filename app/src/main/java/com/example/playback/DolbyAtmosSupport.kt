package com.example.playback

import android.media.MediaCodecList
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/** Capability/format checks for encoded Atmos sources, not a stereo upmixer or system effect. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object DolbyAtmosSupport {
    @Volatile private var cached: Boolean? = null

    fun qualityEligible(quality: AudioQualityCeiling): Boolean =
        quality == AudioQualityCeiling.HIGH || quality == AudioQualityCeiling.MAX

    fun allowed(requested: Boolean, quality: AudioQualityCeiling, supported: Boolean): Boolean =
        requested && qualityEligible(quality) && supported

    fun isAtmosCodec(codec: String): Boolean = codec.lowercase(java.util.Locale.ROOT)
        .replace('_', '-').replace(' ', '-') in setOf("eac3-joc", "ec3-joc", "ec-3-joc", "e-ac-3-joc", "dolby-atmos", "audio/eac3-joc")

    fun isAtmosMime(mime: String?): Boolean = mime == MimeTypes.AUDIO_E_AC3_JOC

    internal fun supportsTypes(types: Iterable<String>): Boolean = types.any {
        it.equals(MimeTypes.AUDIO_E_AC3_JOC, true) || it.equals(MimeTypes.AUDIO_E_AC3, true)
    }

    /** IO only. Media3 also accepts the compatible E-AC-3 core decoder for JOC input. */
    fun supported(): Boolean {
        cached?.let { return it }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return false
        return synchronized(this) {
            cached ?: runCatching {
                listOf(MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_E_AC3).any {
                    MediaCodecUtil.getDecoderInfos(it, false, false).isNotEmpty()
                }
            }.getOrElse {
                runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
                    !it.isEncoder && supportsTypes(it.supportedTypes.asIterable())
                } }.getOrDefault(false)
            }.also { cached = it }
        }
    }
}
