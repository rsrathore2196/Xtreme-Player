package com.example.ui.util

import android.content.Context
import android.util.LruCache
import com.example.data.cache.RecommendationCache
import com.example.data.cache.RecommendationCacheEntry
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Remembers successful URL choices, never pixels or a different recording's artwork. */
internal class ArtworkRenditionCache(
    private val read: suspend (String) -> RecommendationCacheEntry?,
    private val write: suspend (String, String, Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Choice(val url: String?, val expiresAt: Long)
    private val memory = LruCache<String, Choice>(256)
    private val reads = Array(16) { Mutex() }

    fun peek(key: String): String? = memory.get(key)?.takeIf { it.expiresAt > now() }?.url

    suspend fun get(key: String): String? = reads[(key.hashCode() and Int.MAX_VALUE) % reads.size].withLock {
        memory.get(key)?.takeIf { it.expiresAt > now() }?.let { return@withLock it.url }
        val saved = try { read(key) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val timestamp = now()
        // A request may finish resolving while this old disk read is suspended.
        memory.get(key)?.takeIf { it.expiresAt > timestamp }?.let { return@withLock it.url }
        val choice = saved?.takeIf { it.expiresAt > timestamp && it.body.isNotBlank() }
            ?.let { Choice(it.body, it.expiresAt) } ?: Choice(null, timestamp + 60_000)
        memory.put(key, choice)
        choice.url
    }

    suspend fun remember(key: String, url: String) {
        if (url.isBlank() || peek(key) == url) return
        memory.put(key, Choice(url, now() + TTL))
        try { write(key, url, TTL) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A disposable hint must not fail an image request. */ }
    }

    companion object {
        private const val TTL = 7 * 24 * 60 * 60_000L

        fun create(context: Context): ArtworkRenditionCache {
            // Share the existing bounded metadata database and its batched writer.
            val cache = RecommendationCache.get(context)
            return ArtworkRenditionCache(cache::get, { key, url, ttl -> cache.putBody(key, url, ttl) })
        }

        fun key(metadata: ArtworkRequestMetadata, width: Int, height: Int): String {
            // Preserve signed URL and recording identity, dimensions and crop rules.
            val identity = listOf(metadata.providerUrl, metadata.trackId, width, height,
                metadata.sourceDimension, metadata.targetSize, metadata.currentTrack,
                metadata.visibleThumbnail, metadata.allowVideoFallback, metadata.landscapeArtwork,
                metadata.requireSquareSongArtwork, metadata.preserveAspectRatio).joinToString("\u0000")
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return "artwork-rendition-v1:" + buildString(digest.size * 2) {
                digest.forEach { append(hex[(it.toInt() ushr 4) and 15]); append(hex[it.toInt() and 15]) }
            }
        }
    }
}
