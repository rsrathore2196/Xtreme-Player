package com.example

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.MusicDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionsMigrationTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MusicDatabase::class.java)
    @Test fun version15UpgradePreservesPlaylistsAndAddsCascadingImportMapping() {
        val name = "connections_migration_test"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            migration.createDatabase(name, 15).apply {
                execSQL("INSERT INTO playlists (playlistId, title, description, coverUrl, createdAt) VALUES (42, 'Keep my playlist', 'Saved description', '', 123)")
                close()
            }
            migration.runMigrationsAndValidate(name, 16, true, MusicDatabase.MIGRATION_15_16).use { db ->
                db.query("SELECT title FROM playlists WHERE playlistId = 42").use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals("Keep my playlist", cursor.getString(0)) }
                db.execSQL("INSERT INTO connected_collections (provider, accountId, remoteId, playlistId) VALUES ('spotify', 'account', 'playlist', 42)")
                db.execSQL("PRAGMA foreign_keys = ON")
                db.execSQL("DELETE FROM playlists WHERE playlistId = 42")
                db.query("SELECT COUNT(*) FROM connected_collections").use { cursor -> cursor.moveToFirst(); assertEquals(0, cursor.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }
}
