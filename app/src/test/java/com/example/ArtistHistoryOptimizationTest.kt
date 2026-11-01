package com.example

import android.app.Application
import android.os.Looper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.LocalArtistHistory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArtistHistoryOptimizationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun song(id: String, artist: String = "Singer") = MusicTrack(id, id, artist, "Album", 180_000, "https://art/$id", "")
    @Test fun durationCheckpointsAndFeedbackPreserveArtistRevisionWhileRealChangesInvalidateIt() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(app)
        try {
            val track = song("song"); val dao = db.listeningEventDao()
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
            val event = ListeningEventEntity(id = 1, trackId = track.id, artistName = track.artist, timestamp = 100,
                sessionId = "occurrence", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000)
            dao.insertListeningEvent(event)
            val artists = dao.getArtistInputRevision(); val history = dao.getHomeInputRevision()
            dao.insertListeningEvent(event.copy(playedDurationMs = 60_000, completionRatio = .5f, liked = true))
            assertEquals(artists, dao.getArtistInputRevision()); assertNotEquals(history, dao.getHomeInputRevision())
            dao.insertListeningEvent(event.copy(id = 2, eventType = "MEANINGFUL_PLAY"))
            assertEquals(artists, dao.getArtistInputRevision())
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track.copy(artist = "Corrected Singer", primaryArtists = listOf("Corrected Singer"))))
            assertNotEquals(artists, dao.getArtistInputRevision())
            val corrected = dao.getArtistInputRevision()
            assertEquals("Corrected Singer", LocalArtistHistory.read(db).single().name)
            dao.insertListeningEvent(event.copy(id = 3, sessionId = "another-occurrence", timestamp = 1))
            assertNotEquals(corrected, dao.getArtistInputRevision())
            assertEquals(2, LocalArtistHistory.read(db).single().playCount)
            dao.pruneOldEvents(Long.MAX_VALUE)
            assertTrue(LocalArtistHistory.read(db).isEmpty())
        } finally { db.close() }
    }
    @Test fun firstActualAudioAndDeletionCannotBeHiddenByAZeroTimeCheckpoint() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(app)
        try {
            val dao = db.listeningEventDao(); val before = dao.getArtistInputRevision()
            val event = ListeningEventEntity(id = 1, trackId = "missing-track-metadata", artistName = "Real Singer", timestamp = 10, eventType = "ACTUAL_PLAY")
            dao.insertListeningEvent(event); assertEquals(before, dao.getArtistInputRevision())
            dao.insertListeningEvent(event.copy(playedDurationMs = 1)); assertNotEquals(before, dao.getArtistInputRevision())
            val audible = dao.getArtistInputRevision()
            assertEquals("Real Singer", LocalArtistHistory.read(db).single().name)
            dao.insertListeningEvent(event.copy(playedDurationMs = 0)); assertNotEquals(audible, dao.getArtistInputRevision())
            assertTrue(LocalArtistHistory.read(db).isEmpty())
        } finally { db.close() }
    }
    @Test fun latestHistoricalMetadataUsesTimestampThenIdDeterministically() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(app)
        try {
            val dao = db.listeningEventDao()
            for ((id, name) in listOf(4L to "Latest Singer", 2L to "Earlier Singer", 3L to "Another Singer"))
                dao.insertListeningEvent(ListeningEventEntity(id = id, trackId = "missing-track", artistName = name, timestamp = 100,
                    sessionId = "play-$id", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
            repeat(3) {
                assertEquals("Latest Singer", dao.getActualTrackPlayCounts().single().artistName)
                assertEquals("Latest Singer", LocalArtistHistory.read(db).single().name)
            }
        } finally { db.close() }
    }
    @Test fun onlyWinningRepresentativesLoadFullMetadataWithoutALongReadTransactionOrMainWork() = runBlocking {
        data class Query(val sql: String, val bindings: Int, val main: Boolean)
        val queries = CopyOnWriteArrayList<Query>(); val recording = AtomicBoolean()
        val db = Room.inMemoryDatabaseBuilder(app, MusicDatabase::class.java)
            .setQueryCallback({ sql, args -> if (recording.get()) queries += Query(sql, args.size, Thread.currentThread() == Looper.getMainLooper().thread) }, Executor { it.run() })
            .build()
        try {
            db.withTransaction {
                for (index in 0 until 180) {
                    val track = song("track-$index", "Singer ${index / 3}")
                    db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
                    db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist,
                        timestamp = index.toLong(), sessionId = "$index", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
                }
            }
            recording.set(true)
            val result = LocalArtistHistory.read(db)
            assertEquals(15, result.size); assertTrue(result.all { it.tracks.size == 3 })
            val fullRows = queries.filter { it.sql.startsWith("SELECT * FROM tracks", ignoreCase = true) }
            assertEquals(1, fullRows.size); assertTrue(fullRows.single().bindings <= 45)
            assertTrue(queries.any { it.sql.startsWith("SELECT id, artist, singers, writer, primaryArtists", ignoreCase = true) })
            assertFalse(queries.any { it.sql.startsWith("BEGIN", ignoreCase = true) })
            assertFalse(queries.any { it.main })
        } finally { recording.set(false); db.close() }
    }
}
