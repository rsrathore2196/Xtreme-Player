package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.MusicDatabase
import com.example.data.local.ActualTrackPlayCount
import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers
import com.example.recommendation.HomeRecommendationPolicy
import com.example.ui.viewmodel.MostListenedArtist
import kotlinx.coroutines.withContext

/** Aggregate in SQLite, then normalize only distinct track credits, never the raw listening log. */
internal object LocalArtistHistory {
    suspend fun read(database: MusicDatabase): List<MostListenedArtist> = withContext(AppDispatchers.Io) {
        val (counts, tracks) = database.withTransaction {
            val rows = database.listeningEventDao().getActualTrackPlayCounts()
            rows to rows.map { it.trackId }.chunked(900).flatMap { database.musicDao().getTracksByIds(it) }
                .map { it.toMusicTrack() }
        }
        withContext(AppDispatchers.CpuBounded) { rank(counts, tracks) }
    }

    internal fun rank(counts: List<ActualTrackPlayCount>, tracks: List<MusicTrack>): List<MostListenedArtist> {
        data class Artist(val name: String, var count: Int = 0, var lastPlayed: Long = 0, val tracks: MutableList<MusicTrack> = mutableListOf())
        val byId = tracks.associateBy { it.id }
        val artists = linkedMapOf<String, Artist>()
        for (row in counts.sortedByDescending { it.lastPlayed }) {
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
