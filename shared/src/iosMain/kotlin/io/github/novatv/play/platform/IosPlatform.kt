package io.github.novatv.play.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSDiacriticInsensitiveSearch
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSLog
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringByFoldingWithOptions
import platform.Foundation.timeIntervalSince1970
import platform.zlib.gzclose
import platform.zlib.gzopen
import platform.zlib.gzread

actual fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual val IO: CoroutineDispatcher get() = Dispatchers.IO

actual fun logW(msg: String, t: Throwable?) { NSLog("NovaPlay: $msg ${t ?: ""}") }

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun foldAccents(s: String): String = (s.lowercase() as NSString).stringByFoldingWithOptions(NSDiacriticInsensitiveSearch, null)

@OptIn(ExperimentalForeignApi::class)
actual fun gunzip(src: Path, dst: Path) {
    val gz = gzopen(src.toString(), "rb") ?: throw okio.IOException("Cannot open $src")
    try {
        FileSystem.SYSTEM.sink(dst).buffer().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = buf.usePinned { gzread(gz, it.addressOf(0), buf.size.toUInt()) }
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
    } finally { gzclose(gz) }
}

private fun dir(kind: NSSearchPathDirectory): Path =
    (NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String).toPath()

actual fun dataDir(): Path = (dir(NSDocumentDirectory) / "NovaPlay").also { FileSystem.SYSTEM.createDirectories(it) }
actual fun cacheDir(): Path = (dir(NSCachesDirectory) / "NovaPlay").also { FileSystem.SYSTEM.createDirectories(it) }

actual class Prefs actual constructor() {
    private val d = NSUserDefaults.standardUserDefaults
    actual fun getString(k: String, def: String?): String? = d.stringForKey(k) ?: def
    actual fun putString(k: String, v: String?) { if (v == null) d.removeObjectForKey(k) else d.setObject(v, k) }
    actual fun getBoolean(k: String, def: Boolean): Boolean = if (d.objectForKey(k) == null) def else d.boolForKey(k)
    actual fun putBoolean(k: String, v: Boolean) { d.setBool(v, k) }
    actual fun getInt(k: String, def: Int): Int = if (d.objectForKey(k) == null) def else d.integerForKey(k).toInt()
    actual fun putInt(k: String, v: Int) { d.setInteger(v.toLong(), k) }
}
