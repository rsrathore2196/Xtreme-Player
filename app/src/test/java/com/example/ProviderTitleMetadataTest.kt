package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.HomeCardCache
import com.example.data.cache.RecommendationTrackCodec
import com.example.data.cache.SavedHomeCards
import com.example.data.local.MusicDatabase
import com.example.data.model.*
import com.example.data.remote.YouTubeHomeParser
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.MusicRepository
import com.example.playback.*
import com.example.service.PlaybackSessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProviderTitleMetadataTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val original = "Singer - Song (Live Remix) (Official Music Video)"
    private fun selected() = MusicTrack("yt_${NativeFeedFixtures.id(1)}", "Song", "Singer", "Album", 180_000,
        "https://i.ytimg.com/vi/${NativeFeedFixtures.id(1)}/hq720.jpg", "https://r.googlevideo.com/audio",
        source = "YouTube Music", originalProviderTitle = original, playlistEntryId = 42L)

    @Test fun nativeRendererPathsKeepFullTitlesWithoutChangingExistingDisplayFormatting() = runBlocking {
        val responsive = YouTubeMusicApiService.parseAnyRenderer(NativeFeedFixtures.song(1, original))!!
        assertEquals(original, responsive.originalProviderTitle)
        val panel = JSONObject().put("playlistPanelVideoRenderer", JSONObject().put("videoId", NativeFeedFixtures.id(1))
            .put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Singer - Song (Live Remix)"))
                .put(JSONObject().put("text", " (Official Music Video)")))))
        val parsed = YouTubeMusicApiService.parseAnyRenderer(panel)!!
        assertEquals(original, parsed.originalProviderTitle)
        assertEquals("Singer - Song (Live Remix)", parsed.title)
        val twoRow = JSONObject().put("musicTwoRowItemRenderer", JSONObject().put("title", NativeFeedFixtures.text(original))
            .put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", NativeFeedFixtures.id(1)))))
        val feed = YouTubeHomeParser.parseHomeFeed(JSONObject(NativeFeedFixtures.home(NativeFeedFixtures.collectionShelf("Trending", twoRow))))
        val track = feed.shelves.single().tracks.single()
        assertEquals(original, track.originalProviderTitle)
        assertEquals("Singer - Song (Live Remix)", track.title)
        assertTrue(track.primaryArtists.isEmpty())
        val cache = HomeCardCache(app)
        cache.saveHome(SavedHomeCards("IN|anonymous", serverShelves = feed.shelves, serverFetchSucceeded = true))
        assertEquals(original, cache.readServer("IN|anonymous")!!.serverShelves.single().tracks.single().originalProviderTitle)
    }

    @Test fun trackCodecRemainsCompatibleWithRecordsThatLackTheNewMetadata() {
        val track = selected()
        val encoded = RecommendationTrackCodec.encode(listOf(track))
        assertEquals(original, RecommendationTrackCodec.decode(encoded).single().originalProviderTitle)
        val old = JSONArray(encoded)
        old.getJSONObject(0).remove("originalProviderTitle")
        val restored = RecommendationTrackCodec.decode(old.toString()).single()
        assertEquals("", restored.originalProviderTitle)
        assertEquals(track.title, restored.title)
        assertEquals(track.id, restored.id)
    }

    @Test fun mediaItemControllerSourceBookmarkAndDiskSessionKeepOriginalMetadataAndQueueIdentity() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = MusicDatabase.createInMemoryDatabase(app)
        val player = PlaybackManager(app, MusicRepository(db.musicDao(), app))
        try {
            val track = selected()
            val item = track.toMediaItem()
            assertEquals(original, item.mediaMetadata.extras!!.getString("originalProviderTitle"))
            val reconstructed = PlaybackManager::class.java.getDeclaredMethod("controllerTrack", androidx.media3.common.MediaItem::class.java)
                .apply { isAccessible = true }.invoke(player, item) as MusicTrack
            assertEquals(original, reconstructed.originalProviderTitle)
            assertEquals(track.queueIdentity, reconstructed.queueIdentity)
            val origin = SourceTransitionOrigin.capture(track, PlaybackSourcePolicy(AudioQualityCeiling.MAX))!!
            val extras = android.os.Bundle(item.mediaMetadata.extras).apply { SourceTransitionOrigin.write(this, origin) }
            val changed = item.buildUpon().setUri("https://archive.org/download/item/song.flac")
                .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
            assertEquals(original, SourceTransitionOrigin.read(changed, reconstructed)!!.track.originalProviderTitle)
            PlaybackSessionStore.checkpoint(app, PlaybackSessionStore.Snapshot(listOf(changed), 0, 42_000L))
            withTimeout(5_000) {
                while (!withContext(Dispatchers.IO) { runCatching {
                    val file = PlaybackSessionStore::class.java.getDeclaredField("file").apply { isAccessible = true }.get(null) as? android.util.AtomicFile
                    val root = file?.baseFile?.readText()?.let(::JSONObject)
                    root?.optLong("position") == 42_000L && root.getJSONArray("queue").getJSONObject(0).optString("originalProviderTitle") == original
                }.getOrDefault(false) }) delay(10)
            }
            PlaybackSessionStore::class.java.getDeclaredField("latest").apply { isAccessible = true }.set(null, null)
            val restored = PlaybackSessionStore.restore(app)!!
            assertEquals(original, restored.items.single().mediaMetadata.extras!!.getString("originalProviderTitle"))
            assertEquals(42L, restored.items.single().mediaMetadata.extras!!.getLong("playlist_entry_id"))
            assertEquals(track.id, restored.items.single().mediaId)
            assertEquals(42_000L, restored.positionMs)
            assertEquals(changed.localConfiguration!!.uri, restored.items.single().localConfiguration!!.uri)
        } finally { player.release(); db.close(); Dispatchers.resetMain() }
    }
}
