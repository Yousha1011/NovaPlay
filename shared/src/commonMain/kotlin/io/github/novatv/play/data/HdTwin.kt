package io.github.novatv.play.data

/**
 * The HD version of a 4K channel, for multi-view's small screens: half the screen gains nothing from 4K, and an HD
 * stream needs about a quarter of the memory to decode and the bandwidth to receive. Playlists name the versions
 * alike: "4K: SKY SPORTS F1 ᵁᴴᴰ ³⁸⁴⁰ᴾ" and "UK: SKY SPORTS F1 ᴴᴰ ◉".
 */
object HdTwin {
    /** Small letters and figures playlists decorate names with ("ᴴᴰ", "³⁸⁴⁰ᴾ", "ʰᵉᵛᶜ"), as plain ones. */
    private val small = mapOf(
        'ᴬ' to 'A', 'ᴮ' to 'B', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᴳ' to 'G', 'ᴴ' to 'H', 'ᴵ' to 'I', 'ᴶ' to 'J', 'ᴷ' to 'K', 'ᴸ' to 'L',
        'ᴹ' to 'M', 'ᴺ' to 'N', 'ᴼ' to 'O', 'ᴾ' to 'P', 'ᴿ' to 'R', 'ᵀ' to 'T', 'ᵁ' to 'U', 'ⱽ' to 'V', 'ᵂ' to 'W',
        'ᵃ' to 'a', 'ᵇ' to 'b', 'ᶜ' to 'c', 'ᵈ' to 'd', 'ᵉ' to 'e', 'ᶠ' to 'f', 'ᵍ' to 'g', 'ʰ' to 'h', 'ⁱ' to 'i', 'ʲ' to 'j',
        'ᵏ' to 'k', 'ˡ' to 'l', 'ᵐ' to 'm', 'ⁿ' to 'n', 'ᵒ' to 'o', 'ᵖ' to 'p', 'ʳ' to 'r', 'ˢ' to 's', 'ᵗ' to 't', 'ᵘ' to 'u',
        'ᵛ' to 'v', 'ʷ' to 'w', 'ˣ' to 'x', 'ʸ' to 'y', 'ᶻ' to 'z',
        '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9',
    )

    fun plain(name: String): String = buildString(name.length) { for (c in name) append(small[c] ?: c) }.uppercase()

    /** Words that describe the stream, not the channel. */
    private val quality = setOf("4K", "UHD", "HD", "FHD", "SD", "RAW", "HEVC", "H265", "H264", "VIP", "HDR", "8K", "3840P", "2160P",
        "1080P", "720P", "50FPS", "60FPS", "FPS", "50", "60", "MULTI", "BACKUP", "NM")

    /** "4K: SKY SPORTS F1 ᵁᴴᴰ ³⁸⁴⁰ᴾ" → "SKY SPORTS F1": no region prefix, quality words or symbols. */
    fun core(name: String): String {
        var s = plain(name).replace(Regex("""\(.*?\)|\[.*?]"""), " ")
        // The region or quality prefix: "UK:", "4K|", "PL VIP:".
        s = s.replace(Regex("""^\s*[A-Z0-9+ ]{1,10}[:|]\s*"""), "")
        return s.split(Regex("""[^A-Z0-9+&']+""")).filter { it.isNotBlank() && it !in quality }.joinToString(" ")
    }

    /** Whether [name] is a 4K stream. */
    fun is4k(name: String): Boolean = Regex("""(^|[^A-Z0-9])(4K|UHD|2160P|3840P|8K)([^A-Z0-9]|$)""").containsMatchIn(plain(name))

    /** How good an HD stand-in [name] is: HD-marked first, then raw, AVC over HEVC (lighter to decode), SD last. */
    private fun score(name: String): Int {
        val p = plain(name)
        val words = p.split(Regex("""[^A-Z0-9]+""")).toSet()
        var s = 0
        if ("HD" in words || "FHD" in words) s += 4
        if ("RAW" in words) s += 2
        if ("HEVC" in words || "H265" in words) s -= 1
        if ("SD" in words) s -= 3
        if ("BACKUP" in words) s -= 1
        return s
    }

    /** The index for one library: its channel list and their non-4K versions by name. Replaced whole, so no lock is needed. */
    private var index: Pair<List<Channel>, Map<String, List<Channel>>>? = null

    /**
     * The HD version of [ch] among [all], or null when [ch] isn't 4K or has none. Prefers the same region as [ch] when it
     * names one. Blocking the first time for a library (it indexes the names); call off the main thread.
     */
    fun of(ch: Channel, all: List<Channel>): Channel? {
        if (!is4k(ch.name)) return null
        val map = index?.takeIf { it.first === all }?.second
            ?: all.filter { !is4k(it.name) }.groupBy { core(it.name) }.also { index = all to it }
        val key = core(ch.name).ifBlank { return null }
        val candidates = map[key] ?: return null
        val region = Regex("""^\s*([A-Z]{2,3})\s*[:|]""").find(plain(ch.name))?.groupValues?.get(1)?.takeIf { it != "4K" && it != "UHD" }
        return candidates.maxByOrNull { score(it.name) + if (region != null && plain(it.name).startsWith(region)) 3 else 0 }
    }
}
