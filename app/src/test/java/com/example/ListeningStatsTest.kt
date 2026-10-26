package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListeningStatsTest {
    private val song = MusicTrack("yt_abcdefghijk", "Song", "Primary, Guest", "Album", 120_000, "https://art/cover", "",
        primaryArtists = listOf("Primary", "Guest"))
    private val start = Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    @Test fun qualificationUsesActualTimeAndNeverCountsSelectionPausesOrStalls() {
        val out = mutableListOf<ListeningSnapshot>()
        val clock = ListeningClock(out::add)
        clock.update(song, false, 0, start, utc)
        assertTrue(out.isEmpty())
        clock.update(song, true, 100_000, start + 100_000, utc)
        clock.update(song, false, 115_000, start + 115_000, utc)
        assertEquals(15_000, out.last().listenedMs)
        clock.update(song, true, 200_000, start + 200_000, utc)
        clock.update(song, true, 215_000, start + 215_000, utc, advancing = false)
        assertEquals(15_000, out.last().listenedMs)
        for (i in 1..3) clock.update(song, true, 215_000L + i * 15_000, start + 215_000 + i * 15_000, utc)
        assertEquals(60_000, out.last().listenedMs)
        assertEquals(1, out.last().days.values.sumOf { it.plays })
        clock.update(song, true, 275_000, start + 275_000, utc)
        assertEquals(1, out.last().days.values.sumOf { it.plays })
    }
    @Test fun thresholdsCoverShortUnknownAndLongTracks() {
        assertEquals(30_000, ListeningClock.threshold(10_000))
        assertEquals(30_000, ListeningClock.threshold(0))
        assertEquals(90_000, ListeningClock.threshold(180_000))
        assertEquals(240_000, ListeningClock.threshold(900_000))
    }
    @Test fun repeatsCountAgainButSourceReplacementAndSeeksDoNotResetTheOccurrence() {
        val out = mutableListOf<ListeningSnapshot>()
        val clock = ListeningClock(out::add)
        val short = song.copy(durationMs = 60_000)
        clock.update(short, true, 0, start, utc)
        clock.update(short, true, 15_000, start + 15_000, utc)
        val id = out.last().occurrence
        clock.update(short.copy(audioUrl = "replacement", source = "JioSaavn"), true, 16_000, start + 16_000, utc)
        assertEquals(id, out.last().occurrence)
        clock.update(short, true, 30_000, start + 30_000, utc, newOccurrence = true)
        assertEquals(1, out.last().days.values.sumOf { it.plays })
        clock.update(short, true, 60_000, start + 60_000, utc)
        assertNotEquals(id, out.last().occurrence)
        assertEquals(1, out.last().days.values.sumOf { it.plays })
    }
    @Test fun delayedCallbacksAreBoundedAndEarlySkipsStillRetainMinutes() {
        val out = mutableListOf<ListeningSnapshot>()
        val clock = ListeningClock(out::add)
        clock.update(song, true, 0, start, utc)
        clock.update(song, true, 300_000, start + 300_000, utc)
        assertEquals(30_000, out.last().listenedMs)
        clock.update(null, false, 305_000, start + 305_000, utc)
        assertEquals(35_000, out.last().listenedMs)
        assertEquals(0, out.last().days.values.sumOf { it.plays })
    }
    @Test fun midnightMonthAndDstBoundariesSplitDurationWithoutDuplicatingPlays() {
        val out = mutableListOf<ListeningSnapshot>()
        val clock = ListeningClock(out::add)
        val midnight = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        val short = song.copy(durationMs = 60_000)
        clock.update(short, true, 0, midnight - 20_000, utc)
        clock.update(short, true, 30_000, midnight + 10_000, utc)
        assertEquals(ListeningDay(20_000, 0), out.last().days["2026-10-31"])
        assertEquals(ListeningDay(10_000, 1), out.last().days["2026-11-01"])
        val ny = ZoneId.of("America/New_York")
        val dst = ZonedDateTime.of(2026, 11, 1, 1, 59, 50, 0, ny).toInstant().toEpochMilli()
        val second = ListeningClock(out::add)
        second.update(short, true, 0, dst, ny)
        second.update(short, true, 30_000, dst + 30_000, ny)
        assertEquals(ListeningDay(30_000, 1), out.last().days["2026-11-01"])
    }
    @Test fun calendarRangesCrossMonthsAndIsrcIdentityDoesNotMergeAmbiguousSongs() {
        assertEquals("2026-09-28" to "2026-10-05", ListeningPeriod.WEEKLY.bounds(LocalDate.of(2026, 10, 1)))
        assertEquals("2026-10" to "2026-11", ListeningPeriod.MONTHLY.bounds(LocalDate.of(2026, 10, 26)))
        assertEquals("2026-01" to "2027-01", ListeningPeriod.YEARLY.bounds(LocalDate.of(2026, 10, 26)))
        assertNotEquals(ListeningClock.songKey(song), ListeningClock.songKey(song.copy(id = "other")))
        assertEquals(ListeningClock.songKey(song.copy(isrc = "USABC2600001")),
            ListeningClock.songKey(song.copy(id = "other", isrc = "US-ABC-26-00001")))
    }
    @Test fun checkpointsAreIdempotentAndDailyMonthlyArtistTotalsStayConsistent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = MusicDatabase.createInMemoryDatabase(context)
        val repository = ListeningStatsRepository(context, db)
        try {
            val first = ListeningSnapshot("occurrence", song, mapOf("2026-10-31" to ListeningDay(20_000), "2026-11-01" to ListeningDay(40_000, 1)), 60_000, false)
            repository.record(first); repository.record(first); repository.flush()
            assertEquals(1, db.listeningEventDao().getActualPlays().size)
            val rows = db.listeningStatsDao().all()
            assertEquals(8, rows.size)
            assertTrue(rows.filter { it.kind == "artist" }.all { it.artist == "Primary" })
            assertEquals(60_000, rows.filter { it.kind == "song" && it.bucket.length == 10 }.sumOf { it.listenedMs })
            assertEquals(60_000, rows.filter { it.kind == "song" && it.bucket.length == 7 }.sumOf { it.listenedMs })
            val week = repository.summary(ListeningPeriod.WEEKLY, LocalDate.of(2026, 11, 1))
            assertEquals("Calendar weeks include daily records from both months", 60_000, week.millis)
            assertEquals(1, week.plays)
            val october = repository.summary(ListeningPeriod.MONTHLY, LocalDate.of(2026, 10, 31))
            assertEquals(20_000, october.millis); assertEquals(0, october.plays); assertTrue(october.songs.isEmpty())
            val lifetime = repository.summary(ListeningPeriod.LIFETIME)
            assertEquals(60_000, lifetime.millis); assertEquals(1, lifetime.plays)
            val next = first.copy(occurrence = "repeat", days = mapOf("2026-11-01" to ListeningDay(60_000, 1)))
            repository.record(next); repository.flush()
            assertEquals(120_000, repository.summary(ListeningPeriod.LIFETIME).millis)
            assertEquals(2, repository.summary(ListeningPeriod.LIFETIME).plays)
            assertEquals(2, db.listeningEventDao().getActualPlays().size)
            repository.record(next.copy(days = mapOf("2026-11-01" to ListeningDay(15_000)), listenedMs = 15_000))
            repository.record(next); repository.flush()
            assertEquals("An older checkpoint cannot roll back its persisted watermark", 120_000,
                repository.summary(ListeningPeriod.LIFETIME).millis)
            assertEquals(2, repository.summary(ListeningPeriod.LIFETIME).plays)
            val backup = ListeningStatsBackup.decode(ListeningStatsBackup.encode(db.listeningStatsDao().all()))
            ListeningStatsBackup.merge(db.listeningStatsDao(), backup)
            ListeningStatsBackup.merge(db.listeningStatsDao(), backup)
            repository.invalidate()
            assertEquals(120_000, repository.summary(ListeningPeriod.LIFETIME).millis)
            assertEquals(2, repository.summary(ListeningPeriod.LIFETIME).plays)
        } finally { repository.close(); db.close() }
    }
    @Test fun verifiedIsrcEnrichmentMergesCountsWithoutLosingCatalogMetadataOrFavorites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = MusicDatabase.createInMemoryDatabase(context)
        val repository = ListeningStatsRepository(context, db)
        try {
            val original = song.copy(isLiked = true, language = "Punjabi", genre = "Pop", writer = "Writer", audioUrl = "original-source")
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(original))
            val day = mapOf("2026-10-26" to ListeningDay(30_000))
            repository.record(ListeningSnapshot("one", song, day, 30_000, false)); repository.flush()
            val withCode = song.copy(isrc = "USABC2600001", durationMs = 120_000)
            repository.record(ListeningSnapshot("one", withCode, mapOf("2026-10-26" to ListeningDay(60_000, 1)), 60_000, false))
            repository.flush()
            val otherProvider = withCode.copy(id = "other-provider")
            repository.record(ListeningSnapshot("two", otherProvider, mapOf("2026-10-26" to ListeningDay(60_000, 1)), 60_000, false))
            repository.flush()
            val summary = repository.summary(ListeningPeriod.LIFETIME)
            assertEquals(1, summary.songs.size)
            assertEquals(120_000, summary.millis); assertEquals(2, summary.plays)
            val restored = db.musicDao().getTrackById(song.id)!!.toMusicTrack()
            assertTrue(restored.isLiked); assertEquals("Punjabi", restored.language)
            assertEquals("Pop", restored.genre); assertEquals("original-source", restored.audioUrl)
            assertEquals("USABC2600001", restored.isrc)
            val cached = repository.summary(ListeningPeriod.MONTHLY, LocalDate.of(2026, 9, 5))
            repository.record(ListeningSnapshot("two", otherProvider, mapOf("2026-10-26" to ListeningDay(75_000, 1)), 75_000, false))
            repository.flush()
            assertSame("Unchanged periods retain their prepared summary", cached,
                repository.summary(ListeningPeriod.MONTHLY, LocalDate.of(2026, 9, 5)))
        } finally { repository.close(); db.close() }
    }

    @Test fun restoringAPreIsrcBackupCannotReintroduceAlreadyMigratedListeningTotals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = MusicDatabase.createInMemoryDatabase(context)
        val repository = ListeningStatsRepository(context, db)
        try {
            val day = "2026-10-26"
            repository.record(ListeningSnapshot("same-play", song, mapOf(day to ListeningDay(60_000, 1)), 60_000, false))
            repository.flush()
            val oldBackup = db.listeningStatsDao().all()
            repository.record(ListeningSnapshot("same-play", song.copy(isrc = "USABC2600001"),
                mapOf(day to ListeningDay(90_000, 1)), 90_000, false))
            repository.flush()
            ListeningStatsBackup.merge(db.listeningStatsDao(), oldBackup)
            repository.invalidate()
            assertEquals("An old catalog identity must not duplicate its migrated minutes", 90_000L,
                repository.summary(ListeningPeriod.LIFETIME).millis)
            assertEquals(1L, repository.summary(ListeningPeriod.LIFETIME).plays)
        } finally { repository.close(); db.close() }
    }
    @Test fun statsCheckpointsDoNotForceExtraHomeHistoryRefreshes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = MusicDatabase.createInMemoryDatabase(context)
        val repository = ListeningStatsRepository(context, db)
        suspend fun record(millis: Long, playing: Boolean = true) {
            repository.record(ListeningSnapshot("session", song, mapOf("2026-10-26" to ListeningDay(millis)), millis, false, playing))
            repository.flush()
        }
        try {
            record(15_000)
            record(20_000)
            assertEquals(20_000, repository.summary(ListeningPeriod.LIFETIME).millis)
            assertEquals(15_000, db.listeningEventDao().getActualPlays().single().playedDurationMs)
            record(30_000)
            assertEquals("The existing meaningful-play milestone still publishes promptly", 30_000,
                db.listeningEventDao().getActualPlays().single().playedDurationMs)
            record(45_000)
            assertEquals(30_000, db.listeningEventDao().getActualPlays().single().playedDurationMs)
            record(50_000, playing = false)
            assertEquals("Pausing flushes the final listening duration", 50_000,
                db.listeningEventDao().getActualPlays().single().playedDurationMs)
        } finally { repository.close(); db.close() }
    }
    @Test fun occurrenceIdentityIsSharedAndWallClockChangesCannotInflateListening() {
        val out = mutableListOf<ListeningSnapshot>()
        val clock = ListeningClock(out::add, ListeningSessions::publish)
        clock.update(song, true, 0, start, utc)
        val occurrence = ListeningSessions.forTrack(song.id)
        assertNotNull(occurrence)
        clock.update(song, true, 15_000, start + 7 * 86_400_000L, utc)
        assertEquals(15_000, out.last().listenedMs)
        assertEquals(occurrence, out.last().occurrence)
        clock.update(song, true, 30_000, start + 7 * 86_400_000L + 15_000, utc, newOccurrence = true)
        assertNotEquals(occurrence, ListeningSessions.forTrack(song.id))
        clock.update(null, false, 30_000, start + 7 * 86_400_000L + 15_000, utc)
        assertNull(ListeningSessions.forTrack(song.id))
    }
    @Test fun rankingUsesPlayCountsThenDurationAndOnlyFiveEntries() {
        val rows = (1..8).map { i -> ListeningTotal("o", "2026-10", "song", "$i", "$i", "Song $i", "Artist $i", "", "", 120_000, "", i * 1000L, if (i == 2) 3 else 1) }
        val result = ListeningSummary.from(rows, rows)
        assertEquals(listOf("2", "8", "7", "6", "5"), result.songs.map { it.identity })
        assertEquals(listOf("8", "7", "6", "5", "4"), result.artists.map { it.identity })
    }
}
