package com.example.data.remote

import android.content.Context
import android.util.AtomicFile
import android.util.LruCache
import com.example.BuildConfig
import com.example.data.model.TrackLyrics
import com.example.performance.AppDispatchers
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-demand text enrichment. Originals and every source timestamp remain authoritative. */
internal class LyricsTextTransformer(
    private val directory: () -> File?,
    client: OkHttpClient? = null,
    private val now: () -> Long = System::currentTimeMillis
) {
    enum class Mode { ROMANIZE, TRANSLATE }
    sealed interface Result {
        data class Ready(val lyrics: TrackLyrics) : Result
        data object AlreadyRomanized : Result
        data object SameLanguage : Result
        data object Unavailable : Result
    }
    private data class Answer(val texts: List<String>, val language: String)
    private data class Entry(val at: Long, val language: String, val texts: List<String>)
    private val network by lazy { client ?: LyricsProvider.textTransformationClient() }
    private val memory = LruCache<String, Entry>(12)
    private val flights = Array(16) { Mutex() }
    private val requests = Semaphore(2)
    private val diskWrites = Mutex()

    suspend fun transform(lyrics: TrackLyrics, mode: Mode, languageTag: String): Result = withContext(AppDispatchers.Io) {
        val target = if (mode == Mode.ROMANIZE) "en" else targetLanguage(languageTag)
        val texts = withContext(AppDispatchers.CpuBounded) {
            lyrics.lines.map { it.text.trim() }.filter { text -> text.any(Char::isLetter) }.distinct()
        }
        if (texts.isEmpty() || texts.size > 2_000 || texts.sumOf(String::length) > 100_000 ||
            texts.any { it.length > BATCH_SIZE || '\uE100' in it || '\uE101' in it }) return@withContext Result.Unavailable
        if (mode == Mode.ROMANIZE && !withContext(AppDispatchers.CpuBounded) { hasNonLatin(texts) })
            return@withContext Result.AlreadyRomanized
        val key = withContext(AppDispatchers.CpuBounded) {
            val identity = JSONArray().put(lyrics.trackId).put(mode.name).put(if (mode == Mode.ROMANIZE) "Latn" else target)
                .put(JSONArray(texts)).toString()
            MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        flights[(key.hashCode() and Int.MAX_VALUE) % flights.size].withLock {
            val saved = (memory.get(key) ?: read(key))?.takeIf { now() - it.at in 0..CACHE_TTL && it.texts.size == texts.size }
            if (saved != null) {
                memory.put(key, saved)
                return@withLock prepare(lyrics, mode, target, texts, saved)
            }
            val answer = withTimeoutOrNull(40_000L) {
                val batches = withContext(AppDispatchers.CpuBounded) { batch(texts) }
                val initial = fetchBatches(batches, target, mode) ?: return@withTimeoutOrNull null
                var transformed = initial.texts
                val needsNativeScript = withContext(AppDispatchers.CpuBounded) {
                    mode == Mode.TRANSLATE && !sameLanguage(initial.language, target) && predominantlyLatin(texts) &&
                        unchanged(texts, transformed) * 3 >= texts.sumOf(String::length)
                }
                if (needsNativeScript) {
                    // Latin-script Indic lyrics can be detected correctly but left
                    // untranslated. Try native script only when it improves the answer.
                    val source = initial.language.substringBefore('-')
                    if (source != "en" && source.matches(Regex("[a-z]{2,3}"))) {
                        val native = coroutineScope { batches.chunked(2).flatMap { pair -> pair.map { group -> async {
                            nativeScript(payload(group), source)?.let { translated -> request(translated, target, Mode.TRANSLATE, source) }
                                ?.let { split(it.first, group.size)?.let { rows -> Answer(rows, it.second) } }
                        } }.awaitAll() } }
                        if (native.all { it != null }) {
                            val retry = native.filterNotNull().flatMap { it.texts }
                            if (retry.size == texts.size && unchanged(texts, retry) < unchanged(texts, transformed)) transformed = retry
                        }
                    }
                }
                Answer(transformed, initial.language)
            } ?: return@withLock Result.Unavailable
            currentCoroutineContext().ensureActive()
            val unchangedAnswer = withContext(AppDispatchers.CpuBounded) {
                unchanged(texts, answer.texts) == texts.sumOf(String::length)
            }
            if (answer.texts.size != texts.size || answer.language.isBlank() || unchangedAnswer &&
                (mode == Mode.ROMANIZE || !sameLanguage(answer.language, target))) return@withLock Result.Unavailable
            val entry = Entry(now(), answer.language, answer.texts)
            memory.put(key, entry)
            write(key, entry)
            prepare(lyrics, mode, target, texts, entry)
        }
    }

    private suspend fun prepare(original: TrackLyrics, mode: Mode, target: String, texts: List<String>, entry: Entry): Result =
        withContext(AppDispatchers.CpuBounded) {
            if (mode == Mode.TRANSLATE && sameLanguage(entry.language, target)) return@withContext Result.SameLanguage
            val mapped = texts.zip(entry.texts).toMap()
            Result.Ready(original.copy(lines = original.lines.map { line ->
                val addition = mapped[line.text.trim()]?.takeIf { it.isNotBlank() && it != line.text.trim() }
                if (mode == Mode.ROMANIZE) line.copy(romanization = addition, translation = null)
                else line.copy(translation = addition, romanization = null)
            }, hasRomanization = mode == Mode.ROMANIZE, hasTranslation = mode == Mode.TRANSLATE))
        }

    private suspend fun fetchBatches(batches: List<List<String>>, target: String, mode: Mode): Answer? = coroutineScope {
        // At most two active HTTP calls, including retries; no coroutine per lyric line.
        val answers = batches.chunked(2).flatMap { pair -> pair.map { group -> async {
            request(payload(group), target, mode)?.let { (body, language) -> split(body, group.size)?.let { Answer(it, language) } }
        } }.awaitAll() }
        if (answers.any { it == null }) return@coroutineScope null
        val complete = answers.filterNotNull()
        val language = complete.groupBy { it.language }.maxByOrNull { (_, group) -> group.sumOf { it.texts.sumOf(String::length) } }?.key.orEmpty()
        Answer(complete.flatMap { it.texts }, language)
    }

    private fun batch(texts: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var size = 0
        for (text in texts) {
            if (current.isNotEmpty() && size + text.length + 12 > BATCH_SIZE) {
                result += current.toList(); current = mutableListOf(); size = 0
            }
            current += text; size += text.length + 12
        }
        if (current.isNotEmpty()) result += current
        return result
    }
    private fun payload(lines: List<String>) = buildString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append("\n\uE100$index\uE101\n")
            append(line)
        }
    }
    private fun split(text: String, count: Int): List<String>? = text.split(Regex("\uE100[^\uE101]*\uE101"))
        .map(String::trim).takeIf { it.size == count && it.none(String::isBlank) }

    private suspend fun request(payload: String, target: String, mode: Mode, source: String = "auto"): Pair<String, String>? {
        val body = FormBody.Builder().add("client", "dict-chrome-ex").add("sl", source).add("tl", target)
            .add("dt", if (mode == Mode.ROMANIZE) "rm" else "t").add("q", payload).build()
        val response = send("https://translate.googleapis.com/translate_a/single", body) ?: return null
        return withContext(AppDispatchers.CpuBounded) {
            runCatching {
                val json = JSONArray(response)
                val segments = json.getJSONArray(0)
                val column = if (mode == Mode.ROMANIZE) 3 else 0
                val value = buildString { for (i in 0 until segments.length()) {
                    val part = segments.optJSONArray(i)?.opt(column) as? String
                    if (part != null) append(part)
                } }
                val detected = (json.opt(2) as? String).orEmpty()
                (value to detected).takeIf { value.isNotBlank() && detected.matches(Regex("[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*")) }
            }.getOrNull()
        }
    }
    private suspend fun nativeScript(text: String, source: String): String? {
        val body = FormBody.Builder().add("text", text).add("itc", "$source-t-i0-und").add("num", "1")
            .add("cp", "0").add("cs", "1").add("ie", "utf-8").add("oe", "utf-8").build()
        val response = send("https://inputtools.google.com/request", body) ?: return null
        return withContext(AppDispatchers.CpuBounded) { runCatching {
            val root = JSONArray(response)
            if (root.optString(0) != "SUCCESS") null else root.getJSONArray(1).getJSONArray(0).getJSONArray(1).getString(0).takeIf(String::isNotBlank)
        }.getOrNull() }
    }
    private suspend fun send(url: String, body: FormBody): String? = requests.withPermit {
        try {
            val request = Request.Builder().url(url).header("User-Agent", "XtremePlayer/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/json").post(body).build()
            suspendCancellableCoroutine { continuation ->
                val call = network.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        val result = response.use { runCatching {
                            if (!it.isSuccessful) throw IOException("Lyrics text service HTTP ${it.code}")
                            val source = it.body.source()
                            source.request(1_000_001L)
                            if (source.buffer.size > 1_000_000L) throw IOException("Lyrics text response too large")
                            source.readUtf8()
                        } }
                        if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                    }
                })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { null }
    }

    private suspend fun read(key: String): Entry? = try {
        val file = directory()?.let { File(it, "$key.json") }
        if (file == null || !file.isFile || file.length() > 512_000) null else {
            val body = AtomicFile(file).openRead().bufferedReader().use { it.readText() }
            withContext(AppDispatchers.CpuBounded) {
                val json = JSONObject(body); val rows = json.getJSONArray("texts")
                Entry(json.getLong("at"), json.getString("language"), (0 until rows.length()).map(rows::getString))
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private suspend fun write(key: String, entry: Entry) {
        val encoded = withContext(AppDispatchers.CpuBounded) { JSONObject().put("at", entry.at).put("language", entry.language)
            .put("texts", JSONArray(entry.texts)).toString().toByteArray() }
        if (encoded.size > 512_000) return
        diskWrites.withLock {
            try {
                val dir = directory() ?: return@withLock
                if (!dir.isDirectory && !dir.mkdirs()) return@withLock
                val file = AtomicFile(File(dir, "$key.json"))
                val output = file.startWrite()
                try { output.write(encoded); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
                val files = dir.listFiles { candidate -> candidate.extension == "json" }.orEmpty().sortedBy { it.lastModified() }
                var bytes = files.sumOf(File::length)
                files.forEachIndexed { index, old ->
                    if (bytes > 2 * 1024 * 1024 || files.size - index > 64) {
                        val length = old.length()
                        if (old.delete()) bytes -= length
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { /* A cache failure must not hide a usable response. */ }
        }
    }
    private fun hasNonLatin(texts: List<String>) = texts.any { text -> text.codePoints().anyMatch {
        Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
    } }
    private fun predominantlyLatin(texts: List<String>): Boolean {
        var latin = 0; var all = 0
        texts.forEach { text -> text.codePoints().forEach { if (Character.isLetter(it)) {
            all++; if (Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN) latin++
        } } }
        return latin > 0 && latin * 5 >= all * 4
    }
    private fun unchanged(original: List<String>, transformed: List<String>) = original.zip(transformed)
        .sumOf { (a, b) -> if (a.trim().equals(b.trim(), true)) a.length else 0 }

    companion object {
        private const val BATCH_SIZE = 3_500
        private const val CACHE_TTL = 7 * 24 * 60 * 60_000L
        @Volatile private var shared: LyricsTextTransformer? = null
        fun get(context: Context): LyricsTextTransformer = shared ?: synchronized(this) {
            val app = context.applicationContext
            shared ?: LyricsTextTransformer({ File(app.cacheDir, "lyrics_text_v1") }).also { shared = it }
        }
        internal fun targetLanguage(tag: String): String {
            val locale = Locale.forLanguageTag(tag.replace('_', '-'))
            return when (locale.language) {
                "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
                "he" -> "iw"
                "jv" -> "jw"
                "mni" -> "mni-Mtei"
                "fil" -> "tl"
                else -> locale.language.ifBlank { "en" }
            }
        }
        private fun sameLanguage(source: String, target: String): Boolean {
            if (source.startsWith("zh", true) || target.startsWith("zh", true)) return source.equals(target, true)
            return targetLanguage(source) == targetLanguage(target)
        }
    }
}
