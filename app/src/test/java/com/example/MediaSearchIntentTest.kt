package com.example

import android.app.Application
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.example.playback.resumeMediaSearchPlayback
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class MediaSearchIntentTest {
    @Test fun ordinaryLaunchesNeverBecomePlaybackRequests() {
        assertNull(Intent(Intent.ACTION_MAIN).putExtra(SearchManager.QUERY, "Song").mediaPlaybackSearchQuery())
    }
    @Test fun voiceQueriesAndStructuredCreditsKeepTheirOriginalText() {
        val request = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        assertEquals("Song Artist", request.putExtra(SearchManager.QUERY, " Song Artist ").mediaPlaybackSearchQuery())
        request.removeExtra(SearchManager.QUERY)
        request.putExtra(MediaStore.EXTRA_MEDIA_TITLE, "Song").putExtra(MediaStore.EXTRA_MEDIA_ARTIST, "Artist")
        assertEquals("Song Artist", request.mediaPlaybackSearchQuery())
    }
    @Test fun emptyRequestsResumeAndLongQueriesStayBounded() {
        val request = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        assertEquals("", request.mediaPlaybackSearchQuery())
        assertEquals(512, request.putExtra(SearchManager.QUERY, "a".repeat(2048)).mediaPlaybackSearchQuery()!!.length)
    }
    @Test fun restoredDispatchedRequestsNeverReplayAnOldSongButNewRequestsStillWork() {
        val request = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, "Previous song")
        assertNull(request.mediaPlaybackSearchQuery(alreadyDispatched = true))
        assertEquals("Previous song", request.mediaPlaybackSearchQuery())
        request.removeExtra(SearchManager.QUERY)
        assertNull(request.mediaPlaybackSearchQuery(alreadyDispatched = true))
    }
    @Suppress("DEPRECATION")
    @Test fun voiceSearchResolvesToTheExistingPlayerActivity() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val request = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(app.packageName)
        val result = app.packageManager.resolveActivity(request, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(MainActivity::class.java.name, result?.activityInfo?.name)
    }

    @Test fun resumeNeverTogglesPauseReplacesTheQueueOrSeeks() {
        listOf(Player.STATE_READY, Player.STATE_BUFFERING).forEach { state ->
            assertEquals(listOf("play"), resumeCommands(state, itemCount = 1))
        }
    }

    @Test fun resumePreparesOnlyStoppedPlayersAndDoesNotStartAnEmptyQueue() {
        listOf(Player.STATE_IDLE, Player.STATE_ENDED).forEach { state ->
            assertEquals(listOf("prepare", "play"), resumeCommands(state, itemCount = 1))
        }
        assertEquals(emptyList<String>(), resumeCommands(Player.STATE_IDLE, itemCount = 0))
    }

    private fun resumeCommands(state: Int, itemCount: Int): List<String> {
        val commands = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getMediaItemCount" -> itemCount
                "getPlaybackState" -> state
                "play", "prepare" -> { commands += method.name; null }
                else -> throw AssertionError("Unexpected player operation: ${method.name}")
            }
        } as Player
        resumeMediaSearchPlayback(player)
        return commands
    }
}
