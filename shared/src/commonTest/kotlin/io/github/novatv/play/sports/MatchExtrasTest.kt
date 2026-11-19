package io.github.novatv.play.sports

import io.github.novatv.play.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ICC rankings shown for international cricket, and a team's figures in one match. */
class MatchExtrasTest {
    private val cricket = League("cricket", Sport.CRICKET, "All cricket", header = "cricket", country = "International")
    private fun side(name: String) = Side(name, name, name, name.take(3), null, null)
    private fun match(home: String, away: String, detail: String) =
        SportEvent("cricket:1", cricket, "Sri Lanka tour of Pakistan", "$home vs $away", 0L, State.LIVE, detail, side(home), side(away), emptyList(), null)

    @Test fun formatFromTheMatchName() {
        assertEquals(IccRankings.Format.TEST, IccRankings.format(match("Pakistan", "Sri Lanka", "2nd Test · Day 1 · Stumps")))
        assertEquals(IccRankings.Format.ODI, IccRankings.format(match("Pakistan", "Sri Lanka", "3rd ODI")))
        assertEquals(IccRankings.Format.T20, IccRankings.format(match("Pakistan", "Sri Lanka", "1st T20I")))
        assertNull(IccRankings.format(match("Pakistan", "Sri Lanka", "Tour match")))
    }

    @Test fun onlyMenSNationalSidesGetRankings() {
        // Women's and A sides are ranked apart (or not at all): no table rather than the wrong one. No network needed.
        assertNull(IccRankings.forEvent(match("Pakistan Women", "Sri Lanka Women", "1st T20I")))
        assertNull(IccRankings.forEvent(match("Pakistan Shaheens", "Sri Lanka A", "1st ODI")))
        assertNull(IccRankings.forEvent(match("Lahore Qalandars", "Karachi Kings", "Match 3")))
    }

    @Test fun rankingsParseInOrderWithRating() {
        val raw = """{"data":{"bat-rank":{"rank":[
            {"no":"2","team_id":"1","team_name":"Australia","shortname":"AUS","Matches":"30","Points":"3500","Rating":"117"},
            {"no":"1","team_id":"7","team_name":"South Africa","shortname":"SA","Matches":"23","Points":"2860","Rating":"124"}]}}}"""
        val t = IccRankings.parse(IccRankings.Format.TEST, raw, 0L)!!
        val rows = t.groups.single().rows
        assertEquals(listOf("South Africa", "Australia"), rows.map { it.name })
        assertEquals(listOf(1, 2), rows.map { it.pos })
        assertEquals("124", rows[0].values["points"])
        assertTrue(t.title!!.contains("TEST"))
    }

    @Test fun footballFiguresBesideTheOtherSide() {
        val o = JSONObject("""{"boxscore":{"teams":[
            {"team":{"id":"10","displayName":"Kosovo"},"statistics":[{"name":"foulsCommitted","label":"Fouls","displayValue":"16"},{"name":"possessionPct","label":"Possession","displayValue":"46"}]},
            {"team":{"id":"20","displayName":"Israel"},"statistics":[{"name":"foulsCommitted","label":"Fouls","displayValue":"13"},{"name":"possessionPct","label":"Possession","displayValue":"54"}]}]}}""")
        val l = MatchStats.teamStats(o, Side("10", "Kosovo", "Kosovo", "KOS", null, null), Side("20", "Israel", "Israel", "ISR", null, null))
        assertEquals(MatchStats.Line("Possession %", "46", "54"), l[0])
        assertEquals(MatchStats.Line("Fouls", "16", "13"), l[1])
    }

