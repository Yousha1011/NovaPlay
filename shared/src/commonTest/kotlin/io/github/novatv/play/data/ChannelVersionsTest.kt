package io.github.novatv.play.data

import io.github.novatv.play.sports.Airing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every version of a channel (SD, HD, FHD, 4K) on a match's list, and channels named by addon streams. */
class ChannelVersionsTest {
    private fun ch(id: String, name: String, cat: String = "1") = Channel(id = id, name = name, logo = null, categoryId = cat, epgId = null, url = "http://x/$id", number = 0)

    private val all = listOf(
        ch("1", "PK: PTV SPORTS HD"), ch("2", "PK: PTV Sports"), ch("3", "PK: PTV SPORTS FHD"), ch("4", "4K: PTV SPORTS ᵁᴴᴰ"),
        ch("5", "PK: PTV SPORTS SD"), ch("6", "PK: PTV SPORTS PLUS HD"),
        ch("7", "FR: beIN SPORTS 1 HD"), ch("8", "AR: beIN SPORTS 1 HD"), ch("9", "AR: beIN SPORTS 1 4K"),
        ch("10", "UK: SKY SPORTS CRICKET"),
    )

    @Test fun allVersionsBestFirstAndOthersApart() {
        val v = ChannelVersions.versions(all[0], all).map { it.id }
        // 4K, FHD, HD, unmarked, SD; "PTV Sports Plus" is another channel.
        assertEquals(listOf("4", "3", "1", "2", "5"), v)
    }

    @Test fun countriesKeptApart() {
        // beIN Sports 1 in France and in Arabia show different things.
        assertEquals(listOf("9", "8"), ChannelVersions.versions(all[7], all).map { it.id })
        assertEquals(listOf("7"), ChannelVersions.versions(all[6], all).map { it.id })
    }

    @Test fun matchListGetsEveryVersionOnce() {
        val found = listOf(Airing(all[1], null, "In your TV guide"), Airing(all[0], null, "Match feed"))
        val out = ChannelVersions.expand(found, all)
        assertEquals(listOf("4", "3", "1", "2", "5"), out.map { it.channel.id })
        assertTrue(out.all { it.why == "In your TV guide" })
    }

    @Test fun channelsNamedByAddonStreams() {
        fun addon(label: String) = Airing(ch("addon:$label", "$label · CNCVerse Bridge", Addons.CATEGORY), null, "x")
        val streams = listOf(addon("PTV SPORTS HD"), addon("Link 1"), addon("HD QUALITY"), addon("SK LIVE"), addon("SKY SPORTS CRICKET"))
        val named = ChannelVersions.namedByAddons(streams, all)
        assertEquals(listOf("4", "10"), named.map { it.channel.id })
        assertTrue(named.all { it.why == ChannelVersions.ADDON_SAYS })
        // Already found in the guide: not added again; the rest come with all their versions.
        val list = ChannelVersions.forMatch(listOf(Airing(all[9], null, "Match feed")), streams, all)
        assertEquals(listOf("10", "4", "3", "1", "2", "5"), list.map { it.channel.id })
    }
}

class ChannelVersionsCountriesTest {
    private fun ch(id: String, name: String) = Channel(id = id, name = name, logo = null, categoryId = "1", epgId = null, url = "http://x/$id", number = 0)

    @Test fun noCountryTakesOtherCountriesOnlyWhenThereIsOne() {
        val dazn = listOf(ch("1", "VIP: DAZN 5 HD"), ch("2", "PT: DAZN 5 4K"), ch("3", "DE: DAZN 5 HD"), ch("4", "DAZN 5 RAW"))
        // DAZN 5 exists in several countries: only the other country-less version.
        assertEquals(setOf("1", "4"), ChannelVersions.versions(dazn[0], dazn).map { it.id }.toSet())
        // Provider tags aren't countries: "TV:" and "VO:" versions of one channel go together.
        val laliga = listOf(ch("5", "TV: DAZN LALIGA 2 RAW"), ch("6", "VO: DAZN LALIGA 2 HD"))
        assertEquals(setOf("5", "6"), ChannelVersions.versions(laliga[0], laliga).map { it.id }.toSet())
    }
}

class ChannelVersionsTyposTest {
    private fun ch(id: String, name: String, cat: String = "1") = Channel(id = id, name = name, logo = null, categoryId = cat, epgId = null, url = "http://x/$id", number = 0)

    @Test fun addonNameOneLetterOffStillFindsTheChannel() {
        val all = listOf(ch("1", "PK: TENS SPORTS"), ch("2", "SPORTS: TEN 1 HD"), ch("3", "UK: SKY SPORTS 1 HD"), ch("4", "UK: SKY SPORTS TENNIS"))
        fun addon(label: String) = Airing(ch("addon:$label", "$label · CNCVerse Bridge", Addons.CATEGORY), null, "x")
        // "TEN SPORTS HD" is the playlist's "TENS SPORTS"; "SKY SPORTS 2" never becomes "SKY SPORTS 1".
        val named = ChannelVersions.namedByAddons(listOf(addon("TEN SPORTS HD"), addon("SKY SPORTS 2 HD")), all)
        assertEquals(listOf("1"), named.map { it.channel.id })
        assertEquals(null, ChannelVersions.nearest("TEN 1", listOf("TEN 2")))
    }
}
