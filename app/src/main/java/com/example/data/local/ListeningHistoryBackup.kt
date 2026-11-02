package com.example.data.local

import com.example.data.repository.ListeningStatsRepository
import org.json.JSONArray
import org.json.JSONObject

/** Committed history and cumulative watermarks, never live journal work to replay. */
internal data class ListeningHistoryBackup(val origin: String, val events: List<ListeningEventEntity>,
    val checkpoints: List<ListeningCheckpoint>) {
    fun encode() = JSONObject().put("origin", origin).put("pendingAggregated", true)
        .put("events", JSONArray(events.map { e ->
            JSONObject().put("id", e.id).put("trackId", e.trackId).put("artistId", e.artistId)
                .put("artistName", e.artistName).put("album", e.album).put("genre", e.genre).put("language", e.language)
                .put("timestamp", e.timestamp).put("sessionId", e.sessionId).put("playedDurationMs", e.playedDurationMs)
                .put("trackDurationMs", e.trackDurationMs).put("completionRatio", e.completionRatio)
                .put("completed", e.completed).put("skipped", e.skipped).put("liked", e.liked)
                .put("source", e.source).put("eventType", e.eventType)
        })).put("checkpoints", JSONArray(checkpoints.map { c ->
            JSONObject().put("occurrence", c.occurrence).put("days", JSONObject(c.days)).put("eventId", c.eventId)
        }))

    /** Caller owns the Room transaction. Raw events do not add aggregate counters. */
    suspend fun merge(database: MusicDatabase, trackIds: Map<String, String>): Int {
        val dao = database.listeningEventDao()
        val existing = dao.allForBackup()
        fun key(e: ListeningEventEntity): String = if (e.eventType == "ACTUAL_PLAY") "actual|${e.sessionId}"
            else "${e.sessionId}|${e.eventType}|${e.timestamp}"
        val byIdentity = existing.filter { it.sessionId.isNotBlank() }.associateBy(::key).toMutableMap()
        val legacy = existing.filter { it.sessionId.isBlank() }.associateBy { Triple(it.trackId, it.eventType, it.timestamp) }
        val ids = mutableMapOf<Long, Long>()
        var inserted = 0
        for (raw in events) {
            val event = raw.copy(id = 0, trackId = trackIds[raw.trackId] ?: raw.trackId,
                sessionId = raw.sessionId.ifBlank { "backup:$origin:${raw.id}" })
            val old = byIdentity[key(event)] ?: if (raw.sessionId.isBlank()) legacy[Triple(event.trackId, event.eventType, event.timestamp)] else null
            require(old == null || old.trackId == event.trackId) { "Conflicting listening occurrence" }
            val merged = if (old == null) event else {
                val metadata = if (event.playedDurationMs >= old.playedDurationMs) event else old
                metadata.copy(id = old.id, sessionId = event.sessionId, timestamp = maxOf(old.timestamp, event.timestamp),
                    playedDurationMs = maxOf(old.playedDurationMs, event.playedDurationMs),
                    completionRatio = maxOf(old.completionRatio, event.completionRatio), completed = old.completed || event.completed,
                    liked = old.liked || event.liked)
            }
            val id = if (old != null && merged == old) old.id else dao.insertListeningEvent(merged)
            if (old == null) inserted++
            ids[raw.id] = id
            byIdentity[key(merged)] = merged.copy(id = id)
        }
        for (checkpoint in checkpoints) {
            val old = database.listeningStatsDao().checkpoint(checkpoint.occurrence)
            val before = old?.let { ListeningStatsRepository.decodeDays(it.days) }.orEmpty()
            val incoming = ListeningStatsRepository.decodeDays(checkpoint.days)
            val days = (before.keys + incoming.keys).associateWith { day ->
                val a = before[day] ?: ListeningDay(); val b = incoming[day] ?: ListeningDay()
                ListeningDay(maxOf(a.millis, b.millis), maxOf(a.plays, b.plays))
            }
            database.listeningStatsDao().checkpoint(checkpoint.copy(days = ListeningStatsRepository.encodeDays(days),
                eventId = ids.getValue(checkpoint.eventId)))
        }
        return inserted
    }

    companion object {
        fun decode(root: JSONObject): ListeningHistoryBackup {
            fun text(row: JSONObject, key: String) = row.getString(key).also { require(it.length <= 8192) }
            fun number(row: JSONObject, key: String) = row.getLong(key).also { require(it >= 0) }
            val origin = text(root, "origin").also { require(it.isNotBlank()) }
            require(root.getBoolean("pendingAggregated")) { "Uncommitted listening history cannot be restored" }
            val rows = root.getJSONArray("events"); require(rows.length() <= 100_000)
            val events = (0 until rows.length()).map { index ->
                val e = rows.getJSONObject(index)
                val ratio = e.getDouble("completionRatio").also { require(it.isFinite() && it in 0.0..1.0) }
                ListeningEventEntity(number(e, "id").also { require(it > 0) }, text(e, "trackId").also { require(it.isNotBlank()) },
                    text(e, "artistId"), text(e, "artistName"), text(e, "album"), text(e, "genre"), text(e, "language"),
                    number(e, "timestamp"), text(e, "sessionId"), number(e, "playedDurationMs"), number(e, "trackDurationMs"),
                    ratio.toFloat(), e.getBoolean("completed"), e.getBoolean("skipped"), e.getBoolean("liked"), text(e, "source"), text(e, "eventType"))
            }
            require(events.map { it.id }.distinct().size == events.size) { "Duplicate listening event IDs" }
            val byId = events.associateBy { it.id }
            val watermarks = root.getJSONArray("checkpoints"); require(watermarks.length() <= 100_000)
            val checkpoints = (0 until watermarks.length()).map { index ->
                val c = watermarks.getJSONObject(index)
                val occurrence = text(c, "occurrence").also { require(it.isNotBlank()) }
                val id = number(c, "eventId")
                require(byId[id]?.let { it.eventType == "ACTUAL_PLAY" && it.sessionId == occurrence } == true) { "Invalid listening checkpoint relationship" }
                val days = c.getJSONObject("days")
                require(days.length() <= 3660)
                ListeningStatsRepository.decodeDays(days.toString()).forEach { (day, value) ->
                    java.time.LocalDate.parse(day)
                    require(value.millis in 0..1_000_000_000_000L && value.plays in 0..1_000_000_000_000L)
                }
                ListeningCheckpoint(occurrence, days.toString(), id)
            }
            require(checkpoints.map { it.occurrence }.distinct().size == checkpoints.size)
            return ListeningHistoryBackup(origin, events, checkpoints)
        }
    }
}
