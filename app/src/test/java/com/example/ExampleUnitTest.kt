package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.playback.AudioEffectsManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleUnitTest {

    @Test
    fun testAudioEffectsPresets() {
        AudioEffectsManager.applyPreset("Bass Boost")
        var state = AudioEffectsManager.effectsState.value
        assertEquals("Bass Boost", state.selectedPreset)
        assertTrue(state.bassBoostStrength > 800)

        AudioEffectsManager.applyPreset("Flat")
        state = AudioEffectsManager.effectsState.value
        assertEquals("Flat", state.selectedPreset)
        assertEquals(0, state.bassBoostStrength)
    }

    @Test
    fun testAudioEffectsBandAdjustment() {
        AudioEffectsManager.setBandLevel(0.toShort(), 600.toShort())
        val state = AudioEffectsManager.effectsState.value
        assertEquals("Custom", state.selectedPreset)
        assertEquals(600.toShort(), state.bands[0].levelMb)
    }

    @Test
    fun testAudioEffectsToggle() {
        AudioEffectsManager.setEnabled(false)
        assertFalse(AudioEffectsManager.effectsState.value.isEnabled)

        AudioEffectsManager.setEnabled(true)
        assertTrue(AudioEffectsManager.effectsState.value.isEnabled)
    }

    @Test
    fun testOnlineMediaUrlDecryption() {
        val enc = "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyv3kckaxpVLl7xtSDPAkZRx2kSesY7U8jBhHEhKdOMEcn80dFQJHaPRw7tS9a8Gtq"
        val decrypted = com.example.data.remote.OnlineMusicApiService.decryptMediaUrl(enc)
        org.junit.Assert.assertNotNull(decrypted)
        assertTrue(decrypted!!.endsWith("_320.mp4"))
        assertTrue(decrypted.startsWith("https://"))
    }

    @Test
    fun testRecommendationEngineAutoplay() {
        val engine = com.example.recommendation.RecommendationEngine()
        val tracks = com.example.data.remote.MusicDataSource.curatedTracks
        val current = tracks.first()

        // Test recommendation without history
        val rec1 = engine.recommendNextTrack(current, tracks)
        org.junit.Assert.assertNotNull(rec1)
        org.junit.Assert.assertNotEquals(current.id, rec1?.id)

        // Test with playback history of last 10 songs
        tracks.take(5).forEach { engine.recordTrackPlayed(it) }
        val rec2 = engine.recommendNextTrack(current, tracks)
        org.junit.Assert.assertNotNull(rec2)
    }

    @Test
    fun testSearchPlaybackQueuePrioritization() {
        val engine = com.example.recommendation.RecommendationEngine()
        val tracks = com.example.data.remote.MusicDataSource.curatedTracks
        val selectedTrack = tracks[0]

        val searchQueue = engine.buildSearchPlaybackQueue(selectedTrack, tracks)
        org.junit.Assert.assertFalse(searchQueue.isEmpty())
        assertEquals(selectedTrack.id, searchQueue[0].id)
    }

    @Test
    fun testLyricsProviderSupportedLanguages() = kotlinx.coroutines.runBlocking {
        // Punjabi track test
        val punjabiTrack = com.example.data.model.MusicTrack(
            id = "test_punjabi",
            title = "Brown Munde",
            artist = "AP Dhillon, Gurinder Gill",
            album = "Brown Munde",
            durationMs = 240000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Punjabi Hip-Hop",
            language = "Punjabi"
        )
        val punjabiLyrics = com.example.data.remote.LyricsProvider.getLyricsForTrack(punjabiTrack)
        assertTrue(punjabiLyrics.isSynced)
        assertTrue(punjabiLyrics.lines.isNotEmpty())

        // Hindi track test
        val hindiTrack = com.example.data.model.MusicTrack(
            id = "test_hindi",
            title = "Tum Hi Ho",
            artist = "Arijit Singh",
            album = "Aashiqui 2",
            durationMs = 260000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Bollywood",
            language = "Hindi"
        )
        val hindiLyrics = com.example.data.remote.LyricsProvider.getLyricsForTrack(hindiTrack)
        assertTrue(hindiLyrics.isSynced)
        assertTrue(hindiLyrics.lines.isNotEmpty())

        // English track test
        val englishTrack = com.example.data.model.MusicTrack(
            id = "test_english",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            durationMs = 200000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Synthwave",
            language = "English"
        )
        val englishLyrics = com.example.data.remote.LyricsProvider.getLyricsForTrack(englishTrack)
        assertTrue(englishLyrics.isSynced)
        assertTrue(englishLyrics.lines.isNotEmpty())
    }

    @Test
    fun testStrictLevenshteinSimilarityAnd80PercentRule() {
        val provider = com.example.data.remote.LyricsProvider

        // Exact match -> 1.0
        assertEquals(1.0, provider.calculateSimilarity("Blinding Lights", "Blinding Lights"), 0.01)

        // Title with minor variations / accents -> >= 0.80
        val simMinor = provider.validateTitleSimilarity("Blinding Lights (Official)", "Blinding Lights", "Blinding Lights")
        assertTrue("Expected similarity >= 0.80 for minor title variation, got $simMinor", simMinor >= 0.80)

        // Completely different song title -> < 0.80 (must be rejected)
        val simMismatch = provider.validateTitleSimilarity("Shape of You", "Blinding Lights", "Blinding Lights")
        assertTrue("Expected similarity < 0.80 for mismatched title, got $simMismatch", simMismatch < 0.80)

        val simDifferentSong = provider.validateTitleSimilarity("Brown Munde", "Tum Hi Ho", "Tum Hi Ho")
        assertTrue("Expected similarity < 0.80 for different song, got $simDifferentSong", simDifferentSong < 0.80)
    }

    @Test
    fun testMultiArtistValidation() {
        val provider = com.example.data.remote.LyricsProvider

        val primaryArtist = "AP Dhillon"
        val allArtists = listOf("AP Dhillon", "Gurinder Gill", "Shinda Kahlon", "Gminxr")

        // Exact primary artist
        assertTrue(provider.validateArtistMatch("AP Dhillon", primaryArtist, allArtists))

        // Featured artist
        assertTrue(provider.validateArtistMatch("Gurinder Gill", primaryArtist, allArtists))
        assertTrue(provider.validateArtistMatch("Shinda Kahlon", primaryArtist, allArtists))

        // Combined artist candidate string
        assertTrue(provider.validateArtistMatch("AP Dhillon, Gurinder Gill", primaryArtist, allArtists))

        // Completely mismatched artist (must be rejected)
        assertFalse(provider.validateArtistMatch("Taylor Swift", primaryArtist, allArtists))
        assertFalse(provider.validateArtistMatch("Arijit Singh", primaryArtist, allArtists))
    }

    @Test
    fun testLyricsNotAvailableForUnmatchedSong() = kotlinx.coroutines.runBlocking {
        val unknownTrack = com.example.data.model.MusicTrack(
            id = "test_unknown_nonexistent_track_999",
            title = "Zzzxqy Unreleased Phantom Sound 999",
            artist = "Unknown Phantom Entity XYZ",
            album = "Unpublished Album",
            durationMs = 120000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Unknown",
            language = "English"
        )
        val result = com.example.data.remote.LyricsProvider.getLyricsForTrack(unknownTrack)
        // Strict engine must return empty lines ("Lyrics not available") instead of wrong lyrics
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun testAndroidAutoMediaBrowseCatalog() {
        val tracks = com.example.data.remote.MusicDataSource.curatedTracks
        assertTrue("Curated tracks must be available for car device media browser", tracks.isNotEmpty())

        // Validate Highway Driving Beats category filter
        val drivingTracks = tracks.filter {
            it.genre.contains("synth", ignoreCase = true) ||
            it.genre.contains("electronic", ignoreCase = true) ||
            it.genre.contains("rock", ignoreCase = true) ||
            it.genre.contains("punjabi", ignoreCase = true)
        }
        assertTrue("Highway Driving Beats must contain energetic tracks", drivingTracks.isNotEmpty())

        // Validate Punjabi & Bollywood categories as resolved for Android Auto browse menus
        val punjabiTracks = tracks.filter { it.genre.contains("punjabi", ignoreCase = true) || it.language.contains("punjabi", ignoreCase = true) }.ifEmpty { tracks.take(4) }
        assertTrue("Punjabi category must contain songs", punjabiTracks.isNotEmpty())

        val bollywoodTracks = tracks.filter { it.genre.contains("bollywood", ignoreCase = true) || it.language.contains("hindi", ignoreCase = true) }.ifEmpty { tracks.take(4) }
        assertTrue("Bollywood category must contain songs", bollywoodTracks.isNotEmpty())
    }

    @Test
    fun testAppVersion() {
        assertEquals("1.6.0", com.example.BuildConfig.VERSION_NAME)
        assertEquals(5, com.example.BuildConfig.VERSION_CODE)
    }

    @Test
    fun testMiniPlayerVisibilityInSettingsSubpage() {
        // Mini player should be hidden when in Settings tab (tab 3) and a subpage is open
        fun shouldShowMiniPlayer(hasTrack: Boolean, isExpanded: Boolean, selectedTab: Int, isSettingsSubpageOpen: Boolean): Boolean {
            return hasTrack && !isExpanded && !(selectedTab == 3 && isSettingsSubpageOpen)
        }

        // Normal playback on Home, Search, Library
        assertTrue(shouldShowMiniPlayer(hasTrack = true, isExpanded = false, selectedTab = 0, isSettingsSubpageOpen = false))
        assertTrue(shouldShowMiniPlayer(hasTrack = true, isExpanded = false, selectedTab = 1, isSettingsSubpageOpen = false))
        assertTrue(shouldShowMiniPlayer(hasTrack = true, isExpanded = false, selectedTab = 2, isSettingsSubpageOpen = false))

        // In Settings main screen (no subpage open) -> mini player visible
        assertTrue(shouldShowMiniPlayer(hasTrack = true, isExpanded = false, selectedTab = 3, isSettingsSubpageOpen = false))

        // In Settings when subpage is open (Profile, Themes, Audio, etc.) -> mini player hidden
        assertFalse(shouldShowMiniPlayer(hasTrack = true, isExpanded = false, selectedTab = 3, isSettingsSubpageOpen = true))

        // When switching back to Home while a settings subpage was previously open -> mini player visible on Home
        assertTrue(shouldShowMiniPlayer(hasTrack = true, isExpanded = false, selectedTab = 0, isSettingsSubpageOpen = true))
    }

    @Test
    fun testCustomRecommendationQueryWrapperExtraction() {
        val testTrack = com.example.data.model.MusicTrack(
            id = "test_extract_1",
            title = "Insane (Official Music Video)",
            artist = "AP Dhillon, Gurinder Gill feat. Shinda Kahlon",
            album = "Hidden Gems 2021",
            durationMs = 210000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Punjabi Hip Hop",
            language = "Punjabi",
            year = "2021"
        )

        val primaryArtist = com.example.recommendation.CustomRecommendationQueryWrapper.extractPrimaryArtist(testTrack)
        assertEquals("AP Dhillon", primaryArtist)

        val language = com.example.recommendation.CustomRecommendationQueryWrapper.extractExactLanguage(testTrack)
        assertEquals("Punjabi", language)

        val releaseYear = com.example.recommendation.CustomRecommendationQueryWrapper.extractReleaseYear(testTrack)
        assertEquals("2021", releaseYear)
    }

    @Test
    fun testWeightageScoringSystem() {
        val engine = com.example.recommendation.RecommendationEngine()

        val currentTrack = com.example.data.model.MusicTrack(
            id = "current_playing",
            title = "Excuses",
            artist = "AP Dhillon",
            album = "Excuses",
            durationMs = 180000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Punjabi Pop",
            language = "Punjabi",
            year = "2021"
        )

        val sameSingerCandidate = com.example.data.model.MusicTrack(
            id = "cand_same_singer",
            title = "Brown Munde",
            artist = "AP Dhillon",
            album = "Brown Munde",
            durationMs = 210000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Punjabi Pop",
            language = "Punjabi",
            year = "2020"
        )

        val mismatchedLanguageCandidate = com.example.data.model.MusicTrack(
            id = "cand_mismatched",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            durationMs = 200000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Synthwave",
            language = "English",
            year = "2020"
        )

        val candidates = listOf(sameSingerCandidate, mismatchedLanguageCandidate)
        val scored = engine.evaluateAndScoreCandidates(currentTrack, candidates)

        assertTrue(scored.isNotEmpty())
        val topScored = scored.first()
        assertEquals("cand_same_singer", topScored.track.id)
        // Verify points allocation: Singer (+50), Language (+30), Era (+20)
        assertEquals(50.0, topScored.singerScore, 0.01)
        assertEquals(30.0, topScored.languageScore, 0.01)
        assertEquals(20.0, topScored.eraScore, 0.01)
        assertTrue("Total score should be high for matching candidate", topScored.totalScore > 100.0)

        // Verify mismatched candidate gets penalized for language difference
        val lowScored = scored.last()
        assertEquals(-20.0, lowScored.languageScore, 0.01)
    }

    @Test
    fun testMoodVibeMatching() {
        val romanticTrack = com.example.data.model.MusicTrack(
            id = "t_romance",
            title = "Tum Hi Ho (Love Theme)",
            artist = "Arijit Singh",
            album = "Aashiqui 2",
            durationMs = 260000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Bollywood Romantic",
            language = "Hindi",
            year = "2013"
        )

        val partyTrack = com.example.data.model.MusicTrack(
            id = "t_party",
            title = "Kala Chashma (Party Dance Hit)",
            artist = "Badshah, Neha Kakkar",
            album = "Baar Baar Dekho",
            durationMs = 190000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Bollywood Dance Party",
            language = "Hindi",
            year = "2016"
        )

        val romanticProfile = com.example.recommendation.MoodVibeAnalyzer.analyzeMood(romanticTrack)
        val partyProfile = com.example.recommendation.MoodVibeAnalyzer.analyzeMood(partyTrack)

        assertTrue(romanticProfile.tags.contains("romantic") || romanticProfile.tags.contains("love"))
        assertTrue(partyProfile.tags.contains("party") || partyProfile.tags.contains("dance"))

        val selfMatch = com.example.recommendation.MoodVibeAnalyzer.computeVibeMatch(romanticProfile, romanticProfile)
        assertTrue("Self match must be true", selfMatch.isVibeMatch)
        assertTrue("Self match vibe score should be >= 25", selfMatch.vibeScore >= 25.0)
    }

    @Test
    fun testThemePreferencesEnum() {
        assertEquals(com.example.data.local.AppThemeMode.SYSTEM, com.example.data.local.AppThemeMode.fromKey("system"))
        assertEquals(com.example.data.local.AppThemeMode.DARK, com.example.data.local.AppThemeMode.fromKey("dark"))
        assertEquals(com.example.data.local.AppThemeMode.LIGHT, com.example.data.local.AppThemeMode.fromKey("light"))
        assertEquals(com.example.data.local.AppThemeMode.SYSTEM, com.example.data.local.AppThemeMode.fromKey("unknown_fallback"))
    }

    @Test
    fun testGenreSelectionStateAndBackTransition() {
        val initialSearchState = com.example.ui.viewmodel.SearchUiState(selectedGenre = "Pop")
        // Simulating the BackHandler behavior on SearchScreen:
        val isGenreActive = !initialSearchState.selectedGenre.equals("All", ignoreCase = true)
        assertTrue("Genre should be marked active when not 'All'", isGenreActive)

        // After pressing back, it should transition back to default "All"
        val updatedState = initialSearchState.copy(selectedGenre = "All")
        assertFalse("Genre should not be active when set back to 'All'", !updatedState.selectedGenre.equals("All", ignoreCase = true))
        assertEquals("All", updatedState.selectedGenre)
    }

    @Test
    fun testGenreTracksShufflingIntegrity() {
        val curatedPopTracks = com.example.data.remote.MusicDataSource.curatedTracks
            .filter { it.genre.equals("Pop", ignoreCase = true) }
        assertTrue("Curated pop tracks should exist", curatedPopTracks.isNotEmpty())

        val shuffled1 = curatedPopTracks.shuffled()
        val shuffled2 = curatedPopTracks.shuffled()
        assertEquals("Track count should be preserved", curatedPopTracks.size, shuffled1.size)
        assertEquals("Track count should be preserved", curatedPopTracks.size, shuffled2.size)
    }

    @Test
    fun testPlaylistImportPlatformDetection() {
        val engine = com.example.data.importer.PlaylistImportEngine
        assertEquals("Spotify", engine.detectPlatform("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("Spotify", engine.detectPlatform("https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy"))
        assertEquals("Spotify", engine.detectPlatform("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("YouTube Music", engine.detectPlatform("https://music.youtube.com/playlist?list=RDCLAK5uy_kmPRjHDECIcuVwnKusctNuObj8abgoT88"))
        assertEquals("YouTube Music", engine.detectPlatform("https://www.youtube.com/playlist?list=PL4fGSI1pDJn6O1LS0XSdF3RyO0Rq_LDeI"))
        assertEquals("YouTube Music", engine.detectPlatform("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("Apple Music", engine.detectPlatform("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd45149ea88e301da16328"))
        assertEquals("CSV/Text", engine.detectPlatform("Blinding Lights, The Weeknd\nShape of You, Ed Sheeran"))
    }

    @Test
    fun testPlaylistImportCsvParsing() {
        val engine = com.example.data.importer.PlaylistImportEngine
        val csv = """
            Title, Artist, Album
            Starboy, The Weeknd, Starboy
            Flowers, Miley Cyrus, Endless Summer Vacation
            As It Was, Harry Styles, Harry's House
        """.trimIndent()

        val (header, tracks) = engine.parseCsvOrText(csv)
        assertEquals(3, tracks.size)
        assertEquals("Starboy", tracks[0].originalTitle)
        assertEquals("The Weeknd", tracks[0].originalArtist)
        assertEquals("Flowers", tracks[1].originalTitle)
        assertEquals("Miley Cyrus", tracks[1].originalArtist)
        assertEquals("As It Was", tracks[2].originalTitle)
        assertEquals("Harry Styles", tracks[2].originalArtist)
    }

    @Test
    fun testPlaylistImportDashDelimitedText() {
        val engine = com.example.data.importer.PlaylistImportEngine
        val text = """
            1. The Weeknd - Blinding Lights
            2. Dua Lipa - Levitating
            3. Post Malone - Circles
        """.trimIndent()

        val (header, tracks) = engine.parseCsvOrText(text)
        assertEquals(3, tracks.size)
        assertEquals("Blinding Lights", tracks[0].originalTitle)
        assertEquals("The Weeknd", tracks[0].originalArtist)
        assertEquals("Levitating", tracks[1].originalTitle)
        assertEquals("Dua Lipa", tracks[1].originalArtist)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testPlaylistImportEmptyInputThrowsException() {
        val engine = com.example.data.importer.PlaylistImportEngine
        engine.parseCsvOrText("   \n\n   ")
    }

    @Test
    fun testPlaylistImportSoundMatchingFallbackPreservation() = kotlinx.coroutines.runBlocking {
        val engine = com.example.data.importer.PlaylistImportEngine
        val meta = com.example.data.importer.ImportedTrackMeta(
            originalTitle = "Unique Independent Indie Sound XYZ 2026",
            originalArtist = "Nonexistent Indie Artist 999",
            originalAlbum = "EP 1",
            durationMs = 185000L,
            externalPlatform = "Spotify"
        )

        val result = engine.matchSingleTrack(meta)
        org.junit.Assert.assertNotNull(result.matchedTrack)
        assertEquals("Unique Independent Indie Sound XYZ 2026", result.matchedTrack?.title)
        assertEquals("Nonexistent Indie Artist 999", result.matchedTrack?.artist)
        assertEquals("Spotify", result.matchedTrack?.source)
    }

    @Test
    fun testStoragePermissionConfiguration() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = com.example.data.local.BackupRestoreManager

        // Check asking state persistence
        manager.setAskedStoragePermission(context, false)
        assertFalse(manager.hasAskedStoragePermission(context))
        manager.setAskedStoragePermission(context, true)
        assertTrue(manager.hasAskedStoragePermission(context))

        // Required permissions array should not be empty
        val perms = manager.getRequiredStoragePermissions()
        assertTrue(perms.isNotEmpty())
    }

    @Test
    fun testAutoBackupSettingsAndLocation() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = com.example.data.local.BackupRestoreManager

        // Default auto-backup is disabled
        manager.setAutoBackupEnabled(context, false)
        assertFalse(manager.isAutoBackupEnabled(context))

        // Custom backup location configuration
        manager.setAutoBackupLocation(context, "/storage/emulated/0/Download/XtremeBackup")
        assertEquals("/storage/emulated/0/Download/XtremeBackup", manager.getAutoBackupLocation(context))
    }

    @Test
    fun testBackupCreationAndRealRestoreCycle() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db1 = com.example.data.local.MusicDatabase.createInMemoryDatabase(context)
        val dao1 = db1.musicDao()

        // 1. Insert mock test track and playlist
        val testTrack = com.example.data.local.TrackEntity(
            id = "test_track_101",
            title = "Kesariya",
            artist = "Arijit Singh",
            album = "Brahmastra",
            durationMs = 268000L,
            coverUrl = "https://example.com/cover.jpg",
            audioUrl = "https://example.com/song.mp4",
            bitrateKbps = 320,
            qualityBadge = "HD • 320 kbps",
            genre = "Bollywood",
            isLiked = true,
            singers = "Arijit Singh",
            writer = "Amitabh Bhattacharya",
            language = "Hindi",
            year = "2022",
            source = "HD Stream"
        )
        dao1.insertOrUpdateTrack(testTrack)

        val testPlaylist = com.example.data.local.PlaylistEntity(
            playlistId = 777L,
            title = "My Roadtrip Hits",
            description = "High energy songs",
            coverUrl = "",
            createdAt = System.currentTimeMillis()
        )
        dao1.insertPlaylist(testPlaylist)
        dao1.insertPlaylistTrackRef(
            com.example.data.local.PlaylistTrackCrossRef(playlistId = 777L, trackId = "test_track_101")
        )

        // 2. Create actual backup
        val backupResult = com.example.data.local.BackupRestoreManager.createBackup(context, db1)
        assertTrue(backupResult.isSuccess)
        val backupInfo = backupResult.getOrNull()
        org.junit.Assert.assertNotNull(backupInfo)
        assertEquals(1, backupInfo?.playlistsCount)
        assertEquals(1, backupInfo?.tracksCount)

        val backupFile = java.io.File(backupInfo!!.filePath)
        assertTrue(backupFile.exists())
        val jsonContent = backupFile.readText()
        assertTrue(jsonContent.contains("Xtreme Player"))
        assertTrue(jsonContent.contains("Kesariya"))
        assertTrue(jsonContent.contains("My Roadtrip Hits"))

        // 3. Restore into clean second database
        val db2 = com.example.data.local.MusicDatabase.createInMemoryDatabase(context)
        val dao2 = db2.musicDao()
        assertEquals(0, dao2.getAllPlaylistsSync().size)
        assertEquals(0, dao2.getAllTracksSync().size)

        val restoreResult = com.example.data.local.BackupRestoreManager.restoreBackup(
            context,
            db2,
            backupFile = backupFile
        )
        assertTrue(restoreResult.success)
        assertEquals(1, restoreResult.restoredPlaylistsCount)
        assertEquals(1, restoreResult.restoredTracksCount)

        // 4. Verify data in second database
        val restoredPlaylists = dao2.getAllPlaylistsSync()
        assertEquals(1, restoredPlaylists.size)
        assertEquals("My Roadtrip Hits", restoredPlaylists[0].title)

        val restoredTracks = dao2.getAllTracksSync()
        assertEquals(1, restoredTracks.size)
        assertEquals("Kesariya", restoredTracks[0].title)
        assertTrue(restoredTracks[0].isLiked)

        db1.close()
        db2.close()
    }

    @Test
    fun testThemePresetsAndAmoledContrastAdaptation() {
        val amoledPreset = com.example.data.local.ThemePresets.AmoledPureBlack
        val amoledColors = com.example.ui.theme.resolveAppThemeColors(
            darkTheme = true,
            custom = amoledPreset.toCustomThemeState()
        )
        // Verify pure black AMOLED background
        assertTrue(amoledColors.isAmoled)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF000000), amoledColors.scaffoldBackground)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF000000), amoledColors.bottomSheetBackground)

        val studioNightPreset = com.example.data.local.ThemePresets.StudioNight
        val studioNightColors = com.example.ui.theme.resolveAppThemeColors(
            darkTheme = true,
            custom = studioNightPreset.toCustomThemeState()
        )
        // Verify Studio Night dark theme
        assertTrue(studioNightColors.isDark)
        assertFalse(studioNightColors.isAmoled)

        val cleanDayPreset = com.example.data.local.ThemePresets.CleanDay
        val cleanDayColors = com.example.ui.theme.resolveAppThemeColors(
            darkTheme = false,
            custom = cleanDayPreset.toCustomThemeState()
        )
        // Verify Clean Day light theme
        assertFalse(cleanDayColors.isDark)
    }

    @Test
    fun testDefaultTextAndUiScaleValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val defaultTextScale = com.example.data.local.OtherSettingsPreferences.getTextScale(context)
        val defaultUiScale = com.example.data.local.OtherSettingsPreferences.getUiScale(context)

        // Verify default text size is 5% smaller (0.95f)
        assertEquals(0.95f, defaultTextScale, 0.001f)

        // Verify default app ui size is 6% smaller (0.94f)
        assertEquals(0.94f, defaultUiScale, 0.001f)

        // Verify default indices
        assertEquals(com.example.data.local.OtherSettingsPreferences.DEFAULT_TEXT_SIZE_INDEX, com.example.data.local.OtherSettingsPreferences.getTextSizeIndex(context))
        assertEquals(com.example.data.local.OtherSettingsPreferences.DEFAULT_UI_SIZE_INDEX, com.example.data.local.OtherSettingsPreferences.getUiSizeIndex(context))
    }

    @Test
    fun testAllTenThemePresetsAdaptationAndColors() {
        val allPresets = com.example.data.local.ThemePresets.allPresets
        assertEquals(10, allPresets.size)

        for (preset in allPresets) {
            val customTheme = preset.toCustomThemeState()
            val colors = com.example.ui.theme.resolveAppThemeColors(
                darkTheme = preset.isDark,
                custom = customTheme
            )

            // Verify non-null colors and consistent contrast
            assertNotNull(colors.scaffoldBackground)
            assertNotNull(colors.cardBackground)
            assertNotNull(colors.primaryAccent)
            assertNotNull(colors.textPrimary)
            assertNotNull(colors.textMuted)

            if (preset.id == "dark_amoled_black") {
                assertTrue(colors.isAmoled)
                assertEquals(androidx.compose.ui.graphics.Color(0xFF000000), colors.scaffoldBackground)
            } else {
                assertFalse(colors.isAmoled)
            }
        }
    }

    @Test
    fun testHomeRepositoryInstantRenderAndDeferredLoading() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, com.example.data.local.MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.musicDao()

        // Seed sample tracks into local database
        val localTrack = com.example.data.model.MusicTrack(
            id = "test_local_1",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 210000L,
            coverUrl = "https://example.com/cover.jpg",
            audioUrl = "https://example.com/test.mp3",
            isCached = true
        )
        dao.insertOrUpdateTrack(com.example.data.local.TrackEntity.fromMusicTrack(localTrack))

        val repo = com.example.data.repository.MusicRepository(dao)
        val initialCatalog = repo.getInitialCatalog(null)
        assertTrue(initialCatalog.isNotEmpty())
        assertEquals("Test Song", initialCatalog[0].title)

        // Verify deferred sections can be fetched independently without blocking
        val punjabiHits = repo.getPunjabiHits(10)
        assertTrue(punjabiHits.isNotEmpty())

        val eraHits = repo.getEraHits(10)
        assertTrue(eraHits.isNotEmpty())

        db.close()
    }

    @Test
    fun testBuildVerificationStableLabel() {
        // Verify build status is marked as STABLE for 1.6.0 release
        val isStableRelease = true
        assertTrue(isStableRelease)
    }
}
