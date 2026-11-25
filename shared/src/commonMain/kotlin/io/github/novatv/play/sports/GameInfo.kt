package io.github.novatv.play.sports

import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject

/**
 * A match's full information, by sport, from ESPN's match summary: football's formations to draw on a pitch (with
 * cards, goals and who went off), its key moments and benches; cricket's scorecards (batting, bowling and
 * partnerships for each innings); other sports' box scores; then both sides' match figures. Asked for one team, only
 * that team's. Empty before ESPN has any.
 */
object GameInfo {
    /** A table: its title, the column heads, then its rows (the first column the widest); [team] whose it is (null: both). */
    data class Section(val title: String, val head: List<String>, val rows: List<List<String>>, val note: String? = null, val team: String? = null,
                       /** Cricket: "bat", "bowl" or "part", and the innings it belongs to. */
                       val kind: String = "", val innings: String = "",
                       /** Per row, the key of the player's photo (PlayerPhotos), shown before the name; null: none. */
                       val photos: List<String?>? = null,
                       /** Per row, a second player's photo key (the assister, the player going off), shown before the part after " · ". */
                       val photos2: List<String?>? = null)

    /** A starter on the pitch: [row] from the keeper (0) forward, [x] across from left (0) to right (1). */
    data class PitchPlayer(val number: String, val name: String, val row: Int, val x: Float,
                           val yellow: Boolean = false, val red: Boolean = false, val goals: Int = 0, val off: Boolean = false,
                           /** The full name, for finding the player's photo. */ val fullName: String = "")

    /** A side's formation as drawn: its starters placed, its shirts' colour (hex), its rows counting the keeper's. */
    data class Lineup(val team: String, val formation: String?, val color: String?, val rows: Int, val players: List<PitchPlayer>)

    /** A side's substitutes: number, name, and whether they came on. */
    data class Bench(val team: String, val players: List<Triple<String, String, Boolean>>)

    /**
     * Everything about a match, in the order shown: line-ups to draw (football), the tables (key moments, scorecards,
     * box scores), both sides' match figures, then the benches.
     */
    data class Info(val lineups: List<Lineup>, val sections: List<Section>, val figures: Section? = null, val benches: List<Bench> = emptyList(),
                    /** Cricket's whole match: a tab per team ([cricketTab] gives each tab's tables). */
                    val tabs: List<String> = emptyList(),
                    /** Cricket: the innings to show as tabs, and whose view (null: the whole match). */
                    val cricket: CricketPlan? = null) {
        val isEmpty get() = lineups.isEmpty() && sections.isEmpty() && figures == null && benches.isEmpty()
    }

    /** [e]'s information: the whole match, or with [side] only that team's (its line-up, innings, players, figures). Blocking. */
    fun forEvent(e: SportEvent, side: Side? = null, deep: Boolean = true): Info {
        val o = MatchStats.summary(e) ?: return Info(emptyList(), emptyList())
        val sections = when (e.league.sport) {
            Sport.CRICKET -> cricket(o)
            Sport.FOOTBALL -> football(o)
            else -> boxScore(o)
        }
        val lineups = if (e.league.sport == Sport.FOOTBALL) lineups(o) else emptyList()
        if (e.league.sport == Sport.CRICKET) {
            // A tab per side (home first); asked for one team, only its tab.
            val teams = (e.teams.map { it.name } + sections.mapNotNull { it.team }).distinctBy { it.lowercase() }
                .filter { t -> sections.any { s -> s.team != null && sameTeam(s.team, t) } || e.teams.any { it.name == t } }
            // [deep]: the earlier innings completed from the ball-by-ball feed; a match page's early load skips it.
            return Info(emptyList(), if (deep) withBallByBall(e, sections) else sections, cricket = plan(e, sections, side?.name))
        }
        val all = Info(lineups, sections, figures(o, e).firstOrNull(), if (e.league.sport == Sport.FOOTBALL) benches(o) else emptyList())
        return if (side == null) all else forSide(all, side)
    }

