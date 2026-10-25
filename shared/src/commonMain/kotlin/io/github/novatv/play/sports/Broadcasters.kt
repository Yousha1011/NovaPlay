package io.github.novatv.play.sports

import io.github.novatv.play.App
import io.github.novatv.play.data.Epg
import io.github.novatv.play.data.Http
import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.Time
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.platform.SafeMap
import io.github.novatv.play.platform.urlEncode

/** A TV channel that shows a match, and in which country. */
data class Broadcast(val channel: String, val country: String?, val source: String, val logo: String? = null)

/**
 * Who shows a match: TheSportsDB's TV listings (every sport, by country), SportMonks' TV stations (football) and the
 * networks ESPN names. TheSportsDB and SportMonks need the user's own keys (Settings > Sports); each is asked once per
 * day and cached, then matches are found by their two sides' names.
 */
object Broadcasters {
    /** date -> (normalised "home vs away" -> broadcasts), per source. */
    private val dbDays = SafeMap<String, Pair<Long, List<Pair<String, Broadcast>>>>()
    private val smDays = SafeMap<String, Pair<Long, List<Pair<String, Broadcast>>>>()
    private const val TTL = 3 * 3600_000L

    /** Broadcasts for [e], those in the user's country first. Blocking; call off the main thread. */
    fun forEvent(e: SportEvent): List<Broadcast> {
        val out = ArrayList<Broadcast>()
        val dates = listOf(Time.isoDay(e.start), Time.isoDay(e.start - 86_400_000L / 2), Time.isoDay(e.start + 86_400_000L / 2)).distinct()
        val store = App.store
        if (premiumDb()) for (d in dates) out += find(e, sportsDbDay(d))
        else out += sportsDbFree(e)
        if (store.sportMonksKey.isNotBlank() && e.league.sport == Sport.FOOTBALL) for (d in dates) out += find(e, sportMonksDay(d))
        e.broadcasts.forEach { out += Broadcast(it, null, "ESPN") }
        val country = store.sportsCountry
        return out.distinctBy { Epg.norm(it.channel) + "|" + it.country }
            .sortedBy { if (country.isNotBlank() && it.country.equals(country, true)) 0 else if (it.country == null) 1 else 2 }
    }

    /** The entries of [list] whose title names both of [e]'s sides (or, for one-sided events, its title). */
    private fun find(e: SportEvent, list: List<Pair<String, Broadcast>>): List<Broadcast> {
        val keys = if (e.home != null && e.away != null) listOf(sideKeys(e.home), sideKeys(e.away)) else listOf(listOf(Epg.norm(e.title)))
        return list.filter { (title, _) -> keys.all { alts -> alts.any { it.length >= 3 && title.contains(it) } } }.map { it.second }
    }

    /** Ways a side is written: full name, short name, its longest word ("Manchester United" -> "manchester"). */
    private fun sideKeys(s: Side): List<String> = buildList {
        add(Epg.norm(s.name)); add(Epg.norm(s.short))
        (s.name.split(' ', '-') + s.short.split(' ', '-')).map { Epg.norm(it) }.filter { it.length >= 4 && it !in generic }
            .maxByOrNull { it.length }?.let(::add)
    }.filter { it.isNotEmpty() }.distinct()

    private val generic = setOf("united", "city", "club", "real", "athletic", "sporting", "town", "rovers", "county", "women", "division")

    /** A personal (premium) key; the free tier uses TheSportsDB's public key "123". */
    private fun premiumDb() = App.store.sportsDbKey.isNotBlank() && App.store.sportsDbKey.trim() != FREE_KEY
    private const val FREE_KEY = "123"
    private val freeCache = SafeMap<String, Pair<Long, List<Broadcast>>>()

