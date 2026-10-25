package io.github.novatv.play.platform

import android.annotation.SuppressLint
import android.content.Context
import okio.Path
import okio.Path.Companion.toOkioPath

/** The application, set first thing at start. */
@SuppressLint("StaticFieldLeak")
lateinit var appContext: Context

actual fun logW(msg: String, t: Throwable?) { android.util.Log.w("NovaPlay", msg, t) }

actual fun dataDir(): Path = appContext.filesDir.toOkioPath()
actual fun cacheDir(): Path = ensure(appContext.cacheDir.toOkioPath() / "net")

actual class Prefs actual constructor() {
    private val sp = appContext.getSharedPreferences("novaplay", Context.MODE_PRIVATE)
    actual fun getString(k: String, def: String?): String? = try { sp.getString(k, def) } catch (e: ClassCastException) { def }
    actual fun putString(k: String, v: String?) { sp.edit().putString(k, v).apply() }
    actual fun getBoolean(k: String, def: Boolean): Boolean = sp.getBoolean(k, def)
    actual fun putBoolean(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }
    actual fun getInt(k: String, def: Int): Int = sp.getInt(k, def)
    actual fun putInt(k: String, v: Int) { sp.edit().putInt(k, v).apply() }
    /** Earlier versions kept some lists as string sets; read once, then they move to plain values. */
    fun legacySet(k: String): Set<String>? = try { sp.getStringSet(k, null)?.toSet() } catch (e: ClassCastException) { null }
    fun remove(k: String) { sp.edit().remove(k).apply() }
}
