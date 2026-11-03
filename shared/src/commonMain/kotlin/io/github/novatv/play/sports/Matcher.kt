package io.github.novatv.play.sports

import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Epg
import io.github.novatv.play.data.Programme
import io.github.novatv.play.platform.SafeMap

/** A channel in the user's playlist that shows [SportEvent], and why we think so. */
data class Airing(val channel: Channel, val programme: Programme?, val why: String)

/**
 * Finds where a match airs in the user's own playlist: first in the TV guide (a programme around kick-off whose
 * title names both sides, or the competition), then by the broadcasters ESPN lists against channel names.
 */
class Matcher(private val epg: Epg, channels: List<Channel>) {
    /** Channels by guide key and by normalised name, built once per playlist. */
    private val byKey: Map<String, List<Channel>> = channels.mapNotNull { c -> epg.key(c)?.let { it to c } }.groupBy({ it.first }, { it.second })
    private val named: List<Pair<String, Channel>> = channels.map { Epg.norm(it.name) to it }
    /** Match feeds: channels named after one match ("Next | Auxerre vs. Le Mans | ... | FR: SOCCER PPV 78"). */
    private val feeds: List<Pair<String, Channel>> = channels.filter { c ->
        val n = c.name
        n.contains(" vs", true) || n.contains(" v ") || (n.contains('|') && n.contains(" - "))
    }.map { fold(it.name) to it }.filterNot { (n, _) -> n.startsWith("end") }

    /**
     * Channels in the playlist showing [ev]: from the TV guide, then from [broadcasts] (TheSportsDB, SportMonks, ESPN;
     * the user's country first) matched to channel names.
     */
    private val cache = SafeMap<String, List<Airing>>()

    fun airings(ev: SportEvent, broadcasts: List<Broadcast> = ev.broadcasts.map { Broadcast(it, null, "ESPN") }, limit: Int = 16): List<Airing> =
        cache.getOrPut("${ev.id}|$limit|" + broadcasts.joinToString(",") { it.channel }) { find(ev, broadcasts, limit) }

