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
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackCrossRef::class, ListeningEventEntity::class, ArtworkCacheEntity::class],
    version = 10,
    exportSchema = true
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun listeningEventDao(): ListeningEventDao
    abstract fun artworkCacheDao(): ArtworkCacheDao

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
            MIGRATION_9_10
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
