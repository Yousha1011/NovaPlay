package io.github.novatv.play.sports

/** A sport in the Sports centre's filter row. */
enum class Sport(val label: String) {
    FOOTBALL("Football"), CRICKET("Cricket"), BASKETBALL("Basketball"), AMERICAN_FOOTBALL("American football"),
    HOCKEY("Ice hockey"), BASEBALL("Baseball"), TENNIS("Tennis"), MOTORSPORT("Motorsport"), MMA("MMA"),
    GOLF("Golf"), RUGBY("Rugby"),
}

/**
 * A competition the Sports centre follows. [path] is ESPN's "sport/league" slug for the scoreboard feed; leagues
 * with [header] set come from ESPN's all-matches feed for that sport instead (cricket and rugby, which have too
 * many competitions to list one by one).
 */
data class League(val id: String, val sport: Sport, val name: String, val path: String? = null, val header: String? = null,
                  /** Country or region, to pick competitions by country in the Sports setup. */
                  val country: String = "")

object Catalog {
    private fun soccer(slug: String, name: String, country: String) = League(slug, Sport.FOOTBALL, name, "soccer/$slug", country = country)

    val leagues: List<League> = listOf(
        soccer("eng.1", "Premier League", "England"), soccer("eng.2", "Championship", "England"), soccer("eng.3", "League One", "England"),
        soccer("eng.fa", "FA Cup", "England"), soccer("eng.league_cup", "Carabao Cup", "England"),
        soccer("esp.1", "LaLiga", "Spain"), soccer("esp.2", "LaLiga 2", "Spain"), soccer("esp.copa_del_rey", "Copa del Rey", "Spain"),
        soccer("ger.1", "Bundesliga", "Germany"), soccer("ger.2", "2. Bundesliga", "Germany"), soccer("ger.dfb_pokal", "DFB-Pokal", "Germany"),
        soccer("ita.1", "Serie A", "Italy"), soccer("ita.2", "Serie B", "Italy"), soccer("ita.coppa_italia", "Coppa Italia", "Italy"),
        soccer("fra.1", "Ligue 1", "France"), soccer("fra.2", "Ligue 2", "France"),
        soccer("ned.1", "Eredivisie", "Netherlands"), soccer("por.1", "Primeira Liga", "Portugal"), soccer("bel.1", "Belgian Pro League", "Belgium"),
        soccer("sco.1", "Scottish Premiership", "Scotland"), soccer("tur.1", "Süper Lig", "Turkey"), soccer("gre.1", "Super League Greece", "Greece"),
        soccer("ksa.1", "Saudi Pro League", "Saudi Arabia"), soccer("ind.1", "Indian Super League", "India"),
        soccer("jpn.1", "J1 League", "Japan"), soccer("aus.1", "A-League", "Australia"),
        soccer("usa.1", "MLS", "USA"), soccer("mex.1", "Liga MX", "Mexico"), soccer("bra.1", "Brasileirão", "Brazil"), soccer("arg.1", "Argentine Primera", "Argentina"),
        soccer("uefa.champions", "Champions League", "Europe"), soccer("uefa.europa", "Europa League", "Europe"),
        soccer("uefa.europa.conf", "Conference League", "Europe"), soccer("uefa.nations", "Nations League", "Europe"),
        soccer("fifa.world", "FIFA World Cup", "International"), soccer("fifa.worldq.uefa", "World Cup qualifying (UEFA)", "International"),
        soccer("fifa.friendly", "International friendlies", "International"),
        soccer("conmebol.libertadores", "Copa Libertadores", "South America"), soccer("afc.champions", "AFC Champions League", "Asia"),
        League("cricket", Sport.CRICKET, "All cricket", header = "cricket", country = "International"),
        League("nba", Sport.BASKETBALL, "NBA", "basketball/nba", country = "USA"),
        League("wnba", Sport.BASKETBALL, "WNBA", "basketball/wnba", country = "USA"),
        League("ncaab", Sport.BASKETBALL, "College basketball", "basketball/mens-college-basketball", country = "USA"),
        League("nfl", Sport.AMERICAN_FOOTBALL, "NFL", "football/nfl", country = "USA"),
        League("ncaaf", Sport.AMERICAN_FOOTBALL, "College football", "football/college-football", country = "USA"),
        League("nhl", Sport.HOCKEY, "NHL", "hockey/nhl", country = "USA"),
        League("mlb", Sport.BASEBALL, "MLB", "baseball/mlb", country = "USA"),
        League("atp", Sport.TENNIS, "ATP", "tennis/atp", country = "World"),
        League("wta", Sport.TENNIS, "WTA", "tennis/wta", country = "World"),
        League("f1", Sport.MOTORSPORT, "Formula 1", "racing/f1", country = "World"),
        League("indycar", Sport.MOTORSPORT, "IndyCar", "racing/irl", country = "USA"),
        League("nascar", Sport.MOTORSPORT, "NASCAR Cup", "racing/nascar-premier", country = "USA"),
        League("ufc", Sport.MMA, "UFC", "mma/ufc", country = "World"),
        League("pga", Sport.GOLF, "PGA Tour", "golf/pga", country = "World"),
        League("lpga", Sport.GOLF, "LPGA", "golf/lpga", country = "World"),
        League("rugby", Sport.RUGBY, "All rugby", header = "rugby", country = "International"),
    )

