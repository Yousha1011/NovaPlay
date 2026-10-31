package io.github.novatv.play.data

/**
 * Parental controls: with a PIN set and controls on, blocked categories (live, film and series) and blocked
 * channels disappear from every list, the guide, Home, Search and Sports. Entering the PIN shows them again until
 * the app closes. Blocks are kept per playlist.
 *
 * The PIN keeps a TV's content away from children; it is not a lock against a determined adult, so a salted hash
 * of it is kept rather than a strong key.
 */
object Parental {
    /** What is blocked, as kept in [Store]: "<playlist>|l:<category>", "...|m:...", "...|s:...", and channel keys. */
    data class Rules(
        val on: Boolean,
        val blockAdult: Boolean,
        val categories: Set<String>,
        val channels: Set<String>,
    ) {
        val active get() = on && (blockAdult || categories.isNotEmpty() || channels.isNotEmpty())
    }

    fun liveKey(source: String, catId: String) = "$source|l:$catId"
    fun movieKey(source: String, catId: String) = "$source|m:$catId"
    fun seriesKey(source: String, catId: String) = "$source|s:$catId"

    /** Category names that mark adult content (XXX, Adult, 18+ and the like). */
    fun isAdultName(name: String): Boolean =
        Regex("""(^|[^a-z])(xxx|adult|adults|porn|18\+|\+18|for adults)([^a-z]|$)""").containsMatchIn(name.lowercase())

    /**
     * [lib] without what [rules] block for playlist [source]; [channelKey] gives a channel's key. Returns [lib] itself
     * when nothing is blocked, so callers can tell nothing changed.
     */
    fun filter(lib: Library, source: String, rules: Rules, channelKey: (Channel) -> String): Library {
        if (!rules.active) return lib
        fun blocked(cats: List<Category>, key: (String) -> String) =
            cats.filter { key(it.id) in rules.categories || (rules.blockAdult && isAdultName(it.name)) }.map { it.id }.toSet()
        val live = blocked(lib.liveCategories) { liveKey(source, it) }
        val films = blocked(lib.movieCategories) { movieKey(source, it) }
        val shows = blocked(lib.seriesCategories) { seriesKey(source, it) }
        val chans = rules.channels.filter { it.startsWith("ch:$source:") }.toSet()
        if (live.isEmpty() && films.isEmpty() && shows.isEmpty() && chans.isEmpty()) return lib
        return lib.copy(
            liveCategories = lib.liveCategories.filter { it.id !in live },
            channels = lib.channels.filter { it.categoryId !in live && (chans.isEmpty() || channelKey(it) !in chans) },
            movieCategories = lib.movieCategories.filter { it.id !in films },
            movies = if (films.isEmpty()) lib.movies else lib.movies.filter { it.categoryId !in films },
            seriesCategories = lib.seriesCategories.filter { it.id !in shows },
            series = if (shows.isEmpty()) lib.series else lib.series.filter { it.categoryId !in shows },
        )
    }

    /** A salted hash of a PIN, as kept in [Store]. */
    fun hash(pin: String): String {
        var h = 1125899906842597L
        for (c in "nova-play:$pin") h = 31 * h + c.code
        return h.toULong().toString(16)
    }

    fun validPin(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() }
}
