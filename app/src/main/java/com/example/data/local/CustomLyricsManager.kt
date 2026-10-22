package com.example.data.local

import android.content.Context
import com.example.data.model.LyricLine
import com.example.data.model.MusicTrack
import com.example.data.model.SongIdentity
import com.example.data.model.TrackLyrics
import com.example.data.remote.LyricsDocuments
import java.io.File
import java.security.MessageDigest

object CustomLyricsManager {
    const val PROVIDER_NAME = "Import Lyrics"
    private const val DIRECTORY_NAME = "custom_lyrics"
    private const val PREFS_NAME = "custom_lyrics_preferences"
    private const val KEY_ACTIVE_PREFIX = "active_"

    private fun getStorageDir(context: Context): File {
        val dir = File(context.filesDir, DIRECTORY_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun hash(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun primaryKey(trackId: String, title: String, artist: String): String {
        val normalized = "${trackId.trim()}__${title.trim().lowercase()}__${artist.trim().lowercase()}"
        return "trk_" + hash(normalized)
    }

    fun titleArtistKey(title: String, artist: String): String {
        val normalized = "${title.trim().lowercase()}__${artist.trim().lowercase()}"
        return "meta_" + hash(normalized)
    }

    private fun getFileForTrack(context: Context, track: MusicTrack): File {
        val dir = getStorageDir(context)
        val primaryFile = File(dir, "${primaryKey(track.id, track.title, track.artist)}.lrc")
        if (primaryFile.exists() && primaryFile.length() > 0) {
            return primaryFile
        }
        val fallbackFile = File(dir, "${titleArtistKey(track.title, track.artist)}.lrc")
        if (fallbackFile.exists() && fallbackFile.length() > 0) {
            return fallbackFile
        }
        return primaryFile
    }

    fun hasCustomLyrics(context: Context, track: MusicTrack): Boolean {
        val file = getFileForTrack(context, track)
        return file.exists() && file.length() > 0
    }

    fun isCustomLyricsActive(context: Context, track: MusicTrack): Boolean {
        if (!hasCustomLyrics(context, track)) return false
        val key = primaryKey(track.id, track.title, track.artist)
        return getPrefs(context).getBoolean(KEY_ACTIVE_PREFIX + key, true)
    }

    fun setCustomLyricsActive(context: Context, track: MusicTrack, active: Boolean) {
        val key = primaryKey(track.id, track.title, track.artist)
        val metaKey = titleArtistKey(track.title, track.artist)
        getPrefs(context).edit()
            .putBoolean(KEY_ACTIVE_PREFIX + key, active)
            .putBoolean(KEY_ACTIVE_PREFIX + metaKey, active)
            .apply()
    }

    fun saveCustomLyrics(context: Context, track: MusicTrack, lrcText: String): Boolean {
        return try {
            val dir = getStorageDir(context)
            val pKey = primaryKey(track.id, track.title, track.artist)
            val mKey = titleArtistKey(track.title, track.artist)
            val primaryFile = File(dir, "$pKey.lrc")
            val fallbackFile = File(dir, "$mKey.lrc")

            primaryFile.writeText(lrcText, Charsets.UTF_8)
            fallbackFile.writeText(lrcText, Charsets.UTF_8)

            setCustomLyricsActive(context, track, true)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getCustomLyrics(context: Context, track: MusicTrack): TrackLyrics? {
        val file = getFileForTrack(context, track)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val text = file.readText(Charsets.UTF_8)
            val identity = SongIdentity.from(track)
            val parsed = LyricsDocuments.parse(identity, text, PROVIDER_NAME)
                ?: LyricsDocuments.timedText(identity, text, PROVIDER_NAME)

            if (parsed != null) {
                parsed.copy(provider = PROVIDER_NAME)
            } else {
                TrackLyrics(
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    isSynced = false,
                    lines = text.lines().filter { it.isNotBlank() }.map { LyricLine(0, it) },
                    provider = PROVIDER_NAME
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteCustomLyrics(context: Context, track: MusicTrack): Boolean {
        val dir = getStorageDir(context)
        val pKey = primaryKey(track.id, track.title, track.artist)
        val mKey = titleArtistKey(track.title, track.artist)
        setCustomLyricsActive(context, track, false)
        val f1 = File(dir, "$pKey.lrc")
        val f2 = File(dir, "$mKey.lrc")
        var ok = true
        if (f1.exists()) ok = f1.delete() && ok
        if (f2.exists()) ok = f2.delete() && ok
        return ok
    }
}
