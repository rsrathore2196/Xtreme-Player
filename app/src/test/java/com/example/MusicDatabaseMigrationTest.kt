package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ListeningEventEntity
import com.example.data.local.MusicDatabase
import com.example.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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

    private fun createOldDatabase(version: Int, initSchemaAndData: (SupportSQLiteDatabase) -> Unit) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(testDbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    initSchemaAndData(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        db.close()
        helper.close()
    }

    @Test
    fun testMigrateFromV1ToV8PreservesAllDataAndRelationships() = runBlocking {
        // Create V1 database
        createOldDatabase(1) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `tracks` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `artist` TEXT NOT NULL,
                    `album` TEXT NOT NULL,
                    `durationMs` INTEGER NOT NULL,
                    `coverUrl` TEXT NOT NULL,
                    `audioUrl` TEXT NOT NULL,
                    `bitrateKbps` INTEGER NOT NULL,
                    `qualityBadge` TEXT NOT NULL,
                    `genre` TEXT NOT NULL,
                    `isLiked` INTEGER NOT NULL,
                    `isCached` INTEGER NOT NULL,
                    `lastPlayedAt` INTEGER NOT NULL,
                    `addedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `playlists` (
                    `playlistId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `coverUrl` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `playlist_track_cross_ref` (
                    `playlistId` INTEGER NOT NULL,
                    `trackId` TEXT NOT NULL,
                    `orderIndex` INTEGER NOT NULL,
                    PRIMARY KEY(`playlistId`, `trackId`)
                )
            """.trimIndent())

            // Insert representative records into V1
            db.execSQL("INSERT INTO tracks (id, title, artist, album, durationMs, coverUrl, audioUrl, bitrateKbps, qualityBadge, genre, isLiked, isCached, lastPlayedAt, addedAt) " +
                    "VALUES ('v1_song_1', 'Old Hit', 'Legend Artist', 'Classic Album', 210000, 'cover1.jpg', 'audio1.mp3', 320, 'HD • 320 kbps', 'Pop', 1, 1, 1700000000000, 1690000000000)")
            db.execSQL("INSERT INTO tracks (id, title, artist, album, durationMs, coverUrl, audioUrl, bitrateKbps, qualityBadge, genre, isLiked, isCached, lastPlayedAt, addedAt) " +
                    "VALUES ('v1_song_2', 'Second Hit', 'Artist 2', 'Album 2', 180000, 'cover2.jpg', 'audio2.mp3', 320, 'HD • 320 kbps', 'Rock', 0, 0, 0, 1690000001000)")

            db.execSQL("INSERT INTO playlists (playlistId, title, description, coverUrl, createdAt) " +
                    "VALUES (101, 'My V1 Playlist', 'Created in version 1', 'pl_cover.jpg', 1690000000000)")

            db.execSQL("INSERT INTO playlist_track_cross_ref (playlistId, trackId, orderIndex) " +
                    "VALUES (101, 'v1_song_1', 0)")
            db.execSQL("INSERT INTO playlist_track_cross_ref (playlistId, trackId, orderIndex) " +
                    "VALUES (101, 'v1_song_2', 1)")
        }

        // Open via Room with ALL_MIGRATIONS; Room validates the schema on open
        val db = Room.databaseBuilder(context, MusicDatabase::class.java, testDbName)
            .addMigrations(*MusicDatabase.ALL_MIGRATIONS)
            .build()

        val dao = db.musicDao()
        val tracks = dao.getAllTracksSync()
        assertEquals(2, tracks.size)

        val track1 = dao.getTrackById("v1_song_1")
        assertNotNull(track1)
        assertEquals("Old Hit", track1!!.title)
        assertTrue(track1.isLiked)
        assertTrue(track1.isCached)
        assertEquals(1700000000000L, track1.lastPlayedAt)
        // Check new default column values added across migrations
        assertEquals("", track1.singers)
        assertEquals("", track1.writer)
        assertEquals("", track1.language)
        assertEquals("", track1.year)
        assertEquals("HD Stream", track1.source)
        assertEquals("", track1.isrc)

        val playlists = dao.getAllPlaylistsSync()
        assertEquals(1, playlists.size)
        assertEquals(101L, playlists[0].playlistId)
        assertEquals("My V1 Playlist", playlists[0].title)

        val crossRefs = dao.getAllPlaylistCrossRefsSync()
        assertEquals(2, crossRefs.size)
        assertEquals(101L, crossRefs[0].playlistId)
        assertEquals("v1_song_1", crossRefs[0].trackId)
        assertEquals(0, crossRefs[0].orderIndex)

        // Verify newly added tables are fully functional
        val eventsDao = db.listeningEventDao()
        eventsDao.insertListeningEvent(
            ListeningEventEntity(
                trackId = "v1_song_1",
                artistName = "Legend Artist",
                timestamp = System.currentTimeMillis()
            )
        )
        val events = eventsDao.getRecentEvents(10)
        assertEquals(1, events.size)

        db.close()
    }

    @Test
    fun testMigrateFromV5ToV8CreatesListeningEventsAndArtworkCache() = runBlocking {
        createOldDatabase(5) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `tracks` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `artist` TEXT NOT NULL,
                    `album` TEXT NOT NULL,
                    `durationMs` INTEGER NOT NULL,
                    `coverUrl` TEXT NOT NULL,
                    `audioUrl` TEXT NOT NULL,
                    `bitrateKbps` INTEGER NOT NULL,
                    `qualityBadge` TEXT NOT NULL,
                    `genre` TEXT NOT NULL,
                    `isLiked` INTEGER NOT NULL,
                    `isCached` INTEGER NOT NULL,
                    `lastPlayedAt` INTEGER NOT NULL,
                    `addedAt` INTEGER NOT NULL,
                    `singers` TEXT NOT NULL,
                    `writer` TEXT NOT NULL,
                    `language` TEXT NOT NULL,
                    `year` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `isrc` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `playlists` (
                    `playlistId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `coverUrl` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `playlist_track_cross_ref` (
                    `playlistId` INTEGER NOT NULL,
                    `trackId` TEXT NOT NULL,
                    `orderIndex` INTEGER NOT NULL,
                    PRIMARY KEY(`playlistId`, `trackId`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_track_cross_ref_trackId` ON `playlist_track_cross_ref` (`trackId`)")

            db.execSQL("INSERT INTO tracks VALUES ('track_5', 'V5 Title', 'Artist 5', 'Album 5', 200000, 'cover', 'url', 320, 'badge', 'Pop', 1, 0, 5000, 1000, 'Singer', 'Writer', 'Hindi', '2023', 'HD Stream', 'ISRC123')")
            db.execSQL("INSERT INTO playlists VALUES (50, 'V5 Playlist', 'desc', 'cover', 2000)")
            db.execSQL("INSERT INTO playlist_track_cross_ref VALUES (50, 'track_5', 0)")
        }

        val db = Room.databaseBuilder(context, MusicDatabase::class.java, testDbName)
            .addMigrations(*MusicDatabase.ALL_MIGRATIONS)
            .build()

        val track = db.musicDao().getTrackById("track_5")
        assertNotNull(track)
        assertEquals("ISRC123", track!!.isrc)
        assertEquals("Singer", track.singers)

        // Verify tables created in v6 and v7 work without errors
        db.listeningEventDao().insertListeningEvent(
            ListeningEventEntity(trackId = "track_5", artistName = "Artist 5")
        )
        assertEquals(1, db.listeningEventDao().getRecentEvents(10).size)

        db.artworkCacheDao().insert(
            com.example.data.local.ArtworkCacheEntity(cacheKey = "key1", rawArtworkUrl = "http://art.com")
        )
        assertEquals("http://art.com", db.artworkCacheDao().getUrl("key1"))

        db.close()
    }

    @Test
    fun testMigrateFromV7ToV8PreservesData() = runBlocking {
        createOldDatabase(7) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `tracks` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `artist` TEXT NOT NULL,
                    `album` TEXT NOT NULL,
                    `durationMs` INTEGER NOT NULL,
                    `coverUrl` TEXT NOT NULL,
                    `audioUrl` TEXT NOT NULL,
                    `bitrateKbps` INTEGER NOT NULL,
                    `qualityBadge` TEXT NOT NULL,
                    `genre` TEXT NOT NULL,
                    `isLiked` INTEGER NOT NULL,
                    `isCached` INTEGER NOT NULL,
                    `lastPlayedAt` INTEGER NOT NULL,
                    `addedAt` INTEGER NOT NULL,
                    `singers` TEXT NOT NULL,
                    `writer` TEXT NOT NULL,
                    `language` TEXT NOT NULL,
                    `year` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `isrc` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`playlistId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `coverUrl` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_track_cross_ref` (`playlistId` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `trackId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_track_cross_ref_trackId` ON `playlist_track_cross_ref` (`trackId`)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `listening_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `trackId` TEXT NOT NULL,
                    `artistId` TEXT NOT NULL,
                    `artistName` TEXT NOT NULL,
                    `album` TEXT NOT NULL,
                    `genre` TEXT NOT NULL,
                    `language` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `playedDurationMs` INTEGER NOT NULL,
                    `trackDurationMs` INTEGER NOT NULL,
                    `completionRatio` REAL NOT NULL,
                    `completed` INTEGER NOT NULL,
                    `skipped` INTEGER NOT NULL,
                    `liked` INTEGER NOT NULL,
                    `source` TEXT NOT NULL,
                    `eventType` TEXT NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE TABLE IF NOT EXISTS `artwork_cache` (`cacheKey` TEXT NOT NULL, `rawArtworkUrl` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))")

            db.execSQL("INSERT INTO tracks VALUES ('track_7', 'V7 Song', 'Artist 7', 'Album 7', 150000, 'cover', 'url', 320, 'badge', 'Pop', 1, 1, 9999, 1000, 's', 'w', 'Hindi', '2024', 'HD Stream', 'ISRC7')")
        }

        val db = Room.databaseBuilder(context, MusicDatabase::class.java, testDbName)
            .addMigrations(*MusicDatabase.ALL_MIGRATIONS)
            .build()

        val track = db.musicDao().getTrackById("track_7")
        assertNotNull(track)
        assertEquals("V7 Song", track!!.title)
        assertTrue(track.isLiked)
        assertTrue(track.isCached)
        db.close()
    }

    @Test
    fun testSafeDowngradeFromV8ToV7DoesNotWipeData() = runBlocking {
        // Create V8 database
        val dbV8 = Room.databaseBuilder(context, MusicDatabase::class.java, testDbName)
            .addMigrations(*MusicDatabase.ALL_MIGRATIONS)
            .build()

        dbV8.musicDao().insertOrUpdateTrack(
            TrackEntity(
                id = "downgrade_track",
                title = "Safe Track",
                artist = "Safe Artist",
                album = "Album",
                durationMs = 120000,
                coverUrl = "",
                audioUrl = "",
                isLiked = true
            )
        )
        dbV8.close()

        // Apply downgrade migration 8->7 directly
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(testDbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    if (oldVersion == 8 && newVersion == 7) {
                        MusicDatabase.MIGRATION_8_7.migrate(db)
                    }
                }
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val sdb = helper.writableDatabase

        // Verify data was NOT wiped
        val cursor = sdb.query("SELECT title, isLiked FROM tracks WHERE id = 'downgrade_track'")
        assertTrue("Track record must exist after downgrade", cursor.moveToFirst())
        assertEquals("Safe Track", cursor.getString(0))
        assertEquals(1, cursor.getInt(1))
        cursor.close()
        sdb.close()
        helper.close()
    }
}
