package io.github.novatv.play.sports

import io.github.novatv.play.data.Http
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.SafeMap
import io.github.novatv.play.platform.nowMs

/**
 * The ICC's men's team rankings (Test, ODI, T20I), from the feed icc-cricket.com's own rankings pages read. Shown as
 * the table of an international match between full national sides (a tour, a friendly), which has no table of its
 * own. Kept 6 hours (the ICC updates them after each series).
 */
object IccRankings {
    enum class Format(val feed: String, val label: String) { TEST("test", "Test"), ODI("odi", "ODI"), T20("t20", "T20I") }

    private const val FEED = "https://assets-icc.sportz.io/cricket/v1/ranking?client_id=tPZJbRgIub3Vua93/DWtyQ==&feed_format=json&lang=en&type=team"
    private val cache = SafeMap<Format, Pair<Long, Standings>>()

    /** [e]'s format from its name ("2nd Test · Day 1", "3rd ODI", "1st T20I"), or null. */
    fun format(e: SportEvent): Format? {
        val text = listOfNotNull(e.detail, e.title, e.subtitle).joinToString(" ")
        return when {
            Regex("""\bTest\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Format.TEST
            Regex("""\bODI\b|One[- ]Day""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Format.ODI
            Regex("""\bT20I?\b|Twenty20""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Format.T20
            else -> null
        }
    }

    /**
     * The rankings for [e] when both sides are men's national sides (not A, women's or youth teams, whose rankings
     * differ) and its format is known; else null. Blocking.
     */
    fun forEvent(e: SportEvent): Standings? {
        val sides = e.teams
        if (sides.size != 2) return null
        // Women's, A, youth and invitational sides are ranked apart (or not at all).
        val tails = listOf(" women", " a", " u19", " under-19s", " xi", " emerging", " legends", " masters")
        if (sides.any { s -> tails.any { s.name.lowercase().trim().endsWith(it) } }) return null
        val f = format(e) ?: return null
        val t = forFormat(f) ?: return null
        // Both sides in that format's list (the T20I one has over 100 nations, Falkland Islands to Costa Rica).
        val ranked = t.groups.flatMap { it.rows }.map { it.name.lowercase() }.toSet()
        fun inList(name: String) = name.lowercase().trim().let { n -> n in ranked || Cricket.canonical(name).lowercase() in ranked ||
            (n == "united states of america" && "usa" in ranked) || (n == "uae" && "united arab emirates" in ranked) }
        return t.takeIf { sides.all { inList(it.name) } }
    }

    fun forFormat(f: Format): Standings? {
        val now = nowMs()
        cache[f]?.takeIf { now - it.first < 6 * 3_600_000L }?.let { return it.second }
        return runCatching { parse(f, Http.get("$FEED&comp_type=${f.feed}"), now) }.getOrNull()?.also { cache[f] = now to it }
    }

    /**
     * A national side's flag, as ESPN shows it on cricket scoreboards (its team image, by Cricinfo's team id); null
     * for a side not listed (the table shows the name alone).
     */
    internal fun flag(team: String): String? {
        val id = mapOf("England" to 1, "Australia" to 2, "South Africa" to 3, "West Indies" to 4, "New Zealand" to 5, "India" to 6,
            "Pakistan" to 7, "Sri Lanka" to 8, "Zimbabwe" to 9, "USA" to 11, "United States of America" to 11, "Netherlands" to 15,
            "Canada" to 17, "Hong Kong" to 19, "Papua New Guinea" to 20, "Bangladesh" to 25, "Kenya" to 26, "United Arab Emirates" to 27,
            "UAE" to 27, "Namibia" to 28, "Ireland" to 29, "Scotland" to 30, "Nepal" to 32, "Oman" to 37, "Afghanistan" to 40)[team.trim()] ?: return null
        return "https://a.espncdn.com/i/teamlogos/cricket/500/$id.png"
    }

    internal fun parse(f: Format, raw: String, now: Long): Standings? {
        val rank = JSONObject(raw).optJSONObject("data")?.optJSONObject("bat-rank")?.optJSONArray("rank") ?: return null
        val rows = (0 until rank.length()).mapNotNull { rank.optJSONObject(it) }.mapNotNull { r ->
            val name = r.optString("team_name").ifBlank { r.optString("Country") }.ifBlank { return@mapNotNull null }
            StandingsRow(id = "icc:${r.optString("team_id")}", name = name, shortName = name, abbr = r.optString("shortname"), logo = flag(name) ?: FifaRankings.flagFor(name),
                pos = r.optString("no").toIntOrNull() ?: 0,
                values = mapOf("gamesPlayed" to r.optString("Matches"), "iccPoints" to r.optString("Points"), "points" to r.optString("Rating")))
        }.sortedBy { it.pos }
        if (rows.isEmpty()) return null
        return Standings("icc.${f.feed}", null, StandingsKind.TABLE,
            listOf(StandingsColumn("gamesPlayed", "M"), StandingsColumn("iccPoints", "PTS"), StandingsColumn("points", "RTG")),
            listOf(StandingsGroup("", null, rows)), "ICC", now, title = "ICC MEN'S ${f.label.uppercase()} TEAM RANKINGS")
    }
}
