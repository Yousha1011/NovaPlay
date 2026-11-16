package io.github.novatv.play.data

import io.github.novatv.play.sports.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonsTest {
    private fun side(name: String, short: String, abbr: String) = Side("1", name, short, abbr, null, null)
    private val afg = side("Afghanistan", "Afghanistan", "AFG")
    private val zim = side("Zimbabwe", "Zimbabwe", "ZIM")

    @Test fun titlesLoseTheirEmoji() {
        assertEquals("AFG vs ZIM", Addons.clean("🔜 AFG vs ZIM"))
        assertEquals("PlayZTV Live Events", Addons.clean("⚡PlayZTV Live Events"))
    }

    @Test fun sidesComeFromVsTitles() {
        assertEquals("Afghanistan" to "Zimbabwe", Addons.sides("Afghanistan vs Zimbabwe"))
        assertEquals("PAK" to "SL", Addons.sides("PAK vs SL"))
        assertEquals("Arsenal" to "Chelsea", Addons.sides("Premier League: Arsenal v Chelsea"))
        assertNull(Addons.sides("Star Sports 1 HD"))
    }

    @Test fun sidesMatchByNameOrCode() {
        assertTrue(Addons.same("Afghanistan", afg))
        assertTrue(Addons.same("AFG", afg))
        assertTrue(Addons.same("zimbabwe", zim))
        assertFalse(Addons.same("Zambia", zim))
    }

    @Test fun womensAndYouthSidesAreNotTheMainSide() {
        assertFalse(Addons.same("Zimbabwe Women", zim))
        assertTrue(Addons.same("Zimbabwe Women", side("Zimbabwe Women", "Zimbabwe W", "ZIM-W")))
        assertFalse(Addons.same("Pakistan U19", side("Pakistan", "Pakistan", "PAK")))
    }

    @Test fun drmPlaylistsAreNotPlayable() {
        assertTrue(Http.drm("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"skd://x\"\n#EXTINF:4,\na.ts"))
        assertTrue(Http.drm("#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"x\"\n"))
        assertFalse(Http.drm("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\na.ts"))
        assertFalse(Http.drm("#EXTM3U\n#EXTINF:4,\na.ts"))
    }
}