    fun league(id: String) = leagues.firstOrNull { it.id == id }
}

data class Side(val id: String, val name: String, val short: String, val abbr: String, val logo: String?, val score: String?)

enum class State { PRE, LIVE, POST }

data class SportEvent(
    val id: String,
    val league: League,
    /** A sub-competition's name: tennis tournament, F1 session, cricket series. */
    val subtitle: String?,
    val title: String,
    val start: Long,
    val state: State,
    /** "45'", "Q3 2:10", "FT", "7:30 PM" as ESPN words it. */
    val detail: String,
    val home: Side?,
    val away: Side?,
    val broadcasts: List<String>,
    val venue: String?,
) {
    val teams: List<Side> get() = listOfNotNull(home, away)
}

/**
 * What the Sports centre follows. A sport is on when ticked (none ticked: all on). Within a sport, nothing picked
 * means all of it; otherwise a match shows when its competition or one of its clubs is picked.
 */
object Follow {
    private val store get() = io.github.novatv.play.App.store

    fun sportOn(s: Sport) = store.sportsEnabled.let { it.isEmpty() || s.name in it }

    fun hasPicks(s: Sport): Boolean = Catalog.leagues.any { it.sport == s && it.id in store.sportsLeagues } ||
        store.sportsComps.any { it.startsWith(s.name + "|") } || store.favouriteTeams.any { it.startsWith(s.name + "|") }

    fun compKey(e: SportEvent) = "${e.league.sport.name}|${e.subtitle ?: e.league.name}"

    fun teamPicked(e: SportEvent, side: Side) = store.favouriteTeams.any { it.startsWith("${e.league.sport.name}|${side.id}|") || it.endsWith("|${side.name}") }

    fun shows(e: SportEvent): Boolean {
        val s = e.league.sport
        if (!sportOn(s)) return false
        if (!hasPicks(s)) return true
        if (s == Sport.CRICKET && e.teams.any { side -> Cricket.nation(side.name)?.let { Cricket.nationKey(it) in store.sportsComps } == true }) return true
        if (s == Sport.CRICKET) Cricket.tournament(e.subtitle, e.teams.map { it.name })?.let { tn ->
            if (Cricket.ALL_INTERNATIONAL in store.sportsComps || Cricket.tournamentKey(tn.name) in store.sportsComps) return true
        }
        if (s == Sport.CRICKET) e.subtitle?.let { c -> Cricket.leagueHome(c, e.teams.map { it.name }) }?.let { home ->
            if (Cricket.leaguesKey(home) in store.sportsComps) return true
        }
        return e.league.id in store.sportsLeagues || compKey(e) in store.sportsComps || e.teams.any { teamPicked(e, it) }
    }

    /** The feeds to load: every competition of the sports that are on. */
    fun leaguesToFetch(): List<League> = Catalog.leagues.filter { sportOn(it.sport) }
}

/** Cricket by country: national sides ("India", "India Women", "India A") and each country's own competitions. */
object Cricket {
    /** Full members first, in the order the Sports setup lists them. */
    val major = listOf("India", "Pakistan", "England", "Australia", "South Africa", "New Zealand", "Sri Lanka", "Bangladesh",
        "West Indies", "Afghanistan", "Ireland", "Zimbabwe")
    private val others = listOf("Scotland", "Netherlands", "Nepal", "Oman", "United Arab Emirates", "United States of America", "Canada",
        "Namibia", "Kenya", "Hong Kong", "Papua New Guinea", "Uganda", "Nigeria", "Denmark", "Jersey", "Guernsey", "Italy", "Germany",
        "Malaysia", "Singapore", "Qatar", "Kuwait", "Bahrain", "Saudi Arabia", "Tanzania", "Rwanda", "Botswana", "Bermuda",
        "Cayman Islands", "Japan", "Thailand", "Fiji", "Samoa", "Vanuatu", "Austria", "France", "Spain", "Sweden", "Norway", "Finland",
        "Belgium", "Mexico", "Argentina", "Brazil", "Gambia", "Lesotho", "Mozambique", "Zambia", "Sierra Leone", "Ghana", "Cameroon",
        "Malawi", "Eswatini", "China", "Indonesia", "Philippines", "Mongolia", "Cook Islands", "Isle of Man", "Gibraltar", "Portugal",
        "Czech Republic", "Hungary", "Romania", "Bulgaria", "Serbia", "Croatia", "Estonia", "Cyprus", "Greece", "Turkey", "Israel",
        "Switzerland", "Luxembourg", "Peru", "Chile", "Panama", "Bahamas", "Belize", "Maldives", "Bhutan", "Myanmar", "Cambodia", "Iran")
    private val nations = (major + others).toSet()
    private val tails = listOf(" Women", " A", " U19", " Under-19s", " Under 19s", " XI", " Emerging", " Development", " Legends", " Masters")

