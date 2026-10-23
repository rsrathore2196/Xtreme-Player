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
    val online: Boolean = true, val sources: List<PlaybackSource> = PlaybackSource.defaults(ceiling),
    val returnToYouTube: Boolean = false, val requestRevision: Long = 0L, val requestTrackId: String = "", val ready: Boolean = true) {
    val maximum: Boolean get() = ceiling == AudioQualityCeiling.MAX
    val preferOpus: Boolean get() = ceiling == AudioQualityCeiling.HIGH
    val configuredSources: List<PlaybackSource> get() = PlaybackSource.ordered(sources)
    val playbackSources: List<PlaybackSource> get() {
        val enabled = configuredSources.filter { it.enabled && it.available(ceiling) &&
            (it.kind != PlaybackSourceKind.ADDON || it.baseUrl.isNotBlank()) }
        val builtInOrder = if (maximum) listOf(PlaybackSourceKind.INTERNET_ARCHIVE, PlaybackSourceKind.JIOSAAVN, PlaybackSourceKind.YOUTUBE)
            else listOf(PlaybackSourceKind.YOUTUBE, PlaybackSourceKind.JIOSAAVN)
        return enabled.filter { it.kind == PlaybackSourceKind.ADDON } +
            builtInOrder.flatMap { kind -> enabled.filter { it.kind == kind } }
    }
    val resolutionSources: List<PlaybackSource> get() = resolutionSourcesFor(requestTrackId)
    fun returnsToYouTube(trackId: String): Boolean = returnToYouTube && !enhancer &&
        (requestTrackId.isBlank() || requestTrackId == trackId)
    fun shouldReturnToYouTube(track: com.example.data.model.MusicTrack, originalSourceId: String?): Boolean =
        returnsToYouTube(track.id) || !acceptsRemoteTrack(track) && originalSourceId != null &&
            playbackSources.none { it.id == originalSourceId }
    fun resolutionSourcesFor(trackId: String): List<PlaybackSource> = if (returnsToYouTube(trackId))
        playbackSources.filter { it.kind == PlaybackSourceKind.YOUTUBE } + playbackSources.filter { it.kind != PlaybackSourceKind.YOUTUBE }
        else playbackSources
    fun allows(kind: PlaybackSourceKind): Boolean = playbackSources.any { it.kind == kind }
    fun sourceId(kind: PlaybackSourceKind): String? = configuredSources.firstOrNull { it.kind == kind }?.id
    val allowsArchive: Boolean get() = maximum && online && allows(PlaybackSourceKind.INTERNET_ARCHIVE)
    val key: String get() = "sources-v8:${ceiling.name}:$enhancer:$returnToYouTube:$online:$requestRevision:$requestTrackId:$ready:" +
        playbackSources.joinToString(",") { "${it.id}:${it.kind.name}:${it.baseUrl.hashCode()}" }
    /** Only an activated decoder format can make the switch automatically reflect a preferred source. */
    fun automaticallyEnhanced(state: SignalPathState): Boolean {
        if (!state.quality.verified || state.quality.codec.isBlank() || state.trackId.isBlank() ||
            !acceptsStreamBitrate(state.quality.bitrateKbps, android.net.Uri.parse(state.streamUri))) return false
        // A verified fallback above YouTube is also a real substituted source, even
        // when an earlier enabled server did not have this particular recording.
        val preferred = playbackSources.firstOrNull { it.id == state.sourceId } ?: return false
        return preferred.kind != PlaybackSourceKind.YOUTUBE &&
            (preferred.kind != PlaybackSourceKind.INTERNET_ARCHIVE || state.quality.lossless)
    }
    fun hasVerifiedUpgrade(state: SignalPathState): Boolean = state.quality.verified && state.quality.codec.isNotBlank() &&
        state.trackId.isNotBlank() && state.sourceId.isNotBlank() &&
        state.sourceId != sourceId(PlaybackSourceKind.YOUTUBE) &&
        ((state.enhancerOriginalSourceId.isNotBlank() && state.enhancerOriginalUri.isNotBlank() &&
            state.streamUri.isNotBlank() && state.enhancerOriginalSourceId != state.sourceId &&
            state.enhancerOriginalUri != state.streamUri) || automaticallyEnhanced(state))
    fun isPreferred(track: com.example.data.model.MusicTrack): Boolean {
        val first = resolutionSourcesFor(track.id).firstOrNull() ?: return false
        val host = android.net.Uri.parse(track.audioUrl).host.orEmpty()
        return when (first.kind) {
            PlaybackSourceKind.YOUTUBE -> com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(track.id, track.audioUrl)
            PlaybackSourceKind.JIOSAAVN -> host == "saavncdn.com" || host.endsWith(".saavncdn.com")
            PlaybackSourceKind.INTERNET_ARCHIVE -> host == "archive.org" || host.endsWith(".archive.org")
            PlaybackSourceKind.ADDON -> com.example.data.remote.StreamingAddons.fromSource(track.audioUrl, first.id)
        }
    }
    fun acceptsStreamBitrate(bitrateKbps: Int, uri: android.net.Uri): Boolean {
        if (bitrateKbps <= 0 || bitrateKbps.toLong() * 1000 <= ceiling.maxBitrateBps) return true
        // The 320 AAC endpoint can declare a small VBR/container overhead. Keep
        // its actual reported rate, without retrying a valid tier in a loop.
        // This allowance never applies to capped YouTube/Data Saver streams.
        val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        return ceiling == AudioQualityCeiling.HIGH && allows(PlaybackSourceKind.JIOSAAVN) &&
            (host == "saavncdn.com" || host.endsWith(".saavncdn.com")) &&
            saavnRate.find(uri.path.orEmpty())?.groupValues?.get(1) == "320" && bitrateKbps <= 336
    }
    fun acceptsRemoteTrack(track: com.example.data.model.MusicTrack): Boolean {
        if (com.example.data.remote.StreamingAddons.allowsRegisteredTransport(track.audioUrl, this)) return true
        val uri = android.net.Uri.parse(track.audioUrl)
        if (uri.scheme in setOf("file", "content")) return true
        val archive = uri.host.orEmpty().let { it == "archive.org" || it.endsWith(".archive.org") }
        if (archive) return maximum && allows(PlaybackSourceKind.INTERNET_ARCHIVE)
        if (track.source.startsWith("Streaming Addon:")) return false
        val host = uri.host.orEmpty()
        val youtube = com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(track.id, track.audioUrl)
        val rate = track.bitrateKbps.takeIf { it > 0 } ?: saavnRate.find(track.audioUrl)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (rate != null && !acceptsStreamBitrate(rate, uri)) return false
        // A missing representation bitrate is unknown, never a manufactured value.
        // Virtual YouTube streams carry the extractor's enforced ceiling instead.
        if (youtube) return allows(PlaybackSourceKind.YOUTUBE) && (rate != null ||
            com.example.data.remote.YouTubeAudioStreamService.bitrateCapFromUri(uri)?.let { it <= ceiling.maxBitrateBps } == true)
        return allows(PlaybackSourceKind.JIOSAAVN) && rate != null && (host == "saavncdn.com" || host.endsWith(".saavncdn.com"))
    }
    private companion object { val saavnRate = Regex("_(320|160|96|48)\\.(?:mp4|m4a)(?:$|[?#])") }
}
