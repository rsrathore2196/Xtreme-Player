package com.example

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListeningIdentityMigrationTest {
    private val canonical = "isrc:USABC2600001"
    private fun row(identity: String, millis: Long, plays: Long) = ListeningTotal("device", "2026-10", "song", identity,
        identity.removePrefix("catalog:"), "Song", "Artist", "", "", 120_000, "", millis, plays)

    @Test fun distinctCatalogContributionsAreAddedOnceAndOldCopiesCannotAddThemAgain() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        try {
            val dao = db.listeningStatsDao()
            val first = row("catalog:first", 60_000, 1)
            val second = row("catalog:second", 90_000, 1)
            dao.put(row(canonical, 30_000, 0))
            db.withTransaction {
                for (track in listOf(first, second)) { dao.put(track); dao.migrateSongIdentity(track, canonical) }
            }
            assertEquals(180_000L, dao.row("device", "2026-10", "song", canonical)!!.listenedMs)
            repeat(2) { db.withTransaction { ListeningStatsBackup.merge(dao, listOf(first, second)) } }
            assertEquals(180_000L, dao.row("device", "2026-10", "song", canonical)!!.listenedMs)
            assertEquals(2L, dao.row("device", "2026-10", "song", canonical)!!.plays)
            assertEquals(1, dao.all().size)
        } finally { db.close() }
    }

    @Test fun backupImportOrderPreservesOnlyThePreviouslyUnmergedCatalogDelta() = runBlocking {
        for (olderFirst in listOf(false, true)) {
            val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
            try {
                val dao = db.listeningStatsDao()
                val old = row("catalog:original", 90_000, 2)
                val latest = row(canonical, 200_000, 3)
                val migrations = listOf(ListeningIdentityMigration("device", "2026-10", old.identity, canonical, 60_000, 1))
                // Exercise the actual backup encoding/decoding, as on a fresh installation.
                val decoded = ListeningStatsBackup.decodeMigrations(ListeningStatsBackup.encodeMigrations(migrations))
                suspend fun oldBackup() = db.withTransaction { ListeningStatsBackup.merge(dao, listOf(old)) }
                suspend fun newBackup() = db.withTransaction { ListeningStatsBackup.merge(dao, listOf(latest), decoded) }
                if (olderFirst) { oldBackup(); newBackup() } else { newBackup(); oldBackup() }
                oldBackup(); newBackup()
                val result = dao.all().single()
                assertEquals(canonical, result.identity)
                assertEquals(230_000L, result.listenedMs)
                assertEquals(4L, result.plays)
            } finally { db.close() }
        }
    }
}
