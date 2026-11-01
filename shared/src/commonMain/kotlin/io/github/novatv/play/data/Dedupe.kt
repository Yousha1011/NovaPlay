package io.github.novatv.play.data

/**
 * One copy of each repeated text in a library. Big playlists repeat the same few values tens of thousands of times
 * (a film's category, year, rating, file type, often its poster and a channel's logo): kept once each, a playlist of
 * 184,000 films and 57,000 channels takes about 50 MB less, which a Fire TV Stick needs.
 */
class Dedupe {
    private val pool = HashMap<String, String>()

    fun of(s: String?): String? = s?.let { pool.getOrPut(it) { it } }
    fun req(s: String): String = pool.getOrPut(s) { s }

    fun channel(c: Channel) = c.copy(logo = of(c.logo), categoryId = req(c.categoryId), epgId = of(c.epgId))
    fun movie(m: Movie) = m.copy(poster = of(m.poster), categoryId = req(m.categoryId), rating = of(m.rating), year = of(m.year), ext = of(m.ext))
    fun series(s: Series) = s.copy(poster = of(s.poster), categoryId = req(s.categoryId), rating = of(s.rating), year = of(s.year))

    companion object {
        /** [lib] with its repeated texts shared; the lists are rebuilt one at a time to keep the peak low. */
        fun library(lib: Library): Library {
            val d = Dedupe()
            val channels = lib.channels.map(d::channel)
            val movies = lib.movies.map(d::movie)
            val series = lib.series.map(d::series)
            return lib.copy(channels = channels, movies = movies, series = series)
        }
    }
}
