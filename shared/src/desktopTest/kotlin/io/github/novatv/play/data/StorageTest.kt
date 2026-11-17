package io.github.novatv.play.data

import okio.Path.Companion.toOkioPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageTest {
    @Test fun leftoverDownloadsGoButFilesInUseStay() {
        val dir = kotlin.io.path.createTempDirectory("nova").toFile()
        val old = System.currentTimeMillis() - 3 * 3600_000L
        val stale = java.io.File(dir, "dl_1.tmp").apply { writeText("x"); setLastModified(old) }
        val busy = java.io.File(dir, "dl_2.txt").apply { writeText("x") }
        val other = java.io.File(dir, "last-crash.txt").apply { writeText("x"); setLastModified(old) }
        val upd = java.io.File(dir, "updates").apply { mkdirs() }
        val oldUpdate = java.io.File(upd, "NovaPlay-1.9.2.apk").apply { writeText("x"); setLastModified(old - 86_400_000L) }
        val fresh = java.io.File(upd, "NovaPlay-1.9.4.apk").apply { writeText("x") }
        assertEquals(2, Storage.cleanUp(dir.toOkioPath(), System.currentTimeMillis()))
        assertFalse(stale.exists()); assertFalse(oldUpdate.exists())
        assertTrue(busy.exists()); assertTrue(other.exists()); assertTrue(fresh.exists())
        dir.deleteRecursively()
    }
}
