package io.github.novatv.play.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the app remembers: sources, favourites, watch progress, recent channels and Sports choices.
 * Small JSON values in SharedPreferences; the loaded libraries are cached separately ([LibraryCache]).
 */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("novaplay", Context.MODE_PRIVATE)

    var sources: List<Source>
        get() = arr("sources").let { a -> (0 until a.length()).map { Source.fromJson(a.getJSONObject(it)) } }
        set(v) = put("sources", JSONArray(v.map { it.toJson() }))

    var activeSourceId: String?
        get() = sp.getString("active", null)
        set(v) { sp.edit().putString("active", v).apply() }

    /** Favourite channels, movies and series as "kind:sourceId:itemId". */
    var favourites: Set<String>
        get() = sp.getStringSet("favs", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("favs", v).apply() }

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

    // ---------- Sports ----------

    /** Followed leagues (Sports catalogue ids); empty means all. */
    var sportsLeagues: Set<String>
        get() = sp.getStringSet("sportsLeagues", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("sportsLeagues", v).apply() }

    /** Favourite teams as "sport|teamId|name". */
    var favouriteTeams: Set<String>
        get() = sp.getStringSet("favTeams", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("favTeams", v).apply() }

    /** Sports shown in the Sports centre (Sport names); empty means all. */
    var sportsEnabled: Set<String>
        get() = sp.getStringSet("sportsEnabled", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("sportsEnabled", v).apply() }

    /** Competitions picked by name for sports ESPN lists as one feed (cricket, rugby): "SPORT|competition". */
    var sportsComps: Set<String>
        get() = sp.getStringSet("sportsComps", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("sportsComps", v).apply() }

    /** The user's own API keys, typed by them in Settings. */
    var sportsDbKey: String
        get() = sp.getString("sportsDbKey", "") ?: ""
        set(v) { sp.edit().putString("sportsDbKey", v.trim()).apply() }

    var sportMonksKey: String
        get() = sp.getString("sportMonksKey", "") ?: ""
        set(v) { sp.edit().putString("sportMonksKey", v.trim()).apply() }

    /** Broadcasters from this country are listed first ("United Kingdom", as TheSportsDB names countries). */
    var sportsCountry: String
        get() = sp.getString("sportsCountry", "") ?: ""
        set(v) { sp.edit().putString("sportsCountry", v).apply() }

    /** Extra XMLTV guides imported alongside the playlist's own. */
    var extraEpg: List<String>
        get() = strList("extraEpg")
        set(v) = put("extraEpg", JSONArray(v.filter { it.isNotBlank() }))

    /** Adds the sports guides once, after the user's own; removing them in Settings later sticks. */
    fun addSportsGuides() {
        if (sp.getBoolean("sportsGuidesAdded", false)) return
        extraEpg = (extraEpg + sportsGuides).distinct()
        sp.edit().putBoolean("sportsGuidesAdded", true).apply()
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

    // ---------- Settings ----------

    var lastChannel: String?
        get() = sp.getString("lastChannel", null)
        set(v) { sp.edit().putString("lastChannel", v).apply() }

    /** Prefer .m3u8 over .ts for Xtream live streams (some panels play smoother one way). */
    var xtreamHls: Boolean
        get() = sp.getBoolean("xtreamHls", false)
        set(v) { sp.edit().putBoolean("xtreamHls", v).apply() }

    var bufferMs: Int
        get() = sp.getInt("bufferMs", 6000)
        set(v) { sp.edit().putInt("bufferMs", v).apply() }

    // ---------- helpers ----------

    private fun arr(k: String) = try { JSONArray(sp.getString(k, "[]")) } catch (e: Exception) { JSONArray() }
    private fun obj(k: String) = try { JSONObject(sp.getString(k, "{}")!!) } catch (e: Exception) { JSONObject() }
    private fun strList(k: String) = arr(k).let { a -> (0 until a.length()).map { a.getString(it) } }
    private fun put(k: String, v: Any) { sp.edit().putString(k, v.toString()).apply() }
}
