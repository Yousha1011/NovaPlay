package io.github.novatv.play.sports

import io.github.novatv.play.data.Http
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ESPN's public score feeds: no key, no account. The scoreboard feed takes a date range; the header feed lists
 * the current and next matches of a whole sport.
 */
object Espn {
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val HEADER = "https://site.web.api.espn.com/apis/v2/scoreboard/header"

    private val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    /** Coming days' matches by "league|date", kept an hour: fixtures change rarely, today's scores often. */
    private val dayCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<SportEvent>>>()

    /** Matches of [league] from yesterday to [daysAhead] days ahead. */
    fun fetch(league: League, daysAhead: Int = 7): List<SportEvent> = try {
        when {
            league.header != null -> header(league)
            league.path != null -> {
                // ESPN stopped answering date ranges ("dates=A-B" returns nothing for most sports), so one request per
                // day: today live, the next days from an hourly cache.
                val now = System.currentTimeMillis()
                val today = scoreboard(league, JSONObject(Http.get("$SITE/${league.path}/scoreboard?limit=300")))
                val ahead = (1..minOf(daysAhead, 3)).flatMap { n ->
                    val d = synchronized(day) { day.format(Date(now + n * 86_400_000L)) }
                    val key = "${league.id}|$d"
                    dayCache[key]?.takeIf { now - it.first < 3600_000L }?.second ?: try {
                        scoreboard(league, JSONObject(Http.get("$SITE/${league.path}/scoreboard?dates=$d&limit=300"))).also { dayCache[key] = now to it }
                    } catch (e: Exception) { emptyList() }
                }
                today + ahead
            }
            else -> emptyList()
        }
    } catch (e: Exception) { emptyList() }

