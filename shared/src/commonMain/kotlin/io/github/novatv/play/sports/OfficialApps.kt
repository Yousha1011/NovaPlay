package io.github.novatv.play.sports

/**
 * The broadcasters' own apps: a match that only they show (often DRM-protected, so no IPTV player can play it) is opened
 * in their app, or on their website where there is no app to open.
 */
object OfficialApps {
    /** A service: its name, the words that name it in a broadcast listing, its Android apps (phone and TV) and its site. */
    data class Service(val name: String, val words: List<String>, val packages: List<String>, val web: String)

    val all = listOf(
        Service("Prime Video", listOf("prime video", "amazon prime", "amazon"),
            listOf("com.amazon.firebat", "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"), "https://www.primevideo.com"),
        Service("FanCode", listOf("fancode"), listOf("com.dream11sportsguru", "com.fancode.tv"), "https://www.fancode.com"),
        Service("Willow", listOf("willow"), listOf("com.willow.android", "tv.willow", "com.willowtv.androidtv"), "https://www.willow.tv"),
        Service("JioHotstar", listOf("hotstar", "jiohotstar", "jiocinema", "star sports"),
            listOf("in.startv.hotstar", "in.startv.hotstar.dplus.tv", "com.jio.media.ondemand"), "https://www.hotstar.com"),
        Service("Sony LIV", listOf("sony liv", "sonyliv", "sony sports", "sony ten"), listOf("com.sonyliv"), "https://www.sonyliv.com"),
        Service("DAZN", listOf("dazn"), listOf("com.dazn"), "https://www.dazn.com"),
        Service("ESPN", listOf("espn"), listOf("com.espn.score_center", "com.espn.gtv"), "https://www.espn.com/watch"),
        Service("Sky Sports", listOf("sky sports", "sky go", "now tv", "now sports"), listOf("com.bskyb.skygo", "com.nowtv.androidtv", "com.bskyb.nowtv.beta"), "https://www.skysports.com/watch"),
        Service("TNT Sports", listOf("tnt sports", "discovery+", "hbo max"), listOf("com.wbd.stream", "com.discoveryplus.mobile"), "https://www.tntsports.co.uk"),
        Service("Peacock", listOf("peacock"), listOf("com.peacocktv.peacockandroid"), "https://www.peacocktv.com"),
        Service("Paramount+", listOf("paramount+", "paramount plus", "cbs sports"), listOf("com.cbs.app", "com.cbs.ott"), "https://www.paramountplus.com"),
        Service("Kayo Sports", listOf("kayo", "foxtel"), listOf("au.com.streamotion.ares", "au.com.streamotion.ares.tv"), "https://kayosports.com.au"),
        Service("beIN SPORTS", listOf("bein"), listOf("com.bein.beIN", "net.beinsports.connect"), "https://connect.beinsports.com"),
        Service("Tapmad", listOf("tapmad"), listOf("com.tapmad.tapmadtv"), "https://www.tapmad.com"),
        Service("Tamasha", listOf("tamasha"), listOf("com.jazz.tamasha"), "https://tamashaweb.com"),
        Service("PTV Sports", listOf("ptv sports"), listOf("com.ptv.sports"), "https://ptvsports.pk"),
        Service("T Sports", listOf("t sports", "tsports"), listOf("com.tsports.app"), "https://www.tsports.com"),
        Service("SuperSport", listOf("supersport", "dstv"), listOf("com.dstvmobile.android", "com.supersport.app"), "https://www.supersport.com"),
        Service("YouTube", listOf("youtube"), listOf("com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.google.android.youtube"), "https://www.youtube.com"),
    )

    /** The services among [broadcasts]' channel names (and the match's own listing), each once, in the listing's order. */
    fun forBroadcasts(names: List<String>): List<Service> {
        val low = names.map { it.lowercase() }
        return low.flatMap { n -> all.filter { s -> s.words.any { w -> matches(n, w) } } }.distinct()
    }

    /** [word] in [name] as whole words ("max" is not "maximum"). */
    internal fun matches(name: String, word: String): Boolean {
        var i = name.indexOf(word)
        while (i >= 0) {
            val before = i == 0 || !name[i - 1].isLetterOrDigit()
            val end = i + word.length
            val after = end >= name.length || !name[end].isLetterOrDigit()
            if (before && after) return true
            i = name.indexOf(word, i + 1)
        }
        return false
    }

    /** Opens [s]'s app (or site); where neither opens (a TV with no browser), says which app to install. */
    fun open(s: Service) {
        val host = io.github.novatv.play.App.host
        if (!host.openApp(s.packages, s.web, s.name)) host.toast("Install ${s.name} from your app store to watch there")
    }

    /** The services showing [e]: from its broadcast listings, blocking (call off the main thread). */
    fun forEvent(e: SportEvent): List<Service> =
        forBroadcasts(e.broadcasts + runCatching { Broadcasters.forEvent(e) }.getOrElse { emptyList() }.map { it.channel })
}
