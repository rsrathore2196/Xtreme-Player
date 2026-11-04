package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.*
import com.example.data.local.UserProfile
import com.example.data.model.*
import com.example.ui.viewmodel.*
import java.io.File
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeCardCacheRestorationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val profile = UserProfile(countryCode = "IN", languages = listOf("Hindi"))
    private fun song() = MusicTrack("yt_abcdefghijk", "Song", "Singer", "Album", 180_000, "https://cover", "",
        primaryArtists = listOf("Singer"), singers = "Singer", writer = "Writer", isLiked = true)
    private fun saved() = SavedHomeCards(HomeCacheIdentity.home(profile), refreshedAt = 1234,
        history = SectionState("Jump Back In", tracks = listOf(song()), isLoaded = true),
        artists = listOf(MostListenedArtist("Singer", 50, listOf(song()))), inputRevision = "revision",
        serverShelves = listOf(ServerHomeShelf("native", "Native heading", tracks = listOf(song()))),
        profileShelf = ServerHomeShelf("profile_shelf_music_for_you", "Music for You", tracks = listOf(song())),
        continuationToken = "cursor", profileShelfKey = HomeCacheIdentity.profile(profile), profileFetchedAt = 5678,
        serverFetchSucceeded = true, profileFetchSucceeded = true, consumedContinuations = setOf("previous"))
    private fun clearMemory() = (HomeCardCache::class.java.getDeclaredField("memory").apply { isAccessible = true }
        .get(null) as android.util.LruCache<*, *>).evictAll()
    private suspend fun durable(name: String): File {
        val file = File(app.filesDir, name)
        withTimeout(5_000) { while (!file.isFile || file.length() == 0L) delay(10) }
        return file
    }
    @Test fun nativeFeedAndLocalHistoryKeepCompleteMetadataAndOrdering() = runBlocking {
        val cache = HomeCardCache(app); val value = saved(); cache.save(value)
        val restored = cache.read(value.profileKey)!!
        assertEquals(value.serverShelves.map { it.title }, restored.serverShelves.map { it.title })
        assertEquals(value.history.tracks, restored.history.tracks)
        assertEquals(value.artists, restored.artists); assertEquals("cursor", restored.continuationToken)
        assertEquals(setOf("previous"), restored.consumedContinuations)
    }
    @Test fun homeAndProfileHaveIndependentKeysAndFreshnessAfterDiskRestoration() = runBlocking {
        val cache = HomeCardCache(app); val value = saved(); cache.save(value)
        durable("home_server_${HomeCacheIdentity.fileKey(value.profileKey)}_v3.json")
        durable("home_profile_${HomeCacheIdentity.fileKey(value.profileShelfKey)}_v3.json")
        durable("home_local_v3.json"); clearMemory()
        val home = cache.read(value.profileKey)!!; val selected = cache.readProfile(value.profileShelfKey)!!
        assertEquals(1234, home.refreshedAt); assertEquals(5678, selected.profileFetchedAt)
        assertTrue(home.serverFetchSucceeded); assertTrue(selected.profileFetchSucceeded)
        assertNull(home.profileShelf); assertEquals("Music for You", selected.profileShelf!!.title)
    }
    @Test fun languageOnlyChangeKeepsTheSameCachedHomeAndDoesNotReuseTheOldProfileShelf() = runBlocking {
        val cache = HomeCardCache(app); val value = saved(); cache.save(value)
        val changed = profile.copy(languages = listOf("Punjabi", "English"))
        assertEquals(value.serverShelves.map { it.id }, cache.read(HomeCacheIdentity.home(changed))!!.serverShelves.map { it.id })
        assertNull(cache.readProfile(HomeCacheIdentity.profile(changed)))
    }
    @Test fun accountAndRegionCachesNeverLeakButLocalHistoryRemainsAvailable() = runBlocking {
        val cache = HomeCardCache(app); val value = saved(); cache.save(value)
        val other = HomeCacheIdentity.home(profile, "another-account")
        assertTrue(cache.read(other)!!.serverShelves.isEmpty())
        assertEquals(value.history.tracks, cache.read(other)!!.history.tracks)
        assertTrue(cache.read(HomeCacheIdentity.home(profile.copy(countryCode = "GB")))!!.serverShelves.isEmpty())
    }
    @Test fun validBackupFileRecoversAnInterruptedAtomicHomeWrite() = runBlocking {
        val cache = HomeCardCache(app); val value = saved(); cache.save(value)
        val file = durable("home_server_${HomeCacheIdentity.fileKey(value.profileKey)}_v3.json")
        withTimeout(5_000) { while (runCatching { JSONObject(file.readText()).getString("key") }.getOrNull() != value.profileKey) delay(10) }
        File(file.path + ".bak").writeBytes(file.readBytes()); file.writeText("interrupted")
        clearMemory(); assertEquals("Native heading", cache.read(value.profileKey)!!.serverShelves.single().title)
    }
    @Test fun cachePersistenceContainsNoObsoleteCandidateOrTasteState() = runBlocking {
        val cache = HomeCardCache(app); val value = saved(); cache.save(value)
        val text = durable("home_server_${HomeCacheIdentity.fileKey(value.profileKey)}_v3.json").readText()
        assertFalse(text.contains("quickCandidates")); assertFalse(text.contains("tasteSnapshot")); assertFalse(text.contains("shelfLoads"))
    }
    @Test fun legacyCustomPoolsAreIgnoredWithoutLosingTheLocalHistory() = runBlocking {
        File(app.filesDir, "home_cards_v1.json").writeText(JSONObject().put("profileKey", "IN|Hindi")
            .put("quickCandidates", JSONArray().put(JSONObject().put("id", "fake")))
            .put("history", JSONObject().put("tracks", JSONArray().put(JSONObject().put("id", "real")
                .put("title", "Real played song").put("artist", "Real singer")))).toString())
        val cached = HomeCardCache(app).read("IN|anonymous")!!
        assertTrue(cached.serverShelves.isEmpty()); assertEquals("real", cached.history.tracks.single().id)
    }
    @Test fun previousServerCacheSurvivesALanguageOnlyChangeAfterColdStartup() = runBlocking {
        File(app.filesDir, "home_cards_v2.json").writeText(JSONObject().put("profileKey", "IN|Hindi").put("refreshedAt", 123)
            .put("serverShelves", JSONArray().put(JSONObject().put("id", "native-old").put("title", "Actual server heading")
                .put("tracks", JSONArray().put(JSONObject().put("id", "yt_abcdefghijk").put("title", "Native song").put("artist", "Singer"))))).toString())
        val cached = HomeCardCache(app).read(HomeCacheIdentity.home(profile.copy(languages = listOf("Punjabi"))))!!
        assertEquals("Actual server heading", cached.serverShelves.single().title)
    }
    @Test fun olderArtistCacheRetainsItsPlayableRepresentativeWhileTheRankingIsRevalidated() = runBlocking {
        val played = JSONObject().put("id", "played").put("title", "Actual song").put("artist", "Singer")
            .put("album", "Actual album").put("durationMs", 234_000).put("coverUrl", "https://art.example/played.jpg")
            .put("audioUrl", "https://audio.example/played").put("source", "YouTube Music")
        File(app.filesDir, "home_cards_v2.json").writeText(JSONObject().put("profileKey", "IN|Hindi")
            .put("artists", JSONArray().put(JSONObject().put("name", "Singer").put("playCount", 12)
                .put("tracks", JSONArray().put(played)))).toString())
        val cached = HomeCardCache(app).read("IN|anonymous")!!
        val representative = cached.artists.single().tracks.single()
        assertEquals(234_000, representative.durationMs)
        assertEquals("https://art.example/played.jpg", representative.coverUrl)
        assertEquals("https://audio.example/played", representative.audioUrl)
        assertEquals(0, cached.artistDataVersion)
    }

    @Test fun initialProjectionRestoresOnlyPriorityNativeShelvesWithoutLocalCards() = runBlocking {
        val cache = HomeCardCache(app)
        val rows = listOf("Seasonal", "Trending Community Playlists", "Albums & Singles", "Quick Picks", "New releases")
            .mapIndexed { index, name -> ServerHomeShelf("native-$index", name, tracks = listOf(song())) }
        val value = saved().copy(serverShelves = rows)
        cache.save(value)
        durable("home_initial_${HomeCacheIdentity.fileKey(value.profileKey)}_v3.json"); clearMemory()
        val initial = cache.readInitialServer(value.profileKey)!!
        assertTrue(initial.initialProjection)
        assertEquals(listOf("Quick Picks", "Albums & Singles", "Trending Community Playlists"), initial.serverShelves.map { it.title })
        assertTrue(initial.history.tracks.isEmpty()); assertTrue(initial.artists.isEmpty()); assertNull(initial.profileShelf)
        assertEquals(rows.map { it.id }, cache.readServer(value.profileKey)!!.serverShelves.map { it.id })
        assertEquals(value.history.tracks, cache.readRecent(value.profileKey)!!.history.tracks)
        assertEquals(value.artists, cache.readArtists(value.profileKey)!!.artists)
    }

    @Test fun publishingInitialProjectionDoesNotOverwriteTheExpandedCache() = runBlocking {
        val cache = HomeCardCache(app)
        val value = saved().copy(serverShelves = listOf("Quick Picks", "Albums & Singles", "Trending Community Playlists", "Seasonal")
            .mapIndexed { index, name -> ServerHomeShelf("native-$index", name, tracks = listOf(song())) })
        cache.saveHome(value)
        val changed = value.serverShelves.take(3).map { it.copy(subtitle = "Updated native metadata") }
        cache.saveInitialHome(value.copy(serverShelves = changed))
        assertEquals(changed.map { it.id to it.subtitle }, cache.readInitialServer(value.profileKey)!!.serverShelves.map { it.id to it.subtitle })
        assertEquals(value.serverShelves.map { it.id to it.subtitle }, cache.readServer(value.profileKey)!!.serverShelves.map { it.id to it.subtitle })
    }

    @Test fun preProjectionCacheRemainsCompatibleAndAccountIsolated() = runBlocking {
        val value = saved().copy(serverShelves = listOf(ServerHomeShelf("quick", "Quick Picks", tracks = listOf(song()))))
        val cache = HomeCardCache(app); cache.saveHome(value)
        val projection = durable("home_initial_${HomeCacheIdentity.fileKey(value.profileKey)}_v3.json")
        projection.delete(); clearMemory()
        assertEquals("quick", cache.readInitialServer(value.profileKey)!!.serverShelves.single().id)
        assertNull(cache.readInitialServer(HomeCacheIdentity.home(profile, "another-account")))
    }
}
