package com.example.data.cache

import android.content.Context
import android.util.LruCache
import androidx.room.*
import com.example.data.model.MusicTrack
import com.example.performance.BackgroundWorkCoordinator
import com.example.performance.AppDispatchers
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Disposable discovery/mapping data. Deliberately separate from all user records. */
@Entity(tableName = "recommendation_entries")
data class RecommendationCacheEntry(@PrimaryKey val cacheKey: String, val body: String,
    val fetchedAt: Long, val expiresAt: Long)

@Dao
abstract class RecommendationCacheDao {
    @Query("SELECT * FROM recommendation_entries WHERE cacheKey = :key LIMIT 1")
    abstract suspend fun get(key: String): RecommendationCacheEntry?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(rows: List<RecommendationCacheEntry>)
    @Query("DELETE FROM recommendation_entries WHERE cacheKey NOT IN (SELECT cacheKey FROM recommendation_entries ORDER BY fetchedAt DESC LIMIT 2048)")
    protected abstract suspend fun trim()
    @Transaction
    open suspend fun put(rows: List<RecommendationCacheEntry>) { insert(rows); trim() }
}

@Database(entities = [RecommendationCacheEntry::class], version = 1, exportSchema = true)
abstract class RecommendationCacheDatabase : RoomDatabase() {
    abstract fun entries(): RecommendationCacheDao
}

/** A transaction acknowledges precisely its captured versions, never a newer put.
 * Failure/cancellation leaves the pending batch intact for the next bounded retry. */
internal suspend fun persistRecommendationBatch(pending: MutableMap<String, RecommendationCacheEntry>,
    persistence: Mutex = Mutex(), commit: suspend (List<RecommendationCacheEntry>) -> Unit): Boolean = persistence.withLock {
    val batch = synchronized(pending) { pending.values.toList() }
    if (batch.isEmpty()) return@withLock false
    commit(batch)
    synchronized(pending) {
        batch.forEach { entry -> if (pending[entry.cacheKey] === entry) pending.remove(entry.cacheKey) }
    }
    true
}

/** The same lane covers checkpoint validation, commit and acknowledgement. */
internal suspend fun persistRecommendationCheckpoint(pending: MutableMap<String, RecommendationCacheEntry>,
    entry: RecommendationCacheEntry, persistence: Mutex,
    commit: suspend (List<RecommendationCacheEntry>) -> Unit): Boolean = persistence.withLock {
    if (synchronized(pending) { pending[entry.cacheKey] !== entry }) return@withLock false
    commit(listOf(entry))
    synchronized(pending) { if (pending[entry.cacheKey] === entry) pending.remove(entry.cacheKey) }
    true
}

/** Memory hits first; batched writes never delay a matched song or invalidate the library. */
@OptIn(FlowPreview::class)
internal class RecommendationCache private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private val memory = LruCache<String, RecommendationCacheEntry>(512)
    private val pending = linkedMapOf<String, RecommendationCacheEntry>()
    private val persistence = Mutex()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val database by lazy { Room.databaseBuilder(app, RecommendationCacheDatabase::class.java,
        "recommendation_cache.db")
        .setQueryExecutor(AppDispatchers.Database.asExecutor())
        .setTransactionExecutor(AppDispatchers.Io.asExecutor()).build() }
    init {
        scope.launch {
            var lastWrite = 0L
            writes.receiveAsFlow().debounce(2_000L).collect {
                // Slow lookups must not turn the debounce into one disk commit per song.
                delay((10_000L - (android.os.SystemClock.elapsedRealtime() - lastWrite)).coerceAtLeast(0))
                var attempt = 0
                while (attempt < 3) {
                    try {
                        BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                            requireForeground = false) {
                            if (persistRecommendationBatch(pending, persistence) { database.entries().put(it) })
                                lastWrite = android.os.SystemClock.elapsedRealtime()
                        }
                        break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if (++attempt < 3) delay(500L shl (attempt - 1))
                    }
                }
            }
        }
    }
    suspend fun get(key: String): RecommendationCacheEntry? = memory.get(key) ?:
        synchronized(pending) { pending[key] } ?: withContext(AppDispatchers.Io) {
        try { database.entries().get(key)?.also { memory.put(key, it) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    suspend fun put(key: String, tracks: List<MusicTrack>, ttl: Long) {
        val body = withContext(com.example.performance.AppDispatchers.CpuBounded) { RecommendationTrackCodec.encode(tracks) }
        putBody(key, body, ttl)
    }
    /** Small public catalog snapshots share the existing bounded metadata cache and writer. */
    suspend fun putBody(key: String, body: String, ttl: Long, checkpoint: Boolean = false) {
        val now = System.currentTimeMillis()
        val entry = RecommendationCacheEntry(key, body, now, now + ttl)
        memory.put(key, entry)
        synchronized(pending) {
            pending[key] = entry
            if (pending.size > 512) pending.remove(pending.keys.first())
        }
        writes.trySend(Unit)
        if (checkpoint) withContext(AppDispatchers.Io) {
            try {
                persistRecommendationCheckpoint(pending, entry, persistence) { database.entries().put(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The existing bounded writer retains this exact entry. */ }
        }
    }
    companion object {
        @Volatile private var instance: RecommendationCache? = null
        fun get(context: Context): RecommendationCache = instance ?: synchronized(this) {
            instance ?: RecommendationCache(context).also { instance = it }
        }
    }
}

/** Metadata only: no YouTube audio URLs are requested or persisted for discovery. */
internal object RecommendationTrackCodec {
    fun encode(tracks: List<MusicTrack>): String = JSONArray(tracks.map { t -> JSONObject()
        .put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album)
        .put("duration", t.durationMs).put("cover", t.coverUrl).put("audio", if (t.id.startsWith("yt_")) "" else t.audioUrl)
        .put("bitrate", t.bitrateKbps).put("badge", t.qualityBadge).put("genre", t.genre)
        .put("singers", t.singers).put("primaryArtists", JSONArray(t.primaryArtists)).put("language", t.language).put("year", t.year)
        .put("source", t.source).put("isrc", t.isrc) }).toString()
    fun decode(body: String, maxTracks: Int = 80): List<MusicTrack> = try {
        val rows = JSONArray(body)
        (0 until minOf(rows.length(), maxTracks)).mapNotNull { i -> rows.optJSONObject(i)?.let { t ->
            MusicTrack(id = t.getString("id"), title = t.getString("title"), artist = t.getString("artist"),
                album = t.optString("album"), durationMs = t.optLong("duration"), coverUrl = t.optString("cover"),
                audioUrl = t.optString("audio"), bitrateKbps = t.optInt("bitrate"), qualityBadge = t.optString("badge"),
                genre = t.optString("genre", "Unknown"), singers = t.optString("singers"),
                language = t.optString("language", "Unknown"), year = t.optString("year"),
                source = t.optString("source"), isrc = t.optString("isrc"), primaryArtists = t.optJSONArray("primaryArtists")?.let { names ->
                    (0 until names.length()).map { names.optString(it) }.filter(String::isNotBlank)
                }.orEmpty())
        } }
    } catch (_: Exception) { emptyList() }
}