    private fun scoreboard(league: League, o: JSONObject): List<SportEvent> {
        val out = ArrayList<SportEvent>()
        val events = o.optJSONArray("events") ?: return out
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val name = e.optString("name")
            val groupings = e.optJSONArray("groupings")
            if (groupings != null) {
                // Tennis: one event per tournament, matches inside its groupings.
                for (g in 0 until groupings.length()) {
                    val comps = groupings.optJSONObject(g)?.optJSONArray("competitions") ?: continue
                    for (c in 0 until comps.length()) comps.optJSONObject(c)?.let { comp(league, e, it, name)?.let(out::add) }
                }
                continue
            }
            val comps = e.optJSONArray("competitions") ?: continue
            val first = comps.optJSONObject(0) ?: continue
            if (league.sport == Sport.MMA) {
                // A fight night is one listing; its bouts all air on the same channel.
                out.add(event(league, e.optString("id"), name, null, e.optString("date"), e.optJSONObject("status"),
                    null, null, broadcasts(first), venue(first) ?: venue(e)))
            } else if (comps.length() == 1 && (first.optJSONArray("competitors")?.length() ?: 0) == 2) {
                comp(league, e, first, null)?.let(out::add)
            } else {
                // F1 weekends (practice, qualifying, race), golf rounds, UFC cards: one entry per session.
                for (c in 0 until comps.length()) {
                    val cp = comps.optJSONObject(c) ?: continue
                    val kind = cp.optJSONObject("type")?.optString("abbreviation")?.ifBlank { null }
                    if (comps.length() > 1 && kind == null) continue
                    out.add(event(league, e.optString("id") + "-" + c, name, kind, cp.optString("date", e.optString("date")),
                        cp.optJSONObject("status") ?: e.optJSONObject("status"), null, null, broadcasts(cp), venue(cp) ?: venue(e)))
                }
            }
        }
        return out
    }

    /** One match with two sides (teams or players). */
    private fun comp(league: League, e: JSONObject, c: JSONObject, subtitle: String?): SportEvent? {
        val cs = c.optJSONArray("competitors") ?: return null
        val sides = (0 until cs.length()).mapNotNull { cs.optJSONObject(it) }.map { side(it) to it.optString("homeAway") }
        val home = sides.firstOrNull { it.second == "home" }?.first ?: sides.getOrNull(0)?.first
        val away = sides.firstOrNull { it.second == "away" }?.first ?: sides.getOrNull(1)?.first
        val title = if (home != null && away != null) "${home.name} vs ${away.name}" else e.optString("name")
        return event(league, c.optString("id", e.optString("id")), title, subtitle, c.optString("date", e.optString("date")),
            c.optJSONObject("status") ?: e.optJSONObject("status"), home, away, broadcasts(c), venue(c) ?: venue(e))
    }

    private fun side(o: JSONObject): Side {
        val t = o.optJSONObject("team") ?: o.optJSONObject("athlete") ?: o
        val logo = t.optString("logo").ifBlank { null } ?: t.optJSONObject("flag")?.optString("href")?.ifBlank { null }
            ?: t.optJSONArray("logos")?.optJSONObject(0)?.optString("href")
        val name = t.optString("displayName").ifBlank { t.optString("name") }
        return Side(t.optString("id"), name, t.optString("shortDisplayName").ifBlank { name }, t.optString("abbreviation"),
            logo, o.optString("score").ifBlank { null }?.takeIf { it != "null" })
    }

    private fun event(league: League, id: String, title: String, subtitle: String?, date: String, status: JSONObject?,
                      home: Side?, away: Side?, broadcasts: List<String>, venue: String?): SportEvent {
        val type = status?.optJSONObject("type")
        val state = when (type?.optString("state")) { "in" -> State.LIVE; "post" -> State.POST; else -> State.PRE }
        return SportEvent("${league.id}:$id", league, subtitle, title, parseTime(date), state,
            type?.optString("shortDetail").orEmpty(), home, away, broadcasts, venue)
    }

    private fun broadcasts(c: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        c.optJSONArray("broadcasts")?.let { b -> for (i in 0 until b.length()) b.optJSONObject(i)?.optJSONArray("names")?.strings()?.let(out::addAll) }
        c.optJSONArray("geoBroadcasts")?.let { b -> for (i in 0 until b.length()) b.optJSONObject(i)?.optJSONObject("media")?.optString("shortName")?.ifBlank { null }?.let(out::add) }
        return out.toList()
    }

    private fun venue(o: JSONObject) = o.optJSONObject("venue")?.optString("fullName")?.ifBlank { null }

    /** The header feed: every current and upcoming match in [League.header]'s sport, grouped by competition. */
    private fun header(league: League): List<SportEvent> {
        val o = JSONObject(Http.get("$HEADER?sport=${league.header}&lang=en&region=gb"))
        val out = ArrayList<SportEvent>()
        val sports = o.optJSONArray("sports") ?: return out
        for (s in 0 until sports.length()) {
            val leagues = sports.optJSONObject(s)?.optJSONArray("leagues") ?: continue
            for (l in 0 until leagues.length()) {
                val lg = leagues.optJSONObject(l) ?: continue
                val comp = lg.optString("name")
                val evs = lg.optJSONArray("events") ?: continue
                for (i in 0 until evs.length()) {
                    val e = evs.optJSONObject(i) ?: continue
                    val cs = e.optJSONArray("competitors")
                    val sides = (0 until (cs?.length() ?: 0)).mapNotNull { cs!!.optJSONObject(it) }.map {
                        Side(it.optString("id"), it.optString("displayName").ifBlank { it.optString("name") },
                            it.optString("name").ifBlank { it.optString("displayName") }, it.optString("abbreviation"),
                            it.optString("logo").ifBlank { null }, it.optString("score").ifBlank { null }) to it.optString("homeAway")
                    }
                    val home = sides.firstOrNull { it.second == "home" }?.first ?: sides.getOrNull(0)?.first
                    val away = sides.firstOrNull { it.second == "away" }?.first ?: sides.getOrNull(1)?.first
                    val full = e.optJSONObject("fullStatus")
                    val ev = event(league, e.optString("id"), if (home != null && away != null) "${home.name} vs ${away.name}" else e.optString("name"),
                        comp, e.optString("date"), full, home, away, emptyList(), e.optString("location").ifBlank { null })
                    // The header gives a bare "pre/in/post" when fullStatus is missing.
                    out.add(if (full == null) ev.copy(state = when (e.optString("status")) { "in" -> State.LIVE; "post" -> State.POST; else -> State.PRE },
                        detail = e.optString("summary")) else ev)
                }
            }
        }
        return out
    }

    private val iso = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mmX")
        .map { SimpleDateFormat(it, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    @Synchronized
    fun parseTime(s: String): Long {
        for (f in iso) try { return f.parse(s)!!.time } catch (e: Exception) { }
        return 0L
    }

    private fun JSONArray.strings() = (0 until length()).mapNotNull { optString(it).ifBlank { null } }
}
