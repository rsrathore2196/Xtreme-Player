package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.PlaybackSourcesPreferences
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.data.repository.TrackUnavailableException
import com.example.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PlaybackSourcePreferenceTest {
    private val video = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180000, "cover", "", bitrateKbps = 0)
    private val jio = video.copy(id = "online_match", audioUrl = "https://aac.saavncdn.com/song_320.mp4", bitrateKbps = 320)
    private fun youtube(track: MusicTrack, cap: Int) = track.copy(
        audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=$cap", bitrateKbps = minOf(80, cap / 1000), qualityBadge = "Opus")
    private fun custom(url: String) = PlaybackSource(PlaybackSource.addonId(url), PlaybackSourceKind.ADDON, "Server", baseUrl = url)

    @Test fun unconfiguredInstallUsesYoutubeUntilAnOptionalSourceIsExplicitlyEnabled() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        withContext(Dispatchers.IO) {
            app.getSharedPreferences("xtreme_other_settings", 0).edit().clear().commit()
            app.getSharedPreferences("xtreme_playback_sources_prefs", 0).edit().clear().commit()
        }
        assertTrue(OtherSettingsPreferences.loadPlaybackSources(app).isEmpty())
        val fresh = OtherSettingsPreferences.playbackIntegrations(app).sourceList(AudioQualityCeiling.MAX)
        assertEquals(listOf(PlaybackSource.youtube), PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = fresh).playbackSources)
        PlaybackSourcesPreferences.setSourceEnabled(app, PlaybackSource.jio.id, true)
        withTimeout(3000) { while (PlaybackSourcesPreferences.load(app).none { it.kind == PlaybackSourceKind.JIOSAAVN && it.enabled }) delay(10) }
        val saved = PlaybackSourcesPreferences.load(app)
        assertTrue(saved.single { it.kind == PlaybackSourceKind.JIOSAAVN }.enabled)
        assertTrue(saved.single { it.kind == PlaybackSourceKind.YOUTUBE }.enabled)
        assertFalse(saved.single { it.kind == PlaybackSourceKind.INTERNET_ARCHIVE }.enabled)
        assertEquals(listOf(PlaybackSourceKind.YOUTUBE, PlaybackSourceKind.JIOSAAVN),
            PlaybackSourcePolicy(AudioQualityCeiling.HIGH, sources = saved).playbackSources.map { it.kind })
    }

    @Test fun qualityFilteringPreservesSavedOrderAndSwitches() {
        val ordered = listOf(custom("https://music.example"), PlaybackSource.archive, PlaybackSource.jio, PlaybackSource.youtube)
        for (quality in listOf(AudioQualityCeiling.DATA_SAVER, AudioQualityCeiling.NORMAL)) {
            val policy = PlaybackSourcePolicy(quality, sources = ordered)
            assertEquals(listOf(PlaybackSourceKind.YOUTUBE), policy.playbackSources.map { it.kind })
            assertEquals(ordered, policy.configuredSources)
            assertFalse(policy.allowsArchive)
        }
        assertEquals(listOf(PlaybackSourceKind.YOUTUBE, PlaybackSourceKind.JIOSAAVN),
            PlaybackSourcePolicy(AudioQualityCeiling.HIGH, sources = ordered).playbackSources.map { it.kind })
        assertEquals(ordered, PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = ordered).playbackSources)
    }

    @Test fun disabledOptionalProvidersNeverBecomeHiddenFallbacks() = runBlocking {
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = PlaybackSource.builtIns.map { it.copy(enabled = false) })
        assertEquals(listOf(PlaybackSource.youtube), policy.playbackSources)
        val resolver = StreamResolver(searchStandard = { _, _ -> error("Jio is off") },
            searchLossless = { error("Archive is off") }, resolveYouTube = { track, _, _, cap -> youtube(track, cap) })
        assertEquals("YouTube Music", resolver.resolve(video, AudioQuality.HI_RES_LOSSLESS, sourcePolicy = policy).source)
        assertEquals(listOf(PlaybackSource.youtube), PlaybackSourcePolicy(AudioQualityCeiling.HIGH, sources = emptyList()).playbackSources)
    }

    @Test fun builtInRanksAreFixedAndDisablingJioUsesYoutube() = runBlocking {
        var searches = 0
        val resolver = StreamResolver(searchStandard = { _, _ -> searches++; listOf(jio) },
            searchLossless = { error("High excludes Archive") }, resolveYouTube = { track, _, _, cap -> youtube(track, cap) })
        val preferred = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, sources = listOf(PlaybackSource.jio, PlaybackSource.youtube, PlaybackSource.archive))
        assertEquals("YouTube Music", resolver.resolve(video, preferred.ceiling.audioQuality, sourcePolicy = preferred).source)
        val changed = preferred.copy(sources = listOf(PlaybackSource.youtube, PlaybackSource.jio, PlaybackSource.archive))
        assertEquals(preferred.key, changed.key)
        assertEquals("YouTube Music", resolver.resolve(video, changed.ceiling.audioQuality, sourcePolicy = changed).source)
        val disabled = changed.copy(sources = changed.sources.map { if (it.kind == PlaybackSourceKind.JIOSAAVN) it.copy(enabled = false) else it })
        assertEquals("YouTube Music", resolver.resolve(video, disabled.ceiling.audioQuality, sourcePolicy = disabled).source)
        assertEquals(0, searches)
    }

    @Test fun lowerQualitiesNeverContactJioArchiveOrCustomServersEvenWhenEnabled() = runBlocking {
        for (ceiling in listOf(AudioQualityCeiling.DATA_SAVER, AudioQualityCeiling.NORMAL)) {
            val resolver = StreamResolver(searchStandard = { _, _ -> error("Quality blocks Jio") },
                searchLossless = { error("Quality blocks Archive") },
                resolveYouTube = { _, _, _, _ -> null },
                resolveAddon = { _, _, _, _ -> error("Quality blocks custom sources") })
            val policy = PlaybackSourcePolicy(ceiling, sources = listOf(custom("https://custom.example"),
                PlaybackSource.archive, PlaybackSource.jio, PlaybackSource.youtube))
            try { resolver.resolve(video, ceiling.audioQuality, sourcePolicy = policy); fail("Report unavailable YouTube safely") }
            catch (_: TrackUnavailableException) { }
        }
    }

    @Test fun multipleCustomSourcesAreTriedInOrderBeforeTheSafetyFallback() = runBlocking {
        val first = custom("https://first.example"); val second = custom("https://second.example")
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = listOf(first, second,
            PlaybackSource.jio.copy(enabled = false), PlaybackSource.archive.copy(enabled = false), PlaybackSource.youtube))
        val calls = mutableListOf<String>()
        val resolver = StreamResolver(searchStandard = { _, _ -> error("Disabled provider") },
            searchLossless = { error("Disabled provider") }, resolveYouTube = { _, _, _, _ -> error("Second server succeeds") },
            resolveAddon = { source, track, _, _ ->
                calls += source.id
                if (source.id == first.id) null else track.copy(audioUrl = "https://second.example/song.flac",
                    source = "Streaming Addon: Second", qualityBadge = "Custom stream", isLossless = true)
            })
        val result = resolver.resolve(video, policy.ceiling.audioQuality, sourcePolicy = policy)
        assertEquals(listOf(first.id, second.id), calls)
        assertEquals(video.id, result.id); assertEquals(video.coverUrl, result.coverUrl)
        assertEquals("Streaming Addon: Second", result.source)
    }

    @Test fun archiveEligibilityIsIndependentOfItsPositionInThePlaybackOrder() {
        val youtubeFirst = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = PlaybackSource.builtIns)
        assertTrue(youtubeFirst.allowsArchive)
        assertFalse(youtubeFirst.copy(sources = listOf(PlaybackSource.archive.copy(enabled = false), PlaybackSource.youtube))
            .allowsArchive)
    }

    @Test fun sourcePreferencesPersistMultipleServersOrderAndIndependentSwitches() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        withContext(Dispatchers.IO) { app.getSharedPreferences("xtreme_other_settings", 0).edit().clear().commit(); app.getSharedPreferences("xtreme_playback_sources_prefs", 0).edit().clear().commit() }
        val sources = listOf(custom("https://first.example"), PlaybackSource.youtube.copy(enabled = false),
            custom("https://second.example").copy(enabled = false), PlaybackSource.archive, PlaybackSource.jio)
        OtherSettingsPreferences.savePlaybackSources(app, sources)
        withTimeout(3000) { while (OtherSettingsPreferences.loadPlaybackSources(app) != PlaybackSource.ordered(sources)) delay(10) }
        assertEquals(PlaybackSource.ordered(sources), OtherSettingsPreferences.playbackIntegrations(app).sourceList(AudioQualityCeiling.MAX))
        OtherSettingsPreferences.savePlaybackSources(app, sources.reversed())
        withTimeout(3000) { while (OtherSettingsPreferences.loadPlaybackSources(app) != PlaybackSource.ordered(sources.reversed())) delay(10) }
        assertEquals(PlaybackSource.ordered(sources.reversed()), OtherSettingsPreferences.playbackIntegrations(app).sources)
        assertTrue(OtherSettingsPreferences.playbackIntegrations(app).sources.last().enabled)
        assertFalse(OtherSettingsPreferences.playbackIntegrations(app).sources.single { it.baseUrl == "https://second.example" }.enabled)
    }
    @Test fun legacyOrderAndFlagsMigrateWithoutDroppingTheConfiguredServer() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        withContext(Dispatchers.IO) {
            app.getSharedPreferences("xtreme_playback_sources_prefs", 0).edit().clear()
                .putString("sources_order", "addon,jiosaavn,youtube,internet_archive")
                .putBoolean("source_enabled_youtube", false).putBoolean("source_enabled_addon", true).commit()
            app.getSharedPreferences("xtreme_other_settings", 0).edit().clear()
                .putString("streaming_addon_url", "https://legacy.example")
                .putString("streaming_addon_name", "Legacy server").commit()
        }
        val migrated = PlaybackSourcesPreferences.load(app)
        assertEquals(PlaybackSourceKind.ADDON, migrated.first().kind)
        assertEquals("https://legacy.example", migrated.first().baseUrl)
        assertTrue(migrated.first().enabled)
        assertEquals(listOf(PlaybackSourceKind.ADDON, PlaybackSourceKind.INTERNET_ARCHIVE,
            PlaybackSourceKind.JIOSAAVN, PlaybackSourceKind.YOUTUBE), migrated.map { it.kind })
        assertTrue(migrated.single { it.kind == PlaybackSourceKind.YOUTUBE }.enabled)
        PlaybackSourcesPreferences.save(app, migrated)
        withTimeout(3000) { while (withContext(Dispatchers.IO) {
            app.getSharedPreferences("xtreme_playback_sources_prefs", 0).getString("sources_v2", null)
        } == null) delay(10) }
        assertEquals(migrated, PlaybackSourcesPreferences.load(app))
    }

    @Test fun rapidSourceEditsAreSerializedAndAddingAnotherServerDoesNotReplaceTheFirst() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        PlaybackSourcesPreferences.save(app, PlaybackSource.builtIns)
        withTimeout(3000) { while (PlaybackSourcesPreferences.load(app) != PlaybackSource.builtIns) delay(10) }
        PlaybackSourcesPreferences.saveAddon(app, "https://first.example", "First")
        PlaybackSourcesPreferences.saveAddon(app, "https://second.example", "Second")
        PlaybackSourcesPreferences.setSourceEnabled(app, PlaybackSource.jio.id, false)
        withTimeout(3000) { while (true) {
            val saved = PlaybackSourcesPreferences.load(app)
            if (saved.count { it.kind == PlaybackSourceKind.ADDON } == 2 && !saved.single { it.kind == PlaybackSourceKind.JIOSAAVN }.enabled) break
            delay(10)
        } }
        val saved = PlaybackSourcesPreferences.load(app)
        assertEquals(listOf("https://first.example", "https://second.example"), saved.filter { it.kind == PlaybackSourceKind.ADDON }.map { it.baseUrl })
        assertTrue(saved.filter { it.kind == PlaybackSourceKind.ADDON }.none { it.enabled })
    }

    @Test fun onlyAddonRowsCanReorderAndOnlyOptionalProvidersCanBeDisabled() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val first = custom("https://first.example"); val second = custom("https://second.example")
        val initial = listOf(first, second) + PlaybackSource.builtIns
        PlaybackSourcesPreferences.save(app, initial)
        withTimeout(3000) { while (PlaybackSourcesPreferences.load(app) != initial) delay(10) }
        PlaybackSourcesPreferences.swapSources(app, 1, 0)
        val reordered = listOf(second, first) + PlaybackSource.builtIns
        withTimeout(3000) { while (PlaybackSourcesPreferences.load(app) != reordered) delay(10) }
        PlaybackSourcesPreferences.swapSources(app, 1, 2)
        PlaybackSourcesPreferences.swapSources(app, 3, 4)
        PlaybackSourcesPreferences.setSourceEnabled(app, PlaybackSource.youtube.id, false)
        // A real edit provides a completion barrier for all queued mutations.
        PlaybackSourcesPreferences.setSourceEnabled(app, first.id, false)
        withTimeout(3000) { while (PlaybackSourcesPreferences.load(app).single { it.id == first.id }.enabled) delay(10) }
        assertEquals(reordered.map { it.id }, PlaybackSourcesPreferences.load(app).map { it.id })
        assertTrue(PlaybackSourcesPreferences.load(app).last().enabled)
        PlaybackSourcesPreferences.reorderAddons(app, listOf(first.id, PlaybackSource.youtube.id, "removed-server"))
        withTimeout(3000) { while (PlaybackSourcesPreferences.load(app).firstOrNull()?.id != first.id) delay(10) }
        val afterDrop = PlaybackSourcesPreferences.load(app)
        assertEquals(initial.map { it.id }, afterDrop.map { it.id })
        assertFalse(afterDrop.first().enabled)
        assertTrue(afterDrop[1].enabled)
    }

    @Test fun explicitRestoreCannotFallThroughToAnUnrelatedProviderWhenItsSourceIsDisabled() = runBlocking {
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, sources = listOf(
            PlaybackSource.jio.copy(enabled = false), PlaybackSource.youtube))
        val resolver = StreamResolver(searchStandard = { _, _ -> error("Disabled Jio must not be contacted") },
            resolveYouTube = { _, _, _, _ -> error("An original-provider restore must not silently select another source") })
        try {
            resolver.resolve(video, policy.ceiling.audioQuality, sourcePolicy = policy, preferredSourceId = PlaybackSource.jio.id)
            fail("Unavailable original source must be reported")
        } catch (_: TrackUnavailableException) { }
    }

    @Test fun blockedAndDisabledSourceChangesDoNotInvalidateAnIdenticalPlaybackPolicy() {
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, sources = PlaybackSource.builtIns)
        assertEquals(policy.key, policy.copy(sources = policy.sources + custom("https://new.example")).key)
        assertEquals(policy.key, policy.copy(sources = policy.sources + custom("https://off.example").copy(enabled = false)).key)
    }

}
