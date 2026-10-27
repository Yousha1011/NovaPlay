package io.github.novatv.play.platform

import io.github.novatv.play.data.PlayItem
import kotlinx.coroutines.CoroutineDispatcher
import okio.Path

/** What each platform (Fire TV, Windows, iPhone) provides on its own. */
expect fun nowMs(): Long

/** The dispatcher for blocking work: network, files, the guide database. */
expect val IO: CoroutineDispatcher

expect fun logW(msg: String, t: Throwable? = null)

/** Lower case without accents: "Çorum" -> "corum". */
expect fun foldAccents(s: String): String

/** Unpacks a gzip file. */
expect fun gunzip(src: Path, dst: Path)

/** Where the app keeps its files (library cache, guide database) and its image cache. */
expect fun dataDir(): Path
expect fun cacheDir(): Path

/** Small values the app remembers between runs. */
expect class Prefs() {
    fun getString(k: String, def: String?): String?
    fun putString(k: String, v: String?)
    fun getBoolean(k: String, def: Boolean): Boolean
    fun putBoolean(k: String, v: Boolean)
    fun getInt(k: String, def: Int): Int
    fun putInt(k: String, v: Int)
}

/** What differs in how the app is used: a TV remote, a mouse and keyboard, or a touch screen. */
enum class Device { TV, DESKTOP, PHONE }

/** Set by each platform at start. */
interface Host {
    val device: Device
    /** Opens the player full screen. */
    fun play(item: PlayItem)
    fun toast(msg: String)
    /** Opens a web page outside the app; platforms without a browser to hand ignore it. */
    fun openUrl(url: String) {}
}

/** Percent-encodes [s] as UTF-8, as URLEncoder does for query values. */
fun urlEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        when {
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '_'.code ||
                c == '.'.code || c == '*'.code -> append(c.toChar())
            c == ' '.code -> append('+')
            else -> { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
        }
    }
}

/** 12345 -> "12,345". */
fun grouped(n: Number): String {
    val s = n.toLong().toString()
    val neg = s.startsWith("-")
    val d = s.removePrefix("-")
    val out = StringBuilder()
    for ((i, ch) in d.withIndex()) {
        if (i > 0 && (d.length - i) % 3 == 0) out.append(',')
        out.append(ch)
    }
    return (if (neg) "-" else "") + out
}

fun pad2(n: Number): String = n.toLong().toString().padStart(2, '0')
