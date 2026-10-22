package com.example.playback

import androidx.compose.runtime.Immutable

enum class PlaybackSourceKind { YOUTUBE, JIOSAAVN, INTERNET_ARCHIVE, ADDON }

/** Catalog identity stays on MusicTrack; this is only a configured playback provider. */
@Immutable
data class PlaybackSource(val id: String, val kind: PlaybackSourceKind, val name: String,
    val enabled: Boolean = true, val baseUrl: String = "") {
    fun available(ceiling: AudioQualityCeiling): Boolean = when (kind) {
        PlaybackSourceKind.YOUTUBE -> true
        PlaybackSourceKind.JIOSAAVN -> ceiling == AudioQualityCeiling.HIGH || ceiling == AudioQualityCeiling.MAX
        PlaybackSourceKind.INTERNET_ARCHIVE, PlaybackSourceKind.ADDON -> ceiling == AudioQualityCeiling.MAX
    }

    companion object {
        val youtube = PlaybackSource("youtube", PlaybackSourceKind.YOUTUBE, "YouTube Music")
        val jio = PlaybackSource("jiosaavn", PlaybackSourceKind.JIOSAAVN, "JioSaavn")
        val archive = PlaybackSource("internet_archive", PlaybackSourceKind.INTERNET_ARCHIVE, "Internet Archive")
        val builtIns = listOf(youtube, jio, archive)
        @Suppress("UNUSED_PARAMETER") // Quality filters availability, never silently changes saved ordering.
        fun defaults(ceiling: AudioQualityCeiling): List<PlaybackSource> = builtIns

        fun addonId(url: String): String = "custom_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
    }
}
