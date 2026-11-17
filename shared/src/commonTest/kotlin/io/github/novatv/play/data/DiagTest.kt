package io.github.novatv.play.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagTest {
    @Test fun addressesKeepOnlyTheirHost() {
        val s = Diag.scrub("open http://bob:pw@panel.example:8080/live/bob/secret/123.ts?token=abc failed")
        assertEquals("open http://panel.example/… failed", s)
    }

    @Test fun loginsAndMacsAreBlanked() {
        val s = Diag.scrub("username=bob&password=hunter2 mac: 00:1A:79:12:34:56 \"token\":\"xyz\"")
        assertFalse("bob" in s || "hunter2" in s || "xyz" in s || "00:1A" in s, s)
        assertTrue("username=***" in s && "password=***" in s, s)
    }

    @Test fun stackTracesStayReadable() {
        val t = "java.lang.OutOfMemoryError: Failed to allocate\n  at io.github.novatv.play.data.Epg.import(Epg.kt:120)"
        assertEquals(t, Diag.scrub(t))
    }
}
