package com.example.data.local

import android.content.Context
import com.example.data.model.MusicTrack
import com.example.data.model.SongIdentity
import com.example.data.model.TrackLyrics
import com.example.data.remote.LyricsDocuments
import com.example.performance.AppDispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
        ReactiveSettingsRepository.preferences(context, PREFS_NAME)

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

    suspend fun saveCustomLyrics(context: Context, track: MusicTrack, lrcText: String): Boolean = withContext(AppDispatchers.Io) {
        try {
            if (lrcText.length > 2_000_000 || withContext(AppDispatchers.CpuBounded) { parseImported(track, lrcText) }?.lines.isNullOrEmpty())
                return@withContext false
            val dir = getStorageDir(context)
            val pKey = primaryKey(track.id, track.title, track.artist)
            val mKey = titleArtistKey(track.title, track.artist)
            val primaryFile = File(dir, "$pKey.lrc")
            val fallbackFile = File(dir, "$mKey.lrc")

            val bytes = lrcText.toByteArray(Charsets.UTF_8)
            listOf(primaryFile, fallbackFile).forEach { destination ->
                val file = android.util.AtomicFile(destination)
                val stream = file.startWrite()
                try { stream.write(bytes); file.finishWrite(stream) }
                catch (error: Exception) { file.failWrite(stream); throw error }
            }

            setCustomLyricsActive(context, track, true)
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            false
        }
    }

    suspend fun getCustomLyrics(context: Context, track: MusicTrack): TrackLyrics? = withContext(AppDispatchers.Io) {
        val file = getFileForTrack(context, track)
        if (!file.exists() || file.length() == 0L) return@withContext null
        try {
            val text = file.inputStream().use { readImport(it) }
            withContext(AppDispatchers.CpuBounded) { parseImported(track, text) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    /** A user-selected document is authoritative; catalog durations can still be incomplete. */
    internal fun parseImported(track: MusicTrack, text: String): TrackLyrics? {
        val identity = SongIdentity.from(track).copy(durationMs = null)
        val parsed = LyricsDocuments.parse(identity, text, PROVIDER_NAME)
            ?: LyricsDocuments.timedText(identity, text, PROVIDER_NAME)
        // Never quietly flatten a malformed timed document into visible timestamps.
        if (Regex("\\[\\d{1,3}:\\d").containsMatchIn(text) && parsed?.isSynced != true) return null
        return parsed?.copy(provider = PROVIDER_NAME)
    }

    /** SAF files commonly use UTF-16. Decode before parsing and bound imported allocations. */
    suspend fun readImport(input: InputStream): String = withContext(AppDispatchers.Io) {
        val bytes = input.readBytesBounded()
        withContext(AppDispatchers.CpuBounded) {
            val (charset, offset) = when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
                else -> Charsets.UTF_8 to 0
            }
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        }
    }

    private suspend fun InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > 2_000_000) throw IOException("Lyrics files must be smaller than 2 MB")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
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
