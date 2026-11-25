package io.github.novatv.play.data

import io.github.novatv.play.sports.Airing

/**
 * Every version of a channel in the playlist (SD, HD, FHD, 4K…), for a match's "Watch on your channels": a channel
 * found for the match brings its other versions with it, best quality first. Versions are the channels with the same
 * name once quality words and decorations are left out ("PK: PTV SPORTS FHD", "PTV Sports HD", "PTV SPORTS 4K" are all
 * "PTV SPORTS"), and the same country when both name one ("FR: beIN Sports 1" and "AR: beIN Sports 1" show different
 * things). Also finds the playlist channels an addon's streams are named after ("PTV SPORTS HD · CNCVerse Bridge").
 */
object ChannelVersions {
    /** Why a channel is listed when an addon names it (not the guide): its own badge on the match page. */
    const val ADDON_SAYS = "Named by your addon"

    /**
     * Country prefixes that keep versions apart ("FR: beIN Sports 1" is not "AR: beIN Sports 1"). Anything else at the
     * start of a name is a provider's or platform's tag ("TV:", "GO:", "VO:", "M+:", "4K:") and doesn't.
     */
    private val countries = setOf("UK", "GB", "US", "USA", "CA", "IE", "FR", "DE", "ES", "IT", "PT", "NL", "BE", "CH", "AT", "SE",
        "NO", "DK", "FI", "PL", "CZ", "SK", "HU", "RO", "BG", "GR", "TR", "RU", "UA", "AR", "AE", "SA", "QA", "EG", "MA", "DZ",
        "TN", "IL", "IR", "AF", "PK", "IN", "BD", "LK", "NP", "AU", "NZ", "ZA", "NG", "KE", "GH", "MX", "BR", "LAT", "CL", "CO",
        "PE", "JP", "KR", "CN", "HK", "TW", "MY", "SG", "PH", "ID", "TH", "VN", "EXYU", "HR", "RS", "BA", "SI", "AL", "MK", "KU")

    /** The index for one library: the channel list it was built from, and its channels by bare name. */
    @kotlin.concurrent.Volatile private var index: Pair<List<Channel>, Map<String, List<Channel>>>? = null
    /** Bare names without spaces, by length (for [nearest]), built with [index]. */
    @kotlin.concurrent.Volatile private var squeezed: Map<Int, List<Pair<String, String>>> = emptyMap()
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()

    // Built once per library, behind a lock: several threads building it at once (each a copy of the whole
    // playlist's names) filled a Fire TV's memory.
    private fun byName(all: List<Channel>): Map<String, List<Channel>> =
        index?.takeIf { it.first === all }?.second ?: kotlinx.atomicfu.locks.synchronized(lock) {
            index?.takeIf { it.first === all }?.second ?: all.groupBy { HdTwin.core(it.name) }.also { m ->
                squeezed = m.keys.map { it.replace(" ", "") to it }.groupBy { it.first.length }
                index = all to m
            }
        }

    internal fun country(name: String): String? =
        Regex("""^\s*([A-Z]{2,4})\s*[:|]""").find(HdTwin.plain(name))?.groupValues?.get(1)?.takeIf { it in countries }

    /** Best first: 4K, then FHD, HD, unmarked, SD. */
    internal fun rank(name: String): Int {
        val p = HdTwin.plain(name)
        val words = p.split(Regex("""[^A-Z0-9]+""")).toSet()
        return when {
            HdTwin.is4k(name) -> 0
            "FHD" in words || "1080P" in words -> 1
            "HD" in words || "720P" in words -> 2
            "SD" in words -> 4
            else -> 3
        }
    }

    /** Builds the name index ahead (when Sports opens), so a match's page never waits for it. Blocking. */
    fun prepare(all: List<Channel>) { if (all.isNotEmpty()) byName(all) }

    /** All versions of [ch] in [all] (itself included), best quality first. Blocking the first time for a library. */
    fun versions(ch: Channel, all: List<Channel>): List<Channel> {
        val key = HdTwin.core(ch.name).ifBlank { return listOf(ch) }
        val own = country(ch.name)
        val named = byName(all)[key].orEmpty()
        // Without a country of its own ("VIP: DAZN 5"), a channel takes the country versions only when the name is one
        // country's ("PK: PTV Sports HD"); "DAZN 5" in Portugal, Germany and France are different channels.
        val countries = named.mapNotNull { country(it.name) }.toSet()
        val same = named.filter { c -> country(c.name).let { it == null || it == own || (own == null && countries.size == 1) } }
        return (same + ch).distinctBy { it.id }.sortedBy { rank(it.name) }
    }