    /**
     * Free tier, one match at a time: find the match by its two sides' names (searchevents), then its TV listings
     * (lookuptv). Two requests per match, cached for a few hours; the free key allows 30 a minute.
     */
    private fun sportsDbFree(e: SportEvent): List<Broadcast> {
        val home = e.home ?: return emptyList()
        val away = e.away ?: return emptyList()
        freeCache[e.id]?.let { if (nowMs() - it.first < TTL) return it.second }
        val list = try {
            val q = urlEncode("${home.name} vs ${away.name}".replace(' ', '_'))
            val found = JSONObject(Http.get("https://www.thesportsdb.com/api/v1/json/$FREE_KEY/searchevents.php?e=$q")).optJSONArray("event") ?: JSONArray()
            // The fixture nearest the match's start (the same pairing plays more than once a season).
            val ev = (0 until found.length()).mapNotNull { found.optJSONObject(it) }.minByOrNull { o ->
                val t = Espn.parseTime(o.optString("strTimestamp").let { if (it.length == 19) it + "Z" else it })
                if (t == 0L) Long.MAX_VALUE else kotlin.math.abs(t - e.start)
            }?.takeIf { o -> Espn.parseTime(o.optString("strTimestamp") + "Z").let { it == 0L || kotlin.math.abs(it - e.start) < 2 * 86_400_000L } }
            if (ev == null) emptyList() else {
                val tv = JSONObject(Http.get("https://www.thesportsdb.com/api/v1/json/$FREE_KEY/lookuptv.php?id=${ev.optString("idEvent")}"))
                    .optJSONArray("tvevent") ?: JSONArray()
                (0 until tv.length()).mapNotNull { tv.optJSONObject(it) }.mapNotNull { t ->
                    val ch = t.optString("strChannel")
                    if (ch.isBlank()) null else Broadcast(ch, t.optString("strCountry").ifBlank { null }, "TheSportsDB", t.optString("strLogo").ifBlank { null })
                }
            }
        } catch (ex: Exception) { null }
        if (list != null) freeCache[e.id] = nowMs() to list
        return list ?: emptyList()
    }

    // ---------- TheSportsDB: eventstv.php lists every televised event of a day with its channel and country ----------

    private fun sportsDbDay(d: String): List<Pair<String, Broadcast>> {
        dbDays[d]?.let { if (nowMs() - it.first < TTL) return it.second }
        val key = urlEncode(App.store.sportsDbKey.trim())
        val list = try {
            val o = JSONObject(Http.get("https://www.thesportsdb.com/api/v1/json/$key/eventstv.php?d=$d"))
            val a = o.optJSONArray("tvevents") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { t ->
                val ch = t.optString("strChannel")
                if (ch.isBlank()) return@mapNotNull null
                Epg.norm(t.optString("strEvent")) to Broadcast(ch, t.optString("strCountry").ifBlank { null }, "TheSportsDB",
                    t.optString("strLogo").ifBlank { null })
            }
        } catch (e: Exception) { null }
        if (list != null) dbDays[d] = nowMs() to list
        return list ?: emptyList()
    }

    // ---------- SportMonks: football fixtures of a day with their TV stations ----------

    private fun sportMonksDay(d: String): List<Pair<String, Broadcast>> {
        smDays[d]?.let { if (nowMs() - it.first < TTL) return it.second }
        val token = urlEncode(App.store.sportMonksKey.trim())
        val out = ArrayList<Pair<String, Broadcast>>()
        var ok = false
        try {
            var page = 1
            while (page <= 10) {
                val o = JSONObject(Http.get("https://api.sportmonks.com/v3/football/fixtures/date/$d?api_token=$token" +
                    "&include=participants;tvStations.tvStation&per_page=50&page=$page"))
                val data = o.optJSONArray("data") ?: break
                ok = true
                for (i in 0 until data.length()) {
                    val f = data.optJSONObject(i) ?: continue
                    val title = Epg.norm(f.optString("name"))
                    val stations = f.optJSONArray("tvstations") ?: f.optJSONArray("tvStations") ?: continue
                    for (k in 0 until stations.length()) {
                        val st = stations.optJSONObject(k) ?: continue
                        val tv = st.optJSONObject("tvstation") ?: st.optJSONObject("tvStation") ?: continue
                        val name = tv.optString("name")
                        if (name.isBlank()) continue
                        out += title to Broadcast(name, null, "SportMonks", tv.optString("image_path").ifBlank { null })
                    }
                }
                if (o.optJSONObject("pagination")?.optBoolean("has_more") != true) break
                page++
            }
        } catch (e: Exception) { }
        if (ok) smDays[d] = nowMs() to out
        return out
    }

    /** Checks a key: null when it works, else what went wrong. */
    fun testSportsDb(): String? = if (!premiumDb()) null else try {
        val key = urlEncode(App.store.sportsDbKey.trim())
        val t = Http.get("https://www.thesportsdb.com/api/v1/json/$key/eventstv.php?d=${Time.isoDay(nowMs())}")
        val n = JSONObject(t).optJSONArray("tvevents")?.length() ?: 0
        dbDays.clear()
        if (n <= 1) "Key accepted, but only test data comes back. Check it is your premium key." else null
    } catch (e: Exception) { "Not accepted (${e.message})" }

    fun testSportMonks(): String? = try {
        val token = urlEncode(App.store.sportMonksKey.trim())
        JSONObject(Http.get("https://api.sportmonks.com/v3/football/fixtures/date/${Time.isoDay(nowMs())}?api_token=$token&per_page=1"))
        smDays.clear(); null
    } catch (e: Exception) { "Not accepted (${e.message})" }
}
