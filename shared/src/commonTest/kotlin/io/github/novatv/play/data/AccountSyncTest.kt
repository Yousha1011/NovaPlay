package io.github.novatv.play.data

import io.github.novatv.play.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which side wins when two devices share an account: setting by setting, the newer change. */
class AccountSyncTest {
    private val keys = setOf("favs.json", "sources", "recentCh")

    private fun remote(vararg e: Triple<String, String?, Long>) =
        JSONObject().also { o -> e.forEach { (k, v, t) -> o.put(k, JSONObject().put("v", v).put("t", t)) } }

    @Test fun firstDeviceSendsEverything() {
        val local = mapOf("favs.json" to "[\"a\"]", "sources" to "[1]")
        val m = AccountSync.merge(keys, JSONObject(), local::get, JSONObject(), now = 500)
        assertTrue(m.arrived.isEmpty())
        val up = assertNotNull(m.upload)
        assertEquals("[\"a\"]", up.optJSONObject("favs.json")?.optString("v"))
        assertEquals(500, up.optJSONObject("sources")?.optLong("t"))
    }

    @Test fun newDeviceTakesTheAccount() {
        // The TV has its own old favourites but never changed them since signing in: the account's win.
        val local = mapOf("favs.json" to "[\"tv\"]")
        val m = AccountSync.merge(keys, JSONObject(), local::get, remote(Triple("favs.json", "[\"pc\"]", 100)), now = 500)
        assertEquals("[\"pc\"]", m.arrived["favs.json"])
        assertNull(m.upload)
        assertEquals(100, m.times.optLong("favs.json"))
    }

    @Test fun newerSideWinsPerSetting() {
        // Favourites changed here at 300 (after the account's 200); sources changed elsewhere at 400 (after ours at 100).
        val times = JSONObject().put("favs.json", 300L).put("sources", 100L)
        val local = mapOf("favs.json" to "[\"mine\"]", "sources" to "[old]")
        val m = AccountSync.merge(keys, times, local::get,
            remote(Triple("favs.json", "[\"theirs\"]", 200), Triple("sources", "[new]", 400)), now = 500)
        assertEquals(mapOf<String, String?>("sources" to "[new]"), m.arrived)
        val up = assertNotNull(m.upload)
        assertEquals("[\"mine\"]", up.optJSONObject("favs.json")?.optString("v"))
        assertEquals("[new]", up.optJSONObject("sources")?.optString("v"))
    }

    @Test fun nothingNewSendsNothing() {
        val times = JSONObject().put("favs.json", 200L)
        val local = mapOf("favs.json" to "[\"a\"]")
        val m = AccountSync.merge(keys, times, local::get, remote(Triple("favs.json", "[\"a\"]", 200)), now = 500)
        assertTrue(m.arrived.isEmpty())
        assertNull(m.upload)
    }

    @Test fun aClearedSettingArrivesAsNull() {
        val local = mapOf("recentCh" to "[\"x\"]")
        val m = AccountSync.merge(keys, JSONObject(), local::get, remote(Triple("recentCh", null, 100)), now = 500)
        assertTrue("recentCh" in m.arrived)
        assertNull(m.arrived["recentCh"])
    }

    @Test fun passwordFingerprints() {
        val salt = okio.ByteString.of(1, 2, 3, 4)
        val a = AccountSync.fingerprint("secret1", salt, 1000)
        assertEquals(a, AccountSync.fingerprint("secret1", salt, 1000))
        assertTrue(a != AccountSync.fingerprint("secret2", salt, 1000))
        assertTrue(a != AccountSync.fingerprint("secret1", okio.ByteString.of(1, 2, 3, 5), 1000))
        assertTrue("secret1" !in a)
        // The same as the server computes it (accounts.js), so accounts made by the apps log in through it.
        assertEquals("8ejUVah3G0lm2Du7eNgygvPHs8dUtaFTaCZQCFKz5eY=", a)
    }

    @Test fun usernames() {
        assertEquals("ali_99", AccountSync.cleanName(" Ali_99 "))
        assertNull(AccountSync.cleanName("ab"))
        assertNull(AccountSync.cleanName("no spaces"))
        assertNull(AccountSync.cleanName("../etc"))
    }
}
