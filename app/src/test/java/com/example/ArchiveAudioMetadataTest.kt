package com.example

import com.example.data.remote.ArchiveAudioMetadata
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ArchiveAudioMetadataTest {
    private fun header(title: String = "Song", artist: String = "Artist"): ByteArray {
        val info = ByteArray(34)
        val packed = (96000L shl 44) or (1L shl 41) or (23L shl 36) or (96000L * 183)
        for (i in 0..7) info[10 + i] = (packed ushr ((7 - i) * 8)).toByte()
        fun ByteArrayOutputStream.le(value: Int) { for (i in 0..3) write(value ushr (i * 8) and 255) }
        val comments = ByteArrayOutputStream().apply {
            le(0); le(4)
            for (value in listOf("TITLE=$title", "ARTIST=$artist", "ALBUM=Album", "ISRC=USABC2000001")) {
                val bytes = value.toByteArray(Charsets.UTF_8); le(bytes.size); write(bytes)
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("fLaC".toByteArray()); write(byteArrayOf(0, 0, 0, 34)); write(info)
            write(0x84); write(comments.size ushr 16); write(comments.size ushr 8); write(comments.size)
            write(comments)
        }.toByteArray()
    }
    @Test fun streamInfoAndRealUnicodeTagsAreReadWithoutAudioDecoding() {
        val tags = ArchiveAudioMetadata.flac(header("ਪੰਜਾਬੀ ਗੀਤ", "कलाकार"))!!
        assertEquals("ਪੰਜਾਬੀ ਗੀਤ", tags.title)
        assertEquals("कलाकार", tags.artist)
        assertEquals(183000L, tags.durationMs)
        assertEquals(96000, tags.sampleRate)
        assertEquals(24, tags.bitDepth)
        assertEquals(2, tags.channels)
        assertEquals("USABC2000001", tags.isrc)
    }
    @Test fun incompleteCommentBlocksRetainOnlyVerifiedStreamInfoWithoutInventingTags() {
        val tags = ArchiveAudioMetadata.flac(header().copyOf(48))!!
        assertEquals(183000L, tags.durationMs)
        assertEquals("", tags.title)
        assertEquals("", tags.artist)
    }
    @Test fun filenamesAndMalformedOrNonFlacHeadersCannotEstablishLosslessMetadata() {
        assertNull(ArchiveAudioMetadata.flac("Song.flac Artist 24bit".toByteArray()))
        assertNull(ArchiveAudioMetadata.flac(byteArrayOf(1, 2, 3)))
        val malformed = header().apply { this[7] = 33 }
        assertNull(ArchiveAudioMetadata.flac(malformed))
    }
}
