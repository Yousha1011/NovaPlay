package io.github.novatv.play.data

import io.github.novatv.play.App
import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.logW
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.sports.Airing
import io.github.novatv.play.sports.SportEvent
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.buffer
import okio.use

/**
 * Your NovaPlay server (the addon whose link ends ".../k/<key>/manifest.json"): it runs NovaPlay's own code with your
 * playlist, so the apps can take what it has worked out instead of doing it themselves:
 *  - every game's "Watch on your channels" ([forMatch]): match pages show it at once, with no matching on the device;
 *  - the library (channels, films, series) in the apps' own format ([library]): one download instead of the panel's
 *    lists.
 * Only for the playlist the server holds (compared by a fingerprint of its address and username, never the login);
 * anything it can't give, the app works out as before.
 */
object NovaServer {
    /** The server's address with its key, or null without one. */
    fun base(): String? = Addons.list().map { it.base }.firstOrNull { Regex("""/k/[^/]+$""").containsMatchIn(it) }

    /** The fingerprint the server gives for its playlist: its address (as the apps form it) and username. */
    fun accountId(s: Source): String {
        var host = s.url.trim().trimEnd('/')
        if (!host.startsWith("http")) host = "http://$host"
        host = host.removeSuffix("/player_api.php").removeSuffix("/get.php")
        return "${host.lowercase()}|${s.username.trim()}".encodeUtf8().sha256().hex()
    }

    private var account: Pair<Long, String?>? = null

    /** Whether the server holds [s] (asked at most every 10 minutes). Blocking. */
    fun mirrors(s: Source?): Boolean {
        if (s == null || s.type != SourceType.XTREAM) return false
        val b = base() ?: return false
        val id = account?.takeIf { nowMs() - it.first < 600_000L }?.second ?: runCatching {
            JSONObject(Http.get("$b/account")).optString("id")
        }.getOrNull().also { account = nowMs() to it }
        return id != null && id == accountId(s)
    }

    // ---------- games ----------

    private class Row(val id: String, val why: String, val title: String?, val start: Long, val stop: Long)
    @kotlin.concurrent.Volatile private var games: Pair<Long, Map<String, List<Row>>>? = null

    /**
     * [e]'s channels as the server worked them out (all versions, those addons name), as this device's channels; null
     * when the server doesn't have this game (or isn't this playlist's). Read at most every 3 minutes. Blocking.
     */
    fun forMatch(e: SportEvent): List<Airing>? {
        val st = App.repo.state.value
        if (!mirrors(st.source)) return null
        val all = games?.takeIf { nowMs() - it.first < 180_000L }?.second ?: runCatching { readGames() }
            .onFailure { logW("server games", it) }.getOrNull()?.also { games = nowMs() to it } ?: return null
        val rows = all[e.id] ?: return null
        val byId = channelsById(st.lib.channels)
        return rows.mapNotNull { r ->
            val c = byId[r.id] ?: return@mapNotNull null
            val p = r.title?.let { Programme(App.repo.channelKey(c), r.start, r.stop, it, null, null) }
            Airing(c, p, r.why)
        }
    }

    private fun readGames(): Map<String, List<Row>> {
        val b = base() ?: return emptyMap()
        val g = JSONObject(Http.get("$b/matches.json")).optJSONObject("games") ?: return emptyMap()
        val out = HashMap<String, List<Row>>()
        for (k in g.keys()) {
            val a: JSONArray = g.optJSONArray(k) ?: continue
            out[k] = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { r ->
                Row(r.optString("id"), r.optString("why"), r.optString("title").ifBlank { null }, r.optLong("start"), r.optLong("stop"))
            }
        }
        return out
    }

    private var index: Pair<List<Channel>, Map<String, Channel>>? = null
    private fun channelsById(all: List<Channel>): Map<String, Channel> =
        index?.takeIf { it.first === all }?.second ?: all.associateBy { it.id }.also { index = all to it }

    // ---------- library ----------

    /** The server's copy of [s]'s library, or null (not this playlist's, not ready, or no server). Blocking. */
    fun library(s: Source): Library? {
        if (!mirrors(s)) return null
        val b = base() ?: return null
        val fs = FileSystem.SYSTEM
        val dir = io.github.novatv.play.platform.cacheDir()
        val f = dir / "server_lib.bin"
        return try {
            Http.stream("$b/lib.bin.gz") { input -> fs.sink(f).buffer().use { it.writeAll(input) } }
            LibraryCache.read(f)?.first?.takeIf { it.channels.isNotEmpty() }
        } catch (e: Exception) { logW("server library", e); null } finally { fs.delete(f, mustExist = false) }
    }

    // ---------- Sports picks ----------

    private var sentFollow: String? = null

    /** Tells the server your Sports picks, so the games it works out are yours (when they changed). Blocking. */
    fun sendFollow() {
        val b = base() ?: return
        val s = App.store
        fun arr(v: Collection<String>) = JSONArray(v.sorted())
        val body = JSONObject().put("sportsLeagues", arr(s.sportsLeagues)).put("sportsLeagueTeams", arr(s.sportsLeagueTeams))
            .put("favouriteTeams", arr(s.favouriteTeams)).put("sportsEnabled", arr(s.sportsEnabled)).put("sportsComps", arr(s.sportsComps))
            .put("sportsCountry", s.sportsCountry)
            // The broadcaster lookups' keys (TheSportsDB, SportMonks): without them the server finds far fewer channels.
            .put("sportsDbKey", s.sportsDbKey).put("sportMonksKey", s.sportMonksKey).toString()
        if (body == sentFollow) return
        runCatching { Http.send("POST", "$b/follow", emptyMap(), body) }.onSuccess { if (it.first in 200..299) sentFollow = body }
    }
}
