package com.example

import android.app.Application
import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ListeningEventEntity
import com.example.data.local.MusicDatabase
import com.example.data.local.PlaylistEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MusicDatabaseMigrationTest {
    private lateinit var context: Context
    private val testDbName = "migration_test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(testDbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
    }

    private fun schema(version: Int): JSONObject {
        val relative = "com.example.data.local.MusicDatabase/$version.json"
        val stream = try {
            context.assets.open(relative)
        } catch (_: Exception) {
            val file = listOf(
                java.io.File("schemas/$relative"),
                java.io.File("app/schemas/$relative"),
                java.io.File("../app/schemas/$relative")
            ).firstOrNull { it.exists() } ?: error("Schema file not found for version $version")
            file.inputStream()
        }
        return stream.bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
    }

    private fun helper(version: Int, onCreate: (SupportSQLiteDatabase) -> Unit = {}): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(testDbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("Fixture version changed unexpectedly")
                    }
                }).build()
        )

    private fun createOldDatabase(
        version: Int,
        schemaVersion: Int = version,
        customize: (SupportSQLiteDatabase) -> Unit = {}
    ): Map<String, List<Map<String, Any?>>> {
        val fixtureSchema = schema(schemaVersion)
        return helper(version) { db ->
            val entities = fixtureSchema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) {
                    for (j in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
            }
            // Historical exports were reconstructed from source. Install the correct
            // identity, so an already-current database also exercises Room's identity check.
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)", arrayOf(fixtureSchema.getString("identityHash")))
            seedRecords(db, schemaVersion)
            customize(db)
        }.use { old -> snapshot(old.writableDatabase) }
    }

    private fun seedRecords(db: SupportSQLiteDatabase, version: Int) {
        val tracks = listOf(
            linkedMapOf<String, Any>(
                "id" to "old_favorite", "title" to "User-edited title", "artist" to "Original Artist",
                "album" to "Official Album", "durationMs" to 210000L, "coverUrl" to "file:///cover.jpg",
                "audioUrl" to "file:///downloaded.mp3", "bitrateKbps" to 320, "qualityBadge" to "HD",
                "genre" to "Rock", "isLiked" to 1, "isCached" to 1,
                "lastPlayedAt" to 1700000000000L, "addedAt" to 1690000000000L
            ),
            linkedMapOf<String, Any>(
                "id" to "old_second", "title" to "Other Track", "artist" to "Another Artist",
                "album" to "Another Album", "durationMs" to 180000L, "coverUrl" to "https://example.com/cover.jpg",
                "audioUrl" to "https://example.com/track", "bitrateKbps" to 192, "qualityBadge" to "High",
                "genre" to "Jazz", "isLiked" to 0, "isCached" to 0, "lastPlayedAt" to 1700000000100L,
                "addedAt" to 1690000000100L
            )
        )
        tracks.forEach { row ->
            if (version >= 3) row.putAll(mapOf("singers" to "Singer", "writer" to "Writer", "language" to "Tamil", "year" to "2005"))
            if (version >= 4) row["source"] = "JioSaavn"
            if (version >= 5) row["isrc"] = if (row["id"] == "old_favorite") "GBAYE0500001" else "GBAYE0500002"
            insert(db, "tracks", row)
        }
        db.execSQL("INSERT INTO playlists VALUES (101, 'Personal Playlist', 'User notes', 'file:///playlist.jpg', 1690000000010)")
        db.execSQL("INSERT INTO playlists VALUES (110, 'Second Playlist', '', '', 1690000000020)")
        db.execSQL("INSERT INTO playlist_track_cross_ref (playlistId, trackId, orderIndex) VALUES (101, 'old_favorite', 7)")
        db.execSQL("INSERT INTO playlist_track_cross_ref (playlistId, trackId, orderIndex) VALUES (101, 'old_second', 2)")
        db.execSQL("INSERT INTO playlist_track_cross_ref (playlistId, trackId, orderIndex) VALUES (110, 'old_favorite', 9)")
        if (version >= 6) {
            db.execSQL("""
                INSERT INTO listening_events VALUES
                (701, 'old_favorite', 'artist-id', 'Original Artist', 'Official Album', 'Rock', 'Tamil',
                1700000000000, 'old-session', 125000, 210000, 0.595, 0, 0, 1, 'JIOSAAVN', 'ACTUAL_PLAY')
            """.trimIndent())
        }
        if (version in 7..11) {
            db.execSQL("INSERT INTO artwork_cache VALUES ('artist-original', 'https://example.com/artist.jpg', 1700000000000)")
        }
    }

    private fun insert(db: SupportSQLiteDatabase, table: String, values: Map<String, Any>) {
        val columns = values.keys.joinToString(",") { "`$it`" }
        val placeholders = values.keys.joinToString(",") { "?" }
        db.execSQL("INSERT INTO `$table` ($columns) VALUES ($placeholders)", values.values.toTypedArray())
    }

    private fun snapshot(db: SupportSQLiteDatabase): Map<String, List<Map<String, Any?>>> {
        val tables = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('tracks', 'playlists', 'playlist_track_cross_ref', 'listening_events', 'artwork_cache') ORDER BY name").use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        return tables.associateWith { table ->
            db.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.columnNames.withIndex().associate { (index, name) ->
                            name to when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                                else -> cursor.getString(index)
                            }
                        })
                    }
                }
            }
        }
    }

    private fun assertPreserved(
        before: Map<String, List<Map<String, Any?>>>,
        after: Map<String, List<Map<String, Any?>>>
    ) {
        before.filterKeys { it != "artwork_cache" || after.containsKey(it) }.forEach { (table, originalRows) ->
            val migratedRows = requireNotNull(after[table])
            assertEquals("$table record count", originalRows.size, migratedRows.size)
            originalRows.zip(migratedRows).forEach { (original, migrated) ->
                original.forEach { (column, value) ->
                    assertEquals("$table.$column must survive unchanged", value, migrated[column])
                }
            }
        }
    }

    private fun openCurrentDatabase() = Room.databaseBuilder(context, MusicDatabase::class.java, testDbName)
        .addMigrations(*MusicDatabase.ALL_MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    private inline fun <T> MusicDatabase.withClose(block: (MusicDatabase) -> T): T =
        try { block(this) } finally { close() }

    @Test
    fun everySupportedVersionPreservesAllExistingRecordsAndOrder() = runBlocking {
        for (version in 1..13) {
            context.deleteDatabase(testDbName)
            val before = createOldDatabase(version)
            openCurrentDatabase().withClose { db ->
                val sqlite = db.openHelper.writableDatabase // Force the real Room migration and validation.
                assertEquals(14, sqlite.version)
                sqlite.query("SELECT COUNT(*) FROM sqlite_master WHERE name = 'artwork_cache'").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                assertPreserved(before, snapshot(sqlite))
                val oldTrack = requireNotNull(db.musicDao().getTrackById("old_favorite"))
                assertTrue(oldTrack.isLiked)
                assertTrue(oldTrack.isCached)
                assertEquals("[]", oldTrack.primaryArtists)
                if (version < 3) {
                    assertEquals("", oldTrack.singers)
                    assertEquals("", oldTrack.writer)
                    assertEquals("", oldTrack.language)
                    assertEquals("", oldTrack.year)
                }
                if (version < 4) assertEquals("HD Stream", oldTrack.source)
                if (version < 5) assertEquals("", oldTrack.isrc)
                val playlistId = db.musicDao().insertPlaylist(PlaylistEntity(title = "After migration"))
                assertTrue("Playlist autoincrement must not reuse an existing ID", playlistId > 110)
                val eventId = db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = "old_favorite"))
                assertTrue("Listening-event IDs must remain monotonic", eventId > if (version >= 6) 701 else 0)
            }
        }
    }

    @Test
    fun listeningCheckpointMigrationKeepsTheExistingOccurrenceWatermark() = runBlocking {
        createOldDatabase(13) { db ->
            db.execSQL("INSERT INTO listening_checkpoint VALUES (1, 'existing-play', '{\"2026-10-26\":[60000,1]}', 701)")
        }
        openCurrentDatabase().withClose { db ->
            val checkpoint = requireNotNull(db.listeningStatsDao().checkpoint("existing-play"))
            assertEquals("{\"2026-10-26\":[60000,1]}", checkpoint.days)
            assertEquals(701L, checkpoint.eventId)
        }
    }

    @Test
    fun legacyMissingIndicesAreRepairedWithoutReplacingTables() {
        val before = createOldDatabase(7) { db ->
            db.execSQL("DROP INDEX index_playlist_track_cross_ref_trackId")
            db.execSQL("DROP INDEX index_listening_events_trackId")
            db.execSQL("DROP INDEX index_listening_events_artistName")
            db.execSQL("DROP INDEX index_listening_events_timestamp")
            db.execSQL("DROP INDEX index_listening_events_sessionId")
        }
        openCurrentDatabase().withClose { db ->
            assertPreserved(before, snapshot(db.openHelper.writableDatabase))
        }
    }

    @Test
    fun legacyMigrationColumnDefaultsDoNotCauseRecordsToBeLost() {
        // Old ALTER TABLE migrations added SQL defaults although entity fields did not
        // declare @ColumnInfo defaults. Room must accept both fresh and migrated variants.
        val before = createOldDatabase(2)
        helper(2).use { old ->
            val sqlite = old.writableDatabase
            MusicDatabase.MIGRATION_2_3.migrate(sqlite)
            MusicDatabase.MIGRATION_3_4.migrate(sqlite)
            MusicDatabase.MIGRATION_4_5.migrate(sqlite)
            sqlite.version = 5
        }
        openCurrentDatabase().withClose { db ->
            assertPreserved(before, snapshot(db.openHelper.writableDatabase))
        }
    }

    @Test
    fun unsupportedFutureDowngradeFailsWithoutDeletingAnyRecords() {
        val before = createOldDatabase(version = 15, schemaVersion = 12)
        val room = openCurrentDatabase()
        try {
            room.openHelper.writableDatabase
            fail("A future-version database must never be silently downgraded or recreated")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("migration", ignoreCase = true))
        } finally {
            room.close()
        }
        helper(15).use { original ->
            assertEquals(15, original.writableDatabase.version)
            assertPreserved(before, snapshot(original.writableDatabase))
        }
    }

    @Test
    fun failedSchemaValidationRollsBackTheCompleteUpgrade() {
        val before = createOldDatabase(5) { db -> db.execSQL("ALTER TABLE tracks ADD COLUMN unexpected TEXT") }
        val room = openCurrentDatabase()
        try {
            room.openHelper.writableDatabase
            fail("An incompatible schema must fail validation without destructive recovery")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("migration", ignoreCase = true))
        } finally {
            room.close()
        }
        helper(5).use { original ->
            val sqlite = original.writableDatabase
            assertEquals(5, sqlite.version)
            assertPreserved(before, snapshot(sqlite))
            sqlite.query("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('listening_events', 'artwork_cache')").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Failed migrations must roll back newly created tables", 0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun supportedEightToSevenDowngradePreservesEveryTable() {
        val before = createOldDatabase(8)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(testDbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Existing database required")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    check(oldVersion == 8 && newVersion == 7)
                    MusicDatabase.MIGRATION_8_7.migrate(db)
                }
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { downgraded ->
            assertEquals(7, downgraded.writableDatabase.version)
            assertPreserved(before, snapshot(downgraded.writableDatabase))
        }
    }
}
