package io.github.novatv.play.data

import io.github.novatv.play.App
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
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * NovaPlay accounts: a username and password, and the account's playlists, guides, favourites, Sports picks, keys and
 * history ([Store.syncedKeys]). The accounts server ([AccountsConfig.SERVER], the user's sports proxy) keeps them in a
 * private GitHub repository and holds its key: the apps carry none. Logging in gives a session this device keeps (never
 * the password). Changes go up a few seconds after they're made; each device looks for others' changes when it starts
 * and every minute. Every setting carries the time it last changed: the newer one wins, setting by setting.
 */
object AccountSync {
    private const val EVERY_MS = 60_000L
    /** Changes are sent this long after the last one, so a run of edits goes up together. */
    private const val SETTLE_MS = 4_000L
    private const val ROUNDS = 20_000

    private val scope = CoroutineScope(SupervisorJob() + IO)
    private val lock = Mutex()
    private var push: Job? = null
    private var loop: Job? = null

    private val _status = MutableStateFlow<String?>(null)
    /** "Synced 14:02", "Couldn't sync: …", or null before the first sync. */
    val status: StateFlow<String?> = _status

    private val _user = MutableStateFlow<String?>(null)
    /** The account this device is logged in to, or null: the login page shows until there is one. */
    val user: StateFlow<String?> = _user

    /** Whether this build knows where accounts are kept (local.properties when it was built). */
    val available: Boolean get() = AccountsConfig.SERVER.isNotBlank()

    /**
     * Before anything else at start-up: from here on every change to a synced setting is time-stamped, including the
     * app's own updates to settings (a guide added in a new version), so they win over the account's older values.
     */
    fun watch() {
        // Logged in means a session from the accounts server (devices on the first, GitHub-key version log in once more).
        _user.value = App.store.accountName.takeIf { it.isNotBlank() && App.store.accountSession.isNotBlank() }
        App.store.onSyncedChange = { k ->
            val t = App.store.syncTimes
            t.put(k, nowMs())
            App.store.syncTimes = t
            if (_user.value != null) {
                push?.cancel()
                push = scope.launch { delay(SETTLE_MS); sync() }
            }
        }
    }

    /** Once the app is up: syncs now and every minute while logged in. */
    fun start() {
        if (loop == null) loop = scope.launch {
            while (isActive) {
                if (_user.value != null && available) sync()
                delay(EVERY_MS)
            }
        }
    }

    /** "Ali_99" -> "ali_99"; null when it can't be a username. */
    fun cleanName(name: String): String? = name.trim().lowercase().takeIf { Regex("[a-z0-9._-]{3,32}").matches(it) }

    /** Makes account [name] with [password], holding this device's settings. Returns null, or what went wrong. */
    suspend fun signUp(name: String, password: String): String? = withContext(IO) {
        val n = cleanName(name) ?: return@withContext "Use 3 to 32 letters, numbers, dots, dashes or underscores"
        if (password.length < 6) return@withContext "Use a password of at least 6 characters"
        if (!available) return@withContext NOT_SET_UP
        try {
            lock.withLock {
                val store = App.store
                val mine = merge(store.syncedKeys, store.syncTimes, store::raw, JSONObject(), nowMs())
                val r = post("signup", JSONObject().put("name", n).put("password", password).put("settings", mine.upload ?: JSONObject()))
                store.syncTimes = mine.times
                begin(n, r.optString("session"))
                done()
                null
            }
        } catch (e: Exception) { e.message ?: "Couldn't create the account" }
    }

    /** Logs in to account [name]: the account's settings replace this device's. Returns null, or what went wrong. */
    suspend fun logIn(name: String, password: String): String? = withContext(IO) {
        val n = cleanName(name) ?: return@withContext "Wrong username or password"
        if (!available) return@withContext NOT_SET_UP
        try {
            lock.withLock {
                val r = post("login", JSONObject().put("name", n).put("password", password))
                // A new device takes everything from the account: its own settings count as never changed.
                App.store.syncTimes = JSONObject()
                begin(n, r.optString("session"))
                absorb(r.optJSONObject("settings") ?: JSONObject())
                null
            }
        } catch (e: Exception) { e.message ?: "Couldn't log in" }
    }

    /** This device leaves the account; its settings stay as they are, and the login page shows again. */
    fun logOut() {
        App.store.accountName = ""
        App.store.accountSession = ""
        App.store.syncTimes = JSONObject()
        _status.value = null
        _user.value = null
    }

    private fun begin(name: String, session: String) {
        App.store.accountName = name
        App.store.accountSession = session
        _user.value = name
    }

    /** Syncs now; errors are kept in [status], not thrown. */
    suspend fun sync() = withContext(IO) {
        lock.withLock {
            try { exchange() } catch (e: Exception) { _status.value = "Couldn't sync: ${e.message ?: e::class.simpleName}" }
        }
    }

    /** Takes the account's newer settings, then sends this device's newer ones. */
    private fun exchange() {
        val name = App.store.accountName.ifBlank { return }
        absorb(post("get", session(name)).optJSONObject("settings") ?: JSONObject())
    }

    /** Merges the account's [remote] settings in, and sends this device's newer ones up. */
    private fun absorb(remote: JSONObject) {
        val store = App.store
        val m = merge(store.syncedKeys, store.syncTimes, store::raw, remote, nowMs())
        for ((k, v) in m.arrived) store.putFromSync(k, v)
        m.upload?.let { post("put", session(store.accountName).put("settings", it)) }
        store.syncTimes = m.times
        done()
        if (m.arrived.isNotEmpty()) App.repo.afterSync(m.arrived.keys)
    }

    private fun done() {
        App.store.syncedAt = nowMs()
        _status.value = "Synced ${io.github.novatv.play.ui.clock(App.store.syncedAt)}"
    }

    private fun session(name: String) = JSONObject().put("name", name).put("session", App.store.accountSession)

    /** What one sync does: settings to take from the account, the settings to send (null when nothing is newer here), times. */
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

    /** [password] stretched with [salt]: HMAC-SHA256 [rounds] times over, so guessing passwords is slow. */
    fun fingerprint(password: String, salt: ByteString, rounds: Int): String {
        val key = password.encodeUtf8()
        var d = salt
        repeat(rounds) { d = d.hmacSha256(key) }
        return d.base64()
    }

    private const val NONE = "\u0000none"
    const val NOT_SET_UP = "Accounts aren't set up in this build (local.properties needs novaplay.accountsServer)"

    /** A call to the accounts server; its error message when it refuses. */
    private fun post(action: String, body: JSONObject): JSONObject {
        val (code, text) = Http.send("POST", AccountsConfig.SERVER.trimEnd('/') + "/account/$action", emptyMap(), body.toString())
        val reply = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        if (code == 401 && (action == "get" || action == "put")) {
            // The password was changed elsewhere, or the session ended: this device logs in again.
            scope.launch(kotlinx.coroutines.Dispatchers.Main) { logOut() }
        }
        if (code !in 200..299) throw IllegalStateException(reply.optString("error").ifBlank { "The accounts server answered $code" })
        return reply
    }
}
