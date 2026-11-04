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
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import kotlin.random.Random

/**
 * NovaPlay accounts: a username and password, and the account's playlists, guides, favourites, Sports picks, keys and
 * history ([Store.syncedKeys]), kept as accounts/<username>.json in a private GitHub repository ([AccountsConfig]).
 * The password itself is never stored, only a salted, many-times-hashed fingerprint of it. Changes go up a few seconds
 * after they're made; each device looks for others' changes when it starts and every minute. Every setting carries
 * the time it last changed: the newer one wins, setting by setting.
 */
object AccountSync {
    private const val API = "https://api.github.com"
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
    val available: Boolean get() = AccountsConfig.REPO.isNotBlank() && AccountsConfig.TOKEN.isNotBlank()

    /** At start-up: watches this device's changes, and syncs now and every minute while logged in. */
    fun start() {
        _user.value = App.store.accountName.ifBlank { null }
        App.store.onSyncedChange = { k ->
            val t = App.store.syncTimes
            t.put(k, nowMs())
            App.store.syncTimes = t
            if (_user.value != null) {
                push?.cancel()
                push = scope.launch { delay(SETTLE_MS); sync() }
            }
        }
        if (loop == null) loop = scope.launch {
            while (isActive) {
                if (_user.value != null && available) sync()
                delay(EVERY_MS)
            }
        }
    }

    /** "Ali_99" -> "ali_99"; null when it can't be a username. */
    fun cleanName(name: String): String? = name.trim().lowercase().takeIf { Regex("[a-z0-9._-]{3,32}").matches(it) }

    /**
     * Makes account [name] with [password], holding this device's settings. Returns null, or what went wrong.
     */
    suspend fun signUp(name: String, password: String): String? = withContext(IO) {
        val n = cleanName(name) ?: return@withContext "Use 3 to 32 letters, numbers, dots, dashes or underscores"
        if (password.length < 6) return@withContext "Use a password of at least 6 characters"
        if (!available) return@withContext NOT_SET_UP
        try {
            lock.withLock {
                if (fetch(n) != null) return@withLock "That username is taken"
                val salt = ByteString.of(*Random.nextBytes(16))
                val account = JSONObject().put("user", n).put("salt", salt.base64()).put("rounds", ROUNDS)
                    .put("hash", fingerprint(password, salt, ROUNDS)).put("created", nowMs()).put("settings", JSONObject())
                save(n, account, null)
                begin(n)
                exchange()
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
                val (account, _) = fetch(n) ?: return@withLock "Wrong username or password"
                val salt = account.optString("salt").decodeBase64() ?: return@withLock "This account is damaged"
                if (fingerprint(password, salt, account.optLong("rounds", ROUNDS.toLong()).toInt()) != account.optString("hash"))
                    return@withLock "Wrong username or password"
                // A new device takes everything from the account: its own settings count as never changed.
                App.store.syncTimes = JSONObject()
                begin(n)
                exchange()
                null
            }
        } catch (e: Exception) { e.message ?: "Couldn't log in" }
    }

    /** This device leaves the account; its settings stay as they are, and the login page shows again. */
    fun logOut() {
        App.store.accountName = ""
        App.store.syncTimes = JSONObject()
        _status.value = null
        _user.value = null
    }

    private fun begin(name: String) {
        App.store.accountName = name
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
        val store = App.store
        val name = store.accountName.ifBlank { return }
        val (account, sha) = fetch(name) ?: throw IllegalStateException("The account $name no longer exists")
        val m = merge(store.syncedKeys, store.syncTimes, store::raw, account.optJSONObject("settings") ?: JSONObject(), nowMs())
        for ((k, v) in m.arrived) store.putFromSync(k, v)
        m.upload?.let { save(name, account.put("settings", it), sha) }
        store.syncTimes = m.times
        store.syncedAt = nowMs()
        _status.value = "Synced ${io.github.novatv.play.ui.clock(store.syncedAt)}"
        if (m.arrived.isNotEmpty()) App.repo.afterSync(m.arrived.keys)
    }

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
    const val NOT_SET_UP = "Accounts aren't set up in this build (local.properties needs novaplay.accountsRepo and novaplay.accountsToken)"

    private fun headers(raw: Boolean = false) = mapOf(
        "Authorization" to "Bearer ${AccountsConfig.TOKEN}",
        "Accept" to if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
    )

    private fun path(name: String) = "$API/repos/${AccountsConfig.REPO}/contents/accounts/$name.json"

    /** The account file and its version (needed to replace it), or null when there's no such account. */
    private fun fetch(name: String): Pair<JSONObject, String>? {
        val (code, text) = Http.send("GET", path(name), headers())
        if (code == 404) return null
        check(code)
        val o = JSONObject(text)
        val b64 = o.optString("content").replace("\n", "")
        // Over a megabyte, GitHub leaves the content out: it comes raw on request.
        val body = if (b64.isNotBlank()) b64.decodeBase64()?.utf8() ?: "{}" else Http.send("GET", path(name), headers(raw = true)).second
        return JSONObject(body) to o.optString("sha")
    }

    private fun save(name: String, account: JSONObject, sha: String?) {
        val body = JSONObject().put("message", "NovaPlay: $name").put("content", account.toString().encodeUtf8().base64())
        if (sha != null) body.put("sha", sha)
        val (code, _) = Http.send("PUT", path(name), headers(), body.toString())
        if (code == 409 || code == 422) throw IllegalStateException("another device saved at the same moment; trying again shortly")
        check(code)
    }

    private fun check(code: Int) {
        when {
            code == 401 -> throw IllegalStateException("the accounts key in this build isn't accepted by GitHub")
            code == 403 || code == 404 -> throw IllegalStateException("the accounts key can't use ${AccountsConfig.REPO}")
            code !in 200..299 -> throw IllegalStateException("GitHub answered $code")
        }
    }
}
