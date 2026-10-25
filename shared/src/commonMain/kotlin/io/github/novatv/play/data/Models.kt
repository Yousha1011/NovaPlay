package io.github.novatv.play.data

import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.nowMs

/** A playlist the user added. Only the fields its [type] needs are set. */
data class Source(
    val id: String,
    val name: String,
    val type: SourceType,
    val url: String,
    val username: String = "",
    val password: String = "",
    val mac: String = "",
    /** An XMLTV guide to use instead of (or, for M3U, besides) the one the source names. */
    val epgUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type.name).put("url", url)
        .put("username", username).put("password", password).put("mac", mac).put("epgUrl", epgUrl)

    companion object {
        fun fromJson(o: JSONObject) = Source(
            o.getString("id"), o.optString("name"), SourceType.valueOf(o.optString("type", "M3U")), o.optString("url"),
            o.optString("username"), o.optString("password"), o.optString("mac"), o.optString("epgUrl"),
        )
    }
}

enum class SourceType(val label: String) { XTREAM("Xtream Codes"), M3U("M3U playlist"), STALKER("Stalker portal") }

data class Category(val id: String, val name: String)

data class Channel(
    /** Unique within the source: the stream id (Xtream), the URL (M3U) or the channel id (Stalker). */
    val id: String,
    val name: String,
    val logo: String?,
    val categoryId: String,
    /** The XMLTV channel id the guide uses for this channel. */
    val epgId: String?,
    /** For M3U the stream URL; for Xtream and Stalker resolved when played. */
    val url: String?,
    val number: Int,
    val catchup: Boolean = false,
    /** Stalker's play command. */
    val cmd: String? = null,
)

data class Movie(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val rating: String?,
    val year: String?,
    val url: String?,
    val ext: String? = null,
    val added: Long = 0L,
    val cmd: String? = null,
)

data class Series(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val rating: String?,
    val plot: String?,
    val year: String?,
    val added: Long = 0L,
    /** M3U series are built from episode entries, so they carry them. */
    val episodes: List<Episode> = emptyList(),
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val durationSec: Int,
    val url: String?,
    val ext: String? = null,
    val cmd: String? = null,
)

/** More about a movie or a series, loaded when its page opens. */
data class Details(
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val released: String? = null,
    val duration: String? = null,
    val backdrop: String? = null,
    val rating: String? = null,
    val seasons: Map<Int, List<Episode>> = emptyMap(),
)

/** The map in key order (seasons 1, 2, 3...). */
fun <V> Map<Int, V>.sortedByKey(): Map<Int, V> = entries.sortedBy { it.key }.associate { it.key to it.value }

data class Programme(val channelKey: String, val start: Long, val stop: Long, val title: String, val desc: String?, val category: String?) {
    val isLive get() = nowMs() in start until stop
}

/** Everything a source holds, kept in memory once loaded. */
data class Library(
    val liveCategories: List<Category> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val movieCategories: List<Category> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val seriesCategories: List<Category> = emptyList(),
    val series: List<Series> = emptyList(),
    val epgUrl: String? = null,
)

/** What the player is asked to play. [channelIndex] lets live TV zap up and down through [channels]. */
sealed class PlayItem {
    abstract val title: String
    data class Live(val channels: List<Channel>, val channelIndex: Int) : PlayItem() {
        override val title get() = channels[channelIndex].name
    }
    data class Vod(val key: String, override val title: String, val movie: Movie? = null, val episode: Episode? = null,
                   val series: Series? = null, val nextEpisodes: List<Episode> = emptyList()) : PlayItem()
}
