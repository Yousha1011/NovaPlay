package io.github.novatv.play.data

import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.Prefs

/**
 * Everything the app remembers: sources, favourites, watch progress, recent channels and Sports choices.
 * Small JSON values in the platform's settings; the loaded libraries are cached separately ([LibraryCache]).
 */
class Store(private val sp: Prefs = Prefs()) {

    var sources: List<Source>
        get() = arr("sources").let { a -> (0 until a.length()).map { Source.fromJson(a.getJSONObject(it)) } }
        set(v) = put("sources", JSONArray(v.map { it.toJson() }))

    var activeSourceId: String?
        get() = sp.getString("active", null)
        set(v) { ps("active", v) }

    /** Favourite channels, movies and series as "kind:sourceId:itemId". */
    var favourites: Set<String>
        get() = strSet("favs")
        set(v) = putSet("favs", v)

    fun isFavourite(key: String) = key in favourites
    fun toggleFavourite(key: String): Boolean {
        val f = favourites.toMutableSet()
        val on = f.add(key) || run { f.remove(key); false }
        favourites = f
        return on
    }

    /** Last channels watched, newest first (channel keys). */
    var recentChannels: List<String>
        get() = strList("recentCh")
        set(v) = put("recentCh", JSONArray(v.take(20)))

    fun addRecentChannel(key: String) { recentChannels = listOf(key) + recentChannels.filter { it != key } }

    /** Watch progress for movies and episodes: key -> [position, duration] in ms. */
    fun progress(key: String): Pair<Long, Long>? = obj("progress").optJSONArray(key)?.let { it.getLong(0) to it.getLong(1) }

    fun saveProgress(key: String, title: String, poster: String?, pos: Long, dur: Long) {
        val o = obj("progress")
        val done = dur > 0 && pos > dur * 0.93
        if (done || pos < 30_000) o.remove(key) else o.put(key, JSONArray().put(pos).put(dur))
        put("progress", o)
        // Continue watching: newest first, finished items drop out.
        val list = continueWatching.filter { it.key != key }.toMutableList()
        if (!done && pos >= 30_000) list.add(0, Resume(key, title, poster, pos, dur))
        put("continue", JSONArray(list.take(30).map { it.toJson() }))
    }

    data class Resume(val key: String, val title: String, val poster: String?, val pos: Long, val dur: Long) {
        fun toJson(): JSONObject = JSONObject().put("key", key).put("title", title).put("poster", poster ?: "").put("pos", pos).put("dur", dur)
    }

