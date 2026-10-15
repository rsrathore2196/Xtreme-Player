package com.example.data.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/** Disposable recommendation responses, kept separate from the user's music database. */
@Entity(tableName = "lastfm_responses")
data class LastFmCachedResponse(@PrimaryKey val cacheKey: String, val body: String, val fetchedAt: Long)

@Dao
abstract class LastFmResponseDao {
    @Query("SELECT * FROM lastfm_responses WHERE cacheKey = :key LIMIT 1")
    abstract suspend fun get(key: String): LastFmCachedResponse?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(entries: List<LastFmCachedResponse>)

    @Query("DELETE FROM lastfm_responses WHERE cacheKey NOT IN (SELECT cacheKey FROM lastfm_responses ORDER BY fetchedAt DESC LIMIT 512)")
    protected abstract suspend fun trim()

    @Transaction
    open suspend fun put(entries: List<LastFmCachedResponse>) {
        insert(entries)
        trim()
    }
}

@Database(entities = [LastFmCachedResponse::class], version = 1, exportSchema = true)
abstract class LastFmResponseCache : RoomDatabase() {
    abstract fun responses(): LastFmResponseDao

    companion object {
        @Volatile private var instance: LastFmResponseCache? = null
        fun get(context: Context): LastFmResponseCache = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LastFmResponseCache::class.java,
                "lastfm_recommendations.db").build().also { instance = it }
        }
    }
}
