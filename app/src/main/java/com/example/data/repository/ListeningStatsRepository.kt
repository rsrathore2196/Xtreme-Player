package com.example.data.repository

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.room.withTransaction
import com.example.data.local.*
import com.example.performance.AppDispatchers
import com.example.recommendation.HomeRecommendationPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.util.UUID

/** One application-owned serialized writer; no service-owned job can cancel an enqueued flush. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningStatsRepository internal constructor(context: Context, suppliedDatabase: MusicDatabase? = null,
    pendingFile: java.io.File? = null, private val dateProvider: () -> LocalDate = { LocalDate.now() }) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private val database by lazy { suppliedDatabase ?: MusicDatabase.getDatabase(app) }
    private data class Pending(val snapshot: ListeningSnapshot, val revision: Long)
    private data class Flush(val target: Long, val completion: CompletableDeferred<Unit>)
    private val journalWakeup = Channel<Unit>(Channel.CONFLATED)
    private val aggregateWakeup = Channel<Unit>(Channel.CONFLATED)
    private val inputLock = Any()
    private val latest = linkedMapOf<String, Pending>()
    private val pendingSince = linkedMapOf<String, Long>()
    private var inputRevision = 0L
    private val journalBarriers = mutableListOf<CompletableDeferred<Unit>>()
    private val barriers = mutableListOf<Flush>()
    private val journalLock = Mutex()
    private val durable = linkedMapOf<String, Pending>()
    private var journalLoaded = false
    private val journal by lazy {
        ListeningCheckpointJournal(pendingFile ?: java.io.File(app.noBackupFilesDir, "listening_pending_v1.json"))
    }
    private val revisions = MutableStateFlow(0L)
    private val calendarDates = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { trySend(dateProvider()) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter) // Protected system date/time broadcasts only; no app-defined permission.
        trySend(dateProvider())
        awaitClose { app.unregisterReceiver(receiver) }
    }.distinctUntilChanged()
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
        // Journaling must never wait for metadata queries or a Room transaction.
        // Both consumers reuse the existing IO pool; neither owns a timer or thread.
        scope.launch {
            for (signal in journalWakeup) {
                var completions = emptyList<CompletableDeferred<Unit>>()
                try {
                    journalLock.withLock {
                        if (!journalLoaded) {
                            val restored = journal.read()
                            synchronized(inputLock) {
                                restored.forEach { snapshot ->
                                    val current = latest[snapshot.occurrence]
                                    latest[snapshot.occurrence] = Pending(merge(snapshot, current?.snapshot ?: snapshot), current?.revision ?: 0L)
                                    pendingSince[snapshot.occurrence] = 0L
                                }
                            }
                            journalLoaded = true
                        }
                        val updates = synchronized(inputLock) {
                            completions = journalBarriers.toList(); journalBarriers.clear()
                            latest.values.toList()
                        }
                        val next = LinkedHashMap(durable)
                        updates.forEach { update -> next[update.snapshot.occurrence] = Pending(
                            merge(next[update.snapshot.occurrence]?.snapshot, update.snapshot), update.revision) }
                        if (next != durable) journal.write(next.values.map { it.snapshot })
                        durable.clear(); durable.putAll(next)
                    }
                    completions.forEach { it.complete(Unit) }
                    aggregateWakeup.trySend(Unit)
                } catch (cancelled: CancellationException) {
                    completions.forEach { it.completeExceptionally(cancelled) }; throw cancelled
                } catch (error: Exception) {
                    // Includes barriers queued before a failed initial journal read.
                    synchronized(inputLock) { completions = completions + journalBarriers; journalBarriers.clear() }
                    completions.forEach { it.completeExceptionally(java.io.IOException("Listening journal is pending", error)) }
                    android.util.Log.w("ListeningStats", "Journal checkpoint pending; will retry on the next checkpoint", error)
                }
            }
        }
        scope.launch {
            for (signal in aggregateWakeup) {
                val committed = mutableListOf<Pending>()
                var failure: Exception? = null
                try {
                    val batch = journalLock.withLock { durable.values.toList() }
                    for (entry in batch) {
                        try {
                            val changed = persist(entry.snapshot)
                            committed += entry
                            if (changed.totals) invalidateAffected(entry.snapshot, changed.identity)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { failure = error; break }
                    }
                    if (committed.isNotEmpty()) {
                        val protected = journalLock.withLock {
                            val next = LinkedHashMap(durable)
                            committed.forEach { entry ->
                                if (next[entry.snapshot.occurrence]?.revision == entry.revision) next.remove(entry.snapshot.occurrence)
                            }
                            // Commit acknowledgement before retiring a replay watermark.
                            journal.write(next.values.map { it.snapshot })
                            durable.clear(); durable.putAll(next)
                            synchronized(inputLock) {
                                committed.forEach { entry ->
                                    val id = entry.snapshot.occurrence
                                    if (latest[id]?.revision == entry.revision) {
                                        latest.remove(id); pendingSince.remove(id)
                                    } else pendingSince[id]?.let { pendingSince[id] = maxOf(it, entry.revision + 1) }
                                }
                                (durable.keys + pendingSince.keys).toList().ifEmpty { listOf("") }
                            }
                        }
                        // A large failed backlog must not exceed SQLite's bind limit.
                        // Defer optional pruning rather than losing replay protection.
                        if (protected.size <= 512) database.listeningStatsDao().pruneCheckpoints(protected)
                    }
                } catch (cancelled: CancellationException) {
                    completeBarriers(cancelled); throw cancelled
                } catch (error: Exception) { failure = error }
                completeBarriers(failure)
                if (failure != null) android.util.Log.w("ListeningStats", "Durable listening checkpoint awaiting aggregation", failure)
            }
        }
        journalWakeup.trySend(Unit) // Replay only when this repository is first used.
    }
    private fun completeBarriers(failure: Throwable? = null) = synchronized(inputLock) {
        val iterator = barriers.iterator()
        while (iterator.hasNext()) {
            val barrier = iterator.next()
            if (pendingSince.values.none { it <= barrier.target }) {
                barrier.completion.complete(Unit); iterator.remove()
            } else if (failure != null) {
                barrier.completion.completeExceptionally(java.io.IOException("Listening checkpoint is pending", failure)); iterator.remove()
            }
        }
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
        synchronized(inputLock) {
            val revision = ++inputRevision
            latest[snapshot.occurrence] = Pending(merge(latest[snapshot.occurrence]?.snapshot, snapshot), revision)
            pendingSince.putIfAbsent(snapshot.occurrence, revision)
        }
        journalWakeup.trySend(Unit)
    }
    /** Acknowledges durable intent independently of potentially slow Room aggregation. */
    suspend fun flushJournal() {
        val completion = CompletableDeferred<Unit>()
        synchronized(inputLock) { journalBarriers += completion }
        journalWakeup.send(Unit)
        completion.await()
    }
    suspend fun flush() {
        val target = synchronized(inputLock) { inputRevision }
        flushJournal()
        val completion = CompletableDeferred<Unit>()
        synchronized(inputLock) { barriers += Flush(target, completion) }
        aggregateWakeup.send(Unit)
        completion.await()
    }
    internal fun close() {
        val cancelled = CancellationException("Listening repository closed")
        scope.cancel()
        synchronized(inputLock) { journalBarriers.forEach { it.completeExceptionally(cancelled) }; journalBarriers.clear() }
        completeBarriers(cancelled)
    }
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
            // The journal acknowledges committed entries before pruning their
            // watermarks. An existing history occurrence without a watermark is
            // retired, never a new play, even if an old cumulative snapshot reappears.
            if (checkpoint == null && database.listeningEventDao().getActualPlayForOccurrence(snapshot.occurrence) != null)
                return@withTransaction
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
    fun peek(period: ListeningPeriod, today: LocalDate = dateProvider()): ListeningSummary? = summaries[key(period, today)]
    fun observe(period: ListeningPeriod): Flow<ListeningSummary> = combine(revisions, calendarDates) { _, day -> day }.mapLatest { day ->
        summary(period, day)
    }.distinctUntilChanged().flowOn(AppDispatchers.Io)
    suspend fun summary(period: ListeningPeriod, today: LocalDate = dateProvider()): ListeningSummary {
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
        suspend fun flushJournalExisting() { instance?.flushJournal() }
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
