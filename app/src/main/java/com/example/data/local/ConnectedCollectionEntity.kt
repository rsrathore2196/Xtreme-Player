package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Import identity stays out of playlist titles/descriptions and contains no authentication material. */
@Entity(tableName = "connected_collections", primaryKeys = ["provider", "accountId", "remoteId"],
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["playlistId"],
        childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)], indices = [Index("playlistId")])
data class ConnectedCollectionEntity(val provider: String, val accountId: String, val remoteId: String, val playlistId: Long)
