package io.github.novatv.play.sports

import io.github.novatv.play.data.Http
import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.nowMs

/**
 * A team's figures in one match, from ESPN's match summary: possession, shots, corners and the like where the sport
 * has them (with the other side's beside each), or for cricket each innings and the best batters and bowlers.
 * Empty before the match starts. Kept a minute (they change while it's on).
 */
object MatchStats {
    /** One figure: its name, the team's value and the opponent's (null where there's no counterpart). */
    data class Line(val label: String, val value: String, val other: String? = null)

    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private val cache = io.github.novatv.play.platform.SafeMap<String, Pair<Long, JSONObject?>>()

    /** [side]'s figures in [e], or empty when ESPN has none (yet). Blocking. */
    fun forSide(e: SportEvent, side: Side): List<Line> {
        val o = summary(e) ?: return emptyList()
        return if (e.league.sport == Sport.CRICKET) cricket(o, side) else teamStats(o, side, e.teams.firstOrNull { it.id != side.id })
    }

    internal fun summary(e: SportEvent): JSONObject? {
        val id = e.id.substringAfterLast(':')
        val path = e.league.path ?: e.espnLeague?.let { "${e.league.sport.espnSport()}/$it" } ?: return null
        val key = "$path|$id"
        cache[key]?.takeIf { nowMs() - it.first < 60_000L }?.let { return it.second }
        val o = runCatching { JSONObject(Http.get("$SITE/$path/summary?event=$id")) }.getOrNull()
        cache[key] = nowMs() to o
        return o
    }

    private fun Sport.espnSport() = when (this) { Sport.CRICKET -> "cricket"; Sport.RUGBY -> "rugby"; else -> name.lowercase() }

    /** The boxscore's figures, the ones that tell a match first (football: possession, shots...). */
    internal fun teamStats(o: JSONObject, side: Side, other: Side?): List<Line> {
        val teams = o.optJSONObject("boxscore")?.optJSONArray("teams") ?: return emptyList()
        fun statsOf(s: Side?): Map<String, Pair<String, String>> {
            if (s == null) return emptyMap()
            val t = objects(teams).firstOrNull { t -> t.optJSONObject("team")?.let { it.optString("id") == s.id || it.optString("displayName").equals(s.name, true) } == true }
                ?: return emptyMap()
            return objects(t.optJSONArray("statistics")).associate { st ->
                st.optString("name") to ((st.optString("label").ifBlank { st.optString("name") }) to st.optString("displayValue"))
            }
        }
        val mine = statsOf(side); val theirs = statsOf(other)
        if (mine.isEmpty()) return emptyList()
        val first = listOf("possessionPct", "totalShots", "shotsOnTarget", "wonCorners", "foulsCommitted", "yellowCards", "redCards", "offsides",
            "saves", "totalPasses", "accuratePasses")
        val order = first.filter { it in mine } + mine.keys.filter { it !in first }
        return order.take(12).map { k ->
            val (label, v) = mine.getValue(k)
            val pretty = label.lowercase().replaceFirstChar { it.uppercase() }.replace("Possession", "Possession %")
            Line(pretty, v, theirs[k]?.second)
        }
    }

    /** Cricket: each of the side's innings, then its best batters and the best bowlers against the other side. */
    internal fun cricket(o: JSONObject, side: Side): List<Line> {
        val cards = objects(o.optJSONArray("matchcards"))
        fun mine(c: JSONObject) = sameName(c.optString("teamName"), side.name) || sameName(c.optString("teamName"), side.short)
        val out = ArrayList<Line>()
        for (c in cards.filter { it.optString("headline") == "Batting" && mine(it) }) {
            val n = c.optString("inningsNumber")
            out += Line("Innings $n", listOfNotNull(c.optString("runs").ifBlank { null }, c.optString("total").ifBlank { null }).joinToString(" "))
        }
        val bats = cards.filter { it.optString("headline") == "Batting" && mine(it) }.flatMap { objects(it.optJSONArray("playerDetails")) }
            .sortedByDescending { it.optString("runs").toIntOrNull() ?: -1 }.take(3)
        for (b in bats) out += Line(b.optString("playerName"), "${b.optString("runs")} (${b.optString("ballsFaced")})" +
            (if (b.optString("dismissal") == "not out") "*" else ""))
        // The side's bowlers are on the card of the innings the other side batted: "Bowling" by this team's name.
        val bowls = cards.filter { it.optString("headline") == "Bowling" && mine(it) }.flatMap { objects(it.optJSONArray("playerDetails")) }
            .sortedWith(compareByDescending<JSONObject> { it.optString("wickets").toIntOrNull() ?: -1 }.thenBy { it.optString("conceded").toIntOrNull() ?: 999 }).take(3)
        for (b in bowls) out += Line(b.optString("playerName"), "${b.optString("wickets")}/${b.optString("conceded")} (${b.optString("overs")} ov)")
        return out
    }

    private fun sameName(a: String, b: String): Boolean {
        val x = a.lowercase().trim(); val y = b.lowercase().trim()
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.startsWith("$y ") || y.startsWith("$x "))
    }

    private fun objects(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
}