    @Test fun cricketInningsBattersAndBowlers() {
        val o = JSONObject("""{"matchcards":[
            {"headline":"Batting","teamName":"Pakistan","inningsNumber":"2","runs":"121","total":"(3 wkts; 37.2 ovs)","playerDetails":[
                {"playerName":"Tayyab Arif","runs":"46","ballsFaced":"82","dismissal":"caught"},{"playerName":"Azan Awais","runs":"47","ballsFaced":"96","dismissal":"not out"}]},
            {"headline":"Bowling","teamName":"Sri Lanka","inningsNumber":"2","playerDetails":[{"playerName":"AM Fernando","overs":"9.0","conceded":"36","wickets":"1"}]},
            {"headline":"Bowling","teamName":"Pakistan","inningsNumber":"1","playerDetails":[{"playerName":"Shaheen","overs":"12.0","conceded":"30","wickets":"4"}]}]}""")
        val l = MatchStats.cricket(o, side("Pakistan"))
        assertEquals(MatchStats.Line("Innings 2", "121 (3 wkts; 37.2 ovs)"), l[0])
        assertEquals(MatchStats.Line("Azan Awais", "47 (96)*"), l[1])
        assertEquals(MatchStats.Line("Shaheen", "4/30 (12.0 ov)"), l.last())
    }

    @Test fun gameInfoScorecardAndLineUps() {
        val c = GameInfo.cricket(JSONObject("""{"matchcards":[{"headline":"Batting","teamName":"Pakistan","inningsNumber":"2","runs":"121","total":"(3 wkts)","extras":"(b 4)",
            "playerDetails":[{"playerName":"Azan Awais","dismissal":"not out","runs":"47","ballsFaced":"96","fours":"6","sixes":"0"}]}]}"""))
        assertEquals("Pakistan batting · innings 2", c[0].title)
        assertEquals(listOf("Azan Awais", "not out", "47", "96", "6", "0", "48.9"), c[0].rows[0])
        assertEquals("Extras (b 4) · Total 121 (3 wkts)", c[0].note)
        val o = JSONObject("""{"keyEvents":[{"type":{"text":"Kickoff"}},{"type":{"text":"Yellow Card"},"clock":{"displayValue":"20'"},
            "team":{"displayName":"Kosovo"},"participants":[{"athlete":{"displayName":"Veldin Hodza"}}]},
            {"type":{"text":"Goal"},"clock":{"displayValue":"30'"},"team":{"displayName":"Israel"},"participants":[{"athlete":{"displayName":"Raz Shlomo"}}]}],
            "rosters":[{"team":{"displayName":"Kosovo"},"formation":"2-1","roster":[
              {"starter":true,"jersey":"16","formationPlace":"1","athlete":{"displayName":"Amir Saipi","lastName":"Saipi"},"position":{"displayName":"Goalkeeper"}},
              {"starter":true,"jersey":"3","formationPlace":"5","athlete":{"displayName":"Veldin Hodza","lastName":"Hodza"},"position":{"displayName":"Center Right Defender"}},
              {"starter":true,"jersey":"2","formationPlace":"4","athlete":{"displayName":"Lindrit Kamberi","lastName":"Kamberi"},"position":{"displayName":"Center Left Defender"}},
              {"starter":true,"jersey":"9","formationPlace":"9","athlete":{"displayName":"Vedat Muriqi","lastName":"Muriqi"},"position":{"displayName":"Forward"}},
              {"starter":true,"jersey":"10","formationPlace":"10","athlete":{"displayName":"A B","lastName":"B"},"position":{"displayName":"Midfielder"}},
              {"starter":true,"jersey":"11","formationPlace":"11","athlete":{"displayName":"C D","lastName":"D"},"position":{"displayName":"Midfielder"}},
              {"starter":true,"jersey":"12","formationPlace":"12","athlete":{"displayName":"E F","lastName":"F"},"position":{"displayName":"Midfielder"}},
              {"starter":false,"jersey":"21","athlete":{"displayName":"Sub One"},"position":{"displayName":"Substitute"}}]}]}""")
        val f = GameInfo.football(o)
        assertEquals(listOf("20'", "Yellow Card", "Kosovo", "Veldin Hodza"), f[0].rows[0])
        assertEquals(listOf(Triple("21", "Sub One", false)), GameInfo.benches(o).single().players)
        // 7 starters with a "2-1" formation that doesn't add up: rows by depth instead (defenders, midfield, forward).
        val l = GameInfo.lineups(o).single()
        assertEquals(listOf(0, 1, 1, 2, 2, 2, 3), l.players.map { it.row }.sorted())
        val defenders = l.players.filter { it.row == 1 }.sortedBy { it.x }
        assertEquals(listOf("Kamberi", "Hodza"), defenders.map { it.name })   // left to right
        assertTrue(defenders[1].yellow)
        // One team only: its rows of the key moments, its bench and line-up.
        val mine = GameInfo.forSide(GameInfo.Info(listOf(l), f, null, GameInfo.benches(o)), Side("1", "Israel", "Israel", "ISR", null, null))
        assertEquals(listOf("Israel"), mine.sections.single().rows.map { it[2] })
        assertTrue(mine.lineups.isEmpty() && mine.benches.isEmpty())
    }

