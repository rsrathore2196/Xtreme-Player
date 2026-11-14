package com.example

import android.app.Application
import com.example.data.cache.RecommendationTrackCodec
import com.example.data.importer.ImportedTrackMeta
import com.example.data.importer.PlaylistImportEngine
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.data.model.toMediaItem
import com.example.data.repository.RecordingIdentity
import com.example.data.repository.StreamResolver
import com.example.data.repository.TrackUnavailableException
import com.example.playback.AudioQuality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingVersionPlaybackTest {
    private val original = MusicTrack("import_song", "A Beautiful Song", "Singer", "Album", 180_000, "", "")
    private val modifications = listOf("Slowed + Reverb", "Reverbed", "Reverberated", "Mixed", "Club Mix",
        "Remixed", "Sped Up", "Speed-Up", "Lofi", "Bass Boosted", "8D Audio", "Mashup", "Chopped and Screwed")

    @Test fun modificationsCannotMatchTheOriginalInEitherDirectionEvenWithTheSameIsrc() {
        for (label in modifications) {
            val plain = original.copy(isrc = "USABC2000001")
            val modified = plain.copy(title = "${plain.title} ($label)")
            assertFalse(label, RecordingIdentity.same(plain, modified))
            assertNull(label, RecordingIdentity.providerConfidence(plain, modified))
            assertNull(label, RecordingIdentity.providerConfidence(modified, plain))
            assertNull(label, RecordingIdentity.losslessConfidence(plain, modified))
        }
        assertNull(RecordingIdentity.providerConfidence(original.copy(title = "A Beautiful Song (DJ Alpha Mix)"),
            original.copy(title = "A Beautiful Song (DJ Alphi Mix)")))
    }

    @Test fun fullRequestedTitlesReachYouTubeAndOnlyTheRequestedVersionIsExtracted() = runBlocking {
        for (label in modifications) {
            val selected = original.copy(title = "${original.title} ($label)")
            val wrong = original.copy(id = "yt_abcdefghij0", source = "YouTube Music")
            val correct = selected.copy(id = "yt_abcdefghij1", source = "YouTube Music")
            val queries = mutableListOf<String>()
            val resolver = StreamResolver(searchStandard = { _, _ -> listOf(wrong.copy(audioUrl = "https://aac.saavncdn.com/wrong.m4a")) },
                searchLossless = { emptyList() }, fetchById = { _, _ -> null }, searchYouTube = {
                    queries.add(it); listOf(wrong, correct)
                }, resolveYouTube = { candidate, _, _, _ ->
                    assertEquals(label, correct.id, candidate.id)
                    candidate.copy(audioUrl = "xtreme-youtube://audio/abcdefghij1?itag=251", bitrateKbps = 128)
                })
            val result = resolver.resolve(selected, AudioQuality.ULTRA_HD_320)
            assertEquals(selected.title, result.title)
            assertEquals(selected.id, result.id)
            assertTrue(queries.isNotEmpty())
            assertTrue(queries.all { it.contains(selected.title) })
            assertTrue(result.audioUrl.contains("abcdefghij1"))
        }
    }

    @Test fun anOriginalRequestFailsRatherThanPlayingAnAvailableModifiedVersion() = runBlocking {
        val resolver = StreamResolver(searchStandard = { _, _ -> emptyList() }, searchLossless = { emptyList() },
            fetchById = { _, _ -> null }, searchYouTube = {
                modifications.map { label -> original.copy(id = "yt_abcdefghij1", title = "${original.title} ($label)") }
            }, resolveYouTube = { _, _, _, _ -> fail("A conflicting recording must never be extracted"); null })
        try { resolver.resolve(original, AudioQuality.ULTRA_HD_320); fail("Expected unavailable recording") }
        catch (_: TrackUnavailableException) { }
    }

    @Test fun legacyShortDisplayTitleCannotChangeTheExactSelectedYoutubeRecording() = runBlocking {
        val full = "A Beautiful Song (Slowed + Reverb) [Official Audio]"
        val selected = original.copy(id = "yt_abcdefghij2", originalProviderTitle = full)
        val resolver = StreamResolver(searchStandard = { _, _ -> emptyList() }, searchLossless = { emptyList() },
            fetchById = { _, _ -> null }, searchYouTube = { fail("The exact selected video is available"); emptyList() },
            resolveYouTube = { candidate, _, _, _ ->
                assertEquals(full, candidate.title)
                assertEquals(selected.id, candidate.id)
                candidate.copy(audioUrl = "xtreme-youtube://audio/abcdefghij2?itag=251", bitrateKbps = 128)
            })
        assertEquals(full, resolver.resolve(selected, AudioQuality.ULTRA_HD_320).title)
        assertEquals(full, selected.toMediaItem().mediaMetadata.title)
        assertEquals(full, TrackEntity.fromMusicTrack(selected).toMusicTrack().title)
        assertEquals(full, RecommendationTrackCodec.decode(RecommendationTrackCodec.encode(listOf(selected))).single().title)
        assertEquals(original.title, selected.cleanTitle)
    }

    @Test fun playlistMatchingRetainsModifiedTitleAndRejectsTheOriginal() = runBlocking {
        val title = "A Beautiful Song (Reverbed Mix)"
        val meta = ImportedTrackMeta(title, "Singer", "Album", 180_000)
        val queries = mutableListOf<String>()
        val expected = original.copy(id = "yt_abcdefghij3", title = title, source = "YouTube Music")
        val result = PlaylistImportEngine.matchTrack(meta, youtubeOnly = true, forceRefresh = true,
            findYouTube = { query, _ -> queries.add(query); listOf(original.copy(id = "yt_abcdefghij0"), expected) },
            findJio = { error("Explicit YouTube matching must stay on YouTube") })
        assertEquals(expected.id, result.matchedTrack?.id)
        assertEquals(title, result.matchedTrack?.title)
        assertTrue(queries.all { it.contains(title) })
        val split = PlaylistImportEngine::class.java.getDeclaredMethod("splitTitleAndArtist", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(PlaylistImportEngine, "Singer - A Beautiful Song - Slowed + Reverb", "Singer")
        assertEquals("A Beautiful Song - Slowed + Reverb" to "Singer", split)

        val editTitle = "A Beautiful Song feat. Guest - DJ Alpha Mix"
        val edit = expected.copy(id = "yt_abcdefghij4", title = editTitle)
        val editedResult = PlaylistImportEngine.matchTrack(meta.copy(originalTitle = editTitle),
            youtubeOnly = true, forceRefresh = true,
            findYouTube = { _, _ -> listOf(edit.copy(id = "yt_abcdefghij5", title = editTitle.replace("Alpha", "Alphi")), edit) },
            findJio = { error("No provider change") })
        assertEquals(edit.id, editedResult.matchedTrack?.id)
    }
}
