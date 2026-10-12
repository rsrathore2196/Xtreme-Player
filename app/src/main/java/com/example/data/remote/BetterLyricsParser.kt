package com.example.data.remote

import android.util.Xml
import com.example.data.model.LyricLine
import com.example.data.model.LyricSyllable
import com.example.data.model.LyricWord
import com.example.data.model.SongIdentity
import com.example.data.model.TrackLyrics
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Parse XML structure, not tag-order-dependent regexes: adjacent spans may be syllables of one word. */
object BetterLyricsParser {
    private data class Node(val name: String, val attributes: Map<String, String>, val children: MutableList<Any> = mutableListOf())
    private data class Fragment(val text: String, val start: Long, val end: Long, val reliable: Boolean)

    fun parseTtmlTimestamp(raw: String): Long {
        val value = raw.trim()
        val multiplier = when {
            value.endsWith("ms") -> 1.0
            value.endsWith("h") -> 3_600_000.0
            value.endsWith("m") -> 60_000.0
            else -> 1_000.0
        }
        val numeric = value.removeSuffix("ms").removeSuffix("s").removeSuffix("h").removeSuffix("m")
        val parts = numeric.split(':')
        val millis = if (parts.size > 1) parts.fold(0.0) { time, part -> time * 60 + (part.toDoubleOrNull() ?: return 0L) } * 1000
            else (numeric.toDoubleOrNull() ?: return 0L) * multiplier
        return if (millis.isFinite()) millis.toLong().coerceAtLeast(0L) else 0L
    }

    fun parseTtml(identity: SongIdentity, ttml: String): TrackLyrics? {
        return try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(StringReader(ttml))
            }
            val root = Node("root", emptyMap())
            val stack = mutableListOf(root)
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.DOCDECL -> return null
                    XmlPullParser.START_TAG -> {
                        if (stack.size > 64) return null
                        val node = Node(parser.name, (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) })
                        stack.last().children.add(node)
                        stack.add(node)
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> parser.text?.let { stack.last().children.add(it) }
                    XmlPullParser.END_TAG -> if (stack.size > 1) stack.removeAt(stack.lastIndex)
                }
                parser.nextToken()
            }
            fun nodes(node: Node): List<Node> = listOf(node) + node.children.filterIsInstance<Node>().flatMap(::nodes)
            fun plain(node: Node): String = node.children.joinToString("") { if (it is Node) plain(it) else it.toString() }
            val all = nodes(root)
            // Only compare an explicit song duration; the last vocal may precede a long outro.
            val bodyDuration = all.firstOrNull { it.name == "body" }?.attributes?.get("dur")?.let(::parseTtmlTimestamp)
            val targetDuration = identity.durationMs ?: 0L
            if (bodyDuration != null && bodyDuration > 30_000 && targetDuration > 30_000) {
                val difference = kotlin.math.abs(bodyDuration - targetDuration)
                if (difference > 45_000 && difference.toDouble() / targetDuration > 0.25) return null
            }
            val language = all.firstNotNullOfOrNull { it.attributes["lang"] }
            val lines = all.filter { it.name == "p" && it.attributes.containsKey("begin") }.mapNotNull { paragraph ->
                val start = parseTtmlTimestamp(paragraph.attributes.getValue("begin"))
                val explicitEnd = paragraph.attributes["end"]?.let(::parseTtmlTimestamp)
                    ?: paragraph.attributes["dur"]?.let { start + parseTtmlTimestamp(it) }
                val fragments = mutableListOf<Fragment>()
                var translation: String? = null
                var romanization: String? = null
                fun collect(node: Node, inheritedStart: Long, inheritedEnd: Long?, timed: Boolean) {
                    val role = node.attributes["role"].orEmpty()
                    if (role.contains("translation")) { translation = plain(node).trim(); return }
                    if (role.contains("roman") || node.name == "rt") { romanization = plain(node).trim(); return }
                    if (role.contains("bg")) return // Background voices must not advance the lead vocal's highlight.
                    val begin = node.attributes["begin"]?.let(::parseTtmlTimestamp) ?: inheritedStart
                    val end = node.attributes["end"]?.let(::parseTtmlTimestamp)
                        ?: node.attributes["dur"]?.let { begin + parseTtmlTimestamp(it) } ?: inheritedEnd
                    val hasTiming = timed || (node.name == "span" && node.attributes.containsKey("begin") && end != null && end > begin)
                    node.children.forEach { child ->
                        if (child is Node) {
                            if (child.name == "br") fragments.add(Fragment(" ", begin, end ?: begin, false))
                            else collect(child, begin, end, hasTiming)
                        } else fragments.add(Fragment(child.toString(), begin, end ?: begin, hasTiming))
                    }
                }
                collect(paragraph, start, explicitEnd, false)
                val text = fragments.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()
                if (text.isEmpty()) return@mapNotNull null
                val words = mutableListOf<LyricWord>()
                val pieces = mutableListOf<LyricSyllable>()
                val wordText = StringBuilder()
                fun finishWord() {
                    if (wordText.isEmpty()) return
                    val begin = pieces.minOf { it.timestampMs }
                    val end = pieces.maxOf { it.endTimeMs }
                    words.add(LyricWord(begin, (end - begin).coerceAtLeast(0), wordText.toString(), pieces.toList(),
                        isReliableTiming = pieces.all { it.isReliableTiming } && end > begin))
                    pieces.clear(); wordText.clear()
                }
                fragments.forEach { fragment ->
                    val tokens = Regex("\\s+|\\S+").findAll(fragment.text).toList()
                    val multipleWords = tokens.count { !it.value.isBlank() } > 1
                    tokens.forEach { token ->
                        if (token.value.isBlank()) finishWord() else {
                            wordText.append(token.value)
                            pieces.add(LyricSyllable(fragment.start, (fragment.end - fragment.start).coerceAtLeast(0),
                                token.value, fragment.reliable && !multipleWords))
                        }
                    }
                }
                finishWord()
                // Partial/missing word timing cannot be presented as accurate vocal alignment.
                val reliableWords = words.takeIf { it.isNotEmpty() && it.all(LyricWord::isReliableTiming) }.orEmpty()
                val end = explicitEnd ?: reliableWords.maxOfOrNull { it.endTimeMs } ?: start
                LyricLine(start, text, (end - start).coerceAtLeast(0), reliableWords, translation, romanization,
                    agent = paragraph.attributes["agent"], isReliableDuration = end > start)
            }.sortedBy { it.timestampMs }
            if (lines.isEmpty()) null else SyllableSynthesizer.enrichLyrics(TrackLyrics(
                trackId = identity.trackId.orEmpty(), title = identity.title, artist = identity.artist.orEmpty(),
                isSynced = true, lines = lines, provider = "BetterLyrics", language = language,
                hasWordTiming = lines.any { it.words.isNotEmpty() },
                hasTranslation = lines.any { !it.translation.isNullOrBlank() },
                hasRomanization = lines.any { !it.romanization.isNullOrBlank() },
                songWriters = all.filter { it.name == "songwriter" }.map { plain(it).trim() }.distinct()
            ))
        } catch (_: Exception) { null }
    }
}
