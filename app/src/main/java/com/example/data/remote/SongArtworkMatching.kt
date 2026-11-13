package com.example.data.remote

import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogNames
import com.example.data.repository.RecordingIdentity
import com.example.data.repository.StreamResolver
import kotlin.math.abs

private val videoSeparators = Regex("\\s*\\|\\s*|\\s+[–—-]\\s+|\\s*[•·]\\s*")
private val videoPresentation = Regex("(?i)\\b(?:official|video|visualizer|lyric|full song)\\b")
private val numberedPart = Regex("(?i)\\b(?:part|pt|chapter|vol(?:ume)?)\\.?\\s*\\d+")

/** Upload captions may contain cast/release credits; recording versions remain mandatory. */
internal fun videoArtworkTitleVariants(track: MusicTrack): List<MusicTrack> {
    val cleaned = StreamResolver.extractCleanTitle(track.title, track.artist)
    val parts = videoSeparators.split(cleaned)
    var head = parts.firstOrNull().orEmpty().trim()
    if (parts.drop(1).any { numberedPart.containsMatchIn(it) }) return emptyList()
    if (videoPresentation.containsMatchIn(track.originalProviderTitle.ifBlank { track.title }) && ':' in head)
        head = head.substringBefore(':').trim()
    if (head.isBlank() || head == cleaned) return emptyList()
    val versions = RecordingIdentity.signature(track).versions
    val headVersions = RecordingIdentity.signature(track.copy(title = head)).versions
    val missing = versions - headVersions
    if (missing.isNotEmpty()) head += " (${missing.sorted().joinToString(" ")})"
    return listOf(track.copy(title = head))
}

internal fun songArtworkQueries(track: MusicTrack): List<String> {
    val full = artworkIdentityVariants(track).first()
    val artist = RecordingIdentity.artistNames(track.copy(title = "")).firstOrNull().orEmpty()
    val core = videoArtworkTitleVariants(full).firstOrNull()?.title ?: full.title
    // With absent credits keep an Artist - Title prefix searchable rather than
    // interpreting the artist's name alone as the song's title.
    val title = if (artist.isBlank() && " - " in full.title) full.title else core
    return listOf("$title $artist".trim(), title.trim()).filter(String::isNotBlank).distinct().take(2)
}

/** Compare audio releases for artwork only; neither playback nor queue identity changes. */
internal fun songArtworkMatchScore(requested: MusicTrack, candidate: MusicTrack): Int? {
    val wanted = RecordingIdentity.signature(requested)
    val found = RecordingIdentity.signature(candidate)
    if (wanted.isrc != null && found.isrc != null && wanted.isrc != found.isrc) return null
    val credits = RecordingIdentity.artistNames(candidate.copy(title = ""))
    if (credits.isEmpty()) return null
    val requestedCredits = RecordingIdentity.artistNames(requested.copy(title = ""))
    val primaryMatches = requestedCredits.firstOrNull()?.let { CatalogNames.spellingEquivalent(it, credits.first()) } == true
    val raw = requested.originalProviderTitle.ifBlank { requested.title }
    val clauses = videoSeparators.split(raw)
    val artistPrefix = " - " in raw && CatalogNames.normalize(clauses.firstOrNull().orEmpty()) == credits.first()
    val explicitCredits = artistPrefix || clauses.drop(1).any { clause ->
        val normalized = " ${CatalogNames.normalize(clause)} "
        normalized.contains(" ${credits.first()} ")
    }
    if (!primaryMatches && !explicitCredits) return null
    // If the upload already names its primary singer in its title, a shared
    // guest must not replace that singer's recording with a different one.
    if (!primaryMatches && requestedCredits.isNotEmpty() && clauses.any {
            " ${CatalogNames.normalize(it)} ".contains(" ${requestedCredits.first()} ")
        }) return null
    val proofCredits = if (primaryMatches) emptyList() else credits
    val comparable = if (primaryMatches) requested else requested.copy(artist = credits.joinToString(), primaryArtists = credits)
    val titlesMatch = artworkIdentityVariants(comparable, proofCredits).any { a ->
        artworkIdentityVariants(candidate).any { b ->
            val first = RecordingIdentity.signature(a)
            val second = RecordingIdentity.signature(b)
            first.title.isNotBlank() && first.title == second.title && first.versions == second.versions
        }
    }
    if (!titlesMatch) return null
    val albumMatches = wanted.album != null && wanted.album == found.album
    val delta = if (requested.durationMs > 0 && candidate.durationMs > 0) abs(requested.durationMs - candidate.durationMs) else 120_000L
    return (if (wanted.isrc != null && wanted.isrc == found.isrc) 1000 else 0) +
        (if (primaryMatches) 200 else 100) + (if (albumMatches) 40 else 0) +
        (30 - (delta / 1000).coerceAtMost(30).toInt())
}
