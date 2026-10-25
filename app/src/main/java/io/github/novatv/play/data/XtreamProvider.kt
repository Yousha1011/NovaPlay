package io.github.novatv.play.data

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStreamReader
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Xtream Codes panels: player_api.php for the lists, plain URLs for streams, xmltv.php for the guide. */
class XtreamProvider(private val s: Source) : Provider {
    private val base = s.url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        .removeSuffix("/player_api.php").removeSuffix("/get.php")
    private val u = URLEncoder.encode(s.username.trim(), "UTF-8")
    private val p = URLEncoder.encode(s.password.trim(), "UTF-8")
    private val api = "$base/player_api.php?username=$u&password=$p"

    /**
     * Streams a list action straight from the network: each object is read field by field and only [fields] are kept.
     * Big panels return 50-100 MB of movies and series; reading that into one string ran a Fire TV out of memory.
     */
    private fun <T> list(action: String, fields: Set<String>, map: (Map<String, String>) -> T?): List<T> =
        Http.stream("$api&action=$action") { input ->
            val r = JsonReader(InputStreamReader(input, Charsets.UTF_8)).apply { isLenient = true }
            val out = ArrayList<T>()
            if (r.peek() != JsonToken.BEGIN_ARRAY) return@stream out
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                val m = HashMap<String, String>(fields.size * 2)
                r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    if (k in fields) value(r)?.let { m[k] = it } else r.skipValue()
                }
                r.endObject()
                map(m)?.let(out::add)
            }
            out
        }

    private fun value(r: JsonReader): String? = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        JsonToken.NULL -> { r.nextNull(); null }
        else -> { r.skipValue(); null }
    }

    /** Category ids repeat across tens of thousands of entries: share one string per id. */
    private val ids = HashMap<String, String>()
    private fun id(v: String?): String = v.orEmpty().let { ids.getOrPut(it) { it } }

    private fun cats(action: String) = try {
        list(action, setOf("category_id", "category_name")) { Category(id(it["category_id"]), it["category_name"].orEmpty()) }
    } catch (e: Exception) { emptyList() }

    override fun load(progress: (String) -> Unit): Library {
        progress("Signing in")
        val info = JSONObject(Http.get(api))
        val user = info.optJSONObject("user_info")
        if (user == null || user.optInt("auth", 0) == 0) throw IllegalStateException("The server did not accept this username and password")
        if (user.optString("status").let { it.isNotEmpty() && !it.equals("Active", true) }) throw IllegalStateException("This account is ${user.optString("status")}")

        progress("Loading live channels")
        val liveCats = cats("get_live_categories")
        var n = 0
        val channels = list("get_live_streams", setOf("stream_id", "name", "stream_icon", "category_id", "epg_channel_id", "num", "tv_archive")) { o ->
            n++
            Channel(
                id = o["stream_id"] ?: return@list null, name = o["name"].orEmpty().trim(), logo = o["stream_icon"]?.ifBlank { null },
                categoryId = id(o["category_id"]), epgId = o["epg_channel_id"]?.ifBlank { null }, url = null,
                number = o["num"]?.toDoubleOrNull()?.toInt() ?: n, catchup = o["tv_archive"] == "1",
            )
        }
        progress("Loading movies (${"%,d".format(channels.size)} channels)")
        val movieCats = try { cats("get_vod_categories") } catch (e: Exception) { emptyList() }
        val movies = try {
            list("get_vod_streams", setOf("stream_id", "name", "stream_icon", "category_id", "rating", "year", "container_extension", "added")) { o ->
                val name = o["name"].orEmpty().trim()
                Movie(
                    id = o["stream_id"] ?: return@list null, name = name, poster = o["stream_icon"]?.ifBlank { null },
                    categoryId = id(o["category_id"]), rating = o["rating"]?.ifBlank { null }?.takeIf { it != "0" && it != "0.0" },
                    year = o["year"]?.ifBlank { null } ?: Names.year(name), url = null,
                    ext = o["container_extension"]?.ifBlank { null }?.let { id(it) } ?: "mp4", added = o["added"]?.toLongOrNull() ?: 0L,
                )
            }
        } catch (e: Exception) { emptyList() }
        progress("Loading series (${"%,d".format(movies.size)} movies)")
        val seriesCats = try { cats("get_series_categories") } catch (e: Exception) { emptyList() }
        val series = try {
            // The plot is left out of the list (it can be long); the series page loads it.
            list("get_series", setOf("series_id", "name", "cover", "category_id", "rating", "releaseDate", "last_modified")) { o ->
                val name = o["name"].orEmpty().trim()
                Series(
                    id = o["series_id"] ?: return@list null, name = name, poster = o["cover"]?.ifBlank { null },
                    categoryId = id(o["category_id"]), rating = o["rating"]?.ifBlank { null }?.takeIf { it != "0" && it != "0.0" },
                    plot = null, year = o["releaseDate"]?.take(4)?.ifBlank { null } ?: Names.year(name),
                    added = o["last_modified"]?.toLongOrNull() ?: 0L,
                )
            }
        } catch (e: Exception) { emptyList() }
        return Library(liveCats, channels, movieCats, movies, seriesCats, series, s.epgUrl.ifBlank { "$base/xmltv.php?username=$u&password=$p" })
    }

    override fun liveUrl(ch: Channel) = "$base/live/$u/$p/${ch.id}.${if (io.github.novatv.play.App.store.xtreamHls) "m3u8" else "ts"}"
    override fun movieUrl(m: Movie) = "$base/movie/$u/$p/${m.id}.${m.ext ?: "mp4"}"
    override fun episodeUrl(s: Series, e: Episode) = "$base/series/$u/$p/${e.id}.${e.ext ?: "mp4"}"

    override fun movieDetails(m: Movie): Details = try {
        val o = JSONObject(Http.get("$api&action=get_vod_info&vod_id=${m.id}"))
        val i = o.optJSONObject("info") ?: JSONObject()
        Details(
            plot = i.str("plot") ?: i.str("description"), cast = i.str("cast") ?: i.str("actors"), director = i.str("director"),
            genre = i.str("genre"), released = i.str("releasedate") ?: i.str("release_date"), duration = i.str("duration"),
            backdrop = i.optJSONArray("backdrop_path")?.optString(0)?.ifBlank { null } ?: i.str("cover_big"), rating = i.str("rating"),
        )
    } catch (e: Exception) { Details() }

    override fun seriesDetails(s: Series): Details = try {
        val o = JSONObject(Http.get("$api&action=get_series_info&series_id=${s.id}"))
        val i = o.optJSONObject("info") ?: JSONObject()
        val seasons = sortedMapOf<Int, List<Episode>>()
        // "episodes" is an object keyed by season number (some panels send an array of arrays).
        when (val eps = o.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k -> seasons[k.toIntOrNull() ?: 0] = episodes(eps.optJSONArray(k), k.toIntOrNull() ?: 0) }
            is JSONArray -> for (n in 0 until eps.length()) eps.optJSONArray(n)?.let { a ->
                val season = a.optJSONObject(0)?.optInt("season", n + 1) ?: (n + 1)
                seasons[season] = episodes(a, season)
            }
        }
        Details(
            plot = i.str("plot") ?: s.plot, cast = i.str("cast"), director = i.str("director"), genre = i.str("genre"),
            released = i.str("releaseDate"), backdrop = i.optJSONArray("backdrop_path")?.optString(0)?.ifBlank { null },
            rating = i.str("rating"), seasons = seasons,
        )
    } catch (e: Exception) { Details() }

    private fun episodes(a: JSONArray?, season: Int): List<Episode> = a?.objects()?.map { e ->
        val info = e.optJSONObject("info")
        Episode(
            id = e.optString("id"), season = e.optInt("season", season), number = e.optInt("episode_num"),
            title = e.optString("title").ifBlank { "Episode ${e.optInt("episode_num")}" }, plot = info?.str("plot"),
            image = info?.str("movie_image"), durationSec = info?.optInt("duration_secs") ?: 0, url = null,
            ext = e.optString("container_extension").ifBlank { "mp4" },
        )
    }?.sortedBy { it.number } ?: emptyList()

    override fun shortEpg(ch: Channel): List<Programme> = try {
        val o = JSONObject(Http.get("$api&action=get_short_epg&stream_id=${ch.id}&limit=4"))
        o.optJSONArray("epg_listings")?.objects()?.map {
            Programme(
                ch.epgId ?: ch.id, it.optLong("start_timestamp") * 1000, it.optLong("stop_timestamp") * 1000,
                b64(it.optString("title")), b64(it.optString("description")).ifBlank { null }, null,
            )
        } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private fun b64(v: String): String = try { String(Base64.decode(v, Base64.DEFAULT)) } catch (e: Exception) { v }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(k: String): String? = optString(k).takeIf { it.isNotBlank() && it != "null" }
