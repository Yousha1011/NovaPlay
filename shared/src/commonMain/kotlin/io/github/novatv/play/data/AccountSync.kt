package io.github.novatv.play.data

import io.github.novatv.play.App
import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One account across devices: playlists, guides, favourites, Sports picks, keys and history ([Store.syncedKeys]) kept
 * in a private Gist on the user's GitHub. Each device signs in with a GitHub token (Gists permission); changes go up a
 * few seconds after they're made, and each device looks for others' changes when it starts and every minute. Every
 * setting carries the time it last changed: the newer one wins, setting by setting.
 */
object AccountSync {
    private const val FILE = "novaplay-account.json"
    private const val DESCRIPTION = "NovaPlay account sync"
    private const val API = "https://api.github.com"
    private const val EVERY_MS = 60_000L
    /** Changes are sent this long after the last one, so a run of edits goes up together. */
    private const val SETTLE_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + IO)
    private val lock = Mutex()
    private var push: Job? = null
    private var loop: Job? = null

    private val _status = MutableStateFlow<String?>(null)
    /** "Synced 14:02", "Couldn't sync: …", or null before the first sync. */
    val status: StateFlow<String?> = _status

    val signedIn: Boolean get() = App.store.syncToken.isNotBlank()

    /** At start-up: watches this device's changes, and syncs now and every minute while signed in. */
    fun start() {
        App.store.onSyncedChange = { k ->
            val t = App.store.syncTimes
            t.put(k, nowMs())
            App.store.syncTimes = t
            if (signedIn) {
                push?.cancel()
                push = scope.launch { delay(SETTLE_MS); sync() }
            }
        }
        if (loop == null) loop = scope.launch {
            while (isActive) {
                if (signedIn) sync()
                delay(EVERY_MS)
            }
        }
    }

    /** Signs in with [token]: finds the account's Gist (or makes it), then syncs. Returns null, or what went wrong. */
    suspend fun signIn(token: String): String? = withContext(IO) {
        App.store.syncToken = token.trim()
        App.store.syncGist = ""
        try {
            lock.withLock { exchange() }
            null
        } catch (e: Exception) {
            App.store.syncToken = ""
            e.message ?: "Couldn't sign in"
        }
    }

    /** This device stops syncing; its settings stay as they are. */
    fun signOut() {
        App.store.syncToken = ""
        App.store.syncGist = ""
        _status.value = null
    }

    /** Syncs now; errors are kept in [status], not thrown. */
    suspend fun sync() = withContext(IO) {
        lock.withLock {
            try { exchange() } catch (e: Exception) { _status.value = "Couldn't sync: ${e.message ?: e::class.simpleName}" }
        }
    }

    /** Takes the account's newer settings, then sends this device's newer ones. */
    private fun exchange() {
        val store = App.store
        val id = gist()
        val m = merge(store.syncedKeys, store.syncTimes, store::raw, read(id), nowMs())
        for ((k, v) in m.arrived) store.putFromSync(k, v)
        m.upload?.let { write(id, it) }
        store.syncTimes = m.times
        store.syncedAt = nowMs()
        _status.value = "Synced ${io.github.novatv.play.ui.clock(store.syncedAt)}"
        if (m.arrived.isNotEmpty()) App.repo.afterSync(m.arrived.keys)
    }

    /** What one sync does: settings to take from the account, the file to send (null when nothing is newer here), times. */
    class Merge(val arrived: Map<String, String?>, val upload: JSONObject?, val times: JSONObject)

    /**
     * Setting by setting, the newer side wins: the account's value when it changed later than this device's ([times],
     * ms), else this device's, sent up. A setting never changed here (no time) takes the account's.
     */
    fun merge(keys: Set<String>, times: JSONObject, local: (String) -> String?, remote: JSONObject, now: Long): Merge {
        val arrived = LinkedHashMap<String, String?>()
        var send = false
        for (k in keys) {
            val r = remote.optJSONObject(k)
            val rt = r?.optLong("t") ?: 0L
            val lt = times.optLong(k, 0L)
            when {
                r != null && rt > lt -> {
                    val v = r.optString("v", NONE).takeIf { it != NONE }
                    if (local(k) != v) arrived[k] = v
                    times.put(k, rt)
                }
                lt > rt -> send = true
                r == null && local(k) != null -> send = true
            }
        }
        if (!send) return Merge(arrived, null, times)
        val out = JSONObject()
        for (k in keys) {
            val t = times.optLong(k, 0L).takeIf { it > 0 } ?: now.also { times.put(k, it) }
            out.put(k, JSONObject().put("v", if (k in arrived) arrived[k] else local(k)).put("t", t))
        }
        return Merge(arrived, out, times)
    }

    private const val NONE = "\u0000none"

    private fun headers() = mapOf(
        "Authorization" to "Bearer ${App.store.syncToken}",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
    )

    private fun call(method: String, path: String, body: String? = null): String {
        val (code, text) = Http.send(method, API + path, headers(), body)
        if (code == 401) throw IllegalStateException("GitHub didn't accept the token")
        if (code == 403 || code == 404) throw IllegalStateException("The token can't use Gists (give it Gists read and write)")
        if (code !in 200..299) throw IllegalStateException("GitHub answered $code")
        return text
    }

    /** The account's Gist: remembered, else found among the user's Gists, else made. */
    private fun gist(): String {
        App.store.syncGist.takeIf { it.isNotBlank() }?.let { return it }
        var page = 1
        while (page <= 10) {
            val list = JSONArray(call("GET", "/gists?per_page=100&page=$page"))
            for (i in 0 until list.length()) {
                val g = list.optJSONObject(i) ?: continue
                if (g.optString("description") == DESCRIPTION && g.optJSONObject("files")?.has(FILE) == true) {
                    return g.optString("id").also { App.store.syncGist = it }
                }
            }
            if (list.length() < 100) break
            page++
        }
        val made = JSONObject(call("POST", "/gists", JSONObject().put("description", DESCRIPTION).put("public", false)
            .put("files", JSONObject().put(FILE, JSONObject().put("content", "{}"))).toString()))
        return made.optString("id").also { App.store.syncGist = it }
    }

    private fun read(id: String): JSONObject {
        val f = JSONObject(call("GET", "/gists/$id")).optJSONObject("files")?.optJSONObject(FILE) ?: return JSONObject()
        // Over a megabyte, GitHub sends only the start: the whole file is at its raw address.
        val text = if (f.optString("truncated") == "true") Http.get(f.optString("raw_url"), headers()) else f.optString("content", "{}")
        return try { JSONObject(text) } catch (e: Exception) { JSONObject() }
    }

    private fun write(id: String, content: JSONObject) {
        call("PATCH", "/gists/$id", JSONObject().put("files", JSONObject().put(FILE, JSONObject().put("content", content.toString()))).toString())
    }
}
