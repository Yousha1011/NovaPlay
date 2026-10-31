package io.github.novatv.play.data

/**
 * Categories the user doesn't want to see (no PIN: a tidier list, not a lock). Big playlists prefix their categories
 * with a country or region ("UK| NEWS", "IT| CINEMA", "EN: Sports"), so whole regions can be hidden at once, as well
 * as single categories. Kept per playlist, as "<playlist>|r:<region>" and the [Parental] category keys.
 */
object Visibility {
    /** "UK" for "UK| NEWS", "EN" for "EN: Sports", "4K" for "4K| RELAX"; null when the name has no such prefix. */
    fun region(name: String): String? =
        Regex("""^\s*([A-Za-z0-9]{2,4})\s*[|:]""").find(name)?.groupValues?.get(1)?.uppercase()

    fun regionKey(source: String, region: String) = "$source|r:$region"

    /** The regions in [lib]'s live, film and series categories, with how many categories each has, most first. */
    fun regions(lib: Library): List<Pair<String, Int>> =
        (lib.liveCategories + lib.movieCategories + lib.seriesCategories)
            .mapNotNull { region(it.name) }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }

    /** [lib] without its hidden categories and regions for playlist [source]; [lib] itself when nothing is hidden. */
    fun filter(lib: Library, source: String, hidden: Set<String>): Library {
        if (hidden.none { it.startsWith("$source|") }) return lib
        fun gone(cats: List<Category>, key: (String) -> String) = cats.filter { c ->
            key(c.id) in hidden || region(c.name)?.let { regionKey(source, it) in hidden } == true
        }.map { it.id }.toSet()
        val live = gone(lib.liveCategories) { Parental.liveKey(source, it) }
        val films = gone(lib.movieCategories) { Parental.movieKey(source, it) }
        val shows = gone(lib.seriesCategories) { Parental.seriesKey(source, it) }
        if (live.isEmpty() && films.isEmpty() && shows.isEmpty()) return lib
        return lib.copy(
            liveCategories = lib.liveCategories.filter { it.id !in live },
            channels = if (live.isEmpty()) lib.channels else lib.channels.filter { it.categoryId !in live },
            movieCategories = lib.movieCategories.filter { it.id !in films },
            movies = if (films.isEmpty()) lib.movies else lib.movies.filter { it.categoryId !in films },
            seriesCategories = lib.seriesCategories.filter { it.id !in shows },
            series = if (shows.isEmpty()) lib.series else lib.series.filter { it.categoryId !in shows },
        )
    }
}
