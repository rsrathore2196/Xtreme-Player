package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.ListeningStatsRepository
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class ListeningCalendarRolloverTest {
    @Test fun existingObserversRollCalendarPeriodsWithoutNewListeningOrPolling() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cases = listOf(ListeningPeriod.WEEKLY to "2026-11-01", ListeningPeriod.MONTHLY to "2026-10-31", ListeningPeriod.YEARLY to "2026-12-31")
        for ((period, lastDay) in cases) {
            var date = LocalDate.parse(lastDay)
            val next = date.plusDays(1)
            val db = MusicDatabase.createInMemoryDatabase(context)
            val repo = ListeningStatsRepository(context, db, File(context.cacheDir, "calendar-${period.name}.json"), dateProvider = { date })
            val track = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 120_000, "", "")
            val observed = Channel<Long>(Channel.UNLIMITED)
            var collector: Job? = null
            try {
                repo.record(ListeningSnapshot("old", track, mapOf(date.toString() to ListeningDay(60_000, 1)), 60_000, true))
                repo.record(ListeningSnapshot("new", track, mapOf(next.toString() to ListeningDay(90_000, 1)), 90_000, true))
                withTimeout(5_000) { repo.flush() }
                val lifetime = repo.summary(ListeningPeriod.LIFETIME)
                collector = launch(Dispatchers.Default) { repo.observe(period).collect { observed.send(it.millis) } }
                assertEquals(60_000L, withTimeout(5_000) { observed.receive() })
                date = next
                context.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED))
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(90_000L, withTimeout(5_000) { observed.receive() })
                assertSame("Unchanged lifetime summaries retain their cache", lifetime, repo.summary(ListeningPeriod.LIFETIME))
            } finally { collector?.cancelAndJoin(); repo.close(); db.close() }
        }
    }
}
