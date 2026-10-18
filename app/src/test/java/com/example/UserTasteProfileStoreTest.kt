package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ListeningEventEntity
import com.example.data.model.MusicTrack
import com.example.recommendation.UserTasteProfile
import com.example.recommendation.UserTasteProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class UserTasteProfileStoreTest {
    private val track = MusicTrack("song", "ਜੱਟ", "Artist", "Album", 180_000,
        "https://art", "https://audio", language = "Punjabi", genre = "pop")
    private val event = ListeningEventEntity(id = 10, trackId = "song", sessionId = "session",
        eventType = "ACTUAL_PLAY", timestamp = 1_800_000_000_000, playedDurationMs = 90_000,
        trackDurationMs = 180_000, artistName = "Artist", language = "Punjabi")

    @Test fun completeDerivedSnapshotRoundTripsEveryTasteSignal() {
        val taste = UserTasteProfile.fromEvents(listOf(event), listOf(track), listOf(track), event.timestamp)
        val snapshot = UserTasteProfileStore.Snapshot("IN|Punjabi", "fingerprint", true, taste)
        assertEquals(snapshot, UserTasteProfileStore.decode(UserTasteProfileStore.encode(snapshot).toString(Charsets.UTF_8)))
    }

    @Test fun identityAndAgeMustMatchBeforeSkippingRecomputation() {
        val snapshot = UserTasteProfileStore.Snapshot("IN|Punjabi", "first", true,
            UserTasteProfile(lastUpdatedMs = 1_000))
        assertTrue(snapshot.matches("IN|Punjabi", "first", 2_000))
        assertFalse(snapshot.matches("US|English", "first", 2_000))
        assertFalse(snapshot.matches("IN|Punjabi", "changed", 2_000))
        assertFalse(snapshot.matches("IN|Punjabi", "first", 3_601_001))
        assertFalse(snapshot.matches("IN|Punjabi", "first", 999))
    }

    @Test fun changedMilestonesAndFavoritesInvalidateEvenWhenEventIdIsUnchanged() = runTest {
        suspend fun signature(events: List<ListeningEventEntity> = listOf(event),
            tracks: List<MusicTrack> = listOf(track), favorites: List<MusicTrack> = emptyList(),
            key: String = "IN|Punjabi") = UserTasteProfileStore.fingerprint(events, tracks, favorites, key)
        val initial = signature()
        assertNotEquals(initial, signature(listOf(event.copy(playedDurationMs = 100_000))))
        assertNotEquals(initial, signature(listOf(event.copy(skipped = true))))
        assertNotEquals(initial, signature(listOf(event.copy(completed = true))))
        assertNotEquals(initial, signature(listOf(event.copy(liked = true))))
        assertNotEquals(initial, signature(emptyList()))
        assertNotEquals(initial, signature(favorites = listOf(track)))
        assertNotEquals(initial, signature(tracks = listOf(track.copy(artist = "Corrected Artist"))))
        assertNotEquals(initial, signature(key = "US|English"))
        assertEquals("Artwork changes do not change listening taste", initial,
            signature(tracks = listOf(track.copy(coverUrl = "https://better-art"))))
    }

    @Test fun fingerprintingLargeHistoryHasCooperativeBoundaries() = runTest {
        var checkpoints = 0
        UserTasteProfileStore.fingerprint((0..4999).map { event.copy(id = it.toLong()) },
            listOf(track), emptyList(), "IN|Punjabi") { checkpoints++ }
        assertTrue(checkpoints >= 79)
    }

    @Test fun legacyUnscopedAndMalformedProfilesAreNotTrusted() {
        assertNull(UserTasteProfileStore.decode("not json"))
        assertNull(UserTasteProfileStore.decode("""{"version":1,"isColdStart":false}"""))
        assertNull(UserTasteProfileStore.decode("""{"version":2,"contextKey":"IN|Punjabi"}"""))
        val root = JSONObject(UserTasteProfileStore.encode(UserTasteProfileStore.Snapshot(
            "IN|Punjabi", "signature", true, UserTasteProfile())).toString(Charsets.UTF_8))
        root.put("artistAffinities", JSONObject().put("artist", "bad-number"))
        assertNull(UserTasteProfileStore.decode(root.toString()))
    }

    @Test fun persistedCacheIsReadAsynchronouslyAndCannotCrossProfiles() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val snapshot = UserTasteProfileStore.Snapshot("disk-test|Punjabi", "signature", true,
            UserTasteProfile(lastUpdatedMs = 123))
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            java.io.File(app.filesDir, "user_taste_profile.json").writeBytes(UserTasteProfileStore.encode(snapshot))
        }
        assertEquals(snapshot, UserTasteProfileStore.loadAsync(app, snapshot.contextKey))
        assertEquals(snapshot.profile, UserTasteProfileStore.peek(snapshot.contextKey))
        assertNull(UserTasteProfileStore.peek("other-user|English"))
        assertNull(UserTasteProfileStore.loadAsync(app, "other-user|English"))
    }
}
