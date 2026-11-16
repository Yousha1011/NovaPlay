package io.github.novatv.play.data

import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.sports.Side
import io.github.novatv.play.sports.SportEvent
import kotlinx.coroutines.async

/**
 * Stremio addons (the format Stremio and Nuvio use): added by their manifest address. NovaPlay reads their live TV
 * catalogs for matches ("Afghanistan vs Zimbabwe") and lists those that are the match open in Sports under "Watch on
 * your channels", with the addon's streams. Nothing is built in: the user adds the addons they choose.
 */
object Addons {
    /** An addon as added: its manifest address, its name and its live TV catalogs (id, name). */
    data class Addon(val manifest: String, val name: String, val logo: String?, val catalogs: List<Pair<String, String>>) {
        /** The address the addon's resources hang off: the manifest's, without "/manifest.json". */
        val base: String get() = manifest.substringBefore("?").removeSuffix("/manifest.json").trimEnd('/')
    }

    /** A match in an addon's catalog. */
    data class Event(val addon: Addon, val catalog: String, val id: String, val title: String, val home: String?, val away: String?)

    /** One way to watch an [Event]: a label for the list, the stream's address and the headers it needs. */
    data class Stream(val label: String, val detail: String, val url: String, val headers: Map<String, String>)

    /** The category given to channels made from addon streams: their address is their own, not the playlist's. */
    const val CATEGORY = "addon"

    // ---------- the user's addons ----------

    fun list(): List<Addon> = App().addons.mapNotNull { s -> runCatching { fromJson(JSONObject(s)) }.getOrNull() }

    /** Reads the manifest at [url] and adds the addon (replacing one with the same manifest). */
    fun add(url: String): Addon {
        val manifest = url.trim().replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
            .let { if (it.endsWith("manifest.json") || it.contains("/manifest.json?")) it else it.trimEnd('/') + "/manifest.json" }
        val o = JSONObject(Http.get(manifest))
        val tv = o.optJSONArray("catalogs")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .filter { it.optString("type") == "tv" }
            .map { it.optString("id") to it.optString("name").ifBlank { it.optString("id") } }
        val addon = Addon(manifest, o.optString("name").ifBlank { manifest.substringAfter("://").substringBefore('/') }, o.optString("logo").ifBlank { null }, tv)
        save(list().filterNot { it.manifest == manifest } + addon)
        clearCache()
        return addon
    }

    fun remove(a: Addon) { save(list().filterNot { it.manifest == a.manifest }); clearCache() }

    private fun save(l: List<Addon>) { App().addons = l.map { toJson(it).toString() } }

    private fun toJson(a: Addon) = JSONObject().put("m", a.manifest).put("n", a.name).put("l", a.logo ?: "")
        .put("c", JSONArray().also { arr -> a.catalogs.forEach { (id, n) -> arr.put(JSONObject().put("i", id).put("n", n)) } })

