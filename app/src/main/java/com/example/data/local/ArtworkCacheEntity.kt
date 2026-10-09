package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted cache for resolved high-resolution iTunes artwork URLs.
 * Enables instant zero-network retrieval across application session restarts.
 */
@Entity(tableName = "artwork_cache")
data class ArtworkCacheEntity(
    @PrimaryKey
    val cacheKey: String,
    val rawArtworkUrl: String,
    val timestamp: Long = System.currentTimeMillis()
)