    @Test fun photoSearchUsesPlainLetters() {
        assertEquals("viktor gyokeres", PlayerPhotos.plain("Viktor Gyökeres").lowercase())
        assertEquals("jesper karlstrom", PlayerPhotos.plain("Jesper Karlström").lowercase())
        assertEquals("jakub kaminski", PlayerPhotos.plain("Jakub Kamiński").lowercase())
        assertEquals("lukasz skorupski", PlayerPhotos.plain("Łukasz Skorupski").lowercase())
    }

    @Test fun wikipediaTitlesAreEncoded() {
        assertEquals("Viktor_Gy%C3%B6keres", PlayerPhotos.encode("Viktor_Gyökeres"))
        assertEquals("Eric_Smith_(footballer,_born_1997)", PlayerPhotos.encode("Eric_Smith_(footballer,_born_1997)"))
        assertEquals("Jan%20Bednarek%20footballer", PlayerPhotos.encode("Jan Bednarek footballer"))
    }

    @Test fun cricketTabIsTheTeamsBattingTheBowlingAtItAndItsPartnerships() {
        val o = JSONObject("""{"rosters":[{"roster":[{"athlete":{"id":"9","displayName":"Asitha Fernando"}}]}],"matchcards":[
            {"headline":"Batting","teamName":"Pakistan","inningsNumber":"2","runs":"121","playerDetails":[{"playerID":"1","playerName":"Azan Awais","runs":"47","ballsFaced":"96"}]},
            {"headline":"Bowling","teamName":"Sri Lanka","inningsNumber":"2","playerDetails":[{"playerID":"9","playerName":"AM Fernando","overs":"9.0","conceded":"36","wickets":"1"}]},
            {"headline":"Partnerships","teamName":"Pakistan","inningsNumber":"2","playerDetails":[{"partnershipWicketName":"1st","player1Name":"A","player1Runs":"46","player2Name":"B","player2Runs":"28","partnershipRuns":"80","partnershipOvers":"23.5"}]},
            {"headline":"Batting","teamName":"Sri Lanka","inningsNumber":"1","runs":"98","playerDetails":[{"playerID":"9","playerName":"AM Fernando","runs":"4","ballsFaced":"10"}]},
            {"headline":"Bowling","teamName":"Pakistan","inningsNumber":"1","playerDetails":[{"playerID":"1","playerName":"Azan Awais","overs":"2.0","conceded":"5","wickets":"0"}]}]}""")
        val all = GameInfo.cricket(o)
        val pak = GameInfo.cricketTab(all, "Pakistan")
        // Pakistan bowled innings 1 and batted innings 2.
        assertEquals(listOf("bowl" to "Pakistan", "bat" to "Pakistan", "bowl" to "Sri Lanka", "part" to "Pakistan"), pak.map { it.kind to it.team })
        assertEquals(listOf("1st", "A", "46", "B", "28", "80", "23.5"), pak[3].rows.single())
        // The bowler's photo key carries the roster's full name, not the scorecard's initials.
        assertEquals("cricket|9|Asitha Fernando", pak[2].photos!!.single())
        val sl = GameInfo.cricketTab(all, "Sri Lanka")
        // Sri Lanka batted innings 1 and bowled in innings 2: both in its tab, in order.
        assertEquals(listOf("bat" to "Sri Lanka", "bowl" to "Pakistan", "bowl" to "Sri Lanka"), sl.map { it.kind to it.team })
    }

