package io.github.novatv.play.data

import io.github.novatv.play.App
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.SafeMap
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.platform.urlEncode

/** What TMDB knows about a film or series: artwork, score, cast, trailers and reviews. */
data class TmdbInfo(
    val backdrop: String?,
    val poster: String?,
    /** TMDB's audience score, 0 to 10. */
    val score: Double?,
    val votes: Int,
    val overview: String?,
    val genres: List<String>,
    val released: String?,
    val runtimeMin: Int?,
    val cast: List<Person>,
    val trailers: List<Trailer>,
    val reviews: List<Review>,
) {
    data class Person(val name: String, val role: String, val photo: String?)
    data class Trailer(val name: String, val kind: String, val youtubeKey: String) {
        val thumb get() = "https://img.youtube.com/vi/$youtubeKey/hqdefault.jpg"
        val url get() = "https://www.youtube.com/watch?v=$youtubeKey"
    }
    data class Review(val author: String, val rating: Double?, val text: String, val date: String, val url: String?)
}

/**
 * TMDB lookups with the user's own key (Settings): playlist titles are cleaned of their tags ("AR-IN-S - ", "4K",
 * "(2026)"), searched, and the best match's details fetched once per title.
 */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/"
    private val cache = SafeMap<String, Pair<Long, TmdbInfo?>>()

    val enabled: Boolean get() = App.store.tmdbKey.isNotBlank()

    /** Checks the key: null when TMDB accepts it, else what went wrong. */
    fun test(): String? = try { get("/configuration"); cache.clear(); null } catch (e: Exception) { "Not accepted (${e.message})" }

    /** Blocking; call off the main thread. Null when there is no key or no match. */
    fun lookup(name: String, year: String?, series: Boolean): TmdbInfo? {
        if (!enabled) return null
        val title = clean(name)
        val y = year ?: Regex("""(19|20)\d{2}""").find(name)?.value
        val k = "${if (series) "tv" else "movie"}|$title|$y"
        cache[k]?.let { if (nowMs() - it.first < 12 * 3600_000L) return it.second }
        val info = try { find(title, y, series) } catch (e: Exception) { null }
        cache[k] = nowMs() to info
        return info
    }

    private fun get(path: String): JSONObject {
        val key = App.store.tmdbKey.trim()
        // A long key is a v4 read token (sent as a header); a short one is a v3 API key (sent in the address).
        return if (key.length > 40) JSONObject(Http.get("$API$path", mapOf("Authorization" to "Bearer $key")))
        else JSONObject(Http.get("$API$path${if ('?' in path) "&" else "?"}api_key=${urlEncode(key)}"))
    }

    private fun find(title: String, year: String?, series: Boolean): TmdbInfo? {
        val kind = if (series) "tv" else "movie"
        val yearParam = if (year == null) "" else if (series) "&first_air_date_year=$year" else "&year=$year"
        var results = get("/search/$kind?query=${urlEncode(title)}$yearParam").optJSONArray("results")
        if ((results == null || results.length() == 0) && year != null) results = get("/search/$kind?query=${urlEncode(title)}").optJSONArray("results")
        val hit = results?.optJSONObject(0) ?: return null
        val id = hit.optLong("id")
        val d = get("/$kind/$id?append_to_response=credits,videos,reviews")
        val cast = d.optJSONObject("credits")?.optJSONArray("cast")?.objects()?.take(15)?.map {
            TmdbInfo.Person(it.optString("name"), it.optString("character"), it.str("profile_path")?.let { p -> "${IMG}w185$p" })
        }.orEmpty()
        val trailers = d.optJSONObject("videos")?.optJSONArray("results")?.objects()
            ?.filter { it.optString("site") == "YouTube" && it.optString("type") in setOf("Trailer", "Teaser") }
            ?.sortedBy { if (it.optString("type") == "Trailer") 0 else 1 }?.take(6)
            ?.map { TmdbInfo.Trailer(it.optString("name"), it.optString("type"), it.optString("key")) }.orEmpty()
        val reviews = d.optJSONObject("reviews")?.optJSONArray("results")?.objects()?.take(6)?.map {
            TmdbInfo.Review(it.optString("author"), it.optJSONObject("author_details")?.optDouble("rating")?.takeIf { r -> !r.isNaN() },
                it.optString("content").trim(), it.optString("created_at").take(10), it.str("url"))
        }.orEmpty()
        return TmdbInfo(
            backdrop = d.str("backdrop_path")?.let { "${IMG}w1280$it" },
            poster = d.str("poster_path")?.let { "${IMG}w500$it" },
            score = d.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 },
            votes = d.optInt("vote_count"),
            overview = d.str("overview"),
            genres = d.optJSONArray("genres")?.objects()?.map { it.optString("name") }.orEmpty(),
            released = d.str("release_date") ?: d.str("first_air_date"),
            runtimeMin = d.optInt("runtime").takeIf { it > 0 } ?: d.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 },
            cast = cast, trailers = trailers, reviews = reviews,
        )
    }

    private val tag = Regex("""^[A-Z]{2,4}(-[A-Z]{1,4})*\s*[-:|]\s*""")
    private val noise = Regex("""\b(4K|8K|UHD|FHD|HD|SD|HEVC|H265|1080P|720P|2160P|MULTI|SUB|DUB|VOSTFR)\b""", RegexOption.IGNORE_CASE)

    /** "AR-IN-S - Maa Inti Bangaram (2026)" -> "Maa Inti Bangaram". */
    fun clean(name: String): String {
        var t = name.trim()
        repeat(2) { t = t.replace(tag, "") }
        t = t.replace(Regex("""[\(\[](19|20)\d{2}[\)\]]"""), "").replace(noise, "")
        t = t.replace(Regex("""\s*S\d{1,2}\s*$"""), "")
        return t.replace(Regex("""\s+"""), " ").trim(' ', '-', '|', ':')
    }
}
