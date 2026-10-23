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
        val builtIns = listOf(archive, jio, youtube)
        /** Optional providers require an explicit choice; existing saved switches are left intact. */
        val newInstallDefaults = builtIns.map { it.copy(enabled = it.kind == PlaybackSourceKind.YOUTUBE) }
        /** Only user-added servers have a user-defined order; built-in fallback ranks are fixed. */
        fun ordered(sources: List<PlaybackSource>): List<PlaybackSource> {
            val unique = sources.distinctBy { it.id }
            return (unique + builtIns.filter { builtin -> unique.none { it.kind == builtin.kind } })
                .map { if (it.kind == PlaybackSourceKind.YOUTUBE) youtube else it }
                .sortedBy { when (it.kind) {
                    PlaybackSourceKind.ADDON -> 0
                    PlaybackSourceKind.INTERNET_ARCHIVE -> 1
                    PlaybackSourceKind.JIOSAAVN -> 2
                    PlaybackSourceKind.YOUTUBE -> 3
                } }
        }
        @Suppress("UNUSED_PARAMETER") // Quality filters availability, never silently changes saved ordering.
        fun defaults(ceiling: AudioQualityCeiling): List<PlaybackSource> = ordered(builtIns)

        fun addonId(url: String): String = "custom_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
    }
}
