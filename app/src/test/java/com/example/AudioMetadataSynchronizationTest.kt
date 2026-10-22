package com.example

import android.app.Application
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.test.core.app.ApplicationProvider
import com.example.playback.StreamQualityBadge
import com.example.service.MusicService
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AudioMetadataSynchronizationTest {
    private val service get() = Robolectric.buildService(MusicService::class.java).get()
    private fun item(uri: String = "https://aac.saavncdn.com/song.mp4", id: String = "online_song", extras: Bundle? = null) =
        MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build()).build()
    private fun aac(rate: Int = 44100, profile: String = "mp4a.40.2") = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC).setCodecs(profile).setAverageBitrate(320000).setSampleRate(rate).build()
    private fun flac(bitrate: Int = 900000, bits: Int = C.ENCODING_PCM_16BIT) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_FLAC).setAverageBitrate(bitrate).setSampleRate(44100)
        .setChannelCount(2).setPcmEncoding(bits).build()
    private fun properties(owner: MusicService, selected: MediaItem, format: Format, source: String, invalid: Boolean = false): Bundle =
        MusicService::class.java.getDeclaredMethod("actualAudioProperties", MediaItem::class.java, Format::class.java,
            StreamQualityBadge::class.java, String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(owner, selected, format, StreamQualityBadge.from(format), source, invalid) as Bundle
    private fun same(owner: MusicService, before: Bundle?, after: Bundle): Boolean =
        MusicService::class.java.getDeclaredMethod("sameAudioProperties", Bundle::class.java, Bundle::class.java)
            .apply { isAccessible = true }.invoke(owner, before, after) as Boolean

    @Test fun headlessActivationAcceptsSupportedYoutubeFallbackWithoutAssumingOpus() {
        val owner = service
        val method = MusicService::class.java.getDeclaredMethod("resolvedFormatMatches",
            com.example.data.model.MusicTrack::class.java, Format::class.java).apply { isAccessible = true }
        val track = com.example.data.model.MusicTrack("online_original", "Song", "Artist", "Album", 180000,
            "cover", "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=160000", qualityBadge = "AAC • 128 kbps")
        fun matches(candidate: com.example.data.model.MusicTrack, format: Format) = method.invoke(owner, candidate, format) as Boolean
        val opus = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS).setCodecs("opus").build()
        assertTrue(matches(track, aac()))
        assertFalse(matches(track.copy(qualityBadge = "Opus • 128 kbps"), aac()))
        assertTrue(matches(track.copy(qualityBadge = "Opus • 128 kbps"), opus))
        val jio = track.copy(audioUrl = "https://aac.saavncdn.com/song_160.mp4")
        assertTrue(matches(jio, aac()))
        assertFalse(matches(jio, opus))
        assertFalse(matches(track.copy(isLossless = true), aac()))
        assertTrue(matches(track.copy(isLossless = true), flac()))
    }

    @Test fun everyActualPropertyIsComparedEvenIfTheVisibleBadgeIsIdentical() {
        val owner = service
        val first = properties(owner, item(), aac(), "JIOSAAVN")
        assertTrue(same(owner, first, Bundle(first)))
        val mutations: List<Bundle.() -> Unit> = listOf(
            { putString("audio_codec", "OPUS") }, { putString("audio_codecs", "mp4a.40.5") },
            { putString("audio_mime_type", MimeTypes.AUDIO_OPUS) }, { putInt("bitrate", 256) },
            { putInt("sample_rate", 48000) }, { putInt("bit_depth", 24) },
            { putBoolean("isLossless", true) }, { putString("media_source", "YOUTUBE") },
            { putBoolean("audio_actual", false) }, { putBoolean("lossless_invalid", true) },
            { remove("bitrate") })
        mutations.forEach { mutate ->
            val changed = Bundle(first).apply(mutate)
            assertEquals(first.getString("qualityBadge"), changed.getString("qualityBadge"))
            assertFalse("A changed verified property must publish", same(owner, first, changed))
        }
        assertFalse(same(owner, null, first))
        assertTrue("Unrelated metadata must not rebuild the audio path",
            same(owner, first, Bundle(first).apply { putString("title", "Updated title") }))
    }

    @Test fun actualSampleRateCodecProfileAndFlacBitrateChangesCannotHideBehindTheBadge() {
        val owner = service
        val first = properties(owner, item(), aac(), "JIOSAAVN")
        for (format in listOf(aac(48000), aac(profile = "mp4a.40.5"))) {
            val changed = properties(owner, item(), format, "JIOSAAVN")
            assertEquals(first.getString("qualityBadge"), changed.getString("qualityBadge"))
            assertFalse(same(owner, first, changed))
        }
        val lossless = properties(owner, item(), flac(), "INTERNET_ARCHIVE")
        val newBitrate = properties(owner, item(), flac(1100000), "INTERNET_ARCHIVE")
        assertEquals(lossless.getString("qualityBadge"), newBitrate.getString("qualityBadge"))
        assertFalse(same(owner, lossless, newBitrate))
    }

    @Test fun unknownFormatFieldsClearStaleValuesInsteadOfVerifyingCatalogEstimates() {
        val owner = service
        val stale = properties(owner, item(), flac(), "INTERNET_ARCHIVE").apply {
            putLong("duration", 180000); putBoolean("lossless_invalid", true)
        }
        val unknown = properties(owner, item(extras = stale), Format.Builder().build(), "JIOSAAVN")
        listOf("audio_codec", "audio_codecs", "audio_mime_type", "bitrate", "sample_rate", "bit_depth", "isLossless", "lossless_invalid")
            .forEach { assertFalse("$it must be unset", unknown.containsKey(it)) }
        assertTrue(unknown.getBoolean("audio_actual"))
        assertEquals(180000, unknown.getLong("duration"))
        val fallback = properties(owner, item(extras = stale), aac(), "JIOSAAVN")
        assertFalse(fallback.getBoolean("isLossless"))
        assertFalse(fallback.containsKey("bit_depth"))
        assertFalse(fallback.containsKey("lossless_invalid"))
        assertEquals("AAC", fallback.getString("audio_codec"))
    }

    @Test fun unknownLosslessBitDepthStaysUnsetAndInvalidAudioClearsItsOldLosslessClaim() {
        val owner = service
        val format = flac(bits = C.ENCODING_INVALID)
        val audio = properties(owner, item(), format, "INTERNET_ARCHIVE")
        assertFalse(audio.containsKey("bit_depth"))
        assertTrue(audio.getBoolean("isLossless"))
        val invalid = properties(owner, item(extras = audio), aac(), "INTERNET_ARCHIVE", invalid = true)
        assertTrue(invalid.getBoolean("lossless_invalid"))
        assertFalse(invalid.getBoolean("isLossless"))
    }

    private class SourceTimeline(val item: MediaItem, val windowUid: String = "window", val periodUid: String = "period") : Timeline() {
        override fun getWindowCount() = 1
        override fun getWindow(index: Int, window: Window, projectionUs: Long) = window.apply {
            uid = windowUid; mediaItem = item; firstPeriodIndex = 0; lastPeriodIndex = 0
        }
        override fun getPeriodCount() = 1
        override fun getPeriod(index: Int, period: Period, setIds: Boolean): Period =
            period.set(if (setIds) periodUid else null, if (setIds) periodUid else null, 0, 180000000, 0)
        override fun getIndexOfPeriod(uid: Any) = if (uid == periodUid) 0 else C.INDEX_UNSET
        override fun getUidOfPeriod(index: Int): Any = periodUid
    }
    @Test fun delayedCallbacksCannotTargetAnotherTrackSourceWindowOrReadingPeriod() {
        val owner = service
        val current = item()
        val timeline = SourceTimeline(current)
        var periodIndex = 0
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> current; "getCurrentTimeline" -> timeline
                "getCurrentMediaItemIndex" -> 0; "getCurrentPeriodIndex" -> periodIndex
                else -> error("Unexpected player call ${method.name}")
            }
        } as ExoPlayer
        fun event(source: Timeline = timeline, period: MediaPeriodId = MediaPeriodId("period", 1L)) =
            EventTime(0L, source, 0, period, 10000L, timeline, 0, MediaPeriodId("period", 1L), 10000L, 0L)
        fun accepted(time: EventTime) = MusicService::class.java.getDeclaredMethod("currentAudioEventItem", ExoPlayer::class.java,
            EventTime::class.java).apply { isAccessible = true }.invoke(owner, player, time)
        assertSame(current, accepted(event()))
        assertNull(accepted(event(SourceTimeline(item(id = "different_track")))))
        assertNull(accepted(event(SourceTimeline(item(uri = "https://archive.org/song.flac")))))
        assertNull(accepted(event(SourceTimeline(current, windowUid = "old-window"))))
        assertNull(accepted(event(period = MediaPeriodId("old-period", 1L))))
        assertNull(accepted(event(period = MediaPeriodId("period", 0L))))
        assertNull(accepted(event(Timeline.EMPTY)))
        periodIndex = C.INDEX_UNSET
        assertNull(accepted(event(period = MediaPeriodId("unknown-period", 1L))))
    }

    @Test fun changedMetadataPublishesOnceWithoutPreparingSeekingOrChangingTheQueueIdentity() {
        val owner = service
        val app = ApplicationProvider.getApplicationContext<Application>()
        val real = ExoPlayer.Builder(app).build()
        val selected = item()
        real.setMediaItem(selected, 12000L)
        var replacements = 0
        var transportCommands = 0
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            if (method.name == "replaceMediaItem") replacements++
            if (method.name in setOf("prepare", "seekTo", "play", "pause", "setMediaItems")) transportCommands++
            method.invoke(real, *(args ?: emptyArray()))
        } as ExoPlayer
        val session = MediaLibrarySession.Builder(owner, player, object : MediaLibrarySession.Callback {}).build()
        fun set(name: String, value: Any?) = MusicService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
        fun publish(item: MediaItem, audio: Bundle) = MusicService::class.java.getDeclaredMethod("updateActualAudioProperties",
            ExoPlayer::class.java, MediaItem::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(owner, player, item, audio, true)
        try {
            set("mediaLibrarySession", session); set("exoPlayer", player)
            val first = properties(owner, selected, aac(), "JIOSAAVN")
            publish(selected, first)
            assertEquals(1, replacements)
            val unchanged = session.sessionExtras
            publish(player.currentMediaItem!!, Bundle(first))
            assertEquals(1, replacements)
            assertEquals(unchanged, session.sessionExtras)
            val changed = properties(owner, player.currentMediaItem!!, aac(48000), "JIOSAAVN")
            assertEquals(first.getString("qualityBadge"), changed.getString("qualityBadge"))
            publish(player.currentMediaItem!!, changed)
            assertEquals(2, replacements)
            assertEquals(48000, session.sessionExtras.getBundle("active_audio")!!.getInt("sample_rate"))
            assertEquals(selected.mediaId, player.currentMediaItem!!.mediaId)
            assertEquals(selected.localConfiguration!!.uri, player.currentMediaItem!!.localConfiguration!!.uri)
            assertEquals(12000L, player.currentPosition)
            assertEquals(1, player.mediaItemCount)
            assertEquals(0, transportCommands)
            // A late result for the previous URI cannot overwrite a source switch.
            real.setMediaItem(item(uri = "https://archive.org/download/album/song.flac"))
            publish(selected, first)
            assertEquals(2, replacements)
            assertEquals(48000, session.sessionExtras.getBundle("active_audio")!!.getInt("sample_rate"))
        } finally {
            set("mediaLibrarySession", null); set("exoPlayer", null)
            session.release(); real.release()
        }
    }
}
