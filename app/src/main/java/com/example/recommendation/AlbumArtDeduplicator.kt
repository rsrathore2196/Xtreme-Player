package com.example.recommendation

import com.example.data.model.MusicTrack

/**
 * Utility to eliminate duplicate album art, duplicate album tracks,
 * and duplicate song titles across recommendations and shelves.
 */
object AlbumArtDeduplicator {

    /**
     * Extracts normalized base key of an album cover image URL.
     * Strips dimensions (500x500, 150x150, 50x50) and query parameters
     * so different resolutions of the same artwork are recognized as identical.
     */
    fun extractBaseCoverKey(url: String): String {
        if (url.isBlank()) return ""
        return url
            .replace(Regex("500x500|150x150|50x50"), "")
            .substringBefore("?")
            .trim()
            .lowercase()
    }

    /**
     * Normalizes album name by removing generic suffixes like "Soundtrack", "OST", "Deluxe Edition",
     * to identify tracks originating from the exact same album release.
     */
    fun extractCleanAlbumKey(album: String): String {
        val clean = album.trim().lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
            .replace(Regex("(?i)\\b(original motion picture soundtrack|soundtrack|ost|deluxe|edition|special|single|hd stream|online stream|album)\\b"), "")
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
        return clean
    }

    /**
     * Filters tracks to strictly guarantee distinct album art, distinct albums,
     * and distinct songs without repeating names or variants from different albums.
     */
    fun List<MusicTrack>.distinctAlbumAndCover(limit: Int = 15): List<MusicTrack> {
        val seenAlbums = mutableSetOf<String>()
        val seenCovers = mutableSetOf<String>()
        val seenRoots = mutableSetOf<String>()
        val result = mutableListOf<MusicTrack>()

        for (track in this) {
            val rootTitle = RecommendationEngine.extractRootTitle(track.title)
            val coverKey = extractBaseCoverKey(track.coverUrl)
            val albumKey = extractCleanAlbumKey(track.album)

            // 1. Never repeat same song name or variant
            if (rootTitle.isNotBlank() && (seenRoots.contains(rootTitle) || seenRoots.any { RecommendationEngine.isSameSongOrVariant(it, rootTitle) })) {
                continue
            }

            // 2. Never repeat same album cover artwork
            if (coverKey.isNotBlank() && seenCovers.contains(coverKey)) {
                continue
            }

            // 3. Never repeat songs from the exact same album (if album is identified and length >= 3)
            if (albumKey.length >= 3 && seenAlbums.contains(albumKey)) {
                continue
            }

            if (rootTitle.isNotBlank()) seenRoots.add(rootTitle)
            if (coverKey.isNotBlank()) seenCovers.add(coverKey)
            if (albumKey.length >= 3) seenAlbums.add(albumKey)
            result.add(track)

            if (result.size >= limit) break
        }
        return result
    }

    /**
     * Interleaves multiple diverse lists (e.g. from different artists, genres, or queries)
     * in round-robin fashion, then filters to guarantee distinct album art and albums.
     */
    fun interleaveAndDiversify(sources: List<List<MusicTrack>>, limit: Int = 20): List<MusicTrack> {
        val interleaved = mutableListOf<MusicTrack>()
        val maxLen = sources.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (source in sources) {
                if (i < source.size) {
                    interleaved.add(source[i])
                }
            }
        }
        return interleaved.distinctAlbumAndCover(limit)
    }
}
