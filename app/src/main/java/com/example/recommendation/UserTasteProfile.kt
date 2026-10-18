package com.example.recommendation

import com.example.data.local.ListeningEventDao
import com.example.data.local.ListeningEventEntity
import com.example.data.model.MusicTrack
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.ln

data class UserTasteProfile(
    val artistAffinities: Map<String, Float> = emptyMap(),
    val genreAffinities: Map<String, Float> = emptyMap(),
    val languageAffinities: Map<String, Float> = emptyMap(),
    val moodAffinities: Map<String, Float> = emptyMap(),
    val topRecentTrackIds: List<String> = emptyList(),
    val meaningfulPlaysCount: Int = 0,
    val isColdStart: Boolean = true,
    val lastUpdatedMs: Long = System.currentTimeMillis(),
    val playCounts: Map<String, Int> = emptyMap(),
    val skipCounts: Map<String, Int> = emptyMap(),
    val recentRecordings: Set<String> = emptySet(),
    val favoriteRecordings: Set<String> = emptySet(),
    val recordingAliases: Map<String, String> = emptyMap()
) {
    fun keyFor(track: MusicTrack): String = HomeRecommendationPolicy.aliases(track)
        .firstNotNullOfOrNull { recordingAliases[it] } ?: HomeRecommendationPolicy.metadataKey(track)

    companion object {
        val COLD_START_PROFILE = UserTasteProfile(lastUpdatedMs = 0)
        // Visibility follows actual playback, independently of stronger ranking signals.
        fun played(event: ListeningEventEntity): Boolean =
            event.eventType == "ACTUAL_PLAY" && event.playedDurationMs > 0

        fun meaningful(event: ListeningEventEntity): Boolean = event.eventType == "ACTUAL_PLAY" &&
            (event.playedDurationMs >= 30_000 || (event.trackDurationMs > 0 && event.playedDurationMs >= (event.trackDurationMs + 1) / 2))

        suspend fun buildProfile(dao: ListeningEventDao, nowMs: Long = System.currentTimeMillis(),
            tracks: List<MusicTrack> = emptyList(), favorites: List<MusicTrack> = emptyList()): UserTasteProfile =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val events = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dao.getActualPlays(limit = 5000) }
                fromEvents(events, tracks, favorites, nowMs)
            }

        fun fromEvents(events: List<ListeningEventEntity>, tracks: List<MusicTrack>,
            favorites: List<MusicTrack> = emptyList(), nowMs: Long = System.currentTimeMillis()): UserTasteProfile =
            prepareProfile(events, tracks, favorites, nowMs).filterNotNull().first()

        /** Same scoring, with safe boundaries so historical enrichment can yield to gestures. */
        suspend fun fromEventsCooperatively(events: List<ListeningEventEntity>, tracks: List<MusicTrack>,
            favorites: List<MusicTrack> = emptyList(), nowMs: Long = System.currentTimeMillis(),
            checkpoint: suspend () -> Unit): UserTasteProfile {
            checkpoint()
            for (profile in prepareProfile(events, tracks, favorites, nowMs)) {
                checkpoint()
                if (profile != null) return profile
            }
            error("Taste preparation did not produce a profile")
        }

        // The synchronous and cooperative callers consume the exact same computation.
        // Null yields retain local accumulators without publishing partial taste to Home.
        private fun prepareProfile(events: List<ListeningEventEntity>, tracks: List<MusicTrack>,
            favorites: List<MusicTrack>, nowMs: Long): Sequence<UserTasteProfile?> = sequence {
            val byId = mutableMapOf<String, MusicTrack>()
            tracks.forEachIndexed { index, track ->
                if (index % 64 == 0) yield(null)
                byId[track.id] = track
            }
            val aliases = mutableMapOf<String, String>()
            for ((index, track) in (tracks.asSequence() + favorites.asSequence()).withIndex()) {
                if (index % 64 == 0) yield(null)
                val names = HomeRecommendationPolicy.aliases(track)
                val key = names.firstNotNullOfOrNull { aliases[it] } ?: HomeRecommendationPolicy.metadataKey(track)
                names.forEach { aliases[it] = key }
            }
            fun keyFor(track: MusicTrack) = HomeRecommendationPolicy.aliases(track)
                .firstNotNullOfOrNull { aliases[it] } ?: HomeRecommendationPolicy.metadataKey(track)
            val artists = mutableMapOf<String, Float>()
            val genres = mutableMapOf<String, Float>()
            val languages = mutableMapOf<String, Float>()
            val moods = mutableMapOf<String, Float>()
            val plays = mutableMapOf<String, Int>()
            val skips = mutableMapOf<String, Int>()
            val recent = linkedSetOf<String>()
            val recentKeys = mutableSetOf<String>()
            val unique = HomeRecommendationPolicy.Recordings()
            var uniqueCount = 0
            fun add(map: MutableMap<String, Float>, value: String, weight: Float) {
                if (HomeRecommendationPolicy.known(value)) map.merge(HomeRecommendationPolicy.normalize(value), weight, Float::plus)
            }
            for ((index, event) in events.sortedByDescending { it.timestamp }
                .distinctBy { it.sessionId to it.trackId }.withIndex()) {
                if (index % 64 == 0) yield(null)
                if (!played(event)) continue
                val track = byId[event.trackId]
                val key = track?.let(::keyFor) ?: event.trackId
                val isMeaningful = meaningful(event)
                if (isMeaningful) {
                    if (track != null) { if (unique.add(track)) uniqueCount++ }
                    // Unresolved metadata cannot prove that two provider IDs are different recordings.
                }
                recent.add(event.trackId)
                if (nowMs - event.timestamp < 7 * 86_400_000L) recentKeys.add(key)
                plays.merge(key, 1, Int::plus)
                if (event.skipped) skips.merge(key, 1, Int::plus)
                val days = (nowMs - event.timestamp).coerceAtLeast(0) / 86_400_000.0
                // Recent taste leads, while a slower component retains long-term preferences.
                val decay = (0.75 * exp(-ln(2.0) * days / 14) + 0.25 * exp(-ln(2.0) * days / 120)).toFloat()
                val completion = if (event.trackDurationMs > 0) (event.playedDurationMs.toFloat() / event.trackDurationMs).coerceIn(0f, 1f) else 0f
                val weight = decay * (if (event.skipped) -1.5f else (if (isMeaningful) 0.5f else 0.15f) + completion * 2f)
                val names = track?.let(HomeRecommendationPolicy::artists) ?: event.artistName.split(',')
                names.forEach { add(artists, it, weight / names.size.coerceAtLeast(1)) }
                add(genres, track?.genre ?: event.genre, weight)
                HomeRecommendationPolicy.moodForGenre(track?.genre ?: event.genre)?.let { add(moods, it, weight) }
                add(languages, track?.language ?: event.language, weight)
            }
            favorites.forEachIndexed { index, track ->
                if (index % 64 == 0) yield(null)
                HomeRecommendationPolicy.artists(track).forEach { add(artists, it, 3f) }
                add(genres, track.genre, 2f); add(languages, track.language, 2f)
                HomeRecommendationPolicy.moodForGenre(track.genre)?.let { add(moods, it, 2f) }
            }
            fun normalized(values: Map<String, Float>): Map<String, Float> {
                val max = values.values.maxOfOrNull { abs(it) }?.coerceAtLeast(1f) ?: 1f
                return values.mapValues { (it.value / max).coerceIn(-1f, 1f) }
            }
            yield(UserTasteProfile(normalized(artists), normalized(genres), normalized(languages), moodAffinities = normalized(moods),
                topRecentTrackIds = recent.take(30), meaningfulPlaysCount = uniqueCount,
                isColdStart = recent.isEmpty(), lastUpdatedMs = nowMs, playCounts = plays,
                skipCounts = skips, recentRecordings = recentKeys,
                favoriteRecordings = favorites.map(::keyFor).toSet(), recordingAliases = aliases))
        }
    }
}