    private fun find(ev: SportEvent, broadcasts: List<Broadcast>, limit: Int): List<Airing> {
        val out = LinkedHashMap<String, Airing>()
        // Feeds named after this very match come first: their times are often wrong, their team names are not.
        if (ev.home != null && ev.away != null) {
            val h = keyword(ev.home)?.let(::fold); val a = keyword(ev.away)?.let(::fold)
            // A youth game between the same clubs ("UEFA YOUTH LEAGUE ATLÉTICO MADRID - BAYERN") is not this one.
            val youth = Regex("""youth|u1[789]|u2[013]|juvenil|primavera""")
            val ownYouth = youth.containsMatchIn(fold(ev.league.name + " " + ev.title))
            if (h != null && a != null && h.length >= 3 && a.length >= 3)
                for ((n, c) in feeds) if (n.contains(h) && n.contains(a) && (ownYouth || !youth.containsMatchIn(n)))
                    out.putNew(c.id, Airing(c, null, "Match feed"))
        }
        val from = ev.start - 45 * 60_000L
        val to = ev.start + 4 * 3600_000L
        if (epg.hasData) {
            val words = if (ev.home != null && ev.away != null) listOfNotNull(keyword(ev.home), keyword(ev.away))
                else leagueWords(ev)
            if (words.isNotEmpty()) for (p in epg.find(words, from, to)) {
                for (c in byKey[p.channelKey].orEmpty()) out.putNew(c.id, Airing(c, p, "In your TV guide"))
            }
            // One side's name plus the competition catches "Arsenal: Premier League Live" style titles.
            if (out.isEmpty() && ev.home != null && ev.away != null) {
                for (side in ev.teams) keyword(side)?.let { w ->
                    for (p in epg.find(listOf(w), from, to).filter { prog -> leagueWords(ev).any { prog.title.contains(it, true) || prog.desc?.contains(it, true) == true } })
                        for (c in byKey[p.channelKey].orEmpty()) out.putNew(c.id, Airing(c, p, "In your TV guide"))
                }
            }
            // Guides that name only the competition ("UEFA Champions League Football" on TNT Sports 2): on at kick-off,
            // after the channels that name the match, since the same competition may show another of its games.
            competition(ev)?.let { comp ->
                val own = ev.league.name.split(' ').filter { it in bodies }
                val codes = countryCodes[io.github.novatv.play.App.store.sportsCountry].orEmpty()
                epg.find(listOf(comp), ev.start + 10 * 60_000L, ev.start + 20 * 60_000L, limit = 300)
                    .filter { it.start <= ev.start + 15 * 60_000L && unnamed(it.title, own) }
                    .flatMap { p -> byKey[p.channelKey].orEmpty().map { it to p } }
                    // The user's country first: "UK: TNT SPORTS 2" or a guide key ending ".uk".
                    .sortedBy { (c, p) -> if (codes.any { c.name.startsWith("$it:", true) || p.channelKey.endsWith(".$it", true) }) 0 else 1 }
                    .forEach { (c, p) -> out.putNew(c.id, Airing(c, p, "$comp at kick-off")) }
            }
        }
        // Broadcasters ESPN lists ("Sky Sports Main Event", "ESPN", "beIN SPORTS 1"), matched against channel names.
        for (b in broadcasts) {
            val n = broadcasterCore(b.channel)
            if (n.length < 4) continue
            val why = "Broadcaster: ${b.channel}" + (b.country?.let { " ($it)" } ?: "")
            // A streaming service ("Paramount+") is only its own feeds ("US: PARAMOUNT+ 04"), not the TV channels it shares a name with.
            val streaming = b.channel.trim().endsWith("+")
            for ((name, c) in named) if (name == n || name.startsWith(n) || (n.length >= 6 && name.contains(n))) {
                val words = c.name.lowercase()
                if (streaming && '+' !in c.name && "plus" !in words) continue
                if (name != n && notSport.any { Regex("""\b$it\b""").containsMatchIn(words) }) continue
                out.putNew(c.id, Airing(c, null, why))
            }
        }
        return out.values.take(maxOf(limit, out.values.count { it.why == "Match feed" }))
    }

    /** Sports on the user's channels between [from] and [to], from the guide alone (any sport, any country). */
    fun guideSports(from: Long, to: Long): List<Airing> = epg.sportsNow(from, to).flatMap { p ->
        byKey[p.channelKey].orEmpty().take(1).map { Airing(it, p, p.category ?: "Sport") }
    }.distinctBy { it.channel.id + it.programme?.start }

    /** The most telling word of a team's name: "Manchester United" -> "manchester", "Real Madrid" -> "madrid". */
    private fun keyword(s: Side): String? {
        val words = (s.short.split(' ', '-', '.') + s.name.split(' ', '-', '.')).map { it.trim() }.filter { it.length >= 3 && it.lowercase() !in generic }
        return words.maxByOrNull { it.length }?.takeIf { it.length >= 3 } ?: s.abbr.ifBlank { null }
    }

    private fun leagueWords(ev: SportEvent): List<String> = when (ev.league.id) {
        "f1" -> listOf("Formula 1").let { if (ev.title.contains("Grand Prix", true)) it else it }
        "indycar" -> listOf("IndyCar")
        "nascar" -> listOf("NASCAR")
        "ufc" -> listOf("UFC")
        "pga" -> listOf("PGA")
        "lpga" -> listOf("LPGA")
        else -> ev.league.name.split(' ').filter { it.length >= 4 }.take(1)
    }

    /** A competition's name as guides write it, without its body: "UEFA Champions League" -> "Champions League". */
    private fun competition(ev: SportEvent): String? =
        ev.league.name.split(' ').dropWhile { it in bodies }.joinToString(" ").takeIf { it.length >= 8 && ' ' in it }

