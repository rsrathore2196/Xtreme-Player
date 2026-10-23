package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CustomLyricsManager
import com.example.data.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class ImportedLyricsTest {
    private val song = MusicTrack("imported-song", "Song", "Artist", "Album", 180_000, "", "")
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun aPendingImportRestoresItsOriginalRecordingInsteadOfFollowingPlayback() {
        val scope = object : androidx.compose.runtime.saveable.SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(com.example.ui.components.LyricsImportTargetSaver) { scope.save(song) }!!
        val restored = com.example.ui.components.LyricsImportTargetSaver.restore(saved)!!
        assertEquals(song.id, restored.id); assertEquals(song.title, restored.title); assertEquals(song.artist, restored.artist)
        assertEquals(CustomLyricsManager.primaryKey(song.id, song.title, song.artist),
            CustomLyricsManager.primaryKey(restored.id, restored.title, restored.artist))
        assertNull(com.example.ui.components.LyricsImportTargetSaver.restore(emptyList()))
    }

    @Test fun repeatedChorusTimestampsAreSortedAndRetainSynchronizationAfterImport() = runBlocking {
        val document = "[ar:Artist]\n[00:10.00][01:10.00]First chorus line\n[00:20.00][01:20.00]Second chorus line"
        val lyrics = withContext(Dispatchers.IO) {
            assertTrue(CustomLyricsManager.saveCustomLyrics(app, song, document))
            CustomLyricsManager.getCustomLyrics(app, song)
        }
        assertTrue(lyrics?.isSynced == true)
        assertEquals(listOf(10_000L, 20_000L, 70_000L, 80_000L), lyrics?.lines?.map { it.timestampMs })
        assertEquals(listOf("First chorus line", "Second chorus line", "First chorus line", "Second chorus line"), lyrics?.lines?.map { it.text })
        assertTrue(lyrics?.lines?.all { it.words.isEmpty() } == true)
    }

    @Test fun spacesBetweenRepeatedTimestampsDoNotBecomeVisibleLyricText() = runBlocking {
        val document = "\uFEFF[00:02.5] [00:12.250]A repeated line\n[00:05.05]Another line"
        val lyrics = withContext(Dispatchers.IO) {
            assertTrue(CustomLyricsManager.saveCustomLyrics(app, song, document))
            CustomLyricsManager.getCustomLyrics(app, song)
        }
        assertTrue(lyrics?.isSynced == true)
        assertEquals(listOf(2500L, 5050L, 12250L), lyrics?.lines?.map { it.timestampMs })
        assertEquals("A repeated line", lyrics?.lines?.first()?.text)
    }

    @Test fun utf16AndUtf8BomImportsPreserveEnhancedWordTiming() = runBlocking {
        val text = "[00:02.50]<00:02.50>दिल <00:03.00>मेरा<00:04.00>"
        val encodings = listOf(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8))
        for (bytes in encodings) {
            val decoded = CustomLyricsManager.readImport(bytes.inputStream())
            assertEquals(text, decoded)
            assertTrue(CustomLyricsManager.saveCustomLyrics(app, song, decoded))
            val lyrics = CustomLyricsManager.getCustomLyrics(app, song)!!
            assertTrue(lyrics.isSynced); assertTrue(lyrics.hasWordTiming)
            assertEquals(listOf(2500L, 3000L), lyrics.lines.single().words.map { it.timestampMs })
            assertEquals(listOf(500L, 1000L), lyrics.lines.single().words.map { it.durationMs })
        }
    }

    @Test fun manualImportsDoNotLoseTimingBecauseTheCatalogDurationIsIncomplete() = runBlocking {
        val shortCatalog = song.copy(durationMs = 1000)
        val text = "[offset:+500]\n[00:12.005]First\n[00:20:75]Second"
        assertTrue(CustomLyricsManager.saveCustomLyrics(app, shortCatalog, text))
        val lyrics = CustomLyricsManager.getCustomLyrics(app, shortCatalog)!!
        assertTrue(lyrics.isSynced)
        assertEquals(listOf(12505L, 21250L), lyrics.lines.map { it.timestampMs })
        assertNull(com.example.data.remote.LyricsDocuments.timedText(com.example.data.model.SongIdentity.from(shortCatalog), text, "Remote"))
    }

    @Test fun plainLyricsStayPlainAndBadTimedFilesDoNotOverwriteAValidImport() = runBlocking {
        assertTrue(CustomLyricsManager.saveCustomLyrics(app, song, "First plain line\nSecond plain line"))
        assertFalse(CustomLyricsManager.getCustomLyrics(app, song)!!.isSynced)
        assertFalse(CustomLyricsManager.saveCustomLyrics(app, song, "[00:10.1234]Unsupported timestamp"))
        assertEquals("First plain line", CustomLyricsManager.getCustomLyrics(app, song)!!.lines.first().text)
        try {
            CustomLyricsManager.readImport(ByteArray(2_000_001).inputStream())
            fail("Oversized documents must not be read without a bound")
        } catch (_: java.io.IOException) { }
    }
}
