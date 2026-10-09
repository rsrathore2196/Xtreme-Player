package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackCrossRef::class, ListeningEventEntity::class, ArtworkCacheEntity::class],
    version = 8,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun listeningEventDao(): ListeningEventDao
    abstract fun artworkCacheDao(): ArtworkCacheDao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 1 to 2 transition
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE tracks ADD COLUMN singers TEXT NOT NULL DEFAULT ''")
                } catch (_: Exception) {}
                try {
                    db.execSQL("ALTER TABLE tracks ADD COLUMN writer TEXT NOT NULL DEFAULT ''")
                } catch (_: Exception) {}
                try {
                    db.execSQL("ALTER TABLE tracks ADD COLUMN language TEXT NOT NULL DEFAULT ''")
                } catch (_: Exception) {}
                try {
                    db.execSQL("ALTER TABLE tracks ADD COLUMN year TEXT NOT NULL DEFAULT ''")
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE tracks ADD COLUMN source TEXT NOT NULL DEFAULT 'JioSaavn'")
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE tracks ADD COLUMN isrc TEXT NOT NULL DEFAULT ''")
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS listening_events (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            trackId TEXT NOT NULL,
                            artistId TEXT NOT NULL,
                            artistName TEXT NOT NULL,
                            album TEXT NOT NULL,
                            genre TEXT NOT NULL,
                            language TEXT NOT NULL,
                            timestamp INTEGER NOT NULL,
                            sessionId TEXT NOT NULL,
                            playedDurationMs INTEGER NOT NULL,
                            trackDurationMs INTEGER NOT NULL,
                            completionRatio REAL NOT NULL,
                            completed INTEGER NOT NULL,
                            skipped INTEGER NOT NULL,
                            liked INTEGER NOT NULL,
                            source TEXT NOT NULL,
                            eventType TEXT NOT NULL DEFAULT 'STARTED'
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_trackId ON listening_events(trackId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_artistName ON listening_events(artistName)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_timestamp ON listening_events(timestamp)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_sessionId ON listening_events(sessionId)")
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS artwork_cache (
                            cacheKey TEXT PRIMARY KEY NOT NULL,
                            rawArtworkUrl TEXT NOT NULL,
                            timestamp INTEGER NOT NULL
                        )
                    """.trimIndent())
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS listening_events (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            trackId TEXT NOT NULL,
                            artistId TEXT NOT NULL,
                            artistName TEXT NOT NULL,
                            album TEXT NOT NULL,
                            genre TEXT NOT NULL,
                            language TEXT NOT NULL,
                            timestamp INTEGER NOT NULL,
                            sessionId TEXT NOT NULL,
                            playedDurationMs INTEGER NOT NULL,
                            trackDurationMs INTEGER NOT NULL,
                            completionRatio REAL NOT NULL,
                            completed INTEGER NOT NULL,
                            skipped INTEGER NOT NULL,
                            liked INTEGER NOT NULL,
                            source TEXT NOT NULL,
                            eventType TEXT NOT NULL DEFAULT 'STARTED'
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_trackId ON listening_events(trackId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_artistName ON listening_events(artistName)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_timestamp ON listening_events(timestamp)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_sessionId ON listening_events(sessionId)")
                } catch (_: Exception) {}
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS artwork_cache (
                            cacheKey TEXT PRIMARY KEY NOT NULL,
                            rawArtworkUrl TEXT NOT NULL,
                            timestamp INTEGER NOT NULL
                        )
                    """.trimIndent())
                } catch (_: Exception) {}
            }
        }

        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "xtreme_music_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade()
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
