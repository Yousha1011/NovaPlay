package io.github.novatv.play.data

import io.github.novatv.play.platform.SafeMap


/**
 * Sorted and grouped views of a library, made once per library in the background so screens only look them up.
 * Playlists hold 50,000 channels and 180,000 movies: sorting those while a screen opened made every tab lag.
 */
class LibIndex(val lib: Library) {
    val moviesByAdded: List<Movie> = lib.movies.sortedByDescending { it.added }
    val seriesByAdded: List<Series> = lib.series.sortedByDescending { it.added }
    val moviesByCat: Map<String, List<Movie>> = moviesByAdded.groupBy { it.categoryId }
    val seriesByCat: Map<String, List<Series>> = seriesByAdded.groupBy { it.categoryId }
    val channelsByCat: Map<String, List<Channel>> = lib.channels.groupBy { it.categoryId }
    /** Channels whose category the source never listed. */
    val otherChannels: List<Channel> = lib.liveCategories.map { it.id }.toSet().let { known -> lib.channels.filter { it.categoryId !in known } }

    val channelById: Map<String, Channel> = lib.channels.associateBy { it.id }

    /** The channels behind saved keys ("ch:source:id"), in the keys' order; no per-channel work. */
    fun channelsFor(keys: Collection<String>): List<Channel> = keys.mapNotNull { k ->
        if (!k.startsWith("ch:")) null else channelById[k.substring(k.indexOf(':', 3) + 1)]
    }

    /** Other sort orders, made on first use and kept (A-Z and rating of a category or of everything). */
    private val sorted = SafeMap<String, List<Any>>()
    fun <T : Any> sortedOnce(key: String, make: () -> List<T>): List<T> {
        @Suppress("UNCHECKED_CAST")
        return sorted.getOrPut(key) { make() } as List<T>
    }
}
