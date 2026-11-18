package io.github.novatv.play.sports

import io.github.novatv.play.App
import io.github.novatv.play.data.Http
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.locked
import io.github.novatv.play.platform.nowMs
import kotlinx.coroutines.async

/**
 * Footballers' photos for the formation pitch (ESPN has almost none for football): the photo on the player's
 * Wikipedia page (free, no key, no tight limit), else TheSportsDB's cut-out or portrait (the user's key, else the free
 * one, which allows about 30 lookups a minute). Looked up by name once and kept on the device (a found photo for good,
 * "none" for a week), so a player seen before shows at once and costs nothing.
 */
object PlayerPhotos {
    private const val FREE_KEY = "123"
    private const val NONE_FOR_MS = 7 * 86_400_000L

    /** Name -> (when looked up, photo address or "" for none). */
    private val known = HashMap<String, Pair<Long, String>>()
    private val lock = io.github.novatv.play.platform.Lock()
    private var loaded = false
    private fun file() = io.github.novatv.play.platform.cacheDir() / "player-photos-2.json"

    /** [name]'s photo if already known (no network): null when unknown or none. */
    fun cached(name: String): String? { load(); return lock.locked { known[key(name)]?.second?.ifBlank { null } } }

    /** Whether [name] was looked up recently enough not to ask again. */
    private fun fresh(name: String): Boolean = lock.locked {
        known[key(name)]?.let { (at, url) -> url.isNotBlank() || nowMs() - at < NONE_FOR_MS } == true
    }

    /** One lookup at a time across the app: a match page's early lookup and its Game info never ask twice. */
    private val busy = io.github.novatv.play.platform.Lock()

    /**
     * Looks up the photos of [names] not known yet, calling [found] with each one found (the known ones at once):
     * Wikipedia for all of them, six at a time; then TheSportsDB one at a time for those Wikipedia lacks (its free
     * key allows about 30 a minute: a refusal waits 30 s, twice at most). Blocking.
     */
    fun fetch(names: List<String>, found: (String, String) -> Unit) {
        // Known ones at once, before waiting on any lookup still running (a match page's early one).
        load()
        for (n in names.distinct()) if (fresh(n)) cached(n)?.let { found(n, it) }
        busy.locked { lookUp(names, found) }
    }

    private fun lookUp(names: List<String>, found: (String, String) -> Unit) {
        var changed = false
        var waits = 0
        // Those found meanwhile (by the lookup just finished) at once, then the rest looked up.
        val (have, todo) = names.distinct().partition { fresh(it) }
        for (n in have) cached(n)?.let { found(n, it) }
        // Wikipedia, several at once: it has no tight limit.
        val fromWiki = kotlinx.coroutines.runBlocking(io.github.novatv.play.platform.IO) {
            todo.chunked(6).flatMap { batch ->
                batch.map { n -> async { n to runCatching { firstStage(n) }.getOrNull() } }.map { it.await() }
                    .onEach { (n, url) -> if (url != null) found(n, url) }
            }
        }
        for ((n, url) in fromWiki) if (url != null) { lock.locked { known[key(n)] = nowMs() to url }; changed = true }
        // Kept straight away, so a screen opened meanwhile has them without waiting for the slower source.
        if (changed) runCatching { save() }
        val rest = fromWiki.filter { it.second == null }.map { it.first }
        var i = 0
        while (i < rest.size) {
            val n = rest[i]
            val url = try { sportsDb(nameOf(n), if (isCricket(n)) "Cricket" else "Soccer") ?: "" } catch (e: Exception) {
                // Too many lookups at once (the free key's limit): wait a little and go on, twice at most.
                if ("429" in (e.message ?: "") && waits < 2) { waits++; Thread.sleep(30_000L); continue }
                // Any other failure: skip this one (not remembered, so it's tried again next time).
                i++; continue
            }
            lock.locked { known[key(n)] = nowMs() to url }
            changed = true
            if (url.isNotBlank()) found(n, url)
            i++
        }
        if (changed) runCatching { save() }
        Unit
    }

    /*
     * A photo key is a footballer's full name, or for a cricketer "cricket|<ESPN id>|<full name>": ESPN has photos of
     * established cricketers by their id (none of footballers), Wikipedia of most international players.
     */
    private fun isCricket(key: String) = key.startsWith("cricket|")
    private fun nameOf(key: String) = if (isCricket(key)) key.substringAfterLast('|') else key

