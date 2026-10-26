package com.example.data.repository

import android.util.AtomicFile
import com.example.data.local.ListeningSnapshot
import com.example.data.model.MusicTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Used only by the repository's IO writer. Stream URLs and credentials never enter the journal. */
internal class ListeningCheckpointJournal(file: File) {
    private val storage = AtomicFile(file)

    fun read(): List<ListeningSnapshot> {
        val bytes = try { storage.readFully() } catch (_: java.io.FileNotFoundException) { return emptyList() }
        val entries = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("pending")
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val t = entry.getJSONObject("track")
            val artists = t.getJSONArray("primaryArtists")
            val track = MusicTrack(t.getString("id"), t.getString("title"), t.getString("artist"),
                t.getString("album"), t.getLong("durationMs"), t.getString("coverUrl"), "",
                genre = t.getString("genre"), isLiked = t.getBoolean("isLiked"), singers = t.getString("singers"),
                writer = t.getString("writer"), language = t.getString("language"), year = t.getString("year"),
                source = t.getString("source"), isrc = t.getString("isrc"),
                primaryArtists = (0 until artists.length()).map(artists::getString))
            val days = ListeningStatsRepository.decodeDays(entry.getJSONObject("days").toString())
            ListeningSnapshot(entry.getString("occurrence"), track, days, days.values.sumOf { it.millis },
                entry.getBoolean("finished"), entry.getBoolean("playing"))
        }
    }

    fun write(snapshots: Collection<ListeningSnapshot>) {
        val root = JSONObject().put("version", 1).put("pending", JSONArray(snapshots.map { s ->
            val t = s.track
            JSONObject().put("occurrence", s.occurrence).put("finished", s.finished).put("playing", s.playing)
                .put("days", JSONObject(ListeningStatsRepository.encodeDays(s.days)))
                .put("track", JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist)
                    .put("album", t.album).put("durationMs", t.durationMs).put("coverUrl", t.coverUrl)
                    .put("genre", t.genre).put("isLiked", t.isLiked).put("singers", t.singers).put("writer", t.writer)
                    .put("language", t.language).put("year", t.year).put("source", t.source).put("isrc", t.isrc)
                    .put("primaryArtists", JSONArray(t.primaryArtists)))
        }))
        val output = storage.startWrite()
        try {
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            storage.finishWrite(output)
        } catch (error: Exception) { storage.failWrite(output); throw error }
    }
}