    /** Each side's substitutes. */
    internal fun benches(o: JSONObject): List<Bench> = objects(o.optJSONArray("rosters")).mapNotNull { r ->
        val bench = objects(r.optJSONArray("roster")).filter { !it.optBoolean("starter") }
        if (bench.isEmpty()) null else Bench(r.optJSONObject("team")?.optString("displayName").orEmpty(),
            bench.map { Triple(it.optString("jersey"), it.optJSONObject("athlete")?.optString("displayName").orEmpty(), it.optBoolean("subbedIn")) })
    }

    /** Only [side]'s part: its tables, its rows of the key moments, then the figures (its beside the other side's). */
    internal fun forSide(all: Info, side: Side): Info {
        fun mine(t: String?) = t != null && (sameTeam(t, side.name) || sameTeam(t, side.short))
        val own = all.sections.mapNotNull { s ->
            when {
                s.team == null && "Team" in s.head -> {
                    // The rows and their photos kept together.
                    val keep = s.rows.indices.filter { i -> mine(s.rows[i].getOrNull(s.head.indexOf("Team"))) }
                    s.copy(rows = keep.map { s.rows[it] }, photos = s.photos?.let { p -> keep.map { p.getOrNull(it) } },
                        photos2 = s.photos2?.let { p -> keep.map { p.getOrNull(it) } })
                }
                s.team == null -> s
                mine(s.team) -> s
                else -> null
            }?.takeIf { it.rows.isNotEmpty() }
        }
        return Info(all.lineups.filter { mine(it.team) }, own, all.figures, all.benches.filter { mine(it.team) })
    }

