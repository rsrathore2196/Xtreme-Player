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
    val online: Boolean = true, val sources: List<PlaybackSource> = emptyList(),
    val returnToYouTube: Boolean = false) {
    val maximum: Boolean get() = ceiling == AudioQualityCeiling.MAX
    val preferOpus: Boolean get() = ceiling == AudioQualityCeiling.HIGH
    val configuredSources: List<PlaybackSource> get() = sources.ifEmpty { PlaybackSource.defaults(ceiling) }
    val playbackSources: List<PlaybackSource> get() {
        val enabled = configuredSources.filter { it.enabled && it.available(ceiling) &&
            (it.kind != PlaybackSourceKind.ADDON || it.baseUrl.isNotBlank()) }
        // YouTube remains the final safety net even when every switch is off.
        return if (enabled.any { it.kind == PlaybackSourceKind.YOUTUBE }) enabled else enabled + PlaybackSource.youtube
    }
    /** Explicit enhancement considers eligible alternatives before the base YouTube stream. */
    val resolutionSources: List<PlaybackSource> get() = if (returnToYouTube && !enhancer)
        playbackSources.filter { it.kind == PlaybackSourceKind.YOUTUBE } + playbackSources.filter { it.kind != PlaybackSourceKind.YOUTUBE }
        else if (enhancer)
        playbackSources.filter { it.kind != PlaybackSourceKind.YOUTUBE } + playbackSources.filter { it.kind == PlaybackSourceKind.YOUTUBE }
        else playbackSources
    fun allows(kind: PlaybackSourceKind): Boolean = playbackSources.any { it.kind == kind }
    val allowsArchive: Boolean get() = maximum && online && allows(PlaybackSourceKind.INTERNET_ARCHIVE)
    val key: String get() = "sources-v5:${ceiling.name}:$enhancer:$returnToYouTube:$online:" +
        playbackSources.joinToString(",") { it.id }
    /** Only an activated decoder format can make the switch automatically reflect a preferred source. */
    fun automaticallyEnhanced(state: SignalPathState): Boolean {
        if (returnToYouTube || !state.quality.verified || state.quality.codec.isBlank() || state.trackId.isBlank() ||
            state.quality.bitrateKbps.toLong() * 1000 > ceiling.maxBitrateBps) return false
        val preferred = playbackSources.first()
        return preferred.kind != PlaybackSourceKind.YOUTUBE && preferred.id == state.sourceId &&
            (preferred.kind != PlaybackSourceKind.INTERNET_ARCHIVE || state.quality.lossless)
    }
    fun isPreferred(track: com.example.data.model.MusicTrack): Boolean {
        val first = resolutionSources.first()
        val host = android.net.Uri.parse(track.audioUrl).host.orEmpty()
        return when (first.kind) {
            PlaybackSourceKind.YOUTUBE -> com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(track.id, track.audioUrl)
            PlaybackSourceKind.JIOSAAVN -> host == "saavncdn.com" || host.endsWith(".saavncdn.com")
            PlaybackSourceKind.INTERNET_ARCHIVE -> host == "archive.org" || host.endsWith(".archive.org")
            PlaybackSourceKind.ADDON -> com.example.data.remote.StreamingAddons.fromSource(track.audioUrl, first.id)
        }
    }
    fun acceptsRemoteTrack(track: com.example.data.model.MusicTrack): Boolean {
        if (com.example.data.remote.StreamingAddons.isVerified(track.audioUrl, this)) return true
        val uri = android.net.Uri.parse(track.audioUrl)
        if (uri.scheme in setOf("file", "content")) return true
        val archive = uri.host.orEmpty().let { it == "archive.org" || it.endsWith(".archive.org") }
        if (archive) return maximum && allows(PlaybackSourceKind.INTERNET_ARCHIVE)
        if (track.source.startsWith("Streaming Addon:")) return false
        val host = uri.host.orEmpty()
        val youtube = com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(track.id, track.audioUrl)
        val rate = track.bitrateKbps.takeIf { it > 0 } ?: saavnRate.find(track.audioUrl)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (rate != null && rate.toLong() * 1000 > ceiling.maxBitrateBps) return false
        // A missing representation bitrate is unknown, never a manufactured value.
        // Virtual YouTube streams carry the extractor's enforced ceiling instead.
        if (youtube) return rate != null || uri.getQueryParameter("cap")?.toIntOrNull()
            ?.let { it <= ceiling.maxBitrateBps } == true
        return allows(PlaybackSourceKind.JIOSAAVN) && rate != null && (host == "saavncdn.com" || host.endsWith(".saavncdn.com"))
    }
    private companion object { val saavnRate = Regex("_(320|160|96|48)\\.(?:mp4|m4a)") }
}
