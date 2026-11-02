package com.example.data.repository

import com.example.data.local.MusicDatabase
import com.example.data.local.ActualTrackPlayCount
import com.example.data.local.ListeningTotal
import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers
import com.example.recommendation.HomeRecommendationPolicy
import com.example.ui.viewmodel.MostListenedArtist
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/** Aggregate in SQLite, then normalize only distinct track credits, never the raw listening log. */
internal object LocalArtistHistory {
    suspend fun read(database: MusicDatabase): List<MostListenedArtist> = Reader(database).read()

    /** Home follows the same saved-duration ranking as Lifetime Stats, extended to fifteen.
     * Reads aggregate rows only; it never rescans playback occurrences or starts a stats writer. */
    class StatsReader(private val database: MusicDatabase) {
        private val mutex = Mutex()
        private var rows: List<ListeningTotal>? = null
        private var identities = -1L
        private var ranked = emptyList<MostListenedArtist>()
        suspend fun read(totals: List<ListeningTotal>): List<MostListenedArtist> = mutex.withLock {
            val aliases = ArtistPhotoRepository.identities.value
            val next = if (rows == totals && identities == aliases) ranked else withContext(AppDispatchers.CpuBounded) {
                ListeningSummary.from(emptyList(), totals, artistLimit = 15).artists.map { artist ->
                    MostListenedArtist(artist.artist, artist.plays.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), listOf(artist.track()))
                }
            }
            val resolved = loadRepresentatives(database, next, retainFallback = true)
            rows = totals; identities = aliases; ranked = next
            resolved
        }
    }

    /** Home-owned cache of distinct-track counts, not a second listening store. */
    class Reader(private val database: MusicDatabase) {
        private val mutex = Mutex()
        private var revision: String? = null
        private var identities = -1L
        private var counts: List<ActualTrackPlayCount>? = null
        private var ranked = emptyList<MostListenedArtist>()
        /** Called only during Home's cache restoration, before readers are admitted. */
        fun restore(inputRevision: String, artists: List<MostListenedArtist>) {
            revision = inputRevision; ranked = artists.take(15); counts = null
            identities = ArtistPhotoRepository.identities.value
        }
        suspend fun read(expectedRevision: String? = null): List<MostListenedArtist> = mutex.withLock {
            // Home already brackets this read with a revision check. Reuse its
            // token rather than repeating those same scalar queries here.
            val current = expectedRevision ?: withContext(AppDispatchers.Database) { database.listeningEventDao().getArtistInputRevision() }
            val aliases = ArtistPhotoRepository.identities.value
            val changed = current == null || revision != current
            val input = if (changed || identities != aliases && counts == null) withContext(AppDispatchers.Database) {
                database.listeningEventDao().getActualTrackPlayCounts()
            } else counts
            val next = if (changed || identities != aliases) prepareRanking(database, input.orEmpty()) else ranked
            // Commit only coherent inputs; a cancelled/replaced request cannot poison reuse.
            if (current != null && (expectedRevision != null || current == withContext(AppDispatchers.Database) { database.listeningEventDao().getArtistInputRevision() })) {
                counts = input; ranked = next; revision = current; identities = aliases
            }
            loadRepresentatives(database, next)
        }
    }

    private suspend fun prepareRanking(database: MusicDatabase, counts: List<ActualTrackPlayCount>): List<MostListenedArtist> {
        val credits = withContext(AppDispatchers.Database) {
            counts.map { it.trackId }.chunked(900).flatMap { database.musicDao().getArtistCreditsByIds(it) }
        }
        return withContext(AppDispatchers.CpuBounded) {
            rank(counts, credits.map { credit -> MusicTrack(credit.id, "", credit.artist, "", 0L, "", "",
                singers = credit.singers, writer = credit.writer,
                primaryArtists = runCatching { JSONArray(credit.primaryArtists).let { names ->
                    (0 until names.length()).map { names.optString(it) }.filter(String::isNotBlank)
                } }.getOrDefault(emptyList())) })
        }
    }
    private suspend fun loadRepresentatives(database: MusicDatabase, ranked: List<MostListenedArtist>, retainFallback: Boolean = false): List<MostListenedArtist> {
        val ids = ranked.flatMap { it.tracks }.map { it.id }.distinct()
        val representatives = if (ids.isEmpty()) emptyList() else withContext(AppDispatchers.Database) {
            database.musicDao().getTracksByIds(ids)
        }
        return withContext(AppDispatchers.CpuBounded) {
            val byId = representatives.associate { it.id to it.toMusicTrack() }
            ranked.map { artist -> artist.copy(tracks = artist.tracks.mapNotNull { byId[it.id] ?: it.takeIf { retainFallback } }) }
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
