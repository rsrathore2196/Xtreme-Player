package com.example.data.local

import androidx.room.Embedded
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class ArtistAffinityData(
    val artistName: String,
    val playCount: Int,
    val avgCompletion: Float,
    val completedCount: Int
)

data class GenreAffinityData(
    val genre: String,
    val playCount: Int,
    val avgCompletion: Float
)

data class LanguageAffinityData(
    val language: String,
    val playCount: Int,
    val avgCompletion: Float
)

data class TrackPlayCount(
    val trackId: String,
    val playCount: Int,
    val lastPlayed: Long
)

data class ActualPlayStamp(val id: Long, val trackId: String, val timestamp: Long,
    val playedDurationMs: Long, val trackDurationMs: Long, val completionRatio: Float,
    val completed: Boolean, val skipped: Boolean, val liked: Boolean, val metadata: String)

/** Count and recent identities come from one SQLite read, including an empty database. */
data class ActualPlaySnapshotRow(val total: Int, @Embedded val stamp: ActualPlayStamp?)

@Dao
interface ListeningEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListeningEvent(event: ListeningEventEntity): Long

    // Actual playback rows are updated in place: milestones cannot inflate history counts.
    @Query("SELECT * FROM listening_events WHERE eventType = 'ACTUAL_PLAY' ORDER BY timestamp DESC LIMIT 250")
    fun observeActualPlays(): Flow<List<ListeningEventEntity>>

    @Query("SELECT * FROM listening_events WHERE eventType = 'ACTUAL_PLAY' ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getActualPlays(limit: Int = 300): List<ListeningEventEntity>

    // Keyset paging avoids repeatedly decoding/skipping the whole long-term
    // window. The (timestamp, id) cursor also handles plays with equal timestamps.
    @Query("SELECT * FROM listening_events WHERE eventType = 'ACTUAL_PLAY' AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId)) ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getActualPlaysBefore(beforeTimestamp: Long, beforeId: Long, limit: Int): List<ListeningEventEntity>

    @Query("SELECT trackId FROM listening_events WHERE eventType = 'ACTUAL_PLAY' AND playedDurationMs > 0 GROUP BY trackId ORDER BY MAX(timestamp) DESC LIMIT 20")
    suspend fun getRecentActualTrackIds(): List<String>

    @Query("SELECT id, trackId, timestamp, playedDurationMs, trackDurationMs, completionRatio, completed, skipped, liked, artistId || '|' || artistName || '|' || album || '|' || genre || '|' || language || '|' || sessionId || '|' || source AS metadata FROM listening_events WHERE eventType = 'ACTUAL_PLAY' ORDER BY timestamp DESC LIMIT 250")
    fun observeActualPlayStamps(): Flow<List<ActualPlayStamp>>

    @Query("""
        SELECT totals.total, recent.* FROM
        (SELECT COUNT(*) AS total FROM listening_events WHERE eventType = 'ACTUAL_PLAY') AS totals
        LEFT JOIN (SELECT id, trackId, timestamp, playedDurationMs, trackDurationMs,
            completionRatio, completed, skipped, liked,
            artistId || '|' || artistName || '|' || album || '|' || genre || '|' || language || '|' || sessionId || '|' || source AS metadata
            FROM listening_events WHERE eventType = 'ACTUAL_PLAY'
            ORDER BY timestamp DESC, id DESC LIMIT 300) AS recent ON 1 = 1
        ORDER BY recent.timestamp DESC, recent.id DESC
    """)
    fun observeActualPlaySnapshot(): Flow<List<ActualPlaySnapshotRow>>

    // A scalar invalidation signal preserves older history when rows are deleted or restored.
    @Query("SELECT COUNT(*) FROM listening_events WHERE eventType = 'ACTUAL_PLAY'")
    fun observeActualPlayCount(): Flow<Int>

    @Query("SELECT * FROM listening_events WHERE id IN (:ids)")
    suspend fun getEventsByIds(ids: List<Long>): List<ListeningEventEntity>
    @Query("SELECT * FROM listening_events WHERE sessionId=:occurrence AND eventType='ACTUAL_PLAY' LIMIT 1")
    suspend fun getActualPlayForOccurrence(occurrence: String): ListeningEventEntity?

    /**
     * Cold-Start Query: Checks how many tracks have been played meaningfully
     * (> 30 seconds played, or completed, or >= 50% completed).
     * Cold-Start mode is active when count < 5.
     */
    @Query("SELECT COUNT(*) FROM listening_events WHERE (playedDurationMs >= 30000 OR completed = 1 OR completionRatio >= 0.5)")
    suspend fun getMeaningfulListeningCount(): Int

    @Query("SELECT COUNT(*) FROM listening_events WHERE (playedDurationMs >= 30000 OR completed = 1 OR completionRatio >= 0.5)")
    fun getMeaningfulListeningCountFlow(): Flow<Int>

    @Query("SELECT * FROM listening_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int): List<ListeningEventEntity>

    @Query("SELECT * FROM listening_events WHERE timestamp >= :sinceTimestamp ORDER BY timestamp DESC")
    suspend fun getEventsSince(sinceTimestamp: Long): List<ListeningEventEntity>

    /**
     * Top played artists in last N days with aggregated completion ratios.
     */
    @Query("""
        SELECT artistName, COUNT(*) as playCount, AVG(completionRatio) as avgCompletion, 
               SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) as completedCount 
        FROM listening_events 
        WHERE timestamp >= :sinceTimestamp AND artistName != '' 
        GROUP BY artistName 
        ORDER BY playCount DESC 
        LIMIT :limit
    """)
    suspend fun getTopArtistsSince(sinceTimestamp: Long, limit: Int): List<ArtistAffinityData>

    /**
     * Top genres weighted by recency and implicit feedback scores.
     */
    @Query("""
        SELECT genre, COUNT(*) as playCount, AVG(completionRatio) as avgCompletion 
        FROM listening_events 
        WHERE timestamp >= :sinceTimestamp AND genre != '' 
        GROUP BY genre 
        ORDER BY playCount DESC 
        LIMIT :limit
    """)
    suspend fun getTopGenresSince(sinceTimestamp: Long, limit: Int): List<GenreAffinityData>

    /**
     * Top languages weighted by recency and implicit feedback scores.
     */
    @Query("""
        SELECT language, COUNT(*) as playCount, AVG(completionRatio) as avgCompletion 
        FROM listening_events 
        WHERE timestamp >= :sinceTimestamp AND language != '' 
        GROUP BY language 
        ORDER BY playCount DESC 
        LIMIT :limit
    """)
    suspend fun getTopLanguagesSince(sinceTimestamp: Long, limit: Int): List<LanguageAffinityData>

    /**
     * Query recent unique tracks for "Quick Picks" and "Listen Again".
     */
    @Query("""
        SELECT DISTINCT trackId 
        FROM listening_events 
        WHERE (playedDurationMs >= 30000 OR completed = 1) 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    suspend fun getRecentUniqueTrackIds(limit: Int): List<String>

    /**
     * Session deduplication: query tracks played in the active session.
     */
    @Query("SELECT DISTINCT trackId FROM listening_events WHERE sessionId = :sessionId")
    suspend fun getSessionPlayedTrackIds(sessionId: String): List<String>

    @Query("""
        SELECT trackId, COUNT(*) as playCount, MAX(timestamp) as lastPlayed 
        FROM listening_events 
        GROUP BY trackId 
        ORDER BY playCount DESC, lastPlayed DESC 
        LIMIT :limit
    """)
    suspend fun getMostPlayedTracks(limit: Int): List<TrackPlayCount>

    @Query("DELETE FROM listening_events WHERE timestamp < :cutoffTimestamp")
    suspend fun pruneOldEvents(cutoffTimestamp: Long)
}
