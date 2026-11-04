package io.github.novatv.play.data

import io.github.novatv.play.platform.cacheDir
import io.github.novatv.play.platform.nowMs
import okio.FileSystem
import okio.buffer
import okio.use

/**
 * The last time NovaPlay closed on an error: what and where, kept in a file and shown in Settings › About on the next
 * start, so a crash on a device that can't be debugged (a Fire TV Stick, an iPhone) can be read off the screen.
 */
object CrashLog {
    private val file get() = cacheDir() / "last-crash.txt"

    /** Called by each platform's handler for errors nothing else caught, just before the app closes. */
    fun record(t: Throwable, where: String) {
        try {
            val text = buildString {
                append("NovaPlay ${Updates.CURRENT} · $where · ").append(io.github.novatv.play.platform.Time.dateTime(nowMs())).append('\n')
                var e: Throwable? = t
                var depth = 0
                while (e != null && depth < 4) {
                    if (depth > 0) append("Caused by: ")
                    append(e::class.simpleName).append(": ").append(e.message ?: "").append('\n')
                    e.stackTraceToString().lineSequence().drop(1).take(12).forEach { append("  ").append(it.trim()).append('\n') }
                    e = e.cause.takeIf { it !== e }
                    depth++
                }
            }
            FileSystem.SYSTEM.createDirectories(cacheDir())
            FileSystem.SYSTEM.sink(file).buffer().use { it.writeUtf8(text) }
        } catch (_: Throwable) { }
    }

    /** The last crash, or null when there's none. */
    fun last(): String? = try {
        if (FileSystem.SYSTEM.exists(file)) FileSystem.SYSTEM.source(file).buffer().use { it.readUtf8() }.ifBlank { null } else null
    } catch (_: Throwable) { null }

    fun clear() { try { FileSystem.SYSTEM.delete(file) } catch (_: Throwable) { } }
}