    /**
     * Whether [title] is a live programme of the competition that names no game: not another body's competition ("AFC
     * Champions League"), not a show about it, and no two sides ("Galatasaray - Stuttgart", "Shakhtar vs Sporting").
     */
    private fun unnamed(title: String, own: List<String>): Boolean {
        val t = title.lowercase()
        return notLive.none { it in t } && bodies.none { it !in own && Regex("""\b${it.lowercase()}\b""").containsMatchIn(t) } &&
            !Regex("""\S\s+(vs?\.?|x|-|–)\s+\S""").containsMatchIn(title.substringAfter(':', title).replace(Regex("""\s[-–]\s*(live|direto|en vivo)\b""", RegexOption.IGNORE_CASE), ""))
    }

    /**
     * A broadcaster's name as playlists write it: "Arena Sport 1 SI" -> "arenasport1", "Sky Sports Main Event UK" ->
     * "skysportsmainevent". Listings end names with a country code that playlists put in front ("SI: ...") instead.
     */
    private fun broadcasterCore(name: String): String {
        val words = name.trim().split(' ').filter { it.isNotBlank() }.toMutableList()
        if (words.size > 1 && words.last().length in 2..3 && words.last().all { it.isUpperCase() } && words.last().lowercase() !in keepTails) words.removeAt(words.lastIndex)
        return Epg.norm(words.joinToString(" "))
    }

    companion object {
        /** Lower case without accents: "Çorum" and "corum", "Göztepe" and "goztepe" match. */
        fun fold(s: String): String = io.github.novatv.play.platform.foldAccents(s)

        /** Short upper-case words that are part of a channel's name, not a country code. */
        /** Settings' Sports countries as playlists and guides abbreviate them. */
        private val countryCodes = mapOf("United Kingdom" to listOf("uk", "gb"), "Ireland" to listOf("ie", "irl"),
            "United States" to listOf("us", "usa"), "Canada" to listOf("ca"), "Pakistan" to listOf("pk"), "India" to listOf("in"),
            "Bangladesh" to listOf("bd"), "United Arab Emirates" to listOf("ae", "uae"), "Saudi Arabia" to listOf("sa", "ksa"),
            "Qatar" to listOf("qa"), "Australia" to listOf("au"), "New Zealand" to listOf("nz"), "South Africa" to listOf("za"),
            "Germany" to listOf("de"), "France" to listOf("fr"), "Spain" to listOf("es"), "Italy" to listOf("it"),
            "Netherlands" to listOf("nl"), "Portugal" to listOf("pt"), "Turkey" to listOf("tr"), "Brazil" to listOf("br"), "Mexico" to listOf("mx"))
        private val bodies =setOf("UEFA", "FIFA", "CONCACAF", "CONMEBOL", "CAF", "AFC", "English", "Spanish", "German", "Italian", "French")
        /** Channels that share a broadcaster's name but show no sport ("PARAMOUNT CINEMA 1", "SKY CINEMA ACTION"). */
        private val notSport = listOf("cinema", "classic", "movies?", "films?", "kids", "comedy", "drama", "series", "news",
            "crime", "family", "docs?", "documentary", "novelas", "music", "junior", "nick")
        /** Programmes about a competition rather than one of its games. */
        private val notLive = listOf("youth", "goals", "highlights", "review", "magazine", "stories", "show", "classic", "rewind", "preview")
        private val keepTails =setOf("tv", "hd", "uhd", "one", "max", "ppv", "plus")
        private val generic = setOf("fc", "afc", "cf", "sc", "club", "united", "city", "town", "real", "athletic", "sporting",
            "the", "and", "de", "del", "la", "le", "los", "las", "women", "men", "u19", "u21", "u23", "team", "county", "rovers", "wanderers")
    }
}

/** Adds [v] unless [k] is already there (putIfAbsent is JVM-only). */
private fun <K, V> MutableMap<K, V>.putNew(k: K, v: V) { if (k !in this) put(k, v) }
