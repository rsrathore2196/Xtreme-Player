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
import kotlinx.coroutines.flow.first
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
            // Finish Room's asynchronous post-fixture invalidation transaction
            // before measuring the read itself; it is not an artist read transaction.
            kotlinx.coroutines.withContext(com.example.performance.AppDispatchers.Database) {
                db.invalidationTracker.refreshVersionsSync()
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
    @Test fun artworkFavoritesAndOtherMetadataNeverInvalidateArtistCountsOnReplaceOrUpdate() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(app)
        try {
            val track = song("song"); val dao = db.listeningEventDao()
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
            dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist,
                sessionId = "one", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
            val before = dao.getArtistInputRevision()
            val metadata = dao.observeActualPlaySnapshot().first().first().trackMetadataRevision
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track.copy(title = "Corrected title", album = "Compilation",
                durationMs = 190_000, coverUrl = "new artwork", genre = "Pop", isLiked = true, language = "Hindi", isrc = "USABC2100012")))
            assertEquals(before, dao.getArtistInputRevision())
            assertNotEquals(metadata, dao.observeActualPlaySnapshot().first().first().trackMetadataRevision)
            db.openHelper.writableDatabase.execSQL("UPDATE tracks SET coverUrl = 'newer artwork', isLiked = 0 WHERE id = 'song'")
            assertEquals(before, dao.getArtistInputRevision())
            db.openHelper.writableDatabase.execSQL("UPDATE tracks SET primaryArtists = '[\"Corrected Singer\"]' WHERE id = 'song'")
            assertNotEquals(before, dao.getArtistInputRevision())
            assertEquals("Corrected Singer", LocalArtistHistory.read(db).single().name)
        } finally { db.close() }
    }
    @Test fun upgradingExistingTriggerDefinitionsPreservesHistoryAndRevisionEpochs() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(app)
        try {
            val track = song("song"); val dao = db.listeningEventDao()
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
            dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist,
                sessionId = "one", eventType = "ACTUAL_PLAY", playedDurationMs = 1))
            val sql = db.openHelper.writableDatabase
            sql.execSQL("DROP TRIGGER home_artist_track_insert_v2")
            sql.execSQL("DROP TRIGGER home_artist_track_update_v2")
            sql.execSQL("DROP TRIGGER IF EXISTS home_artist_track_update")
            sql.execSQL("CREATE TRIGGER home_artist_track_update AFTER UPDATE ON tracks BEGIN UPDATE home_input_revision SET generation = generation + 1 WHERE id = 2; END")
            val before = dao.getArtistInputRevision()
            HomeInputRevisionTriggers.install(sql); HomeInputRevisionTriggers.install(sql)
            sql.execSQL("UPDATE tracks SET coverUrl = 'changed' WHERE id = 'song'")
            assertEquals(before, dao.getArtistInputRevision())
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track.copy(coverUrl = "changed again", isLiked = true)))
            assertEquals(before, dao.getArtistInputRevision())
            assertEquals(1, dao.getActualTrackPlayCounts().single().playCount)
            sql.execSQL("UPDATE tracks SET artist = 'Another Singer' WHERE id = 'song'")
            assertNotEquals(before, dao.getArtistInputRevision())
        } finally { db.close() }
    }
    @Test fun readerReusesLifetimeAggregationButRefreshesRepresentativeMetadataAndNewPlays() = runBlocking {
        val aggregations = java.util.concurrent.atomic.AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(app, MusicDatabase::class.java)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) { HomeInputRevisionTriggers.install(db) }
            })
            .setQueryCallback({ sql, _ -> if (sql.contains("COUNT(DISTINCT CASE WHEN sessionId")) aggregations.incrementAndGet() }, Executor { it.run() }).build()
        try {
            val track = song("song"); val dao = db.listeningEventDao()
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
            dao.insertListeningEvent(ListeningEventEntity(id = 1, trackId = track.id, artistName = track.artist,
                sessionId = "one", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
            val reader = LocalArtistHistory.Reader(db)
            assertEquals(1, reader.read().single().playCount)
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track.copy(coverUrl = "new artwork", isLiked = true)))
            val result = reader.read().single()
            assertEquals(1, aggregations.get()); assertEquals("new artwork", result.tracks.single().coverUrl); assertTrue(result.tracks.single().isLiked)
            dao.insertListeningEvent(ListeningEventEntity(id = 2, trackId = track.id, artistName = track.artist,
                sessionId = "two", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
            assertEquals(2, reader.read().single().playCount); assertEquals(2, aggregations.get())
            dao.pruneOldEvents(Long.MAX_VALUE)
            assertTrue(reader.read().isEmpty()); assertEquals(3, aggregations.get())
        } finally { db.close() }
    }

    @Test fun validRestoredArtistRankingsRefreshMetadataWithoutALifetimeAggregation() = runBlocking {
        val aggregations = java.util.concurrent.atomic.AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(app, MusicDatabase::class.java)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) { HomeInputRevisionTriggers.install(db) }
            })
            .setQueryCallback({ sql, _ -> if (sql.contains("COUNT(DISTINCT CASE WHEN sessionId")) aggregations.incrementAndGet() }, Executor { it.run() }).build()
        try {
            val track = song("song"); val dao = db.listeningEventDao()
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
            dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist,
                sessionId = "one", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
            val revision = dao.getArtistInputRevision()!!
            val reader = LocalArtistHistory.Reader(db)
            reader.restore(revision, listOf(com.example.ui.viewmodel.MostListenedArtist(track.artist, 1, listOf(track))))
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track.copy(coverUrl = "fresh art")))
            val result = reader.read(revision).single()
            assertEquals(0, aggregations.get()); assertEquals(1, result.playCount); assertEquals("fresh art", result.tracks.single().coverUrl)
        } finally { db.close() }
    }

    @Test fun oldTrackMetadataRefreshesArtistRepresentativesWithoutInvalidatingTheRecentWindow() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(app)
        try {
            val old = song("old", "Historical Singer"); val recent = song("recent", "Recent Singer")
            db.withTransaction {
                db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(old))
                db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(recent))
                db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = old.id, artistName = old.artist,
                    timestamp = 1L, sessionId = "old", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
                repeat(301) { index -> db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = recent.id,
                    artistName = recent.artist, timestamp = index + 2L, sessionId = "recent-$index", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000)) }
            }
            val reader = LocalArtistHistory.Reader(db)
            reader.read()
            val before = db.listeningEventDao().observeActualPlaySnapshot().first()
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(old.copy(coverUrl = "historical art corrected", isLiked = true)))
            val after = db.listeningEventDao().observeActualPlaySnapshot().first()
            assertEquals(before.first().trackMetadataRevision, after.first().trackMetadataRevision)
            assertNotEquals(before.first().artistMetadataRevision, after.first().artistMetadataRevision)
            assertEquals(before.first().artistInputRevision, after.first().artistInputRevision)
            assertEquals(before.map { it.event }, after.map { it.event })
            assertEquals("historical art corrected", reader.read().first { it.name == old.artist }.tracks.single().coverUrl)
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(recent.copy(isLiked = true)))
            assertNotEquals(after.first().trackMetadataRevision,
                db.listeningEventDao().observeActualPlaySnapshot().first().first().trackMetadataRevision)
        } finally { db.close() }
    }

    @Test fun aLargeHistoryReusesItsAggregateInsteadOfIntroducingIncrementalCounters() = runBlocking {
        val aggregations = java.util.concurrent.atomic.AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(app, MusicDatabase::class.java)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) { HomeInputRevisionTriggers.install(db) }
            }).setQueryCallback({ sql, _ -> if (sql.contains("COUNT(DISTINCT CASE WHEN sessionId")) aggregations.incrementAndGet() }, Executor { it.run() }).build()
        try {
            db.withTransaction {
                repeat(200) { index -> db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song("track-$index", "Singer ${index / 4}"))) }
                repeat(4_000) { index -> db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = "track-${index % 200}",
                    artistName = "Singer ${(index % 200) / 4}", timestamp = index.toLong(), sessionId = "occurrence-$index",
                    eventType = "ACTUAL_PLAY", playedDurationMs = 30_000)) }
            }
            val reader = LocalArtistHistory.Reader(db)
            val started = System.nanoTime(); val first = reader.read(); val firstMs = (System.nanoTime() - started) / 1_000_000
            val reused = System.nanoTime(); val second = reader.read(); val reusedMs = (System.nanoTime() - reused) / 1_000_000
            assertEquals(first, second); assertEquals(15, first.size); assertEquals(1, aggregations.get())
            println("Artist fixture: 4000 events/200 tracks/50 artists; first=${firstMs}ms, cached=${reusedMs}ms; aggregate queries=${aggregations.get()}")
        } finally { db.close() }
    }

}
