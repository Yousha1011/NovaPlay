package io.github.novatv.play.data

import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * M3U / M3U8 playlists (a URL, or a file:// path). Entries are sorted into live channels, movies and series
 * episodes by their URL (/movie/, /series/, video file extensions) and their names (S01E02).
 */
class M3uProvider(private val s: Source) : Provider {
    private val attrRe = Regex("""([\w-]+)="([^"]*)"""")
    private val episodeRe = Regex("""^(.*?)[\s._\-]*[Ss](\d{1,2})[\s._\-]*[Ee](\d{1,3})\b(.*)$""")
    private val vodExt = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm")

    override fun load(progress: (String) -> Unit): Library {
        progress("Downloading playlist")
        val url = s.url.trim()
        return if (url.startsWith("/") || url.startsWith("file:")) {
            FileSystem.SYSTEM.source(url.removePrefix("file://").toPath()).buffer().use { parse(it, progress) }
        } else Http.stream(url) { parse(it, progress) }
    }

    private fun parse(r: BufferedSource, progress: (String) -> Unit): Library {
        val liveCats = LinkedHashMap<String, Category>()
        val movieCats = LinkedHashMap<String, Category>()
        val seriesCats = LinkedHashMap<String, Category>()
        val channels = ArrayList<Channel>()
        val movies = ArrayList<Movie>()
        // Series built from their episodes: show name -> (category, poster, episodes)
        val shows = LinkedHashMap<String, Triple<String, String?, MutableList<Episode>>>()
        var epg: String? = null
        var attrs: Map<String, String> = emptyMap()
        var title = ""
        // #KODIPROP lines (a DRM licence), before or after the entry's #EXTINF: they belong to the next address.
        var props = HashMap<String, String>()
        var count = 0
        while (true) {
            val line = r.readUtf8Line() ?: break
            val t = line.trim()
            when {
                t.startsWith("#EXTM3U") -> {
                    val a = attrRe.findAll(t).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    epg = a["url-tvg"] ?: a["x-tvg-url"]
                }
                t.startsWith("#EXTINF") -> {
                    val comma = lastTitleComma(t)
                    attrs = attrRe.findAll(t.substring(0, comma)).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    title = t.substring(comma + 1).trim()
                }
                t.startsWith("#KODIPROP:", true) -> {
                    val kv = t.substring(10)
                    props[kv.substringBefore('=').trim().lowercase()] = kv.substringAfter('=', "").trim()
                }
                t.isEmpty() || t.startsWith("#") -> {}
                else -> {
                    val name = title.ifEmpty { attrs["tvg-name"] ?: t.substringAfterLast('/') }
                    val group = attrs["group-title"]?.ifBlank { null } ?: "Uncategorised"
                    val logo = attrs["tvg-logo"]?.ifBlank { null }
                    val lower = t.lowercase().substringBefore('?')
                    val ext = lower.substringAfterLast('.', "")
                    val ep = episodeRe.find(name)
                    when {
                        lower.contains("/series/") || (ep != null && (ext in vodExt || lower.contains("/movie/"))) -> {
                            val show = ep?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: name
                            val cat = seriesCats.getOrPut(group) { Category("s${seriesCats.size}", group) }
                            val entry = shows.getOrPut(show) { Triple(cat.id, logo, mutableListOf()) }
                            entry.third.add(Episode(
                                id = t, season = ep?.groupValues?.get(2)?.toIntOrNull() ?: 1, number = ep?.groupValues?.get(3)?.toIntOrNull() ?: (entry.third.size + 1),
                                title = name, plot = null, image = logo, durationSec = 0, url = t,
                            ))
                        }
                        lower.contains("/movie/") || ext in vodExt -> {
                            val cat = movieCats.getOrPut(group) { Category("m${movieCats.size}", group) }
                            movies.add(Movie(t, name, logo, cat.id, null, Names.year(name), t))
                        }
                        else -> {
                            val cat = liveCats.getOrPut(group) { Category("l${liveCats.size}", group) }
                            channels.add(Channel(
                                id = t, name = name, logo = logo, categoryId = cat.id,
                                epgId = attrs["tvg-id"]?.ifBlank { null } ?: attrs["tvg-name"]?.ifBlank { null }, url = t,
                                number = attrs["tvg-chno"]?.toIntOrNull() ?: (channels.size + 1),
                                catchup = attrs["catchup"] != null || attrs["tvg-rec"]?.let { it != "0" } == true,
                                cmd = DrmLicences.fromKodiProps(props)?.let(DrmLicences::encode),
                            ))
                        }
                    }
                    attrs = emptyMap(); title = ""; if (props.isNotEmpty()) props = HashMap()
                    if (++count % 5000 == 0) progress("Reading playlist: $count entries")
                }
            }
        }
        val series = shows.map { (show, v) ->
            Series(id = "m3u:$show", name = show, poster = v.second, categoryId = v.first, rating = null, plot = null,
                year = Names.year(show), episodes = v.third.sortedWith(compareBy({ it.season }, { it.number })))
        }
        if (channels.isEmpty() && movies.isEmpty() && series.isEmpty()) throw IllegalStateException("The playlist is empty or not an M3U file")
        return Library(liveCats.values.toList(), channels, movieCats.values.toList(), movies, seriesCats.values.toList(), series,
            s.epgUrl.ifBlank { null } ?: epg?.split(',')?.firstOrNull()?.trim())
    }

    /** The comma before the title: the first one outside quotes (titles may contain commas). */
    private fun lastTitleComma(t: String): Int {
        var q = false
        for (i in t.indices) {
            when (t[i]) { '"' -> q = !q; ',' -> if (!q) return i }
        }
        return t.length - 1
    }

    override fun liveUrl(ch: Channel): String {
        val url = ch.url ?: ch.id
        // A DRM-protected channel's licence, for the player to find by the address it is given.
        DrmLicences.decode(ch.cmd)?.let { DrmLicences.put(url, it) }
        return url
    }
    override fun movieUrl(m: Movie) = m.url ?: m.id
    override fun episodeUrl(s: Series, e: Episode) = e.url ?: e.id
    override fun movieDetails(m: Movie) = Details()
    override fun seriesDetails(s: Series) = Details(seasons = s.episodes.groupBy { it.season }.sortedByKey())
}
