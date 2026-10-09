package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArtworkCacheDao {
    @Query("SELECT rawArtworkUrl FROM artwork_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun getUrl(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ArtworkCacheEntity)

    @Query("DELETE FROM artwork_cache WHERE timestamp < :expiryTime")
    suspend fun deleteExpired(expiryTime: Long)

    @Query("SELECT COUNT(*) FROM artwork_cache")
    suspend fun getCacheCount(): Int
}
