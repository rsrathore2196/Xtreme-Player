package com.example.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.data.local.*
import com.example.performance.AppDispatchers
import com.example.recommendation.HomeRecommendationPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.util.UUID

/** One application-owned serialized writer; no service-owned job can cancel an enqueued flush. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningStatsRepository internal constructor(context: Context, suppliedDatabase: MusicDatabase? = null,
    pendingFile: java.io.File? = null) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private val database by lazy { suppliedDatabase ?: MusicDatabase.getDatabase(app) }
    private val pending = Channel<Unit>(Channel.CONFLATED)
    private val inputLock = Any()
    private val incoming = linkedMapOf<String, ListeningSnapshot>()
    private val barriers = mutableListOf<CompletableDeferred<Unit>>()
    private val journal by lazy {
        ListeningCheckpointJournal(pendingFile ?: java.io.File(app.noBackupFilesDir, "listening_pending_v1.json"))
    }
    private val revisions = MutableStateFlow(0L)
    private val _progress = MutableStateFlow<ListeningSnapshot?>(null)
    val progress = _progress.asStateFlow()
    private val summaries = android.util.LruCache<String, ListeningSummary>(8)
    private var lastHistoryRecord: ListeningEventEntity? = null
    private val origin by lazy {
        val prefs = app.getSharedPreferences("listening_stats", Context.MODE_PRIVATE)
        prefs.getString("origin", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("origin", it).commit())
        }
    }
    init {
        scope.launch {
            val retry = linkedMapOf<String, ListeningSnapshot>()
            var loaded = false
            for (signal in pending) {
                val completions = synchronized(inputLock) {
                    incoming.values.forEach { retry[it.occurrence] = merge(retry[it.occurrence], it) }
                    incoming.clear()
                    barriers.toList().also { barriers.clear() }
                }
                try {
                    if (!loaded) {
                        val restored = journal.read().associateByTo(linkedMapOf()) { it.occurrence }
                        retry.values.forEach { restored[it.occurrence] = merge(restored[it.occurrence], it) }
                        retry.clear(); retry.putAll(restored)
                        loaded = true
                    }
                    if (retry.isNotEmpty()) {
                        // Durable intent precedes aggregation. A crash between commit and
                        // acknowledgement can replay safely using occurrence watermarks.
                        journal.write(retry.values)
                        var failure: Exception? = null
                        for (next in retry.values.toList()) {
                            try {
                                val changed = persist(next)
                                retry.remove(next.occurrence)
                                if (changed.totals) invalidateAffected(next, changed.identity)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { failure = error; break }
                        }
                        journal.write(retry.values)
                        // Never prune a watermark before removing its journal entry durably.
                        database.listeningStatsDao().pruneCheckpoints(retry.keys.toList().ifEmpty { listOf("") })
                        failure?.let { throw it }
                    }
                    completions.forEach { it.complete(Unit) }
                } catch (cancelled: CancellationException) {
                    completions.forEach { it.completeExceptionally(cancelled) }
                    throw cancelled
                }
                catch (error: Exception) {
                    android.util.Log.w("ListeningStats", "Listening checkpoint pending; will retry on the next checkpoint", error)
                    completions.forEach { it.completeExceptionally(java.io.IOException("Listening checkpoint is pending", error)) }
                }
            }
        }
        pending.trySend(Unit) // Replay a previous process's journal only on this lazy repository's IO lane.
    }
    private fun invalidateAffected(snapshot: ListeningSnapshot, identityChanged: Boolean) = synchronized(summaries) {
        if (identityChanged) summaries.evictAll()
        summaries.snapshot().keys.forEach { cacheKey ->
            val parts = cacheKey.split('|')
            if (snapshot.days.keys.any { day ->
                val bucket = if (parts[1].length == 7) day.take(7) else day
                bucket >= parts[1] && bucket < parts[2]
            }) summaries.remove(cacheKey)
        }
        revisions.update { it + 1 }
    }
    private fun merge(old: ListeningSnapshot?, next: ListeningSnapshot): ListeningSnapshot {
        if (old == null) return next
        val days = (old.days.keys + next.days.keys).associateWith { day ->
            val a = old.days[day] ?: ListeningDay()
            val b = next.days[day] ?: ListeningDay()
            ListeningDay(maxOf(a.millis, b.millis), maxOf(a.plays, b.plays))
        }
        return next.copy(days = days, listenedMs = days.values.sumOf { it.millis },
            finished = old.finished || next.finished, playing = !old.finished && next.playing)
    }
    fun record(snapshot: ListeningSnapshot) {
        _progress.value = snapshot
        synchronized(inputLock) { incoming[snapshot.occurrence] = merge(incoming[snapshot.occurrence], snapshot) }
        pending.trySend(Unit)
    }
    suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        synchronized(inputLock) { barriers += completion }
        pending.send(Unit)
        completion.await()
    }
    internal fun close() { scope.cancel() }
    private data class Persisted(val totals: Boolean, val identity: Boolean)
    private suspend fun persist(snapshot: ListeningSnapshot): Persisted {
        // Reuse rich catalog metadata already owned by the library. The service's
        // decoder metadata must not replace credits, language, artwork or favorites.
        val saved = database.musicDao().getTrackById(snapshot.track.id)
        val (track, artist) = withContext(AppDispatchers.CpuBounded) {
            val original = saved?.toMusicTrack() ?: snapshot.track
            val metadata = original.copy(
                durationMs = snapshot.track.durationMs.takeIf { it > 0 } ?: original.durationMs,
                isrc = RecordingIdentity.isrc(snapshot.track.isrc) ?: original.isrc,
                primaryArtists = original.primaryArtists.ifEmpty { snapshot.track.primaryArtists })
            metadata to HomeRecommendationPolicy.mainArtists(metadata).firstOrNull()?.let(ArtistPhotoRepository::cachedArtist)
        }
        val key = ListeningClock.songKey(track)
        val artistKey = artist?.let { it.id.ifBlank { CatalogNames.normalize(it.name) } }.orEmpty()
        val origin = origin
        var changed = false
        var rekeyed = false
        var historyRecord: ListeningEventEntity? = null
        database.withTransaction {
            val dao = database.listeningStatsDao()
            val catalogKey = "catalog:${track.id}"
            if (key != catalogKey) {
                // A later verified ISRC can enrich an existing catalog track. Merge
                // its already-counted aggregates before applying just this delta.
                val priorRows = dao.songRows(catalogKey)
                for (row in priorRows) {
                    if (dao.migrateSongIdentity(row, key)) rekeyed = true
                }
            }
            val checkpoint = dao.checkpoint(snapshot.occurrence)
            val previous = checkpoint?.takeIf { it.occurrence == snapshot.occurrence }?.let { decodeDays(it.days) }.orEmpty()
            val cumulative = (previous.keys + snapshot.days.keys).associateWith { day ->
                val old = previous[day] ?: ListeningDay()
                val incoming = snapshot.days[day] ?: ListeningDay()
                ListeningDay(maxOf(old.millis, incoming.millis), maxOf(old.plays, incoming.plays))
            }
            for ((day, total) in cumulative) {
                val old = previous[day] ?: ListeningDay()
                val millis = (total.millis - old.millis).coerceAtLeast(0)
                val plays = (total.plays - old.plays).coerceAtLeast(0)
                if (millis == 0L && plays == 0L) continue
                changed = true
                for (bucket in listOf(day, day.take(7))) for (kind in listOf("song", "artist")) {
                    if (kind == "artist" && artistKey.isBlank()) continue
                    val id = if (kind == "song") key else artistKey
                    val existing = dao.row(origin, bucket, kind, id)
                    dao.put(ListeningTotal(origin, bucket, kind, id, track.id, track.title,
                        artist?.name ?: track.artist, artist?.id.orEmpty(), track.coverUrl, track.durationMs, track.isrc,
                        (existing?.listenedMs ?: 0) + millis, (existing?.plays ?: 0) + plays))
                }
            }
            // Home/history keeps its existing ranking semantics and reads this
            // same service-owned occurrence; Stats uses the qualified totals.
            val eventId = checkpoint?.takeIf { it.occurrence == snapshot.occurrence }?.eventId ?: 0L
            if (saved == null) database.musicDao().mergeTrackMetadata(TrackEntity.fromMusicTrack(track))
            else if (saved.isrc.isBlank() && RecordingIdentity.isrc(track.isrc) != null)
                database.musicDao().rememberRecordingIsrc(track.id, track.isrc)
            val listened = cumulative.values.sumOf { it.millis }
            val event = ListeningEventEntity.fromTrack(track, snapshot.occurrence, listened,
                track.durationMs, "ACTUAL_PLAY", completed = track.durationMs > 0 && listened >= track.durationMs * .9,
                skipped = snapshot.finished && listened < 15_000).copy(id = eventId)
            val history = database.listeningEventDao()
            val priorEvent = lastHistoryRecord?.takeIf { it.id == eventId && it.sessionId == snapshot.occurrence }
                ?: if (eventId > 0) history.getEventsByIds(listOf(eventId)).firstOrNull() else null
            fun meaningful(millis: Long) = millis >= 30_000 || (track.durationMs > 0 && millis >= (track.durationMs + 1) / 2)
            // Stats checkpoint every 15s; ordinary Home/history publications keep
            // their existing 30s cadence, plus milestones and playback transitions.
            val publishHistory = priorEvent == null || snapshot.finished || !snapshot.playing ||
                listened - priorEvent.playedDurationMs >= 30_000 ||
                meaningful(listened) && !meaningful(priorEvent.playedDurationMs)
            val published = if (publishHistory) event.copy(id = history.insertListeningEvent(event)) else requireNotNull(priorEvent)
            historyRecord = published
            dao.checkpoint(ListeningCheckpoint(occurrence = snapshot.occurrence, days = encodeDays(cumulative), eventId = published.id))
        }
        lastHistoryRecord = historyRecord
        return Persisted(changed || rekeyed, rekeyed)
    }
    fun peek(period: ListeningPeriod, today: LocalDate = LocalDate.now()): ListeningSummary? = summaries[key(period, today)]
    fun observe(period: ListeningPeriod): Flow<ListeningSummary> = revisions.mapLatest {
        summary(period)
    }.distinctUntilChanged().flowOn(AppDispatchers.Io)
    suspend fun summary(period: ListeningPeriod, today: LocalDate = LocalDate.now()): ListeningSummary {
        val cacheKey = key(period, today)
        summaries[cacheKey]?.let { return it }
        val revision = revisions.value
        val (start, end) = period.bounds(today)
        val rows = database.withTransaction {
            val dao = database.listeningStatsDao()
            dao.range("song", start.length, start, end) to dao.range("artist", start.length, start, end)
        }
        val result = withContext(AppDispatchers.CpuBounded) { ListeningSummary.from(rows.first, rows.second) }
        synchronized(summaries) {
            if (revision == revisions.value) summaries.put(cacheKey, result)
        }
        return result
    }
    suspend fun playbackTracks(rows: List<com.example.data.model.MusicTrack>): List<com.example.data.model.MusicTrack> = withContext(AppDispatchers.Io) {
        val saved = database.musicDao().getTracksByIds(rows.map { it.id })
        withContext(AppDispatchers.CpuBounded) {
            val byId = saved.associateBy { it.id }
            rows.map { byId[it.id]?.toMusicTrack() ?: it }
        }
    }
    fun invalidate() = synchronized(summaries) { summaries.evictAll(); revisions.update { it + 1 } }
    private fun key(period: ListeningPeriod, date: LocalDate) = period.bounds(date).let { "${period.name}|${it.first}|${it.second}" }
    companion object {
        @Volatile private var instance: ListeningStatsRepository? = null
        fun get(context: Context): ListeningStatsRepository = instance ?: synchronized(this) {
            instance ?: ListeningStatsRepository(context).also { instance = it }
        }
        suspend fun flushExisting() { instance?.flush() }
        fun invalidateExisting() { instance?.invalidate() }
        fun encodeDays(days: Map<String, ListeningDay>) = JSONObject().apply {
            days.forEach { (day, value) -> put(day, JSONArray().put(value.millis).put(value.plays)) }
        }.toString()
        fun decodeDays(text: String): Map<String, ListeningDay> = JSONObject(text).let { json ->
            json.keys().asSequence().associateWith { day -> json.getJSONArray(day).let { ListeningDay(it.getLong(0), it.getLong(1)) } }
        }
    }
}

data class ListeningSummary(val millis: Long, val plays: Long, val songs: List<ListeningTotal>, val artists: List<ListeningTotal>) {
    companion object {
        fun from(songs: List<ListeningTotal>, artists: List<ListeningTotal>): ListeningSummary {
            val artistIds = artists.filter { it.artistId.startsWith("UC") }
                .associate { CatalogNames.normalize(it.artist) to it.artistId }
            fun merge(rows: List<ListeningTotal>, artist: Boolean) = rows.groupBy {
                if (artist) ArtistPhotoRepository.cachedArtist(it.artist).let { a ->
                    it.artistId.takeIf { id -> id.startsWith("UC") } ?: a.id.ifBlank {
                        artistIds[CatalogNames.normalize(a.name)] ?: CatalogNames.normalize(a.name)
                    }
                } else it.identity
            }.values.map { group -> group.first().copy(listenedMs = group.sumOf { it.listenedMs }, plays = group.sumOf { it.plays },
                artistId = group.firstOrNull { it.artistId.startsWith("UC") }?.artistId.orEmpty()) }
            return ListeningSummary(songs.sumOf { it.listenedMs }, songs.sumOf { it.plays },
                merge(songs, false).filter { it.plays > 0 }.sortedWith(compareByDescending<ListeningTotal> { it.plays }
                    .thenByDescending { it.listenedMs }.thenBy { it.identity }).take(5),
                merge(artists, true).filter { it.listenedMs > 0 }.sortedWith(compareByDescending<ListeningTotal> { it.listenedMs }
                    .thenBy { it.identity }).take(5))
        }
    }
}
