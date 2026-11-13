package com.example.data.model

import com.example.data.repository.CatalogDestination

sealed interface CollectionPlaybackTarget {
    val key: String
    data class Catalog(val destination: CatalogDestination) : CollectionPlaybackTarget {
        override val key: String get() = destination.key
    }
    data class SavedPlaylist(val id: Long) : CollectionPlaybackTarget {
        override val key: String get() = "saved-playlist:$id"
    }
    data class AlbumName(val name: String) : CollectionPlaybackTarget {
        override val key: String get() = "album-name:$name"
    }
}
