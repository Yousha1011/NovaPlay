package io.github.novatv.play.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Stalker / Ministra "MAC" portals. The client poses as a MAG set-top box: a handshake gives a token, then
 * load.php serves genres, channels and VOD pages, and create_link turns a channel's command into a stream URL.
 */
class StalkerProvider(private val s: Source) : Provider {
    private val mac = s.mac.trim().uppercase()
    private val api: String
    private val referer: String
    @Volatile private var token: String = ""

    init {
        var u = s.url.trim().trimEnd('/')
        if (!u.startsWith("http")) u = "http://$u"
        // "http://host:port/c" is the portal page; the API sits next to it.
        u = u.removeSuffix("/c").removeSuffix("/index.html")
        api = when {
            u.endsWith(".php") -> u
            u.endsWith("/stalker_portal") -> "$u/server/load.php"
            else -> "$u/portal.php"
        }
        referer = "$u/c/"
    }

    private fun headers(): Map<String, String> = buildMap {
        put("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3")
        put("X-User-Agent", "Model: MAG250; Link: WiFi")
        put("Referer", referer)
        put("Cookie", "mac=${URLEncoder.encode(mac, "UTF-8")}; stb_lang=en; timezone=UTC")
        if (token.isNotEmpty()) put("Authorization", "Bearer $token")
    }

    private fun call(params: String): JSONObject {
        val t = Http.get("$api?$params&JsHttpRequest=1-xml", headers())
        return JSONObject(t.substring(t.indexOf('{').coerceAtLeast(0)))
    }

    private fun js(params: String): Any? = call(params).opt("js")

    private fun handshake() {
        if (token.isNotEmpty()) return
        val r = call("type=stb&action=handshake&token=")
        token = (r.opt("js") as? JSONObject)?.optString("token").orEmpty()
        if (token.isEmpty()) throw IllegalStateException("The portal did not answer the handshake. Check the URL and MAC address.")
        val sn = mac.replace(":", "").takeLast(13)
        val prof = js("type=stb&action=get_profile&hd=1&ver=ImageDescription%3A%200.2.18-r23-250%3B&num_banks=2&sn=$sn&stb_type=MAG250&client_type=STB&image_version=218&video_out=hdmi&device_id=&device_id2=&signature=&auth_second_step=1&hw_version=1.7-BD-00&not_valid_token=0&metrics=&hw_version_2=&timestamp=${System.currentTimeMillis() / 1000}&api_signature=262&prehash=")
        if (prof is JSONObject && prof.optInt("blocked") == 1) throw IllegalStateException("This MAC address is blocked by the portal")
    }

    override fun load(progress: (String) -> Unit): Library {
        progress("Connecting to the portal")
        handshake()
        progress("Loading live channels")
        val liveCats = (js("type=itv&action=get_genres") as? JSONArray)?.objects()
            ?.filter { it.optString("id") != "*" }?.map { Category(it.optString("id"), it.optString("title")) } ?: emptyList()
        val all = js("type=itv&action=get_all_channels") as? JSONObject
        val channels = all?.optJSONArray("data")?.objects()?.mapIndexed { i, o ->
            Channel(
                id = o.optString("id"), name = o.optString("name").trim(), logo = o.str("logo")?.let { abs(it) },
                categoryId = o.optString("tv_genre_id"), epgId = o.str("xmltv_id"), url = null,
                number = o.optString("number").toIntOrNull() ?: (i + 1), catchup = o.optInt("tv_archive", 0) == 1, cmd = o.optString("cmd"),
            )
        } ?: emptyList()
        progress("Loading movies and series")
        val vodCats = (js("type=vod&action=get_categories") as? JSONArray)?.objects()
            ?.filter { it.optString("id") != "*" }?.map { Category(it.optString("id"), it.optString("title")) } ?: emptyList()
        val movies = ArrayList<Movie>()
        val series = ArrayList<Series>()
        // VOD is paged (14 a page by default); the newest six pages of each category keep loading quick.
        for (c in vodCats) {
            var page = 1
            while (page <= 6) {
                val d = js("type=vod&action=get_ordered_list&category=${c.id}&p=$page&sortby=added") as? JSONObject ?: break
                val items = d.optJSONArray("data")?.objects() ?: break
                for (o in items) {
                    val name = o.optString("name").trim()
                    val poster = o.str("screenshot_uri")?.let { abs(it) }
                    if (o.optInt("is_series", 0) == 1 || o.optJSONArray("series")?.length() ?: 0 > 0) {
                        series.add(Series("st:${o.optString("id")}", name, poster, c.id, o.str("rating_imdb"), o.str("description"), o.str("year"),
                            episodes = (o.optJSONArray("series")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()).map { n ->
                                Episode("${o.optString("id")}:$n", 1, n, "Episode $n", null, poster, 0, null, cmd = o.optString("cmd"))
                            }))
                    } else {
                        movies.add(Movie(o.optString("id"), name, poster, c.id, o.str("rating_imdb"), o.str("year"), null, cmd = o.optString("cmd")))
                    }
                }
                val total = d.optInt("total_items"); val per = d.optInt("max_page_items", 14).coerceAtLeast(1)
                if (page * per >= total || items.isEmpty()) break
                page++
            }
        }
        return Library(liveCats, channels, vodCats, movies, vodCats, series, s.epgUrl.ifBlank { null })
    }

    private fun abs(path: String) = if (path.startsWith("http")) path else api.substringBefore("/portal.php").substringBefore("/server/") + "/" + path.trimStart('/')

    /** create_link returns "ffmpeg http://..." (or the URL alone). */
    private fun link(type: String, cmd: String, extra: String = ""): String {
        handshake()
        val c = URLEncoder.encode(cmd, "UTF-8")
        val r = js("type=$type&action=create_link&cmd=$c$extra&forced_storage=0&disable_ad=0&download=0") as? JSONObject
        val out = r?.optString("cmd").orEmpty().ifEmpty { cmd }
        return out.substringAfter("ffmpeg ").substringAfter("auto ").trim()
    }

    override fun liveUrl(ch: Channel): String {
        val cmd = ch.cmd.orEmpty()
        // Some portals hand out direct URLs in cmd; the rest need create_link.
        val direct = cmd.substringAfter("ffmpeg ").trim()
        return if (direct.startsWith("http") && !direct.contains("localhost")) direct else link("itv", cmd)
    }

    override fun movieUrl(m: Movie) = link("vod", m.cmd.orEmpty())
    override fun episodeUrl(s: Series, e: Episode) = link("vod", e.cmd.orEmpty(), "&series=${e.number}")
    override fun movieDetails(m: Movie) = Details()
    override fun seriesDetails(s: Series) = Details(plot = s.plot, seasons = s.episodes.groupBy { it.season }.toSortedMap())

    override fun shortEpg(ch: Channel): List<Programme> = try {
        handshake()
        (js("type=itv&action=get_short_epg&ch_id=${ch.id}&size=4") as? JSONArray)?.objects()?.map {
            Programme(ch.epgId ?: ch.id, it.optLong("start_timestamp") * 1000, it.optLong("stop_timestamp") * 1000,
                it.optString("name"), it.str("descr"), null)
        } ?: emptyList()
    } catch (e: Exception) { emptyList() }
}
