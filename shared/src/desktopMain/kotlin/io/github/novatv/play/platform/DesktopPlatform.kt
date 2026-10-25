package io.github.novatv.play.platform

import io.github.novatv.play.json.JSONObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

actual fun logW(msg: String, t: Throwable?) { System.err.println("NovaPlay: $msg"); t?.printStackTrace() }

/** %APPDATA%\NovaPlay on Windows, ~/.novaplay elsewhere. */
private val home: Path = (System.getenv("APPDATA")?.let { "$it/NovaPlay" } ?: "${System.getProperty("user.home")}/.novaplay").toPath()

actual fun dataDir(): Path = ensure(home)
actual fun cacheDir(): Path = ensure(home / "cache")

/** Settings in one JSON file, written on every change (they are small). */
actual class Prefs actual constructor() {
    private val file = dataDir() / "settings.json"

    private fun o(): JSONObject = synchronized(lock) { cache ?: load().also { cache = it } }

    private fun load(): JSONObject = try {
        if (FileSystem.SYSTEM.exists(file)) JSONObject(FileSystem.SYSTEM.read(file) { readUtf8() }) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    private fun set(k: String, v: Any?) = synchronized(lock) {
        o().put(k, v)
        val tmp = dataDir() / "settings.json.tmp"
        FileSystem.SYSTEM.write(tmp) { writeUtf8(o().toString()) }
        FileSystem.SYSTEM.atomicMove(tmp, file)
    }

    actual fun getString(k: String, def: String?): String? = synchronized(lock) { if (o().opt(k) != null) o().optString(k) else def }
    actual fun putString(k: String, v: String?) { set(k, v) }
    actual fun getBoolean(k: String, def: Boolean): Boolean = synchronized(lock) { if (o().has(k)) o().optBoolean(k, def) else def }
    actual fun putBoolean(k: String, v: Boolean) { set(k, v) }
    actual fun getInt(k: String, def: Int): Int = synchronized(lock) { if (o().has(k)) o().optInt(k, def) else def }
    actual fun putInt(k: String, v: Int) { set(k, v.toLong()) }

    private companion object {
        val lock = Any()
        var cache: JSONObject? = null
    }
}