    val continueWatching: List<Resume>
        get() = arr("continue").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Resume(it.getString("key"), it.optString("title"), it.optString("poster").ifEmpty { null }, it.optLong("pos"), it.optLong("dur"))
            }
        }

    /** Drops one title from Continue watching, and its saved place with it. */
    fun removeContinue(key: String) {
        put("continue", JSONArray(continueWatching.filter { it.key != key }.map { it.toJson() }))
        put("progress", obj("progress").also { it.remove(key) })
    }

    fun clearContinue() { put("continue", JSONArray()); put("progress", JSONObject()) }

    fun removeRecentChannel(key: String) { recentChannels = recentChannels.filter { it != key } }
    fun clearRecentChannels() { recentChannels = emptyList() }

    // ---------- Sports ----------

    /** Followed leagues (Sports catalogue ids); empty means all. */
    var sportsLeagues: Set<String>
        get() = strSet("sportsLeagues")
        set(v) = putSet("sportsLeagues", v)

    /** Favourite teams as "sport|teamId|name". */
    var favouriteTeams: Set<String>
        get() = strSet("favTeams")
        set(v) = putSet("favTeams", v)

    /** Sports shown in the Sports centre (Sport names); empty means all. */
    var sportsEnabled: Set<String>
        get() = strSet("sportsEnabled")
        set(v) = putSet("sportsEnabled", v)

    /** Competitions picked by name for sports ESPN lists as one feed (cricket, rugby): "SPORT|competition". */
    var sportsComps: Set<String>
        get() = strSet("sportsComps")
        set(v) = putSet("sportsComps", v)

    /** The user's own API keys, typed by them in Settings. */
    var sportsDbKey: String
        get() = sp.getString("sportsDbKey", "") ?: ""
        set(v) { ps("sportsDbKey", v.trim()) }

    var sportMonksKey: String
        get() = sp.getString("sportMonksKey", "") ?: ""
        set(v) { ps("sportMonksKey", v.trim()) }

    /** Broadcasters from this country are listed first ("United Kingdom", as TheSportsDB names countries). */
    var sportsCountry: String
        get() = sp.getString("sportsCountry", "") ?: ""
        set(v) { ps("sportsCountry", v) }

    /** Extra XMLTV guides imported alongside the playlist's own. */
    var extraEpg: List<String>
        get() = strList("extraEpg")
        set(v) = put("extraEpg", JSONArray(v.filter { it.isNotBlank() }))

    /** Adds the sports guides once, after the user's own; removing them in Settings later sticks. */
    fun addSportsGuides() {
        if (sp.getBoolean("sportsGuidesAdded", false)) return
        extraEpg = (extraEpg + sportsGuides).distinct()
        sp.putBoolean("sportsGuidesAdded", true)
    }

    private val sportsGuides = listOf(
        "https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/Sports_guide.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz",
        "https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/US_guide.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_US_SPORTS1.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_SG1.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_GR1.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz",
        "https://free-epg.de/api/epg/fr.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_ES1.xml.gz",
        "https://free-epg.de/api/epg/us.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_HR1.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_RS1.xml.gz",
    )

    // ---------- iPhone extras ----------

    /** The user's own TMDB key (v3 API key or v4 read token), typed in Settings: ratings, cast and trailers. */
    var tmdbKey: String
        get() = sp.getString("tmdbKey", "") ?: ""
        set(v) { ps("tmdbKey", v.trim()) }

    /** The iPhone app's colour theme (an id from the theme list). */
    var phoneTheme: String
        get() = sp.getString("phoneTheme", "graphite") ?: "graphite"
        set(v) { ps("phoneTheme", v) }

    /** Recent searches, newest first. */
    var searchHistory: List<String>
        get() = strList("searchHistory")
        set(v) = put("searchHistory", JSONArray(v.take(30)))

    fun addSearch(q: String) {
        val t = q.trim()
        if (t.length < 2 || searchHistoryOff) return
        searchHistory = listOf(t) + searchHistory.filterNot { it.equals(t, ignoreCase = true) }
    }

    var searchHistoryOff: Boolean
        get() = sp.getBoolean("searchHistoryOff", false)
        set(v) { sp.putBoolean("searchHistoryOff", v) }

    /** Movies and series played, newest first (their favourite keys: "mv:..." and "se:..."). */
    var watched: List<String>
        get() = strList("watched")
        set(v) = put("watched", JSONArray(v.take(200)))

    fun addWatched(key: String) { watched = listOf(key) + watched.filter { it != key } }

    // ---------- Settings ----------

    var lastChannel: String?
        get() = sp.getString("lastChannel", null)
        set(v) { ps("lastChannel", v) }

    /** Prefer .m3u8 over .ts for Xtream live streams (some panels play smoother one way). */
    var xtreamHls: Boolean
        get() = sp.getBoolean("xtreamHls", false)
        set(v) { sp.putBoolean("xtreamHls", v) }

    /** Recordings (see [Recordings]); not in the backup, since their files stay on the device. */
    var recordingsJson: JSONArray
        get() = arr("recordings")
        set(v) = put("recordings", v)

    var bufferMs: Int
        get() = sp.getInt("bufferMs", 6000)
        set(v) { sp.putInt("bufferMs", v) }

    // ---------- helpers ----------

    private fun arr(k: String) = try { JSONArray(sp.getString(k, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
    private fun obj(k: String) = try { JSONObject(sp.getString(k, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun strList(k: String) = arr(k).let { a -> (0 until a.length()).map { a.getString(it) } }
    private fun put(k: String, v: Any) { ps(k, v.toString()) }

    /** Writes [k], and refreshes the backup when it is a value worth keeping across reinstalls. */
    private fun ps(k: String, v: String?) {
        sp.putString(k, v)
        if (k in backedUp) backup?.invoke(snapshot())
    }

    // ---------- backup (iPhone: kept in the Keychain, which outlives reinstalling the app) ----------

    /** Set by the platform: stores a snapshot somewhere that survives the app being reinstalled. */
    var backup: ((String) -> Unit)? = null

    /** Playlists (with their logins), guides, favourites, Sports picks and keys. */
    private val backedUp = setOf("sources", "active", "extraEpg", "favs.json", "favTeams.json", "sportsComps.json", "sportsEnabled.json",
        "sportsLeagues.json", "tmdbKey", "sportsDbKey", "sportMonksKey", "sportsCountry", "searchHistory", "watched", "recentCh", "phoneTheme")

    fun snapshot(): String = JSONObject().also { o -> for (k in backedUp) sp.getString(k, null)?.let { o.put(k, it) } }.toString()

    /** Puts a snapshot back (only into an empty app: one with no playlists yet). Returns whether it did. */
    fun restore(json: String): Boolean {
        if (sources.isNotEmpty()) return false
        val o = try { JSONObject(json) } catch (e: Exception) { return false }
        if (!o.has("sources")) return false
        for (k in o.keys()) if (k in backedUp) sp.putString(k, o.optString(k))
        return sources.isNotEmpty()
    }
    /** Sets are kept as JSON arrays; "<key>.json" (earlier Fire TV versions used Android string sets under "<key>"). */
    private fun strSet(k: String): Set<String> = strList("$k.json").toSet()
    private fun putSet(k: String, v: Set<String>) = put("$k.json", JSONArray(v.toList()))
}
