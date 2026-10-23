package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PlaybackSourcesPreferences
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.playback.*
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
class YouTubeFallbackAvailabilityTest {
    @Test fun youtubeRemainsPlayableAtEveryQualityWhenAllSavedSwitchesAreOff() = runBlocking {
        val track = MusicTrack("yt_abcdefghijk", "Selected Song", "Artist", "Album", 180_000, "cover", "")
        for (quality in AudioQualityCeiling.entries) {
            val policy = PlaybackSourcePolicy(quality, sources = PlaybackSource.builtIns.map { it.copy(enabled = false) })
            assertEquals(listOf(PlaybackSourceKind.YOUTUBE), policy.playbackSources.map { it.kind })
            var attempts = 0
            val resolver = StreamResolver(searchStandard = { _, _ -> error("JioSaavn is off") },
                searchLossless = { error("Archive is off") }, resolveYouTube = { selected, _, _, cap ->
                    attempts++
                    selected.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=$cap&itag=139",
                        source = "YouTube Music", bitrateKbps = 48)
                })
            val result = resolver.resolve(track, quality.audioQuality, sourcePolicy = policy)
            assertEquals(1, attempts)
            assertEquals(track.id, result.id)
            assertEquals("YouTube Music", result.source)
            assertFalse(result.isLossless)
        }
    }

    @Test fun missingAndRenamedYoutubeEntriesNormalizeToOnePermanentCanonicalSource() {
        for (saved in listOf(emptyList(), listOf(PlaybackSource.youtube.copy(id = "old_youtube", enabled = false)))) {
            val normalized = PlaybackSource.ordered(saved)
            assertEquals(PlaybackSource.youtube, normalized.single { it.kind == PlaybackSourceKind.YOUTUBE })
            assertFalse(normalized.single { it.kind == PlaybackSourceKind.JIOSAAVN }.enabled)
            assertFalse(normalized.single { it.kind == PlaybackSourceKind.INTERNET_ARCHIVE }.enabled)
        }
    }

    @Test fun loadingAnOldDisabledYoutubePreferenceRepairsAndPersistsOnlyThatSource() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        withContext(Dispatchers.IO) {
            app.getSharedPreferences("xtreme_playback_sources_prefs", 0).edit().putString("sources_v2",
                """[{"id":"youtube","kind":"YOUTUBE","enabled":false},{"id":"jiosaavn","kind":"JIOSAAVN","enabled":false},{"id":"internet_archive","kind":"INTERNET_ARCHIVE","enabled":false}]""").commit()
        }
        val restored = PlaybackSourcesPreferences.load(app)
        assertTrue(restored.single { it.kind == PlaybackSourceKind.YOUTUBE }.enabled)
        assertFalse(restored.single { it.kind == PlaybackSourceKind.JIOSAAVN }.enabled)
        assertFalse(restored.single { it.kind == PlaybackSourceKind.INTERNET_ARCHIVE }.enabled)
        val stored = withContext(Dispatchers.IO) { app.getSharedPreferences("xtreme_playback_sources_prefs", 0).getString("sources_v2", "") }
        val rows = org.json.JSONArray(stored)
        val youtube = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("kind") == "YOUTUBE" }
        assertTrue(youtube.getBoolean("enabled"))
    }
}
