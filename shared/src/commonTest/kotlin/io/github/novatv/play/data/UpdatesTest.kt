package io.github.novatv.play.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spotting a new release and picking this device's file from it. */
class UpdatesTest {
    @Test fun versionsCompareByNumber() {
        assertTrue(Updates.newer("1.3", "1.2"))
        assertTrue(Updates.newer("v1.10", "1.9"))
        assertTrue(Updates.newer("2.0", "1.9.9"))
        assertFalse(Updates.newer("1.2.0", "1.2"))
        assertFalse(Updates.newer("1.1", "1.2"))
        assertFalse(Updates.newer("v1.2", "1.2"))
    }

    private val json = """{"tag_name":"v1.3","html_url":"https://github.com/x/releases/tag/v1.3","body":"Notes",
        "assets":[{"name":"NovaPlay-1.3.apk","browser_download_url":"https://d/tv.apk","size":100},
                  {"name":"NovaPlay-Android-1.3.apk","browser_download_url":"https://d/phone.apk","size":200},
                  {"name":"NovaPlay-Setup-1.3.0.msi","browser_download_url":"https://d/win.msi","size":300}]}"""

    @Test fun eachDeviceGetsItsOwnFile() {
        val tv = Updates.parse(json, Regex("""NovaPlay-[\d.]+\.apk"""))!!
        assertEquals("1.3", tv.version)
        assertEquals("https://d/tv.apk", tv.fileUrl)
        assertEquals(100, tv.size)
        assertEquals("https://d/phone.apk", Updates.parse(json, Regex("""NovaPlay-Android-[\d.]+\.apk"""))!!.fileUrl)
        assertEquals("https://d/win.msi", Updates.parse(json, Regex("""NovaPlay-Setup-[\d.]+\.msi"""))!!.fileUrl)
    }

    @Test fun withoutAFileTheReleasePageOpens() {
        val r = Updates.parse(json, null)!!
        assertNull(r.fileUrl)
        assertEquals("https://github.com/x/releases/tag/v1.3", r.page)
        assertNull(Updates.parse("""{"message":"Not Found"}""", null))
    }
}
