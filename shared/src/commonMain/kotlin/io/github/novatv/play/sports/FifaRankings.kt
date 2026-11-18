package io.github.novatv.play.sports

import io.github.novatv.play.data.Http
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.SafeMap
import io.github.novatv.play.platform.nowMs

/**
 * The FIFA world rankings (men's, or women's for women's sides), from FIFA's own rankings feed, with each team's
 * flag: the table of a national-team match whose competition has none of its own (friendlies). Kept 12 hours (FIFA
 * publishes a few times a year).
 */
object FifaRankings {
    private const val FEED = "https://api.fifa.com/api/v3/rankings?locale=en&count=300&gender="
    private val cache = SafeMap<Int, Pair<Long, Standings>>()

    /** FIFA's names written as ESPN writes them, so the two sides of a match are found in the table. */
    private val aliases = mapOf(
        "USA" to "United States", "Korea Republic" to "South Korea", "Korea DPR" to "North Korea", "IR Iran" to "Iran",
        "Côte d'Ivoire" to "Ivory Coast", "Türkiye" to "Turkey", "China PR" to "China", "Czechia" to "Czech Republic",
        "Cabo Verde" to "Cape Verde", "Bosnia and Herzegovina" to "Bosnia-Herzegovina", "Congo DR" to "DR Congo",
        "Kyrgyz Republic" to "Kyrgyzstan", "Chinese Taipei" to "Chinese Taipei", "Republic of Ireland" to "Republic of Ireland",
    )

    /** Whether [e] is between two national sides (both in FIFA's list), and so ranked by FIFA. */
    fun forEvent(e: SportEvent): Standings? {
        if (e.league.sport != Sport.FOOTBALL) return null
        val women = e.teams.any { "women" in it.name.lowercase() }
        val t = forGender(if (women) 2 else 1) ?: return null
        val names = t.groups.flatMap { it.rows }.map { it.name.lowercase() }.toSet()
        fun ranked(s: Side) = s.name.removeSuffix(" Women").trim().lowercase() in names
        return t.takeIf { e.teams.size == 2 && e.teams.all(::ranked) }
    }

    /** A country's flag by its name, from FIFA's list (for other sports' tables: cricket nations); null if not a FIFA member. */
    fun flagFor(country: String): String? {
        val n = country.trim().lowercase().let { if (it == "united states of america") "united states" else if (it == "uae") "united arab emirates" else it }
        return forGender(1)?.groups?.flatMap { it.rows }?.firstOrNull { it.name.lowercase() == n }?.logo
    }

    fun forGender(gender: Int): Standings? {
        val now = nowMs()
        cache[gender]?.takeIf { now - it.first < 12 * 3_600_000L }?.let { return it.second }
        return runCatching { parse(gender, Http.get(FEED + gender), now) }.getOrNull()?.also { cache[gender] = now to it }
    }

    internal fun parse(gender: Int, raw: String, now: Long): Standings? {
        val arr = JSONObject(raw).optJSONArray("Results") ?: return null
        val rows = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { r ->
            val fifaName = r.optJSONArray("TeamName")?.optJSONObject(0)?.optString("Description")?.ifBlank { null } ?: return@mapNotNull null
            val name = aliases[fifaName] ?: fifaName
            val code = r.optString("IdCountry")
            val points = r.optDouble("DecimalTotalPoints", r.optDouble("TotalPoints", 0.0))
            val prev = r.optInt("PrevRank", 0)
            val rank = r.optInt("Rank", 0)
            StandingsRow(id = "fifa:$code", name = name, shortName = name, abbr = code,
                logo = code.ifBlank { null }?.let { "https://api.fifa.com/api/v3/picture/flags-sq-2/$it" },
                pos = rank,
                values = mapOf("gamesPlayed" to r.optInt("Matches").toString(), "move" to (if (prev > 0) (prev - rank).let { m -> if (m > 0) "+$m" else "$m" } else "–"),
                    "points" to ((points * 10).toLong() / 10.0).toString()))
        }.filter { it.pos > 0 }.sortedBy { it.pos }
        if (rows.isEmpty()) return null
        return Standings(if (gender == 2) "fifa.women" else "fifa.men", null, StandingsKind.TABLE,
            listOf(StandingsColumn("gamesPlayed", "M"), StandingsColumn("move", "+/-"), StandingsColumn("points", "PTS")),
            listOf(StandingsGroup("", null, rows)), "FIFA", now, title = if (gender == 2) "FIFA WOMEN'S WORLD RANKING" else "FIFA MEN'S WORLD RANKING")
    }
}
