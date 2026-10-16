package com.example.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.data.remote.YouTubeAudioStreamService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One bounded writer for the real session; no Room schema changes or demo fallback. */
internal object PlaybackSessionStore {
    data class Snapshot(val items: List<MediaItem>, val index: Int, val positionMs: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<Snapshot>(Channel.CONFLATED)
    private val fileMutex = Mutex()
    @Volatile private var file: AtomicFile? = null
    @Volatile private var latest: Snapshot? = null

    @Synchronized
    private fun initialize(context: Context) {
        if (file != null) return
        file = AtomicFile(File(context.applicationContext.filesDir, "real_playback_session.json"))
        scope.launch {
            for (snapshot in pending) fileMutex.withLock {
                try {
                    val queue = JSONArray()
                    snapshot.items.forEach { item ->
                        val meta = item.mediaMetadata
                        val extras = meta.extras
                        queue.put(JSONObject().put("id", item.mediaId)
                            .put("uri", item.localConfiguration?.uri?.toString().orEmpty())
                            .put("title", meta.title?.toString().orEmpty()).put("artist", meta.artist?.toString().orEmpty())
                            .put("album", meta.albumTitle?.toString().orEmpty()).put("artwork", meta.artworkUri?.toString().orEmpty())
                            .put("genre", meta.genre?.toString() ?: extras?.getString("genre").orEmpty())
                            .put("source", extras?.getString("media_source").orEmpty())
                            .put("bitrate", extras?.getInt("bitrate", 0) ?: 0)
                            .put("duration", extras?.getLong("duration", 0L) ?: 0L)
                            .put("qualityBadge", extras?.getString("qualityBadge").orEmpty()).apply {
                                listOf("singers", "writer", "language", "year", "isrc").forEach { key -> put(key, extras?.getString(key).orEmpty()) }
                                put("isLiked", extras?.getBoolean("isLiked", false) ?: false)
                                put("isLossless", extras?.getBoolean("isLossless", false) ?: false)
                                put("recommendationOnly", extras?.getBoolean("recommendation_only", false) ?: false)
                            })
                    }
                    val json = JSONObject().put("version", 1).put("index", snapshot.index)
                        .put("position", snapshot.positionMs).put("queue", queue).toString()
                    val output = requireNotNull(file).startWrite()
                    try { output.write(json.toByteArray(Charsets.UTF_8)); requireNotNull(file).finishWrite(output) }
                    catch (error: Exception) { requireNotNull(file).failWrite(output); throw error }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.w("PlaybackSession", "Session checkpoint failed: ${e.message}") }
            }
        }
    }

    fun isReal(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return false
        val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        return item.mediaId.isNotBlank() && uri.toString().isNotBlank() &&
            host != "soundhelix.com" && !host.endsWith(".soundhelix.com") &&
            uri.scheme in setOf("https", "http", "file", "content", "xtreme-youtube", "xtreme-sabr")
    }

    fun checkpoint(context: Context, snapshot: Snapshot) {
        if (snapshot.items.isEmpty() || snapshot.index !in snapshot.items.indices ||
            !snapshot.items.all(::isReal)) return
        initialize(context)
        latest = snapshot
        pending.trySend(snapshot)
    }

    suspend fun restore(context: Context): Snapshot? = withContext(Dispatchers.IO) {
        initialize(context)
        latest?.let { return@withContext it }
        fileMutex.withLock {
            try {
                val root = requireNotNull(file).openRead().bufferedReader().use { JSONObject(it.readText()) }
                if (root.optInt("version") != 1) return@withLock null
                val queue = root.getJSONArray("queue")
                if (queue.length() == 0) return@withLock null
                val ids = (0 until queue.length()).map { queue.getJSONObject(it).getString("id") }.distinct()
                val known = (context.applicationContext as? com.example.XtremeMusicApp)?.database?.musicDao()?.let { dao ->
                    ids.chunked(900).flatMap { dao.getTracksByIds(it) }.associateBy { it.id }
                }.orEmpty()
                val items = (0 until queue.length()).map { index ->
                    val entry = queue.getJSONObject(index)
                    val id = entry.getString("id")
                    val url = YouTubeAudioStreamService.playbackUri(id, entry.getString("uri"))
                    val metadata = MediaMetadata.Builder().setTitle(entry.getString("title"))
                        .setArtist(entry.getString("artist")).setAlbumTitle(entry.optString("album"))
                        .setGenre(entry.optString("genre")).setArtworkUri(entry.optString("artwork")
                            .takeIf(String::isNotBlank)?.let(Uri::parse))
                        .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setExtras(Bundle().apply {
                            putString("media_source", entry.optString("source")); putInt("bitrate", entry.optInt("bitrate"))
                            putLong("duration", entry.optLong("duration")); putString("qualityBadge", entry.optString("qualityBadge"))
                            listOf("singers", "writer", "language", "year", "isrc").forEach { key -> putString(key, entry.optString(key)) }
                            putBoolean("isLiked", known[id]?.isLiked ?: entry.optBoolean("isLiked"))
                            putBoolean("isLossless", entry.optBoolean("isLossless"))
                            putBoolean("recommendation_only", entry.optBoolean("recommendationOnly"))
                        }).build()
                    MediaItem.Builder().setMediaId(id).setUri(url).setMediaMetadata(metadata).build()
                }
                val index = root.getInt("index")
                val position = root.getLong("position")
                if (index !in items.indices || position < 0L || !items.all(::isReal)) return@withLock null
                latest ?: Snapshot(items, index, position).also { latest = it }
            } catch (e: CancellationException) { throw e }
            catch (_: java.io.FileNotFoundException) { null }
            catch (e: Exception) { android.util.Log.w("PlaybackSession", "Saved session is unavailable: ${e.message}"); null }
        }
    }
}