    private fun sameTeam(a: String, b: String): Boolean {
        val x = a.lowercase().trim(); val y = b.lowercase().trim()
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.startsWith("$y ") || y.startsWith("$x "))
    }

    /** Earlier innings worked out from the ball-by-ball ("event|innings" -> partnerships, how each batter was out): finished, so kept. */
    private val finished = io.github.novatv.play.platform.SafeMap<String, Pair<Section?, Map<String, String>>>()

    /**
     * The innings ESPN's scorecards leave out (all but the latest) completed from its ball-by-ball feed: their
     * partnerships, and the full way each batter was out ("c Jansen b Rabada"). Read 100 balls at a time, each page
     * counted and let go before the next (one innings at once was 2 MB of text, and filled a Fire TV's memory); an
     * innings worked out is kept, as it won't change. Only when Game info opens; anything that fails leaves the
     * innings as it was.
     */
    internal fun withBallByBall(e: SportEvent, sections: List<Section>): List<Section> {
        val missing = sections.filter { it.kind == "bat" && sections.none { p -> p.kind == "part" && p.innings == it.innings } }
        if (missing.isEmpty()) return sections
        val league = e.espnLeague ?: return sections
        val id = e.id.substringAfterLast(':')
        val extra = ArrayList<Section>()
        val howOut = HashMap<String, String>()
        for (bat in missing) {
            val key = "${e.id}|${bat.innings}"
            val done = finished[key] ?: run {
                val count = PartnershipCount(bat.team.orEmpty(), bat.innings)
                var page = 1; var pages = 1
                while (page <= pages && page <= 20) {
                    val items = runCatching {
                        val o = JSONObject(io.github.novatv.play.data.Http.get(
                            "https://site.api.espn.com/apis/site/v2/sports/cricket/$league/playbyplay?event=$id&period=${bat.innings}&limit=100&page=$page"))
                        val c = o.optJSONObject("commentary")
                        pages = c?.optInt("pageCount", 1) ?: 1
                        objects(c?.optJSONArray("items"))
                    }.getOrNull() ?: break
                    items.filter { it.optInt("period") == bat.innings.toIntOrNull() }.sortedBy { it.optLong("sequence") }.forEach(count::feed)
                    page++
                }
                (count.result() to count.howOut.toMap()).also { if (page > pages) finished[key] = it }
            }
            done.first?.let { extra += it }
            howOut.putAll(done.second)
        }
        // The squads' plain "caught" made "c Jansen b Rabada" where the feed says so.
        val better = sections.map { s ->
            if (s.kind != "bat" || s !in missing || howOut.isEmpty()) s
            else s.copy(rows = s.rows.map { r -> howOut[r[0]]?.let { listOf(r[0], it) + r.drop(2) } ?: r })
        }
        return better + extra
    }

    /**
     * Web text codes ESPN leaves in names and dismissals, as their characters: "c &dagger;Rickelton" (the
     * wicketkeeper's mark) -> "c †Rickelton", "&amp;" -> "&", "&#39;" -> "'".
     */
    internal fun unescape(s: String): String {
        if ('&' !in s) return s
        val named = mapOf("dagger" to "†", "Dagger" to "‡", "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
            "nbsp" to " ", "ndash" to "–", "mdash" to "—", "rsquo" to "’", "lsquo" to "‘", "bull" to "•")
        return Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""").replace(s) { m ->
            val code = m.groupValues[1]
            when {
                code.startsWith("#x") || code.startsWith("#X") -> code.drop(2).toIntOrNull(16)?.let { codePoint(it) }
                code.startsWith("#") -> code.drop(1).toIntOrNull()?.let { codePoint(it) }
                else -> named[code]
            } ?: m.value
        }
    }

    /** A character from its number, without Java-only helpers (the iPhone has none). */
    private fun codePoint(cp: Int): String? = when {
        cp < 0 || cp > 0x10FFFF -> null
        cp < 0x10000 -> cp.toChar().toString()
        else -> (cp - 0x10000).let { charArrayOf((0xD800 + (it shr 10)).toChar(), (0xDC00 + (it and 0x3FF)).toChar()).concatToString() }
    }

    /** "Mahfijul Islam c Jansen b Rabada 0 (1m 1b 0x4 0x6) SR: 0" -> "c Jansen b Rabada". */
    internal fun dismissalText(text: String, batter: String): String? {
        val rest = unescape(text).trim().removePrefix(batter).trim()
        val m = Regex("""^(.*?)\s+\d+\s*\(""").find(rest) ?: return null
        return m.groupValues[1].trim().ifBlank { null }
    }

    /** An innings' partnerships from its balls (all at once: tests and small innings). */
    internal fun partnerships(balls: List<JSONObject>, team: String, innings: String): Section? =
        PartnershipCount(team, innings).apply { balls.forEach(::feed) }.result()

    /**
     * Counts an innings' partnerships ball by ball, a page at a time: each ball carries both batters' running scores
     * and the innings' total; a wicket closes a partnership (its runs and balls since the last one, each batter's
     * share), and the pair still in makes the last, unbroken one. Also notes how each batter was out.
     */
    internal class PartnershipCount(private val team: String, private val innings: String) {
        private val rows = ArrayList<List<String>>()
        private var startTotal = 0; private var startBalls = 0
        private val startRuns = HashMap<String, Int>()
        private val last = HashMap<String, Int>()
        private var pair = listOf<String>()
        private var total = 0; private var legal = 0
        val howOut = HashMap<String, String>()

        private fun overs(n: Int) = "${n / 6}" + (if (n % 6 != 0) ".${n % 6}" else "")
        private fun ordinal(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }
        private fun close() {
            if (pair.size < 2) return
            val (a, b) = pair
            rows += listOf(ordinal(rows.size + 1), a, "${(last[a] ?: 0) - (startRuns[a] ?: 0)}", b, "${(last[b] ?: 0) - (startRuns[b] ?: 0)}",
                "${total - startTotal}", overs(legal - startBalls))
        }

        fun feed(ball: JSONObject) {
            val inn = ball.optJSONObject("innings") ?: return
            val now = listOfNotNull(ball.optJSONObject("batsman"), ball.optJSONObject("otherBatsman")).mapNotNull { x ->
                x.optJSONObject("athlete")?.optString("displayName")?.ifBlank { null }?.let { it to x.optInt("totalRuns") }
            }
            val names = now.map { it.first }
            when {
                pair.isEmpty() && names.size == 2 -> { pair = names; names.forEach { startRuns[it] = 0 } }
                // The pair changed with no wicket (retired hurt): the old one ends, the new one starts here.
                pair.size == 2 && names.size == 2 && names.toSet() != pair.toSet() -> {
                    close(); startTotal = total; startBalls = legal
                    names.forEach { startRuns[it] = last[it] ?: 0 }
                    pair = names
                }
                // A new batter after a wicket joins whoever stayed.
                pair.size < 2 && names.size == 2 -> {
                    names.filter { it !in pair }.forEach { startRuns[it] = last[it] ?: 0 }
                    pair = names
                }
            }
            now.forEach { (n, r) -> last[n] = r }
            total = inn.optInt("runs"); legal = inn.optInt("balls")
            val d = ball.optJSONObject("dismissal")
            if (d?.optBoolean("dismissal") == true) {
                close(); startTotal = total; startBalls = legal
                val gone = d.optJSONObject("batsman")?.optJSONObject("athlete")?.optString("displayName")
                gone?.let { g -> dismissalText(d.optString("text"), g)?.let { howOut[g] = it } }
                pair = pair.filter { it != gone }
                pair.forEach { startRuns[it] = last[it] ?: 0 }
            }
        }

        fun result(): Section? {
            // The pair still in (counted on a copy, so more pages could still follow).
            val all = ArrayList(rows)
            if (pair.size == 2 && (total > startTotal || legal > startBalls)) {
                val (a, b) = pair
                all += listOf(ordinal(all.size + 1), a, "${(last[a] ?: 0) - (startRuns[a] ?: 0)}", b, "${(last[b] ?: 0) - (startRuns[b] ?: 0)}",
                    "${total - startTotal}", overs(legal - startBalls))
            }
            if (all.isEmpty()) return null
            return Section("$team partnerships · innings $innings", listOf("Wicket", "Batter", "Runs", "Batter", "Runs", "Total", "Overs"), all,
                team = team, kind = "part", innings = innings)
        }
    }

    /**
     * A cricket match's innings: who bats in each ([batting], 2 for a one-day or T20 match, 4 for a Test, later ones
     * guessed by turns until they start), [team] when it's one team's view, and the innings to open on.
     */
    data class CricketPlan(val batting: List<String>, val teams: List<String>, val team: String?, val open: Int)

    internal fun plan(e: SportEvent, sections: List<Section>, team: String?): CricketPlan {
        val teams = e.teams.map { it.name }.ifEmpty { sections.mapNotNull { it.team }.distinct() }
        val test = IccRankings.format(e) == IccRankings.Format.TEST || Regex("""\bDay \d""").containsMatchIn(e.detail)
        val count = if (test) 4 else 2
        fun batted(n: Int) = sections.firstOrNull { it.kind == "bat" && it.innings == "$n" }?.team
        // Known innings from the scorecards; the rest in turns from the first (A, B, A, B; a follow-on shows once played).
        val first = batted(1) ?: batted(2)?.let { b -> teams.firstOrNull { !sameTeam(it, b) } } ?: teams.firstOrNull().orEmpty()
        val second = teams.firstOrNull { !sameTeam(it, first) }.orEmpty()
        val batting = (1..count).map { n -> batted(n) ?: if (n % 2 == 1) first else second }
        val played = (1..count).filter { n -> batted(n) != null }
        // Opens on the innings being played (the latest), in the whole match and in one team's view.
        val open = played.lastOrNull() ?: 1
        return CricketPlan(batting, teams, team, open)
    }

    /**
     * Innings [n]'s tables: for the whole match, or when [team] batted in it, the batting, the other side's bowling
     * and the partnerships; for [team] when it bowled, its bowling only.
     */
    fun inningsSections(sections: List<Section>, plan: CricketPlan, n: Int): List<Section> {
        val inn = "$n"
        val bat = plan.batting.getOrNull(n - 1).orEmpty()
        val team = plan.team
        return if (team == null || sameTeam(bat, team)) listOfNotNull(
            sections.firstOrNull { it.kind == "bat" && it.innings == inn },
            sections.firstOrNull { it.kind == "bowl" && it.innings == inn },
            sections.firstOrNull { it.kind == "part" && it.innings == inn })
        else listOfNotNull(sections.firstOrNull { it.kind == "bowl" && it.innings == inn && it.team != null && sameTeam(it.team, team) })
    }

    /** What an innings not played yet says: "Bangladesh yet to bat", or for the bowling side's view "... yet to bowl". */
    fun notYet(plan: CricketPlan, n: Int): String {
        val bat = plan.batting.getOrNull(n - 1).orEmpty()
        val team = plan.team
        return if (team == null || sameTeam(bat, team)) "$bat yet to bat" else "$team yet to bowl"
    }

    /**
     * [team]'s tab, every innings of the match in order: one it batted gives its batting, the other side's bowling at
     * it and its partnerships; one it bowled gives its bowling.
     */
    fun cricketTab(sections: List<Section>, team: String): List<Section> {
        fun mine(t: String?) = t != null && sameTeam(t, team)
        val innings = sections.filter { it.kind.isNotEmpty() }.map { it.innings }.distinct().sortedBy { it.toIntOrNull() ?: 0 }
        return innings.flatMap { n ->
            val batted = sections.firstOrNull { it.kind == "bat" && it.innings == n && mine(it.team) }
            if (batted != null) listOfNotNull(batted,
                sections.firstOrNull { it.kind == "bowl" && it.innings == n && !mine(it.team) },
                sections.firstOrNull { it.kind == "part" && it.innings == n && mine(it.team) })
            else listOfNotNull(sections.firstOrNull { it.kind == "bowl" && it.innings == n && mine(it.team) })
        }
    }

    /**
     * Each innings: batting (how out, runs, balls, 4s, 6s, strike rate), the bowling (by the bowling side) and the
     * partnerships ([Wicket, batter, runs, batter, runs, total, overs], drawn as bars). Batters and bowlers carry
     * their photo key ("cricket|<ESPN id>|<full name>": the roster's full name, as the scorecard writes "AM Fernando").
     */
    internal fun cricket(o: JSONObject): List<Section> {
        val fullNames = objects(o.optJSONArray("rosters")).flatMap { objects(it.optJSONArray("roster")) }.mapNotNull { p ->
            val a = p.optJSONObject("athlete") ?: return@mapNotNull null
            a.optString("id").ifBlank { null }?.let { it to a.optString("displayName").ifBlank { a.optString("name") } }
        }.toMap()
        fun photo(p: JSONObject) = p.optString("playerID").ifBlank { null }?.let { id -> "cricket|$id|${fullNames[id] ?: p.optString("playerName")}" }
        val cards = objects(o.optJSONArray("matchcards")).mapNotNull { c ->
            val n = c.optString("inningsNumber")
            val team = c.optString("teamName")
            val players = objects(c.optJSONArray("playerDetails"))
            when (c.optString("headline")) {
                "Batting" -> Section("$team batting · innings $n", listOf("Batter", "How out", "R", "B", "4s", "6s", "SR"),
                    players.map { p ->
                        val r = p.optString("runs"); val b = p.optString("ballsFaced")
                        val sr = r.toDoubleOrNull()?.let { rr -> b.toDoubleOrNull()?.takeIf { it > 0 }?.let { bb -> ((rr * 1000 / bb).toLong() / 10.0).toString() } } ?: "–"
                        listOf(p.optString("playerName"), unescape(p.optString("dismissal")), r, b, p.optString("fours"), p.optString("sixes"), sr)
                    },
                    note = listOfNotNull(c.optString("extras").ifBlank { null }?.let { "Extras $it" },
                        c.optString("runs").ifBlank { null }?.let { "Total $it ${c.optString("total")}".trim() }).joinToString(" · ").ifBlank { null },
                    team = team, kind = "bat", innings = n, photos = players.map(::photo))
                "Bowling" -> Section("$team bowling · innings $n", listOf("Bowler", "O", "M", "R", "W", "Econ"),
                    players.map { p -> listOf(p.optString("playerName"), p.optString("overs"), p.optString("maidens"), p.optString("conceded"),
                        p.optString("wickets"), p.optString("economyRate")) }, team = team, kind = "bowl", innings = n, photos = players.map(::photo))
                "Partnerships" -> Section("$team partnerships · innings $n", listOf("Wicket", "Batter", "Runs", "Batter", "Runs", "Total", "Overs"),
                    players.map { p -> listOf(p.optString("partnershipWicketName"), p.optString("player1Name"), p.optString("player1Runs"),
                        p.optString("player2Name"), p.optString("player2Runs"), p.optString("partnershipRuns"), p.optString("partnershipOvers")) },
                    team = team, kind = "part", innings = n)
                else -> null
            }?.takeIf { it.rows.isNotEmpty() }
        }
        // ESPN's scorecards cover the latest innings only; the squads hold every innings: the rest built from them.
        val have = cards.map { it.kind to it.innings }.toSet()
        return cards + fromRosters(o).filter { (it.kind to it.innings) !in have }
    }

    /**
     * Every innings' batting and bowling from the squads (each player's figures per innings, "period"), for the
     * innings ESPN's scorecards leave out: batters in batting order with how they were out; bowlers in the order
     * they bowled; the innings total from the match header.
     */
    internal fun fromRosters(o: JSONObject): List<Section> {
        data class Line(val team: String, val period: String, val id: String, val name: String, val st: Map<String, String>)
        val lines = objects(o.optJSONArray("rosters")).flatMap { r ->
            val team = r.optJSONObject("team")?.optString("displayName").orEmpty()
            objects(r.optJSONArray("roster")).flatMap { p ->
                val a = p.optJSONObject("athlete")
                val id = a?.optString("id").orEmpty(); val name = a?.optString("displayName").orEmpty()
                objects(p.optJSONArray("linescores")).flatMap { per ->
                    val period = per.optString("period")
                    objects(per.optJSONArray("linescores")).map { ls ->
                        val st = HashMap<String, String>()
                        for (c in objects(ls.optJSONObject("statistics")?.optJSONArray("categories")))
                            for (x in objects(c.optJSONArray("stats"))) st[x.optString("name")] = x.optString("displayValue")
                        Line(team, period, id, name, st)
                    }
                }
            }
        }
        // The innings totals: "386/7 (90 ov)" per team and innings.
        val totals = HashMap<Pair<String, String>, String>()
        objects(o.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors")).forEach { t ->
            val team = t.optJSONObject("team")?.optString("displayName").orEmpty()
            objects(t.optJSONArray("linescores")).forEach { l ->
                val runs = l.optString("runs"); val w = l.optString("wickets")
                if (runs.isNotBlank() && runs != "0") totals[team to l.optString("period")] = runs + (if (w == "10") " all out" else "/$w")
            }
        }
        fun how(st: Map<String, String>) = when (st["dismissalCard"].orEmpty()) {
            "c" -> "caught"; "b" -> "bowled"; "lbw" -> "lbw"; "st" -> "stumped"; "ro", "run out" -> "run out"; "hw" -> "hit wicket"
            "" -> if (st["outs"] == "1") "out" else "not out"
            else -> st["dismissalCard"].orEmpty()
        }
        val out = ArrayList<Section>()
        for (period in lines.map { it.period }.distinct().sortedBy { it.toIntOrNull() ?: 0 }) {
            val bat = lines.filter { it.period == period && it.st["batted"] == "1" }.sortedBy { it.st["battingPosition"]?.toIntOrNull() ?: 99 }
            if (bat.isEmpty()) continue
            val team = bat.first().team
            out += Section("$team batting · innings $period", listOf("Batter", "How out", "R", "B", "4s", "6s", "SR"),
                bat.map { l ->
                    val r = l.st["runs"].orEmpty(); val b = l.st["ballsFaced"].orEmpty()
                    val sr = r.toDoubleOrNull()?.let { rr -> b.toDoubleOrNull()?.takeIf { it > 0 }?.let { bb -> ((rr * 1000 / bb).toLong() / 10.0).toString() } } ?: "–"
                    listOf(l.name, how(l.st), r, b, l.st["fours"].orEmpty(), l.st["sixes"].orEmpty(), sr)
                }, note = totals[team to period]?.let { "Total $it" }, team = team, kind = "bat", innings = period,
                photos = bat.map { "cricket|${it.id}|${it.name}" })
            val bowl = lines.filter { it.period == period && it.st["bowled"] == "1" && it.team != team }
            if (bowl.isNotEmpty()) out += Section("${bowl.first().team} bowling · innings $period", listOf("Bowler", "O", "M", "R", "W", "Econ"),
                bowl.map { l -> listOf(l.name, l.st["overs"].orEmpty(), l.st["maidens"].orEmpty(), l.st["conceded"].orEmpty(), l.st["wickets"].orEmpty(), l.st["economyRate"].orEmpty()) },
                team = bowl.first().team, kind = "bowl", innings = period, photos = bowl.map { "cricket|${it.id}|${it.name}" })
        }
        return out
    }

    /**
     * A moment's players in words: a goal "Nico Paz · assist Enzo Fernández", a change "↑ John Ruiz  ↓ Aarón Salazar"
     * (ESPN names the one coming on first), anything else the names.
     */
    internal fun momentText(type: String, who: List<String>): String {
        val t = type.lowercase()
        return when {
            who.isEmpty() -> ""
            "substitution" in t -> "↑ ${who[0]}" + (who.getOrNull(1)?.let { " · ↓ $it" } ?: "")
            "goal" in t || "scored" in t -> "${who[0]} 🎯" + (who.getOrNull(1)?.let { " · $it 👟" } ?: "")
            else -> who.joinToString(", ")
        }
    }

    /** The goals, cards and changes as they happened, then each side's bench (the starters are drawn on the pitch). */
    internal fun football(o: JSONObject): List<Section> {
        val out = ArrayList<Section>()
        // Each moment: who it's about, with their face (the scorer, the player booked, the one coming on).
        val moments = objects(o.optJSONArray("keyEvents")).mapNotNull { k ->
            val type = k.optJSONObject("type")?.optString("text").orEmpty()
            if (type.isBlank() || type in setOf("Kickoff", "Start 2nd Half", "Halftime", "End Regular Time", "Start Extra Time")) return@mapNotNull null
            val team = k.optJSONObject("team")?.optString("displayName").orEmpty()
            val who = objects(k.optJSONArray("participants")).mapNotNull { it.optJSONObject("athlete")?.optString("displayName")?.ifBlank { null } }
            Triple(listOf(k.optJSONObject("clock")?.optString("displayValue").orEmpty(), type, team, momentText(type, who).ifBlank { k.optString("shortText") }),
                who.firstOrNull(), who.getOrNull(1))
        }
        if (moments.isNotEmpty()) out += Section("Key moments", listOf("Time", "Event", "Team", "Player"), moments.map { it.first },
            photos = moments.map { it.second }, photos2 = moments.map { it.third })
        return out
    }

    /**
     * Each side's starting XI placed on the pitch: the formation gives the rows ("4-2-3-1": 4, then 2, 3, 1), each
     * player's position how far forward (keeper, defender, holding, midfield, attacking, forward) and which side
     * (left, centre, right). Cards, goals and who went off come from the key moments.
     */
    internal fun lineups(o: JSONObject): List<Lineup> {
        class Marks(var yellow: Boolean = false, var red: Boolean = false, var goals: Int = 0, var off: Boolean = false)
        val marks = HashMap<String, Marks>()
        for (k in objects(o.optJSONArray("keyEvents"))) {
            val type = k.optJSONObject("type")?.optString("text").orEmpty().lowercase()
            val who = objects(k.optJSONArray("participants")).mapNotNull { it.optJSONObject("athlete")?.optString("displayName")?.ifBlank { null } }
            val first = who.firstOrNull() ?: continue
            when {
                "own goal" in type -> {}
                "goal" in type || "penalty - scored" in type -> marks.getOrPut(first) { Marks() }.goals++
                "yellow" in type -> marks.getOrPut(first) { Marks() }.yellow = true
                "red" in type -> marks.getOrPut(first) { Marks() }.red = true
            }
        }
        return objects(o.optJSONArray("rosters")).mapNotNull { r ->
            val teamObj = r.optJSONObject("team")
            val starters = objects(r.optJSONArray("roster")).filter { it.optBoolean("starter") }
            if (starters.size < 7) return@mapNotNull null
            val formation = r.optString("formation").ifBlank { null }
            fun pos(p: JSONObject) = p.optJSONObject("position")?.optString("displayName").orEmpty()
            fun place(p: JSONObject) = p.optString("formationPlace").toIntOrNull() ?: 99
            val keeper = starters.firstOrNull { "goalkeeper" in pos(it).lowercase() } ?: starters.minBy(::place)
            // Outfield players from the back forward (ties by ESPN's slot), then cut into the formation's rows.
            val outfield = starters.filter { it !== keeper }.sortedWith(compareBy({ depth(pos(it)) }, { place(it) }))
            val shape = formation?.split('-')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.sum() == outfield.size }
            val sizes = shape ?: rowsByDepth(outfield.map { depth(pos(it)) })
            val players = ArrayList<PitchPlayer>()
            fun add(p: JSONObject, row: Int, x: Float) {
                val a = p.optJSONObject("athlete")
                val name = a?.optString("displayName").orEmpty()
                val m = marks[name]
                val short = a?.optString("lastName")?.ifBlank { null } ?: name.substringAfterLast(' ')
                players += PitchPlayer(p.optString("jersey"), short, row, x, m?.yellow == true, m?.red == true, m?.goals ?: 0, p.optBoolean("subbedOut"), name)
            }
            add(keeper, 0, 0.5f)
            var i = 0
            sizes.forEachIndexed { ri, n ->
                val row = outfield.drop(i).take(n).sortedBy { side(pos(it)) }
                i += n
                row.forEachIndexed { j, p -> add(p, ri + 1, (j + 1f) / (row.size + 1f)) }
            }
            Lineup(teamObj?.optString("displayName").orEmpty(), formation, teamObj?.optString("color")?.ifBlank { null }, sizes.size + 1, players)
        }
    }

    /** Without a usable formation: a row per depth the players play at. */
    private fun rowsByDepth(depths: List<Int>): List<Int> = depths.groupingBy { it }.eachCount().entries.sortedBy { it.key }.map { it.value }

    /** How far forward a position plays: keeper 0, defenders 1, holding 2, midfield 3, attacking 4, forwards 5. */
    internal fun depth(position: String): Int {
        val p = position.lowercase()
        return when {
            "goalkeeper" in p -> 0
            "defensive midfield" in p -> 2
            "attacking midfield" in p -> 4
            "midfield" in p -> 3
            "back" in p || "defender" in p || "sweeper" in p -> 1
            "forward" in p || "striker" in p || "wing" in p -> 5
            else -> 3
        }
    }

    /** Left (-1) to right (1): "Left Back" -1, "Center Left Defender" -0.5, "Center Defender" 0. */
    internal fun side(position: String): Float {
        val p = position.lowercase()
        val left = "left" in p; val right = "right" in p; val centre = "center" in p || "centre" in p
        return when {
            left && centre -> -0.5f
            right && centre -> 0.5f
            left -> -1f
            right -> 1f
            else -> 0f
        }
    }

    /** Other sports: each side's players and their main figures (points, rebounds...), as the box score lists them. */
    internal fun boxScore(o: JSONObject): List<Section> = objects(o.optJSONObject("boxscore")?.optJSONArray("players")).flatMap { t ->
        val team = t.optJSONObject("team")?.optString("displayName").orEmpty()
        objects(t.optJSONArray("statistics")).mapNotNull { st ->
            val labels = strings(st.optJSONArray("labels")).take(7)
            val rows = objects(st.optJSONArray("athletes")).map { a ->
                listOf(a.optJSONObject("athlete")?.optString("displayName").orEmpty()) + strings(a.optJSONArray("stats")).take(labels.size)
            }.filter { it.size > 1 }
            if (labels.isEmpty() || rows.isEmpty()) null
            else Section(team + (st.optString("name").ifBlank { null }?.let { " · ${it.replaceFirstChar { c -> c.uppercase() }}" } ?: ""),
                listOf("Player") + labels, rows, team = team)
        }
    }

    /** Both sides' match figures (possession, shots...), the home side's first. */
    private fun figures(o: JSONObject, e: SportEvent): List<Section> {
        val home = e.home ?: return emptyList(); val away = e.away ?: return emptyList()
        val lines = MatchStats.teamStats(o, home, away)
        if (lines.isEmpty() || lines.none { it.other != null }) return emptyList()
        return listOf(Section(if (e.state == State.PRE) "Before the game · competition so far" else "Match figures", listOf("", home.short, away.short), lines.map { listOf(it.label, it.value, it.other ?: "–") }))
    }

    private fun objects(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
    private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
}
