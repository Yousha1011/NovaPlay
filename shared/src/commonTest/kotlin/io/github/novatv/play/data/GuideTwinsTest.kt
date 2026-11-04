package io.github.novatv.play.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Different guides' ids for one channel come out the same, so a guide that names the teams reaches the playlist's channel. */
class GuideTwinsTest {
    @Test fun tntAcrossGuides() {
        val provider = Epg.norm("tntsports2.uk")
        assertEquals(provider, Epg.norm("TNT.Sports.2.HD.uk"))
        assertEquals(provider, Epg.norm("TNT Sports 2 HD"))
    }

    @Test fun numbersStayApart() {
        assertTrue(Epg.norm("TNT.Sports.1.HD.uk") != Epg.norm("TNT.Sports.10.HD.uk"))
        assertTrue(Epg.norm("tntsports2.uk") != Epg.norm("TNT.Sports.Ultimate.HD.uk"))
    }
}
