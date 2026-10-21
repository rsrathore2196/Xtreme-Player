package com.example.playback

import androidx.compose.runtime.Immutable

@Immutable
enum class AudioQualityCeiling(val maxBitrateBps: Int, val label: String, val description: String) {
    DATA_SAVER(96_000, "Data Saver (~96 kbps)", "Lowest data usage for slow connections"),
    NORMAL(160_000, "Standard (~160 kbps)", "Balanced audio quality and data efficiency"),
    HIGH(320_000, "High (~320 kbps)", "Best lossy quality (Opus/AAC)"),
    MAX(Int.MAX_VALUE, "Maximum / Lossless", "Uncapped bitrate — highest available source stream");

    /** Existing playback/Archive policy consumes this enum; profiles now own the preference. */
    val audioQuality: AudioQuality get() = when (this) {
        DATA_SAVER -> AudioQuality.MEDIUM_96
        NORMAL -> AudioQuality.HIGH_160
        HIGH -> AudioQuality.ULTRA_HD_320
        MAX -> AudioQuality.HI_RES_LOSSLESS
    }

    companion object {
        fun fromName(value: String?, fallback: AudioQualityCeiling): AudioQualityCeiling =
            entries.firstOrNull { it.name == value } ?: fallback

        /** JioSaavn publishes discrete AAC rates; never manufacture a 64/128/256 URL. */
        fun saavnKbps(requestedKbps: Int, availableKbps: Int = 320, maxBitrateBps: Int = Int.MAX_VALUE): Int {
            val limit = minOf(requestedKbps, availableKbps.takeIf { it > 0 } ?: 320, maxBitrateBps / 1000)
            return listOf(320, 160, 96, 48).firstOrNull { it <= limit } ?: 48
        }
    }
}

@Immutable
data class NetworkAudioPreferences(
    val wifiQualityCeiling: AudioQualityCeiling = AudioQualityCeiling.MAX,
    val cellularQualityCeiling: AudioQualityCeiling = AudioQualityCeiling.HIGH,
    val treatMeteredWifiAsCellular: Boolean = true
)

enum class NetworkConnectionType { UNMETERED_WIFI_OR_ETHERNET, METERED_WIFI, CELLULAR, OFFLINE }

@Immutable
data class ActiveQualityState(
    val effectiveCeiling: AudioQualityCeiling = AudioQualityCeiling.HIGH,
    val connectionType: NetworkConnectionType = NetworkConnectionType.OFFLINE
) {
    val allowsLosslessUpgrade: Boolean get() = effectiveCeiling == AudioQualityCeiling.MAX &&
        connectionType != NetworkConnectionType.OFFLINE
}

/** Source policy is independent of catalog identity and renderer/DSP settings. */
@Immutable
data class PlaybackSourcePolicy(val ceiling: AudioQualityCeiling, val enhancer: Boolean = false,
    val online: Boolean = true) {
    val maximum: Boolean get() = ceiling == AudioQualityCeiling.MAX
    val preferOpus: Boolean get() = !maximum && enhancer
    val allowsArchive: Boolean get() = maximum && online
    val key: String get() = "${ceiling.name}:${preferOpus}:$online"
    fun acceptsRemoteTrack(track: com.example.data.model.MusicTrack): Boolean {
        if (com.example.data.remote.StreamingAddons.isVerified(track.audioUrl, this)) return true
        val uri = android.net.Uri.parse(track.audioUrl)
        if (uri.scheme in setOf("file", "content") || maximum) return true
        val host = uri.host.orEmpty()
        val rate = track.bitrateKbps.takeIf { it > 0 } ?: saavnRate.find(track.audioUrl)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return false
        if (rate.toLong() * 1000 > ceiling.maxBitrateBps) return false
        return if (com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(track.id, track.audioUrl))
            preferOpus && track.qualityBadge.contains("Opus", true)
        else host == "saavncdn.com" || host.endsWith(".saavncdn.com")
    }
    private companion object { val saavnRate = Regex("_(320|160|96|48)\\.(?:mp4|m4a)") }
}
