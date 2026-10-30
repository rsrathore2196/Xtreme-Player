package com.example.data.model

import androidx.compose.runtime.Immutable

/**
 * Dynamic server-driven recommendation shelf fetched directly from YouTube Music.
 * Preserves server title, subtitle, track order, artwork, and genuine video IDs.
 */
@Immutable
data class ServerHomeShelf(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val tracks: List<MusicTrack> = emptyList(),
    val continuationToken: String? = null
)