    /** The quick sources, tried several players at once: ESPN's cricket photo by id, then Wikipedia. */
    private suspend fun firstStage(key: String): String? {
        if (isCricket(key)) {
            val id = key.removePrefix("cricket|").substringBefore('|')
            val espn = "https://a.espncdn.com/i/headshots/cricket/players/full/$id.png"
            if (id.isNotBlank() && Http.probe(espn, timeoutMs = 5_000L) == Http.Probe.PLAYS) return espn
            return wikipedia(nameOf(key), "cricketer")
        }
        return wikipedia(key)
    }

    /** [key]'s photo from any source (tests and one-offs): the quick ones, else TheSportsDB. Blocking. */
    internal fun lookup(key: String): String? =
        kotlinx.coroutines.runBlocking { runCatching { firstStage(key) }.getOrNull() } ?: sportsDb(nameOf(key), if (isCricket(key)) "Cricket" else "Soccer")

    /**
     * The photo on the footballer's Wikipedia page: the page by that name when it's a footballer's, else the top
     * search result for "<name> footballer" (common names: "Eric Smith (footballer, born 1997)"). Only pages that
     * describe a footballer count, so a namesake's photo is never used.
     */
    internal fun wikipedia(name: String, what: String = "footballer"): String? {
        fun summary(title: String): String? {
            val o = JSONObject(Http.get("https://en.wikipedia.org/api/rest_v1/page/summary/" + encode(title.trim().replace(' ', '_'))))
            val about = o.optString("description").lowercase()
            if (what !in about && !(what == "footballer" && "soccer" in about)) return null
            return o.optJSONObject("thumbnail")?.optString("source")?.ifBlank { null }
        }
        runCatching { summary(name) }.getOrNull()?.let { return it }
        val q = encode("$name $what")
        val hits = JSONObject(Http.get("https://en.wikipedia.org/w/api.php?action=query&list=search&srlimit=1&format=json&srsearch=$q"))
            .optJSONObject("query")?.optJSONArray("search") ?: return null
        val title = hits.optJSONObject(0)?.optString("title")?.ifBlank { null } ?: return null
        // The page found must still be this player: the surname in its title.
        if (plain(name.substringAfterLast(' ')).lowercase() !in plain(title).lowercase()) return null
        return summary(title)
    }

    /** Percent-encodes [s] as UTF-8 for a web address (letters, digits and _-.~ as they are). */
    internal fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in "_-.~(),") append(c.toChar())
            else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }

    /** TheSportsDB's best match for a footballer called [name]: a cut-out, else a portrait. */
    internal fun sportsDb(name: String, sport: String = "Soccer"): String? {
        val k = App.store.sportsDbKey.trim().ifBlank { FREE_KEY }
        // Plain letters ("Gyökeres" -> "Gyokeres"): the search refuses accented ones, and finds the player either way.
        val q = plain(name).trim().replace(Regex("""\s+"""), "_").filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '\'' }
        val arr = JSONObject(Http.get("https://www.thesportsdb.com/api/v1/json/$k/searchplayers.php?p=$q")).optJSONArray("player") ?: return null
        val players = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val p = players.firstOrNull { it.optString("strSport") == sport } ?: return null
        return p.optString("strCutout").ifBlank { null } ?: p.optString("strThumb").ifBlank { null }
    }

    /** [name] in plain letters: accents dropped, and the letters folding misses (ł, ø, æ, ß, đ) spelled out. */
    internal fun plain(name: String): String {
        val spelled = name.map { c -> when (c) { 'ł' -> "l"; 'Ł' -> "L"; 'ø' -> "o"; 'Ø' -> "O"; 'æ' -> "ae"; 'Æ' -> "Ae"; 'ß' -> "ss"; 'đ' -> "d"; 'Đ' -> "D"
            'ı' -> "i"; 'þ' -> "th"; 'ð' -> "d"; else -> c.toString() } }.joinToString("")
        return io.github.novatv.play.platform.foldAccents(spelled)
    }

    private fun key(name: String) = io.github.novatv.play.platform.foldAccents(name.trim().lowercase())

    private fun load() {
        lock.locked {
            if (loaded) return
            loaded = true
            runCatching {
                val fs = okio.FileSystem.SYSTEM
                if (!fs.exists(file())) return
                val o = JSONObject(fs.read(file()) { readUtf8() })
                for (k in o.keys()) o.optJSONObject(k)?.let { known[k] = it.optLong("t") to it.optString("u") }
            }
        }
    }

    private fun save() {
        val text = lock.locked { JSONObject().also { o -> known.forEach { (k, v) -> o.put(k, JSONObject().put("t", v.first).put("u", v.second)) } }.toString() }
        okio.FileSystem.SYSTEM.write(file()) { writeUtf8(text) }
    }
}
