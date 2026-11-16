package io.github.novatv.play.data

import io.github.novatv.play.sports.OfficialApps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrmLicencesTest {
    private val t = "inputstream.adaptive.license_type"
    private val k = "inputstream.adaptive.license_key"

    @Test fun clearKeyHexPairsBecomeLicenceJson() {
        val l = assertNotNull(DrmLicences.fromKodiProps(mapOf(t to "clearkey", k to "feedf00deedeadbeeff0baadf00dd00d:00112233445566778899aabbccddeeff")))
        assertTrue(l.localKeys)
        assertTrue("\"kid\":\"_u3wDe7erb7v8Lqt8A3QDQ\"" in l.key, l.key)
        assertTrue("\"k\":\"ABEiM0RVZneImaq7zN3u_w\"" in l.key, l.key)
    }

    @Test fun licenceServersKeepTheirHeaders() {
        val l = assertNotNull(DrmLicences.fromKodiProps(mapOf(t to "com.widevine.alpha", k to "https://lic.example/wv|User-Agent=Nova&Referer=https%3A%2F%2Fx.example%2F")))
        assertEquals(DrmLicences.WIDEVINE, l.type)
        assertEquals("https://lic.example/wv", l.key)
        assertEquals(mapOf("User-Agent" to "Nova", "Referer" to "https://x.example/"), l.headers)
        assertFalse(l.localKeys)
    }

    @Test fun unusableLicencesAreIgnored() {
        assertNull(DrmLicences.fromKodiProps(mapOf(t to "com.microsoft.playready", k to "https://pr.example")))
        assertNull(DrmLicences.fromKodiProps(mapOf(t to "clearkey", k to "nothex:1234")))
        assertNull(DrmLicences.fromKodiProps(emptyMap()))
    }

    @Test fun licencesSurviveTheChannelCache() {
        val l = DrmLicences.Licence(DrmLicences.WIDEVINE, "https://lic.example", mapOf("Referer" to "https://x/"))
        assertEquals(l, DrmLicences.decode(DrmLicences.encode(l)))
        assertNull(DrmLicences.decode("stalker cmd"))
    }

    @Test fun broadcastersNameTheirApps() {
        val names = OfficialApps.forBroadcasts(listOf("Amazon Prime Video", "FanCode", "Willow TV", "Maximum TV")).map { it.name }
        assertEquals(listOf("Prime Video", "FanCode", "Willow"), names)
        assertTrue(OfficialApps.matches("sky sports main event", "sky sports"))
        assertFalse(OfficialApps.matches("skyline", "sky"))
    }
}
