package io.github.novatv.play.data

/** One playlist format. All calls block (network); run them on Dispatchers.IO. */
interface Provider {
    /** Loads categories, channels, movies and series. [progress] gets short status lines for the UI. */
    fun load(progress: (String) -> Unit): Library
    fun liveUrl(ch: Channel): String
    fun movieUrl(m: Movie): String
    fun episodeUrl(s: Series, e: Episode): String
    fun movieDetails(m: Movie): Details
    /** Plot, cast and the seasons with their episodes. */
    fun seriesDetails(s: Series): Details
    /** Now and next for one channel when there is no XMLTV guide. */
    fun shortEpg(ch: Channel): List<Programme> = emptyList()

    companion object {
        fun of(s: Source): Provider = when (s.type) {
            SourceType.XTREAM -> XtreamProvider(s)
            SourceType.M3U -> M3uProvider(s)
            SourceType.STALKER -> StalkerProvider(s)
        }
    }
}

/** "1080p 2023 Some Film (2023)" style helpers shared by the providers. */
internal object Names {
    private val yearRe = Regex("""[\(\[](19|20)\d{2}[\)\]]""")
    fun year(name: String): String? = yearRe.find(name)?.value?.trim('(', ')', '[', ']')
}
