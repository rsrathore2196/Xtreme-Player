package com.example

import android.app.Application
import com.example.data.model.*
import com.example.data.remote.LyricsTextTransformer
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class LyricsTextTransformerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val lyrics = TrackLyrics("song", "Title", "Artist", true, listOf(
        LyricLine(1000, "दिल मेरा", 1200, listOf(LyricWord(1000, 500, "दिल")), isReliableDuration = true),
        LyricLine(4000, "तेरे लिए", 1500), LyricLine(7000, "दिल मेरा", 1000)), provider = "Import Lyrics", language = "hi")
    private fun reply(text: String, language: String = "hi", romanization: Boolean = false): String {
        val segment = JSONArray().put(if (romanization) JSONObject.NULL else text).put(JSONObject.NULL).put(JSONObject.NULL)
            .put(if (romanization) text else JSONObject.NULL)
        return JSONArray().put(JSONArray().put(segment)).put(JSONObject.NULL).put(language).toString()
    }
    private fun client(block: (Request, FormBody) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = block(chain.request(), chain.request().body as FormBody)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody()).build()
    }.build()
    private fun FormBody.value(key: String) = (0 until size).first { name(it) == key }.let(::value)
    private fun translated(text: String) = text.replace("दिल मेरा", "My heart").replace("तेरे लिए", "For you")

    @Test fun translationsAreSubtitlesAndRetainTheOriginalTimingWordsAndProvider() = runBlocking {
        var calls = 0
        val service = LyricsTextTransformer({ temporary.root }, client { request, body ->
            calls++
            assertEquals("translate.googleapis.com", request.url.host)
            assertEquals("dict-chrome-ex", body.value("client")); assertEquals("auto", body.value("sl"))
            assertEquals("t", body.value("dt")); assertEquals("en", body.value("tl"))
            assertTrue(request.header("User-Agent").orEmpty().startsWith("XtremePlayer/"))
            // Repeated chorus lines are sent once, then mapped to every occurrence.
            assertEquals(1, Regex("दिल मेरा").findAll(body.value("q")).count())
            200 to reply(translated(body.value("q")))
        })
        val result = service.transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "en-US") as LyricsTextTransformer.Result.Ready
        assertEquals(1, calls)
        assertEquals(listOf("My heart", "For you", "My heart"), result.lyrics.lines.map { it.translation })
        assertEquals(lyrics.lines.map { it.text }, result.lyrics.lines.map { it.text })
        assertEquals(lyrics.lines.map { it.timestampMs to it.durationMs }, result.lyrics.lines.map { it.timestampMs to it.durationMs })
        assertSame(lyrics.lines.first().words, result.lyrics.lines.first().words)
        assertEquals(lyrics.provider, result.lyrics.provider)
        assertTrue(result.lyrics.isSynced)
        val restored = LyricsTextTransformer({ temporary.root }, client { _, _ -> error("Disk cache must avoid HTTP") })
            .transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "en") as LyricsTextTransformer.Result.Ready
        assertEquals(result.lyrics, restored.lyrics)
    }

    @Test fun romanizationReadsThePhoneticColumnAndDoesNotReplaceActualLyrics() = runBlocking {
        var calls = 0
        val service = LyricsTextTransformer({ temporary.root }, client { _, body ->
            calls++
            assertEquals("rm", body.value("dt"))
            200 to reply(body.value("q").replace("दिल मेरा", "dil mera").replace("तेरे लिए", "tere liye"), romanization = true)
        })
        val result = service.transform(lyrics, LyricsTextTransformer.Mode.ROMANIZE, "en") as LyricsTextTransformer.Result.Ready
        assertEquals(listOf("dil mera", "tere liye", "dil mera"), result.lyrics.lines.map { it.romanization })
        assertEquals(lyrics.lines.map { it.text }, result.lyrics.lines.map { it.text })
        assertEquals(lyrics.lines.map { it.timestampMs }, result.lyrics.lines.map { it.timestampMs })
        assertTrue(service.transform(lyrics, LyricsTextTransformer.Mode.ROMANIZE, "hi") is LyricsTextTransformer.Result.Ready)
        assertEquals(1, calls)
    }

    @Test fun alreadyLatinAndSameLanguageResultsDoNotPretendToConvertLyrics() = runBlocking {
        var calls = 0
        val service = LyricsTextTransformer({ temporary.root }, client { _, body -> calls++; 200 to reply(body.value("q")) })
        assertSame(LyricsTextTransformer.Result.AlreadyRomanized, service.transform(lyrics.copy(lines = listOf(LyricLine(0, "Dil mera"))),
            LyricsTextTransformer.Mode.ROMANIZE, "en"))
        assertEquals(0, calls)
        assertSame(LyricsTextTransformer.Result.SameLanguage, service.transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "hi-IN"))
    }

    @Test fun unchangedForeignLanguageResponsesDoNotEnableTranslationOrCreateASuccessCache() = runBlocking {
        var calls = 0
        val service = LyricsTextTransformer({ temporary.root }, client { _, body ->
            calls++; 200 to reply(if (calls == 1) body.value("q") else translated(body.value("q")))
        })
        assertSame(LyricsTextTransformer.Result.Unavailable,
            service.transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "fr"))
        assertTrue(service.transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "fr") is LyricsTextTransformer.Result.Ready)
        assertEquals(2, calls)
    }

    @Test fun cancellingATrackConversionCancelsItsActualHttpCallAndDoesNotPersistItsReply() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val transport = client { _, body ->
            entered.complete(Unit)
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            200 to reply(translated(body.value("q")))
        }.newBuilder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.set(true) }
        }).build()
        val service = LyricsTextTransformer({ temporary.root }, transport)
        val pending = async { service.transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "en") }
        try {
            withTimeout(5000) { entered.await() }
            pending.cancelAndJoin()
            assertTrue(cancelled.get())
            assertTrue(temporary.root.listFiles().orEmpty().none { it.extension == "json" })
        } finally { release.countDown(); pending.cancel() }
    }

    @Test fun lostLineMarkersAndTransientFailuresAreNotCachedAsSuccessfulConversions() = runBlocking {
        var calls = 0
        val service = LyricsTextTransformer({ temporary.root }, client { _, body ->
            calls++
            when (calls) { 1 -> 429 to "unavailable"; 2 -> 200 to reply("Only one line"); else -> 200 to reply(translated(body.value("q"))) }
        })
        repeat(2) { assertSame(LyricsTextTransformer.Result.Unavailable, service.transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "en")) }
        assertTrue(service.transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "en") is LyricsTextTransformer.Result.Ready)
        assertEquals(3, calls)
    }

    @Test fun cacheReuseKeepsNewClockCorrectionsButChangedLyricsAndExpiryRefresh() = runBlocking {
        var now = 100_000L; var calls = 0
        val service = LyricsTextTransformer({ temporary.root }, client { _, body -> calls++; 200 to reply(translated(body.value("q"))) }, { now })
        service.transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "en")
        val corrected = lyrics.copy(lines = lyrics.lines.map { it.copy(timestampMs = it.timestampMs + 250) })
        val cached = service.transform(corrected, LyricsTextTransformer.Mode.TRANSLATE, "en") as LyricsTextTransformer.Result.Ready
        assertEquals(corrected.lines.map { it.timestampMs }, cached.lyrics.lines.map { it.timestampMs }); assertEquals(1, calls)
        service.transform(lyrics.copy(lines = lyrics.lines + LyricLine(9000, "एक नई पंक्ति")), LyricsTextTransformer.Mode.TRANSLATE, "en")
        assertEquals(2, calls)
        now += 8 * 24 * 60 * 60_000L
        service.transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "en")
        assertEquals(3, calls)
    }

    @Test fun latinScriptLyricsCanRetryThroughNativeScriptWhenThatImprovesTranslation() = runBlocking {
        var nativeRequests = 0; var translations = 0
        val source = lyrics.copy(lines = listOf(LyricLine(1000, "dil mera"), LyricLine(4000, "tere liye")))
        val service = LyricsTextTransformer({ temporary.root }, client { request, body ->
            if (request.url.host == "inputtools.google.com") {
                nativeRequests++; assertEquals("hi-t-i0-und", body.value("itc"))
                val native = body.value("text").replace("dil mera", "दिल मेरा").replace("tere liye", "तेरे लिए")
                200 to JSONArray().put("SUCCESS").put(JSONArray().put(JSONArray().put("input").put(JSONArray().put(native)))).toString()
            } else {
                translations++
                200 to reply(translated(body.value("q")))
            }
        })
        val result = service.transform(source, LyricsTextTransformer.Mode.TRANSLATE, "en") as LyricsTextTransformer.Result.Ready
        assertEquals(listOf("My heart", "For you"), result.lyrics.lines.map { it.translation })
        assertEquals(1, nativeRequests); assertEquals(2, translations)
    }

    @Test fun parallelRequestsShareResultsAndNetworkConcurrencyStaysBounded() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger(); val calls = AtomicInteger()
        val service = LyricsTextTransformer({ temporary.root }, client { _, body ->
            calls.incrementAndGet(); val count = active.incrementAndGet(); peak.accumulateAndGet(count, ::maxOf)
            try { Thread.sleep(30); 200 to reply(body.value("q").replace("ह", "ha")) }
            finally { active.decrementAndGet() }
        })
        val long = lyrics.copy(lines = (0..7).map { LyricLine(it * 1000L, "ह".repeat(1600) + it) })
        val answers = (0..3).map { async { service.transform(long, LyricsTextTransformer.Mode.TRANSLATE, "en") } }.awaitAll()
        assertTrue(answers.all { it is LyricsTextTransformer.Result.Ready })
        assertTrue(peak.get() in 1..2)
        assertEquals(4, calls.get())
    }

    @Test fun languageTargetsPreserveTraditionalChineseAndUseTheDeviceLanguage() {
        assertEquals("en", LyricsTextTransformer.targetLanguage("en-IN"))
        assertEquals("pa", LyricsTextTransformer.targetLanguage("pa-IN"))
        assertEquals("zh-TW", LyricsTextTransformer.targetLanguage("zh-Hant-HK"))
        assertEquals("zh-CN", LyricsTextTransformer.targetLanguage("zh-Hans-CN"))
        assertEquals("mni-Mtei", LyricsTextTransformer.targetLanguage("mni-Mtei"))
        assertEquals("tl", LyricsTextTransformer.targetLanguage("fil"))
    }

    @Test fun changingTheChosenLanguageUsesTheNewTargetAndSeparateCachedSubtitles() = runBlocking {
        val targets = mutableListOf<String>()
        val service = LyricsTextTransformer({ temporary.root }, client { _, body ->
            val target = body.value("tl"); targets.add(target)
            val text = if (target == "fr") body.value("q").replace("दिल मेरा", "Mon cœur").replace("तेरे लिए", "Pour toi")
                else translated(body.value("q"))
            200 to reply(text)
        })
        val english = service.transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "en") as LyricsTextTransformer.Result.Ready
        val french = service.transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "fr") as LyricsTextTransformer.Result.Ready
        assertEquals("My heart", english.lyrics.lines.first().translation)
        assertEquals("Mon cœur", french.lyrics.lines.first().translation)
        assertEquals(lyrics.lines.map { it.timestampMs }, french.lyrics.lines.map { it.timestampMs })
        assertEquals(english, service.transform(lyrics, LyricsTextTransformer.Mode.TRANSLATE, "en"))
        assertEquals(listOf("en", "fr"), targets)
    }
}
