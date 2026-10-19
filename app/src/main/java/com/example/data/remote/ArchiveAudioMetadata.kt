package com.example.data.remote

/** Reads only bounded FLAC metadata, never derives recording identity from a filename. */
internal object ArchiveAudioMetadata {
    data class Tags(val title: String = "", val artist: String = "", val album: String = "",
        val isrc: String = "", val durationMs: Long = 0L, val sampleRate: Int = 0,
        val bitDepth: Int = 0, val channels: Int = 0)

    fun flac(bytes: ByteArray): Tags? {
        var start = 0
        if (bytes.size >= 10 && String(bytes, 0, 3, Charsets.US_ASCII) == "ID3") {
            if ((6..9).any { bytes[it].toInt() and 0x80 != 0 }) return null
            var length = 0
            for (i in 6..9) length = (length shl 7) or (bytes[i].toInt() and 0x7f)
            start = 10 + length + if (bytes[5].toInt() and 0x10 != 0) 10 else 0
        }
        if (start < 0 || start + 4 > bytes.size || String(bytes, start, 4, Charsets.US_ASCII) != "fLaC") return null
        var offset = start + 4
        var info: Tags? = null
        val comments = linkedMapOf<String, String>()
        repeat(64) {
            if (offset + 4 > bytes.size) return merge(info, comments)
            val type = bytes[offset].toInt() and 0x7f
            val last = bytes[offset].toInt() and 0x80 != 0
            val length = ((bytes[offset + 1].toInt() and 255) shl 16) or
                ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
            offset += 4
            if (length > bytes.size - offset) return merge(info, comments)
            if (type == 0) {
                if (length != 34) return null
                var packed = 0L
                for (i in offset + 10 until offset + 18) packed = (packed shl 8) or (bytes[i].toLong() and 255)
                val rate = (packed ushr 44).toInt()
                val bits = ((packed ushr 36) and 31).toInt() + 1
                val channels = ((packed ushr 41) and 7).toInt() + 1
                val samples = packed and 0xfffffffffL
                if (rate !in 8_000..768_000 || bits !in 4..32) return null
                info = Tags(durationMs = samples * 1000L / rate, sampleRate = rate, bitDepth = bits, channels = channels)
            } else if (type == 4) readComments(bytes, offset, offset + length, comments)
            offset += length
            if (last) return merge(info, comments)
        }
        return merge(info, comments)
    }

    private fun merge(info: Tags?, values: Map<String, String>): Tags? = info?.copy(
        title = values["TITLE"].orEmpty().let { title ->
            val version = values["VERSION"].orEmpty()
            if (title.isNotBlank() && version.isNotBlank() && !title.contains(version, true)) "$title ($version)" else title
        }, artist = values["ARTIST"].orEmpty().ifBlank { values["ALBUMARTIST"].orEmpty() },
        album = values["ALBUM"].orEmpty(), isrc = values["ISRC"].orEmpty())

    private fun readComments(bytes: ByteArray, start: Int, end: Int, values: MutableMap<String, String>) {
        var position = start
        fun integer(): Int? {
            if (position + 4 > end) return null
            var result = 0L
            for (i in 0..3) result = result or ((bytes[position++].toLong() and 255) shl (i * 8))
            return result.takeIf { it <= Int.MAX_VALUE }?.toInt()
        }
        val vendor = integer() ?: return
        if (vendor > end - position) return
        position += vendor
        val count = integer()?.coerceAtMost(512) ?: return
        repeat(count) {
            val length = integer() ?: return
            if (length > end - position) return
            val value = String(bytes, position, length, Charsets.UTF_8)
            position += length
            val key = value.substringBefore('=').uppercase(java.util.Locale.ROOT)
            if (key in setOf("TITLE", "ARTIST", "ALBUMARTIST", "ALBUM", "ISRC", "VERSION")) {
                val content = value.substringAfter('=', "").trim()
                if (content.isNotBlank()) values[key] = values[key]?.takeUnless { it == content }
                    ?.let { if (key == "ARTIST") "$it, $content" else it } ?: content
            }
        }
    }
}
