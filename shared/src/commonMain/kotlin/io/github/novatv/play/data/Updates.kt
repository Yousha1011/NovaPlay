package io.github.novatv.play.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.novatv.play.App
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.cacheDir
import io.github.novatv.play.platform.logW
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use

/**
 * New versions, from the project's GitHub releases. At start (when the setting is on) the latest release is compared
 * with this version; a newer one puts up [offer], and Update downloads this device's file and hands it to the platform
 * to install. Devices with no file to install (the iPhone) open the release page instead.
 */
object Updates {
    /** This version; raised with the build files' versions at each release. */
    const val CURRENT = "1.5"
    const val REPO = "Yousha1011/NovaPlay"

    class Release(val version: String, val notes: String, val page: String, val file: String?, val fileUrl: String?, val size: Long)

    /** The release on offer, while the prompt is up. */
    var offer by mutableStateOf<Release?>(null)
    /** The newest release when it's newer than this one; stays known after "Not now", so Settings › About can install it. */
    var available by mutableStateOf<Release?>(null)
        private set
    /** How far the download has got, 0 to 1, while it runs. */
    var progress by mutableStateOf<Float?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var checked = false

    /** "1.10" > "1.9" > "1.2.0" == "1.2"; a leading "v" is ignored. */
    fun newer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Reads GitHub's "latest release" answer; [asset] picks this device's file by name. */
    fun parse(json: String, asset: Regex?): Release? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name").ifBlank { return null }
        val assets = o.optJSONArray("assets")
        var file: String? = null; var url: String? = null; var size = 0L
        if (asset != null && assets != null) for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (asset.matches(a.optString("name"))) { file = a.optString("name"); url = a.optString("browser_download_url"); size = a.optLong("size"); break }
        }
        return Release(tag.removePrefix("v"), o.optString("body"), o.optString("html_url"), file, url, size)
    }

    /** At start: once per run, when the setting is on. */
    fun checkAtStart() {
        if (checked || !App.store.checkUpdates) return
        checked = true
        check(quiet = true)
    }

    /** Asks GitHub for the latest release; [quiet] says nothing when there's none or the check fails. */
    fun check(quiet: Boolean = false) {
        scope.launch {
            val r = try {
                withContext(IO) { parse(Http.get("https://api.github.com/repos/$REPO/releases/latest", mapOf("Accept" to "application/vnd.github+json")), App.host.updateAsset) }
            } catch (e: Exception) {
                logW("update check failed", e)
                if (!quiet) io.github.novatv.play.toast("Couldn't check for updates")
                return@launch
            }
            if (r != null && newer(r.version, CURRENT)) { available = r; offer = r }
            else if (!quiet) io.github.novatv.play.toast("NovaPlay is up to date ($CURRENT)")
        }
    }

    /** Puts the prompt up again for the release found earlier. */
    fun reopen() { available?.let { offer = it } }

    fun cancel() { if (progress == null) offer = null }

    /** Downloads this device's file and installs it; without one, opens the release page. */
    fun update() {
        val r = offer ?: return
        if (progress != null) return
        val url = r.fileUrl
        if (url == null) { App.host.openUrl(r.page); offer = null; return }
        progress = 0f
        scope.launch {
            try {
                val file = withContext(IO) { download(url, r.file!!, r.size) }
                offer = null
                App.host.installUpdate(file.toString())
            } catch (e: Exception) {
                logW("update download failed", e)
                io.github.novatv.play.toast("The download failed. Try again later.")
            } finally { progress = null }
        }
    }

    private suspend fun download(url: String, name: String, size: Long): Path {
        val fs = FileSystem.SYSTEM
        val dir = cacheDir() / "updates"
        // Only the newest download is kept.
        if (fs.exists(dir)) fs.list(dir).forEach { fs.delete(it, mustExist = false) }
        fs.createDirectories(dir)
        val part = dir / "$name.part"
        val done = dir / name
        Http.client.prepareGet(url) { header("User-Agent", Http.UA) }.execute { resp ->
            if (resp.status.value !in 200..299) throw IOException("HTTP ${resp.status.value}")
            val total = if (size > 0) size else resp.headers["Content-Length"]?.toLongOrNull() ?: 0L
            val ch = resp.bodyAsChannel()
            fs.sink(part).buffer().use { sink ->
                val buf = ByteArray(64 * 1024)
                var got = 0L
                while (true) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) {
                        sink.write(buf, 0, n); got += n
                        if (total > 0) withContext(Dispatchers.Main) { progress = (got.toFloat() / total).coerceIn(0f, 1f) }
                    } else if (ch.isClosedForRead) break
                }
            }
        }
        fs.atomicMove(part, done)
        return done
    }
}