    /** The country a national side plays for, or null for a club or franchise. */
    fun nation(side: String): String? {
        var n = side.trim()
        for (t in tails) if (n.endsWith(t)) n = n.removeSuffix(t).trim()
        if (n == "USA" || n == "United States") n = "United States of America"
        if (n == "UAE") n = "United Arab Emirates"
        return n.takeIf { it in nations }
    }

    /** Competitions and the country they belong to, by name. */
    private val leagueHomes = listOf(
        "Indian Premier League" to "India", "IPL" to "India", "Ranji" to "India", "Syed Mushtaq" to "India", "Vijay Hazare" to "India",
        "Duleep" to "India", "Irani" to "India", "WPL" to "India", "Women's Premier League" to "India",
        "Big Bash" to "Australia", "BBL" to "Australia", "WBBL" to "Australia", "Sheffield Shield" to "Australia", "Marsh Cup" to "Australia",
        "Pakistan Super League" to "Pakistan", "PSL" to "Pakistan", "Quaid" to "Pakistan", "National T20 Cup" to "Pakistan",
        "County Championship" to "England", "Vitality Blast" to "England", "T20 Blast" to "England", "The Hundred" to "England",
        "One-Day Cup" to "England", "Metro Bank" to "England", "Charlotte Edwards" to "England", "Rachael Heyhoe" to "England",
        "Caribbean Premier League" to "West Indies", "CPL" to "West Indies", "Super50" to "West Indies", "CG United" to "West Indies",
        "SA20" to "South Africa", "CSA" to "South Africa", "South Africa Domestic" to "South Africa",
        "Bangladesh Premier League" to "Bangladesh", "BPL" to "Bangladesh", "National Cricket League" to "Bangladesh", "Dhaka Premier" to "Bangladesh",
        "Lanka Premier League" to "Sri Lanka", "LPL" to "Sri Lanka", "SLC" to "Sri Lanka",
        "Super Smash" to "New Zealand", "Plunket Shield" to "New Zealand", "Ford Trophy" to "New Zealand", "Hallyburton" to "New Zealand",
        "ILT20" to "United Arab Emirates", "International League T20" to "United Arab Emirates",
        "Major League Cricket" to "United States of America", "MLC" to "United States of America",
        "GT20 Canada" to "Canada", "Pro50" to "Zimbabwe", "Logan Cup" to "Zimbabwe", "Zim Afro" to "Zimbabwe",
        "Shpageeza" to "Afghanistan", "Inter-Provincial" to "Ireland", "Nepal Premier" to "Nepal",
    )

    /** The country a domestic competition belongs to, or null when it is international (national sides playing). */
    fun leagueHome(comp: String, sides: List<String>): String? {
        if (sides.isNotEmpty() && sides.any { nation(it) != null }) return null
        // Short names ("IPL", "CSA") as whole words only.
        leagueHomes.firstOrNull { (k, _) -> if (k.length > 4) comp.contains(k, ignoreCase = true) else Regex("\\b$k\\b").containsMatchIn(comp) }?.let { return it.second }
        return (major + others).firstOrNull { comp.contains(it, ignoreCase = true) } ?: "Other"
    }

    /** An international tournament: named by its competition ([pattern]) or, for bilateral trophies, by the two sides. */
    class Tournament(val name: String, val group: String, val pattern: Regex? = null, val pair: Set<String>? = null, val not: Regex? = null)

    private fun t(name: String, group: String, re: String, not: String? = null) =
        Tournament(name, group, Regex(re, RegexOption.IGNORE_CASE), null, not?.let { Regex(it, RegexOption.IGNORE_CASE) })
    private fun trophy(name: String, a: String, b: String) = Tournament(name, "Bilateral trophies", pair = setOf(a, b))

