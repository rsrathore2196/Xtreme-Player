package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.performance.AppDispatchers
import kotlinx.coroutines.asExecutor

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackCrossRef::class, ListeningEventEntity::class, ListeningTotal::class, ListeningCheckpoint::class, ListeningIdentityMigration::class],
    version = 14,
    exportSchema = true
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun listeningStatsDao(): ListeningStatsDao
    abstract fun musicDao(): MusicDao
    abstract fun listeningEventDao(): ListeningEventDao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex != -1) {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex).equals(column, ignoreCase = true)) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        fun addColumnIfNotExists(db: SupportSQLiteDatabase, table: String, column: String, columnDef: String) {
            if (!hasColumn(db, table, column)) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $columnDef")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_track_cross_ref_trackId` ON `playlist_track_cross_ref` (`trackId`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "tracks", "singers", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "tracks", "writer", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "tracks", "language", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "tracks", "year", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "tracks", "source", "TEXT NOT NULL DEFAULT 'HD Stream'")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "tracks", "isrc", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_events_trackId` ON `listening_events` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_events_artistName` ON `listening_events` (`artistName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_events_timestamp` ON `listening_events` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_events_sessionId` ON `listening_events` (`sessionId`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `artwork_cache` (
                        `cacheKey` TEXT NOT NULL,
                        `rawArtworkUrl` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`cacheKey`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure both relationship and listening indices exist in legacy databases.
                // CREATE INDEX leaves every record and playlist order unchanged.
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
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_events_trackId` ON `listening_events` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_events_artistName` ON `listening_events` (`artistName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_events_timestamp` ON `listening_events` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_events_sessionId` ON `listening_events` (`sessionId`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `artwork_cache` (
                        `cacheKey` TEXT NOT NULL,
                        `rawArtworkUrl` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`cacheKey`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_8_7 = object : Migration(8, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 8 and 7 share identical entity schemas; data is safely preserved.
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "tracks", "primaryArtists", "TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_eventType_timestamp_id ON listening_events(eventType, timestamp, id)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE `playlist_entries_new` (`playlistId` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, `entryId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                db.execSQL("INSERT INTO `playlist_entries_new` (`playlistId`, `trackId`, `orderIndex`) SELECT `playlistId`, `trackId`, `orderIndex` FROM `playlist_track_cross_ref` ORDER BY rowid")
                db.execSQL("DROP TABLE `playlist_track_cross_ref`")
                db.execSQL("ALTER TABLE `playlist_entries_new` RENAME TO `playlist_track_cross_ref`")
                db.execSQL("CREATE INDEX `index_playlist_track_cross_ref_trackId` ON `playlist_track_cross_ref` (`trackId`)")
                db.execSQL("CREATE INDEX `index_playlist_track_cross_ref_playlistId_orderIndex` ON `playlist_track_cross_ref` (`playlistId`, `orderIndex`)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Only obsolete image-lookup mappings are removed; the library and history remain intact.
                db.execSQL("DROP TABLE IF EXISTS `artwork_cache`")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS listening_totals (origin TEXT NOT NULL, bucket TEXT NOT NULL, kind TEXT NOT NULL, identity TEXT NOT NULL, trackId TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, artistId TEXT NOT NULL, coverUrl TEXT NOT NULL, durationMs INTEGER NOT NULL, isrc TEXT NOT NULL, listenedMs INTEGER NOT NULL, plays INTEGER NOT NULL, PRIMARY KEY(origin, bucket, kind, identity))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_totals_kind_bucket ON listening_totals (kind, bucket)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_totals_kind_identity ON listening_totals (kind, identity)")
                db.execSQL("CREATE TABLE IF NOT EXISTS listening_checkpoint (id INTEGER NOT NULL PRIMARY KEY, occurrence TEXT NOT NULL, days TEXT NOT NULL, eventId INTEGER NOT NULL)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE listening_checkpoint_new (occurrence TEXT NOT NULL PRIMARY KEY, days TEXT NOT NULL, eventId INTEGER NOT NULL)")
                db.execSQL("INSERT INTO listening_checkpoint_new (occurrence, days, eventId) SELECT occurrence, days, eventId FROM listening_checkpoint")
                db.execSQL("DROP TABLE listening_checkpoint")
                db.execSQL("ALTER TABLE listening_checkpoint_new RENAME TO listening_checkpoint")
                db.execSQL("CREATE TABLE listening_identity_migrations (origin TEXT NOT NULL, bucket TEXT NOT NULL, catalogIdentity TEXT NOT NULL, canonicalIdentity TEXT NOT NULL, listenedMs INTEGER NOT NULL, plays INTEGER NOT NULL, PRIMARY KEY(origin, bucket, catalogIdentity))")
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_7,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14
        )

        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "xtreme_music_db"
                )
                    .setQueryExecutor(AppDispatchers.Database.asExecutor())
                    .setTransactionExecutor(AppDispatchers.Io.asExecutor())
                    .addMigrations(*ALL_MIGRATIONS)
                    // Missing upgrade/downgrade paths must fail and leave the existing
                    // database intact. Never add destructive migration fallbacks here.
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun createInMemoryDatabase(context: Context): MusicDatabase {
            return Room.inMemoryDatabaseBuilder(context.applicationContext, MusicDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        }
    }
}
