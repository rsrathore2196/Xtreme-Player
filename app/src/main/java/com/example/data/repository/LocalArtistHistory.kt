package com.example.data.repository

import com.example.data.local.MusicDatabase
import com.example.data.local.ActualTrackPlayCount
import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers
import com.example.recommendation.HomeRecommendationPolicy
import com.example.ui.viewmodel.MostListenedArtist
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Aggregate in SQLite, then normalize only distinct track credits, never the raw listening log. */
internal object LocalArtistHistory {
    suspend fun read(database: MusicDatabase): List<MostListenedArtist> {
        val counts = withContext(AppDispatchers.Database) { database.listeningEventDao().getActualTrackPlayCounts() }
        val credits = withContext(AppDispatchers.Database) {
            counts.map { it.trackId }.chunked(900).flatMap { database.musicDao().getArtistCreditsByIds(it) }
        }
        val ranked = withContext(AppDispatchers.CpuBounded) {
            rank(counts, credits.map { credit -> MusicTrack(credit.id, "", credit.artist, "", 0L, "", "",
                singers = credit.singers, writer = credit.writer,
                primaryArtists = runCatching { JSONArray(credit.primaryArtists).let { names ->
                    (0 until names.length()).map { names.optString(it) }.filter(String::isNotBlank)
                } }.getOrDefault(emptyList())) })
        }
        val ids = ranked.flatMap { it.tracks }.map { it.id }.distinct()
        val representatives = if (ids.isEmpty()) emptyList() else withContext(AppDispatchers.Database) {
            database.musicDao().getTracksByIds(ids)
        }
        return withContext(AppDispatchers.CpuBounded) {
            val byId = representatives.associate { it.id to it.toMusicTrack() }
            ranked.map { artist -> artist.copy(tracks = artist.tracks.mapNotNull { byId[it.id] }) }
        }
    }

    internal fun rank(counts: List<ActualTrackPlayCount>, tracks: List<MusicTrack>): List<MostListenedArtist> {
        data class Artist(val name: String, var count: Int = 0, var lastPlayed: Long = 0, val tracks: MutableList<MusicTrack> = mutableListOf())
        val byId = tracks.associateBy { it.id }
        val artists = linkedMapOf<String, Artist>()
        for (row in counts.sortedWith(compareByDescending<ActualTrackPlayCount> { it.lastPlayed }.thenBy { it.trackId })) {
            val track = byId[row.trackId]
            val credits = track ?: MusicTrack(row.trackId, "", row.artistName, "", 0L, "", "")
            for (name in HomeRecommendationPolicy.mainArtists(credits)) {
                val key = HomeRecommendationPolicy.mainArtistKey(name)
                val artist = artists.getOrPut(key) { Artist(ArtistPhotoRepository.canonicalName(name)) }
                artist.count += row.playCount
                artist.lastPlayed = maxOf(artist.lastPlayed, row.lastPlayed)
                if (track != null && artist.tracks.size < 3 && artist.tracks.none { it.id == track.id }) artist.tracks.add(track)
            }
        }
        return artists.values.sortedWith(compareByDescending<Artist> { it.count }.thenByDescending { it.lastPlayed }.thenBy { it.name })
            .take(15).map { MostListenedArtist(it.name, it.count, it.tracks.toList()) }
    }
}
