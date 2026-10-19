package com.example

import com.example.data.model.MusicTrack
import com.example.ui.components.playerSingerLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSingerLabelTest {
    private fun track(artist: String, singers: String = "") = MusicTrack(
        "song", "Song", artist, "Album", 180_000, "", "", singers = singers)

    @Test fun performerCreditsTakePriorityOverOtherArtistRolesAndStayUnmodified() {
        val song = track("Composer, Producer, Lead", "Lead, Guest, Third Singer")
        assertEquals("Lead, Guest", playerSingerLabel(song))
        assertEquals("Lead, Guest, Third Singer", song.singers)
        assertEquals("Composer, Producer, Lead", song.artist)
    }

    @Test fun missingSingerCreditsUseOnlyTheFirstTwoDistinctArtists() {
        assertEquals("Lead, Guest", playerSingerLabel(track("Lead, LEAD, Guest, Third")))
        assertEquals("Lead, Guest", playerSingerLabel(track("Lead; Guest; Third", " , ; ")))
    }

    @Test fun featuredAndUnicodeCreditsKeepTheirNamesAndOrder() {
        assertEquals("ਦਿਲਜੀਤ ਦੋਸਾਂਝ, अरिजीत सिंह",
            playerSingerLabel(track("ਦਿਲਜੀਤ ਦੋਸਾਂਝ feat. अरिजीत सिंह & Third")))
        assertEquals("AC/DC, Shankar-Ehsaan-Loy", playerSingerLabel(track("AC/DC, Shankar-Ehsaan-Loy, Third")))
    }

    @Test fun soloAndEmptyMetadataDoNotGainExtraCredits() {
        assertEquals("Solo Singer", playerSingerLabel(track("Solo Singer")))
        assertEquals("", playerSingerLabel(track("", "")))
    }
}