    @Test fun earlierInningsComeFromTheSquads() {
        fun stats(vararg kv: Pair<String, String>) = kv.joinToString(",") { (k, v) -> """{"name":"$k","displayValue":"$v"}""" }
        val o = JSONObject("""{"header":{"competitions":[{"competitors":[{"team":{"displayName":"Bangladesh"},"linescores":[{"period":1,"runs":400,"wickets":10}]}]}]},
          "matchcards":[{"headline":"Batting","teamName":"South Africa","inningsNumber":"2","runs":"386","playerDetails":[{"playerID":"5","playerName":"AK Markram","runs":"5","ballsFaced":"9"}]}],
          "rosters":[
           {"team":{"displayName":"Bangladesh"},"roster":[{"athlete":{"id":"1","displayName":"Towhid Hridoy"},"linescores":[
             {"period":1,"linescores":[{"statistics":{"categories":[{"stats":[${stats("batted" to "1", "battingPosition" to "5", "runs" to "137", "ballsFaced" to "227", "fours" to "14", "sixes" to "1", "dismissalCard" to "lbw", "outs" to "1")}]}]}}]}]},
             {"athlete":{"id":"2","displayName":"Tanzid Hasan"},"linescores":[
             {"period":1,"linescores":[{"statistics":{"categories":[{"stats":[${stats("batted" to "1", "battingPosition" to "2", "runs" to "67", "ballsFaced" to "108", "dismissalCard" to "", "outs" to "0")}]}]}}]}]}]},
           {"team":{"displayName":"South Africa"},"roster":[{"athlete":{"id":"3","displayName":"Kagiso Rabada"},"linescores":[
             {"period":1,"linescores":[{"statistics":{"categories":[{"stats":[${stats("bowled" to "1", "overs" to "25", "maidens" to "5", "conceded" to "80", "wickets" to "3", "economyRate" to "3.2")}]}]}}]}]}]}]}""")
        val all = GameInfo.cricket(o)
        val ban = GameInfo.cricketTab(all, "Bangladesh")
        assertEquals(listOf("bat" to "1", "bowl" to "1"), ban.take(2).map { it.kind to it.innings })
        assertEquals(listOf("Tanzid Hasan", "Towhid Hridoy"), ban[0].rows.map { it[0] })   // batting order
        assertEquals(listOf("not out", "lbw"), ban[0].rows.map { it[1] })
        assertEquals("Total 400 all out", ban[0].note)
        assertEquals(listOf("Kagiso Rabada", "25", "5", "80", "3", "3.2"), ban[1].rows.single())
        // South Africa's innings 2 still from ESPN's own scorecard, not built twice.
        assertEquals(1, all.count { it.kind == "bat" && it.innings == "2" })
    }

    @Test fun testInningsTabsOpenWhereTheTeamBats() {
        // Bangladesh batted innings 1, South Africa is batting innings 2 of a Test.
        fun sec(kind: String, team: String, n: String) = GameInfo.Section("$team $kind $n", listOf("x"), listOf(listOf("y")), team = team, kind = kind, innings = n)
        val sections = listOf(sec("bat", "Bangladesh", "1"), sec("bowl", "South Africa", "1"),
            sec("bat", "South Africa", "2"), sec("bowl", "Bangladesh", "2"), sec("part", "South Africa", "2"))
        val e = SportEvent("cricket:1", cricket, "Bangladesh tour of South Africa", "South Africa vs Bangladesh", 0L, State.LIVE,
            "1st Test · Day 3 · Stumps", side("South Africa"), side("Bangladesh"), emptyList(), null)
        val whole = GameInfo.plan(e, sections, null)
        assertEquals(listOf("Bangladesh", "South Africa", "Bangladesh", "South Africa"), whole.batting)
        assertEquals(2, whole.open)
        assertEquals(listOf("Bangladesh bat 1", "South Africa bowl 1"), GameInfo.inningsSections(sections, whole, 1).map { it.title })
        assertEquals("Bangladesh yet to bat", GameInfo.notYet(whole, 3))
        val sa = GameInfo.plan(e, sections, "South Africa")
        assertEquals(2, sa.open)
        assertEquals(listOf("South Africa bowl 1"), GameInfo.inningsSections(sections, sa, 1).map { it.title })
        assertEquals(3, GameInfo.inningsSections(sections, sa, 2).size)
        assertEquals("South Africa yet to bowl", GameInfo.notYet(sa, 3))
        assertEquals("South Africa yet to bat", GameInfo.notYet(sa, 4))
        // One team's view also opens on the innings being played.
        assertEquals(2, GameInfo.plan(e, sections, "Bangladesh").open)
        // A one-day match has two innings.
        assertEquals(2, GameInfo.plan(e.copy(detail = "2nd ODI"), sections, null).batting.size)
    }

