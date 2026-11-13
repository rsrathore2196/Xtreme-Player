package com.example.connections

import android.content.Context
import android.util.AtomicFile
import androidx.work.*
import com.example.data.local.ListeningSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Uses the service's measured listening clock, so seeking and paused time cannot qualify a play. */
internal class LastFmScrobbler(private val context: Context,
    private val sendNowPlaying: suspend (LastFmConnection, Map<String, String>) -> Unit = { connection, params ->
        LastFmClient.call(connection, "track.updateNowPlaying", params); Unit
    }) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var occurrence = ""
    private var startedAt = 0L
    private var submitted = false
    private var nowPlaying = false
    private val snapshots = kotlinx.coroutines.channels.Channel<ListeningSnapshot>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    init { scope.launch {
        try { for (snapshot in snapshots) {
            try { record(snapshot) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Retry on the next measured checkpoint. */ }
        } } finally { scope.cancel() }
    } }
    fun accept(snapshot: ListeningSnapshot) { snapshots.trySend(snapshot) }
    private suspend fun record(snapshot: ListeningSnapshot) {
        val store = ConnectionStore.get(context)
        store.load()
        val config = store.state.value.lastFm
        if (!config.enabled || config.session.isBlank()) { nowPlaying = false; return }
        if (snapshot.occurrence != occurrence) {
            occurrence = snapshot.occurrence
            startedAt = (System.currentTimeMillis() - snapshot.listenedMs) / 1000L
            submitted = false; nowPlaying = false
        }
        val artist = if (config.primaryArtistOnly) snapshot.track.primaryArtists.firstOrNull()
            ?: snapshot.track.artist.substringBefore(",").trim() else snapshot.track.artist
        if (artist.isBlank() || snapshot.track.title.isBlank()) return
        val params = mutableMapOf("artist" to artist, "track" to snapshot.track.title,
            "album" to snapshot.track.album, "duration" to (snapshot.track.durationMs / 1000L).toString())
        if (!snapshot.playing) nowPlaying = false
        if (config.nowPlaying && snapshot.playing && !nowPlaying) {
            nowPlaying = true
            val nowPlayingParameters = params.toMap()
            scope.launch { runCatching { sendNowPlaying(config, nowPlayingParameters) } }
        }
        if (!submitted && shouldScrobble(snapshot.track.durationMs, snapshot.listenedMs,
                config.minDurationSeconds, config.thresholdPercent, config.thresholdSeconds)) {
            params["timestamp"] = startedAt.toString()
            ScrobbleOutbox.get(context).enqueue(config.username, snapshot.occurrence, params)
            submitted = true
        }
    }
    fun close() { snapshots.close() }
}

internal class ScrobbleOutbox private constructor(context: Context) {
    private val context = context.applicationContext
    private val lock = Mutex()
    private val file get() = AtomicFile(File(context.noBackupFilesDir, "scrobble_outbox.json"))
    private fun read(): JSONObject = if (file.baseFile.exists()) JSONObject(file.openRead().bufferedReader().use { it.readText() })
        else JSONObject().put("pending", JSONArray()).put("sent", JSONArray())
    private fun write(root: JSONObject) {
        val output = file.startWrite()
        try { output.write(root.toString().toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    suspend fun enqueue(user: String, occurrence: String, parameters: Map<String, String>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val root = read(); val pending = root.getJSONArray("pending"); val sent = root.getJSONArray("sent")
            val id = ConnectionStore.digest("$user|$occurrence")
            val exists = (0 until pending.length()).any { pending.getJSONObject(it).getString("id") == id } ||
                (0 until sent.length()).any { sent.getString(it) == id }
            if (!exists) {
                pending.put(JSONObject().put("id", id).put("owner", user).put("params", JSONObject(parameters)))
                write(root)
            }
        }
        schedule()
    }
    suspend fun drain(): Boolean = withContext(Dispatchers.IO) {
        val store = ConnectionStore.get(context); store.load()
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = lock.withLock { read().getJSONArray("pending").optJSONObject(0) } ?: return@withContext true
            val session = store.state.value.lastFm
            if (session.session.isBlank() || session.username != entry.getString("owner")) { acknowledge(entry.getString("id")); continue }
            if (!session.enabled) return@withContext true
            val params = entry.getJSONObject("params").let { value -> value.keys().asSequence().associateWith { value.getString(it) } }
            try { LastFmClient.call(session, "track.scrobble", params) }
            catch (error: LastFmException) {
                if (error.code == 9) { store.change { if (it.lastFm.session == session.session) it.copy(lastFm = it.lastFm.copy(session = "")) else it }; return@withContext true }
                if (error.code in setOf(6, 10, 13, 26)) { acknowledge(entry.getString("id")); continue }
                return@withContext false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@withContext false }
            acknowledge(entry.getString("id"))
        }
        @Suppress("UNREACHABLE_CODE") false
    }
    private suspend fun acknowledge(id: String) = lock.withLock {
        val root = read(); val pending = root.getJSONArray("pending"); val sent = root.getJSONArray("sent")
        root.put("pending", JSONArray((0 until pending.length()).map { pending.getJSONObject(it) }.filterNot { it.getString("id") == id }))
        val remembered = (0 until sent.length()).map { sent.getString(it) }.takeLast(499) + id
        root.put("sent", JSONArray(remembered)); write(root)
    }
    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { file.delete() }; WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    suspend fun resumeIfPending() = withContext(Dispatchers.IO) {
        if (lock.withLock { file.baseFile.exists() && read().getJSONArray("pending").length() > 0 }) schedule()
    }
    fun schedule() {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ScrobbleWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    companion object {
        private const val WORK = "lastfm_scrobbles"
        @android.annotation.SuppressLint("StaticFieldLeak") // Constructor retains only applicationContext.
        @Volatile private var instance: ScrobbleOutbox? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: ScrobbleOutbox(context.applicationContext).also { instance = it } }
    }
}
class ScrobbleWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork() = if (ScrobbleOutbox.get(applicationContext).drain()) Result.success() else Result.retry()
}