    private fun fromJson(o: JSONObject) = Addon(o.optString("m"), o.optString("n"), o.optString("l").ifBlank { null },
        o.optJSONArray("c")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("i") to it.optString("n") } }.orEmpty())

    private fun App() = io.github.novatv.play.App.store

    // ---------- matches ----------

    private val cache = io.github.novatv.play.platform.SafeMap<String, Pair<Long, List<Event>>>()
    private fun clearCache() = cache.clear()

    /**
     * Every match in the addons' live TV catalogs, read again after 5 minutes. Catalogs named for events ("Live
     * Events") are read; an addon with none has all its live TV catalogs read. One that fails is left out.
     */
    fun events(): List<Event> = list().flatMap { a ->
        val events = a.catalogs.filter { (id, n) -> "event" in id.lowercase() || "event" in n.lowercase() }
        (events.ifEmpty { a.catalogs }).flatMap { (id, name) ->
            val key = "${a.manifest}|$id"
            cache[key]?.takeIf { nowMs() - it.first < 5 * 60_000L }?.second ?: runCatching { catalog(a, id, name) }.getOrElse { emptyList() }
                .also { cache[key] = nowMs() to it }
        }
    }

    private fun catalog(a: Addon, id: String, name: String): List<Event> {
        val metas = JSONObject(Http.get("${a.base}/catalog/tv/${encode(id)}.json")).optJSONArray("metas") ?: return emptyList()
        return (0 until metas.length()).mapNotNull { metas.optJSONObject(it) }.mapNotNull { m ->
            val title = clean(m.optString("name"))
            val sides = sides(title)
            val vid = m.optJSONObject("behaviorHints")?.optString("defaultVideoId")?.ifBlank { null } ?: m.optString("id")
            // "⚡SportzX Live Events (tv)" -> "SportzX Live Events".
            if (vid.isBlank()) null else Event(a, clean(name).removeSuffix("(tv)").trim(), vid, title, sides?.first, sides?.second)
        }
    }

    /** The addon matches that are [e]: both sides named (in full, short or as their code), in either order. */
    fun forMatch(e: SportEvent): List<Event> {
        val home = e.home ?: return emptyList(); val away = e.away ?: return emptyList()
        return events().filter { ev ->
            val h = ev.home ?: return@filter false; val a = ev.away ?: return@filter false
            (same(h, home) && same(a, away)) || (same(h, away) && same(a, home))
        }
    }

    /** The streams an addon offers for [ev]: its own address and headers each; donation and info rows left out. */
    fun streams(ev: Event): List<Stream> {
        val arr = JSONObject(Http.get("${ev.addon.base}/stream/tv/${encode(ev.id)}.json")).optJSONArray("streams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { s ->
            val url = s.optString("url").ifBlank { return@mapNotNull null }
            val headers = LinkedHashMap<String, String>()
            s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h -> for (k in h.keys()) headers[k] = h.optString(k) }
            val lines = s.optString("title").lines().map { clean(it) }.filter { it.isNotBlank() }
            // CNCVerse's "🏷️ TAPMAD - AQ" line is the source's name; else the stream's own name.
            val label = s.optString("title").lines().firstOrNull { it.contains("🏷") }?.let { clean(it) }
                ?: clean(s.optString("name")).ifBlank { lines.firstOrNull() ?: ev.title }
            Stream(label, lines.filterNot { it == label }.take(2).joinToString(" · "), url, headers)
        }
    }

    /**
     * [e]'s streams from the user's addons, as rows for "Watch on your channels" (after the user's own channels): each
     * matching event's streams, its catalog named ("PlayZTV Live Events · CNCVerse Bridge"). Empty with no addons.
     */
    fun airings(e: SportEvent, limit: Int = 20): List<io.github.novatv.play.sports.Airing> {
        if (list().isEmpty()) return emptyList()
        var n = 0
        // Each source's streams, then taken in turns (each source's first, then each one's second...), so one with
        // many streams doesn't hide the others.
        val bySource = forMatch(e).map { ev ->
            runCatching { streams(ev) }.getOrElse { emptyList() }.map { s ->
                // The match as the addon names it first ("AFG vs ZIM"), then where it's from.
                io.github.novatv.play.sports.Airing(channel(ev, s, n++), null, "${ev.title} · ${ev.catalog}")
            }
        }
        val turns = (0 until (bySource.maxOfOrNull { it.size } ?: 0)).flatMap { i -> bySource.mapNotNull { it.getOrNull(i) } }
            .distinctBy { it.channel.url }
        // Only streams that open now: addons list many that are down (their server's 502, or refused where you are).
        // All checked at once, a few seconds each at most.
        val live = kotlinx.coroutines.runBlocking(io.github.novatv.play.platform.IO) {
            turns.map { a -> async {
                a.takeIf { Http.opens(a.channel.url!!, StreamHeaders.forUrl(a.channel.url!!).orEmpty()) }
            } }.mapNotNull { it.await() }
        }
        return live.take(limit)
    }

    /** A stream as a channel the players play: its address is its own; its headers are sent with every request. */
    fun channel(ev: Event, s: Stream, i: Int): Channel {
        if (s.headers.isNotEmpty()) StreamHeaders.put(s.url, s.headers)
        return Channel(id = "addon:${s.url}", name = "${s.label} · ${ev.addon.name}", logo = ev.addon.logo, categoryId = CATEGORY,
            epgId = null, url = s.url, number = i + 1)
    }

    // ---------- names ----------

    /** "🔜 AFG vs ZIM" -> "AFG vs ZIM": emoji and symbols round a name left out. */
    internal fun clean(s: String): String = s.replace(Regex("""[^\p{L}\p{N}\s.,:;'&()/+\-|]"""), " ").replace(Regex("""\s+"""), " ").trim()

    /** "A vs B" (also "A v B", "A - B" in a match title) -> (A, B). */
    internal fun sides(title: String): Pair<String, String>? {
        val parts = title.split(Regex("""\s+(?:vs\.?|v\.?|x)\s+""", RegexOption.IGNORE_CASE))
        if (parts.size != 2) return null
        val a = parts[0].substringAfterLast(':').trim(); val b = parts[1].substringBefore(" - ").substringBefore('|').substringBefore('(').trim()
        return if (a.length >= 2 && b.length >= 2) a to b else null
    }

    private fun fold(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N} ]"""), "").replace(Regex("""\s+"""), " ").trim()

    /** An addon's side name is [side]: its full or short name or code, or one containing the other ("Zimbabwe Women"). */
    internal fun same(name: String, side: Side): Boolean {
        val n = fold(name)
        if (n.isEmpty()) return false
        val forms = listOf(side.name, side.short, side.abbr).map(::fold).filter { it.length >= 2 }
        if (forms.any { it == n }) return true
        // Women's, A and youth sides must agree: "Pakistan Women" is not "Pakistan".
        val marks = listOf("women", "w", "u19", "u21", "u23", "a", "xi")
        fun marked(s: String) = s.split(' ').filter { it in marks }.toSet()
        return forms.filter { it.length >= 4 }.any { f -> (n.startsWith(f) || f.startsWith(n) && n.length >= 4) && marked(n) == marked(f) }
    }

    private fun encode(s: String) = s.replace("%", "%25").replace("/", "%2F").replace("?", "%3F").replace("#", "%23").replace(" ", "%20")
}

/**
 * Headers some streams need (a Referer, a User-Agent), by host: the players' HTTP layer adds them to every request to
 * that host, the stream's segments included.
 */
object StreamHeaders {
    private val byHost = io.github.novatv.play.platform.SafeMap<String, Map<String, String>>()
    fun put(url: String, headers: Map<String, String>) { host(url)?.let { byHost[it] = headers } }
    fun forUrl(url: String): Map<String, String>? = host(url)?.let { byHost[it] }
    private fun host(url: String): String? = url.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase().ifBlank { null }
}
