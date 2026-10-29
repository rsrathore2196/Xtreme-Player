package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.recommendation.UserTasteProfile
import com.example.recommendation.UserTasteProfileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeInputRevisionTest {
    private fun database() = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext())
    private inline fun <T> MusicDatabase.withClose(block: (MusicDatabase) -> T): T =
        try { block(this) } finally { close() }
    private val song = TrackEntity.fromMusicTrack(MusicTrack("song", "Title", "Singer", "Album",
        180_000, "https://art", "https://audio"))

    @Test fun unchangedReopeningCanTrustBothDerivedFilesButLegacyPartialAndExpiredFilesCannot() {
        val snapshot = UserTasteProfileStore.Snapshot("IN|Hindi", "hash", true,
            UserTasteProfile(lastUpdatedMs = 1_000), "database:12")
        fun valid(s: UserTasteProfileStore.Snapshot? = snapshot, key: String = "IN|Hindi",
            revision: String? = "database:12", cards: String? = revision, complete: Boolean = true, now: Long = 2_000) =
            UserTasteProfileStore.canRestoreWithoutHistory(s, key, revision, cards, complete, now)
        assertTrue(valid())
        assertFalse(valid(snapshot.copy(inputRevision = null)))
        assertFalse(valid(snapshot.copy(completeHistory = false)))
        assertFalse(valid(cards = "database:11"))
        assertFalse(valid(revision = "recreated-database:12"))
        assertFalse(valid(revision = null))
        assertFalse(valid(complete = false))
        assertFalse(valid(key = "US|English"))
        assertFalse(valid(now = 3_601_001))
        assertFalse(valid(now = 999))
        assertEquals(snapshot, UserTasteProfileStore.decode(UserTasteProfileStore.encode(snapshot).decodeToString()))
    }

    @Test fun countAndRecentMetadataStayIdenticalButOldRowEditsInvalidateCachedPersonalization() = runBlocking {
        database().withClose { db ->
            val dao = db.listeningEventDao()
            db.musicDao().insertOrUpdateTrack(song)
            repeat(310) { index -> dao.insertListeningEvent(ListeningEventEntity(id = index + 1L,
                trackId = song.id, timestamp = index.toLong(), eventType = "ACTUAL_PLAY")) }
            val before = dao.observeActualPlaySnapshot().first()
            assertEquals(310, before.first().total)
            assertEquals(300, before.size)
            assertNotNull(before.first().inputRevision)
            dao.insertListeningEvent(ListeningEventEntity(id = 1, trackId = song.id, timestamp = 0,
                playedDurationMs = 80_000, eventType = "ACTUAL_PLAY"))
            val after = dao.observeActualPlaySnapshot().first()
            assertEquals(before.map { it.event }, after.map { it.event })
            assertEquals(before.first().total, after.first().total)
            assertNotEquals(before.first().inputRevision, after.first().inputRevision)
            assertEquals(before.first().inputRevision?.substringBefore(':'), after.first().inputRevision?.substringBefore(':'))
            db.openHelper.writableDatabase.execSQL("DELETE FROM listening_events WHERE id = 1")
            assertNotEquals(after.first().inputRevision, dao.getHomeInputRevision())
        }
    }

    @Test fun trackMetadataAndLikesInvalidateButUnrelatedCatalogAndAudioBookkeepingDoNot() = runBlocking {
        database().withClose { db ->
            val dao = db.listeningEventDao()
            val tracks = db.musicDao()
            tracks.insertOrUpdateTrack(song)
            dao.insertListeningEvent(ListeningEventEntity(trackId = song.id, eventType = "ACTUAL_PLAY"))
            var revision = dao.getHomeInputRevision()
            db.openHelper.writableDatabase.query("SELECT replacingTrack, replacingEvent FROM home_input_revision").use {
                assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertTrue(it.isNull(1))
            }
            val event = dao.getActualPlays(1).single()
            dao.insertListeningEvent(event)
            assertEquals("An unchanged checkpoint is not new input", revision, dao.getHomeInputRevision())
            tracks.insertOrUpdateTrack(song)
            tracks.insertOrUpdateTrack(song.copy(audioUrl = "https://renewed", bitrateKbps = 320, lastPlayedAt = 123))
            tracks.insertOrUpdateTrack(song.copy(id = "unplayed"))
            assertEquals(revision, dao.getHomeInputRevision())
            tracks.insertOrUpdateTrack(song.copy(artist = "Correct Singer"))
            assertNotEquals(revision, dao.getHomeInputRevision())
            revision = dao.getHomeInputRevision()
            tracks.updateFavorite("unplayed", true)
            assertNotEquals(revision, dao.getHomeInputRevision())
            revision = dao.getHomeInputRevision()
            tracks.updateFavorite("unplayed", false)
            assertNotEquals(revision, dao.getHomeInputRevision())
            revision = dao.getHomeInputRevision()
            db.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE id = 'song'")
            assertNotEquals(revision, dao.getHomeInputRevision())
        }
    }

    @Test fun rolledBackRestoreDoesNotChangeRevisionAndClearedDatabaseCannotReuseItsOldEpoch() = runBlocking {
        database().withClose { db ->
            val dao = db.listeningEventDao()
            val before = dao.getHomeInputRevision()
            val sql = db.openHelper.writableDatabase
            sql.beginTransaction()
            try {
                sql.execSQL("INSERT INTO tracks (id, title, artist, album, durationMs, coverUrl, audioUrl, bitrateKbps, qualityBadge, genre, isLiked, isCached, lastPlayedAt, addedAt, singers, writer, language, year, source, isrc, primaryArtists) VALUES ('favorite', '', '', '', 0, '', '', 0, '', '', 1, 0, 0, 0, '', '', '', '', '', '', '[]')")
                sql.query("SELECT epoch || ':' || generation FROM home_input_revision").use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertNotEquals(before, cursor.getString(0))
                }
            } finally { sql.endTransaction() }
            assertEquals(before, dao.getHomeInputRevision())
            sql.execSQL("DELETE FROM home_input_revision")
            dao.insertListeningEvent(ListeningEventEntity(trackId = "new", eventType = "ACTUAL_PLAY"))
            assertNotEquals(before?.substringBefore(':'), dao.getHomeInputRevision()?.substringBefore(':'))
        }
    }
}
