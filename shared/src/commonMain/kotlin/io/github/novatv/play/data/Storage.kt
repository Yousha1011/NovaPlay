package io.github.novatv.play.data

import io.github.novatv.play.platform.cacheDir
import io.github.novatv.play.platform.nowMs
import okio.FileSystem

/**
 * Files the app leaves behind: a guide or playlist download stopped part-way (the app closed, crashed, or the device
 * slept) keeps its temporary copies, often hundreds of MB each, and old updates stay after they're installed. Cleared at
 * every start, before anything downloads.
 */
object Storage {
    fun cleanUp(dir: okio.Path = cacheDir(), now: Long = nowMs()): Int {
        val fs = FileSystem.SYSTEM
        var freed = 0L
        var files = 0
        runCatching {
            for (f in fs.list(dir)) {
                if (!f.name.startsWith("dl_")) continue
                // Untouched for half an hour: no download or import is still using it (another copy of the app may be).
                val m = runCatching { fs.metadata(f) }.getOrNull() ?: continue
                if ((m.lastModifiedAtMillis ?: 0L) > now - 30 * 60_000L) continue
                freed += m.size ?: 0L
                if (runCatching { fs.delete(f) }.isSuccess) files++
            }
        }
        // Updates: the installer has long finished with a file a day old.
        runCatching {
            val upd = dir / "updates"
            if (fs.exists(upd)) for (f in fs.list(upd)) {
                val m = fs.metadata(f)
                if ((m.lastModifiedAtMillis ?: 0L) < now - 86_400_000L) {
                    freed += m.size ?: 0L
                    if (runCatching { fs.delete(f) }.isSuccess) files++
                }
            }
        }
        if (files > 0) Diag.note("Removed $files leftover files (${freed / 1_048_576} MB)")
        return files
    }
}
