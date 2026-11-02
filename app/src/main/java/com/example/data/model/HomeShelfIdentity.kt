package com.example.data.model

import java.text.Normalizer
import java.util.Locale
import java.security.MessageDigest

/** Native identities only; never fuzzy-match recordings or rank server results. */
internal object HomeShelfIdentity {
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    fun title(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace("&amp;", "&").replace(punctuation, " ").trim()
    fun entries(shelf: ServerHomeShelf): List<ServerHomeItem> = shelf.items.ifEmpty { shelf.tracks.map { ServerHomeItem.Song(it) } }
    fun item(entry: ServerHomeItem): String = when (entry) {
        is ServerHomeItem.Song -> "song:${entry.track.id.removePrefix("yt_")}"
        is ServerHomeItem.Album -> "album:${entry.album.id}"
        is ServerHomeItem.Playlist -> "playlist:${entry.playlist.id.removePrefix("VL")}"
    }
    fun stableId(shelf: ServerHomeShelf): String {
        val destination = shelf.browseId.takeIf(String::isNotBlank)?.let { "$it|${shelf.browseParams}" }
        val native = shelf.providerId.takeIf(String::isNotBlank)
        val identity = destination ?: native ?: entries(shelf).map(::item).distinct().sorted().joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest("${title(shelf.title)}|$identity".toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
        return "server_shelf_$hash"
    }
    private data class Signature(val shelf: ServerHomeShelf, val title: String, val content: Set<String>)
    private fun signature(shelf: ServerHomeShelf) = Signature(shelf, title(shelf.title), entries(shelf).mapTo(hashSetOf(), ::item))
    private fun same(a: Signature, b: Signature): Boolean {
        val first = a.shelf; val second = b.shelf
        if (first.browseId.isNotBlank() && second.browseId.isNotBlank() &&
            (first.browseId != second.browseId || first.browseParams != second.browseParams)) return false
        if (first.id == second.id) return true
        if (a.title != b.title) return false
        if (first.browseId.isNotBlank() && first.browseId == second.browseId && first.browseParams == second.browseParams) return true
        if (first.providerId.isNotBlank() && first.providerId == second.providerId) return true
        if (a.content == b.content) return true
        val overlap = a.content.count(b.content::contains)
        return overlap >= 3 && overlap * 5 >= minOf(a.content.size, b.content.size) * 4 &&
            overlap * 2 >= maxOf(a.content.size, b.content.size)
    }
    fun equivalent(first: ServerHomeShelf, second: ServerHomeShelf) = same(signature(first), signature(second))
    private fun merge(first: ServerHomeShelf, next: ServerHomeShelf): ServerHomeShelf {
        val entries = entries(first)
        val ids = entries.mapTo(hashSetOf(), ::item)
        val added = entries(next).filter { ids.add(item(it)) }
        val merged = first.copy(tracks = first.tracks + added.filterIsInstance<ServerHomeItem.Song>().map { it.track },
            items = if (first.items.isEmpty() && next.items.isEmpty()) emptyList() else entries + added,
            providerId = first.providerId.ifBlank { next.providerId },
            browseId = first.browseId.ifBlank { next.browseId },
            browseParams = if (first.browseId.isBlank()) next.browseParams else first.browseParams,
            continuationToken = next.continuationToken ?: first.continuationToken)
        return if (merged == first) first else merged
    }
    fun deduplicate(shelves: List<ServerHomeShelf>): List<ServerHomeShelf> {
        val result = mutableListOf<Signature>()
        for (raw in shelves) {
            if (raw.title.isBlank()) continue
            val seen = hashSetOf<String>()
            val valid = entries(raw).filter { entry -> when (entry) {
                is ServerHomeItem.Song -> entry.track.id.isNotBlank() && entry.track.title.isNotBlank()
                is ServerHomeItem.Album -> entry.album.id.isNotBlank() && entry.album.title.isNotBlank()
                is ServerHomeItem.Playlist -> entry.playlist.id.isNotBlank() && entry.playlist.title.isNotBlank()
            } && seen.add(item(entry)) }
            if (valid.isEmpty()) continue
            val shelf = if (valid == entries(raw)) raw else raw.copy(
                tracks = valid.filterIsInstance<ServerHomeItem.Song>().map { it.track }, items = if (raw.items.isEmpty()) emptyList() else valid)
            val descriptor = signature(shelf)
            val index = result.indexOfFirst { same(it, descriptor) }
            if (index >= 0) result[index] = signature(merge(result[index].shelf, shelf))
            else result.add(descriptor)
        }
        val normalized = result.map { it.shelf }
        return if (normalized == shelves) shelves else normalized
    }
    fun refresh(previous: List<ServerHomeShelf>, incoming: List<ServerHomeShelf>): List<ServerHomeShelf> {
        if (incoming.isEmpty()) return incoming
        val fresh = deduplicate(incoming).map { shelf ->
            previous.firstOrNull { equivalent(it, shelf) }?.let { old ->
                (if (old.id == shelf.id) shelf else merge(shelf, old)).copy(id = old.id)
            } ?: shelf
        }
        // A refreshed explicit identity replaces its old payload. Other cached
        // continuation shelves remain useful and join the same equivalence pass.
        val refreshed = fresh.mapTo(hashSetOf()) { it.id }
        return deduplicate(fresh + previous.filterNot { it.id in refreshed })
    }
}