    /**
     * [airings] with every version of each channel listed after it (Option A: a row per version), each group best
     * quality first, in the order the channels were found; a version found twice is listed once.
     */
    fun expand(airings: List<Airing>, all: List<Channel>): List<Airing> {
        if (all.isEmpty()) return airings
        val seen = HashSet<String>()
        val out = ArrayList<Airing>()
        for (a in airings) {
            if (a.channel.categoryId == Addons.CATEGORY) { if (seen.add(a.channel.id)) out += a; continue }
            for (c in versions(a.channel, all)) if (seen.add(c.id)) out += Airing(c, a.programme, a.why)
        }
        return out
    }

    /**
     * The playlist channels [addonStreams] are named after ("PTV SPORTS HD · CNCVerse Bridge" -> your "PTV Sports"),
     * one per name (the best version; [expand] adds the rest). Labels that name no channel ("Link 1", "HD Quality",
     * "SK LIVE") match nothing in the playlist and are left out.
     */
    fun namedByAddons(addonStreams: List<Airing>, all: List<Channel>): List<Airing> {
        if (all.isEmpty()) return emptyList()
        val map = byName(all)
        return addonStreams.mapNotNull { a ->
            val label = a.channel.name.substringBefore(" · ").trim()
            val key = HdTwin.core(label)
            // A real channel name: letters, at least 4 of them, and found as is in the playlist.
            if (key.length < 4 || key.none { it.isLetter() }) return@mapNotNull null
            // As named, else a name one letter off (playlists' typos: the addon's "TEN SPORTS" is "PK: TENS SPORTS").
            val versions = map[key] ?: nearSqueezed(key)?.let { map[it] } ?: return@mapNotNull null
            val found = versions.minByOrNull { rank(it.name) } ?: return@mapNotNull null
            Airing(found, null, ADDON_SAYS)
        }.distinctBy { HdTwin.core(it.channel.name) }
    }

    /**
     * The one bare name among [names] that is [key] with one letter added, dropped or changed (spaces ignored), for
     * names of 8 letters or more; never a different number ("SKY SPORTS 1" is not "SKY SPORTS 2"), and none when
     * two names are equally near.
     */
    internal fun nearest(key: String, names: Collection<String>): String? {
        val k = key.replace(" ", "")
        if (k.length < 8) return null
        val near = names.filter { n ->
            val m = n.replace(" ", "")
            kotlin.math.abs(m.length - k.length) <= 1 && m.firstOrNull() == k.firstOrNull() &&
                m.filter { it.isDigit() } == k.filter { it.isDigit() } && oneEditApart(k, m)
        }
        return near.singleOrNull()
    }

    /** [nearest] over the index's names, comparing only names one letter longer or shorter. */
    private fun nearSqueezed(key: String): String? {
        val k = key.replace(" ", "")
        if (k.length < 8) return null
        val near = (k.length - 1..k.length + 1).flatMap { squeezed[it].orEmpty() }.filter { (m, _) ->
            m[0] == k[0] && m.filter { it.isDigit() } == k.filter { it.isDigit() } && oneEditApart(k, m)
        }
        return near.singleOrNull()?.second
    }

    /** Whether [a] and [b] differ by exactly one letter added, dropped or changed. */
    private fun oneEditApart(a: String, b: String): Boolean {
        if (a == b) return false
        if (a.length == b.length) return a.indices.count { a[it] != b[it] } == 1
        val (s, l) = if (a.length < b.length) a to b else b to a
        var i = 0
        while (i < s.length && s[i] == l[i]) i++
        return s.substring(i) == l.substring(i + 1)
    }

    /** "Watch on your channels": the guide's channels, then those addons name, with all their versions. Blocking. */
    fun forMatch(airings: List<Airing>, addonStreams: List<Airing>, all: List<Channel>): List<Airing> {
        val named = namedByAddons(addonStreams, all).filter { n -> airings.none { HdTwin.core(it.channel.name) == HdTwin.core(n.channel.name) } }
        return expand(airings + named, all)
    }
}