    @Test fun partnershipsFromBallByBall() {
        fun ball(seq: Int, a: String, ar: Int, b: String, br: Int, runs: Int, balls: Int, out: String? = null) = JSONObject(
            """{"sequence":$seq,"period":1,"innings":{"runs":$runs,"balls":$balls},
              "batsman":{"athlete":{"displayName":"$a"},"totalRuns":$ar},"otherBatsman":{"athlete":{"displayName":"$b"},"totalRuns":$br}""" +
            (out?.let { ""","dismissal":{"dismissal":true,"batsman":{"athlete":{"displayName":"$it"}},"text":"$it c Jansen b Rabada 5 (10b) SR: 50"}}""" } ?: "}"))
        val balls = listOf(
            ball(1, "A", 4, "B", 0, 4, 1), ball(2, "B", 1, "A", 4, 6, 2),          // 6 runs: A 4, B 1, 1 extra
            ball(3, "A", 5, "B", 1, 7, 3, out = "A"),                                // A out on 5: 1st wicket 7 (0.3)
            ball(4, "C", 2, "B", 1, 9, 4), ball(5, "B", 4, "C", 2, 12, 5))           // unbroken: C 2, B 3, 5 runs
        val p = GameInfo.partnerships(balls, "Bangladesh", "1")!!
        assertEquals(listOf("1st", "A", "5", "B", "1", "7", "0.3"), p.rows[0])
        assertEquals(listOf("2nd", "C", "2", "B", "3", "5", "0.2"), p.rows[1])
        assertEquals("c Jansen b Rabada", GameInfo.dismissalText("A c Jansen b Rabada 5 (10b) SR: 50", "A"))
    }

    @Test fun keyMomentsNameWhoAndHow() {
        assertEquals("Nico Paz 🎯 · Enzo Fernández 👟", GameInfo.momentText("Goal", listOf("Nico Paz", "Enzo Fernández")))
        assertEquals("↑ John Ruiz · ↓ Aarón Salazar", GameInfo.momentText("Substitution", listOf("John Ruiz", "Aarón Salazar")))
        assertEquals("Veldin Hodza", GameInfo.momentText("Yellow Card", listOf("Veldin Hodza")))
        // One team's view keeps each row with its own player's photo.
        val s = GameInfo.Section("Key moments", listOf("Time", "Event", "Team", "Player"),
            listOf(listOf("8'", "Goal", "Argentina", "Nico Paz"), listOf("45'", "Substitution", "Costa Rica", "↑ John Ruiz")), photos = listOf("Nico Paz", "John Ruiz"))
        val cr = GameInfo.forSide(GameInfo.Info(emptyList(), listOf(s)), Side("2", "Costa Rica", "Costa Rica", "CRC", null, null))
        assertEquals(listOf("John Ruiz"), cr.sections.single().photos)
    }

    @Test fun webCodesInDismissalsAreDecoded() {
        assertEquals("c †Rickelton b Rabada", GameInfo.dismissalText("Tanzid Hasan c &dagger;Rickelton b Rabada 4 (21b) SR: 19", "Tanzid Hasan"))
        assertEquals("Tom & Jerry's", GameInfo.unescape("Tom &amp; Jerry&#39;s"))
    }
}