    /** Most specific first: a match counts for the first tournament it fits. */
    val tournaments: List<Tournament> = listOf(
        t("Women's U19 T20 World Cup", "ICC", "women.*(u19|under.?19).*world cup|(u19|under.?19).*women.*world cup"),
        t("U19 World Cup", "ICC", "(u19|under.?19).*world cup"),
        t("Women's T20 World Cup Qualifiers", "ICC", "women.*(t20|twenty20).*(world cup|wc).*qualif"),
        t("Women's T20 World Cup", "ICC", "women.*(t20|twenty20).*world cup"),
        t("Women's World Cup Qualifier", "ICC", "women.*world cup.*qualif"),
        t("Women's World Cup", "ICC", "women.*world cup"),
        t("T20 World Cup Qualifiers", "ICC", "(t20|twenty20).*(world cup|wc).*qualif"),
        t("T20 World Cup", "ICC", "(t20|twenty20) world cup|world twenty20"),
        t("World Cup League 2", "ICC", "world cup league 2|mcwc league"),
        t("World Cup Challenge League", "ICC", "challenge league"),
        t("World Cup Qualifier", "ICC", "world cup qualif"),
        t("Cricket World Cup", "ICC", "world cup"),
        t("World Test Championship", "ICC", "world test championship|\\bwtc\\b"),
        t("Women's Championship", "ICC", "women'?s championship"),
        t("Champions Trophy", "ICC", "champions trophy", not = "asia pacific"),
        t("Women's Asia Cup", "Continental and multi-sport", "women.*asia cup"),
        t("Emerging Asia Cup", "Continental and multi-sport", "emerging|rising stars"),
        t("Asia Cup", "Continental and multi-sport", "asia cup"),
        t("ACC Premier Cup", "Continental and multi-sport", "acc .*premier|acc premier"),
        t("Asian Games", "Continental and multi-sport", "asian games"),
        t("Commonwealth Games", "Continental and multi-sport", "commonwealth games"),
        t("Olympics", "Continental and multi-sport", "olympic"),
        t("Asia Pacific Champions Trophy", "Continental and multi-sport", "asia pacific"),
        t("European Championship", "Continental and multi-sport", "european (cricket )?championship"),
        t("The Ashes", "Bilateral trophies", "ashes"),
        trophy("The Ashes", "Australia", "England"),
        trophy("Border-Gavaskar Trophy", "India", "Australia"),
        trophy("Anderson-Tendulkar Trophy", "England", "India"),
        trophy("Chappell-Hadlee Trophy", "Australia", "New Zealand"),
        trophy("Frank Worrell Trophy", "Australia", "West Indies"),
        trophy("Basil D'Oliveira Trophy", "England", "South Africa"),
        trophy("Richards-Botham Trophy", "England", "West Indies"),
        trophy("Freedom Trophy", "India", "South Africa"),
        trophy("Benaud-Qadir Trophy", "Australia", "Pakistan"),
        trophy("Warne-Muralitharan Trophy", "Australia", "Sri Lanka"),
        trophy("Sobers-Tissera Trophy", "West Indies", "Sri Lanka"),
        trophy("Ganguly-Durjoy Trophy", "India", "Bangladesh"),
        trophy("Rose Bowl", "Australia Women", "New Zealand Women"),
        t("Tri-series and multi-nation series", "Other international", "tri.?series|tri.?nation|triangular|quadrangular|four.?nation|quad series"),
        t("Bilateral series and tours", "Other international", "tour of|\\bin\\b.*20\\d\\d|series"),
    )

    private val multiNation = Regex("world cup|asia cup|asian games|olympic|commonwealth|champions trophy", RegexOption.IGNORE_CASE)

    /** The international tournament a match belongs to, or null for domestic cricket. */
    fun tournament(comp: String?, sides: List<String>): Tournament? {
        val c = comp.orEmpty()
        val national = sides.filter { nation(it) != null }
        if (national.isEmpty() && !multiNation.containsMatchIn(c)) return null
        // A named event (a tri-series, a World Cup) wins over the trophy between the two sides.
        val named = tournaments.firstOrNull { it.pattern != null && it.group != "Bilateral trophies" && it.group != "Other international" &&
            it.pattern.containsMatchIn(c) && it.not?.containsMatchIn(c) != true }
        if (named != null) return named
        tournaments.firstOrNull { it.pair != null && it.pair == sides.toSet() }?.let { return it }
        return tournaments.firstOrNull { it.pattern != null && it.pattern.containsMatchIn(c) && it.not?.containsMatchIn(c) != true }
            ?: if (national.size == sides.size && sides.isNotEmpty()) tournaments.last() else null
    }

    fun tournamentKey(name: String) = "CRICKET|intl:$name"
    const val ALL_INTERNATIONAL = "CRICKET|intl:*"

    fun nationKey(country: String) = "CRICKET|nation:$country"
    /** Every league of a country, including ones that start later. */
    fun leaguesKey(country: String) = "CRICKET|leagues:$country"
}
