package io.github.novatv.play.data

import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.sports.Side
import io.github.novatv.play.sports.SportEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import io.github.novatv.play.platform.locked

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
        clearCache(); found.clear()
        return addon
    }

    fun remove(a: Addon) { save(list().filterNot { it.manifest == a.manifest }); clearCache(); found.clear() }

    private fun save(l: List<Addon>) { App().addons = l.map { toJson(it).toString() } }

    private fun toJson(a: Addon) = JSONObject().put("m", a.manifest).put("n", a.name).put("l", a.logo ?: "")
        .put("c", JSONArray().also { arr -> a.catalogs.forEach { (id, n) -> arr.put(JSONObject().put("i", id).put("n", n)) } })

    private fun fromJson(o: JSONObject) = Addon(o.optString("m"), o.optString("n"), o.optString("l").ifBlank { null },
        o.optJSONArray("c")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("i") to it.optString("n") } }.orEmpty())

    private fun App() = io.github.novatv.play.App.store

    // ---------- matches ----------

    /** Each catalog's matches as last read ("manifest|catalog" -> (when, matches)): kept on the device, like the guide. */
    private val cache = io.github.novatv.play.platform.SafeMap<String, Pair<Long, List<Event>>>()
    /** Each match's streams as last read ("manifest|event id" -> (when, streams)): kept on the device too. */
    private val streamCache = io.github.novatv.play.platform.SafeMap<String, Pair<Long, List<Stream>>>()
    private fun clearCache() { cache.clear(); streamCache.clear(); runCatching { save() } }

    /** How long stored lists are used: the background update ([refresh]) renews them long before. */
    private const val KEEP_MS = 6 * 3_600_000L
    /** How often [refresh] reads the addons again while the app is open. */
    const val REFRESH_MS = 15 * 60_000L

    private fun catalogsOf(a: Addon) =
        a.catalogs.filter { (id, n) -> "event" in id.lowercase() || "event" in n.lowercase() }.ifEmpty { a.catalogs }

    /**
     * Every match in the addons' live TV catalogs, from the stored lists ([refresh] keeps them current); a catalog
     * never read, or not for hours, is read now. Catalogs named for events ("Live Events") are read; an addon with none
     * has all its live TV catalogs read. One that fails is left out.
     */
    fun events(addons: List<Addon> = list()): List<Event> {
        load()
        return addons.flatMap { a ->
            catalogsOf(a).flatMap { (id, name) ->
                val key = "${a.manifest}|$id"
                cache[key]?.takeIf { nowMs() - it.first < KEEP_MS }?.second ?: runCatching { catalog(a, id, name) }.getOrElse { emptyList() }
                    .also { cache[key] = nowMs() to it }
            }
        }
    }

    /** [ev]'s streams: stored ones when read in the last hour, else read now (and stored). */
    private fun storedStreams(ev: Event): List<Stream> {
        val key = "${ev.addon.manifest}|${ev.id}"
        streamCache[key]?.takeIf { nowMs() - it.first < 3_600_000L }?.let { return it.second }
        return streams(ev).also { streamCache[key] = nowMs() to it }
    }

    /**
     * The background update, like the guide's: every addon's matches and their streams read again and stored on the
     * device, so a match's page has them the moment it opens. Each catalog that fails keeps what it had.
     */
    fun refresh() {
        val addons = list()
        if (addons.isEmpty()) return
        load()
        val t0 = nowMs()
        val evs = addons.flatMap { a ->
            catalogsOf(a).flatMap { (id, name) ->
                val key = "${a.manifest}|$id"
                runCatching { catalog(a, id, name) }.onSuccess { cache[key] = nowMs() to it }.getOrNull() ?: cache[key]?.second.orEmpty()
            }
        }
        // Only matches with both sides named can be matched to a game; their streams read a few at a time.
        val named = evs.filter { it.home != null && it.away != null }
        kotlinx.coroutines.runBlocking(io.github.novatv.play.platform.IO) {
            named.chunked(8).forEach { batch ->
                batch.map { ev -> async { runCatching { streams(ev) }.onSuccess { streamCache["${ev.addon.manifest}|${ev.id}"] = nowMs() to it } } }
                    .forEach { it.await() }
            }
        }
        // Old matches go: what no catalog lists any more.
        val keep = evs.map { "${it.addon.manifest}|${it.id}" }.toSet()
        streamCache.keys().filter { it !in keep }.forEach { streamCache.remove(it) }
        runCatching { save() }
        io.github.novatv.play.platform.logW("addons: refreshed ${evs.size} matches, ${named.size} with streams, in ${nowMs() - t0} ms")
    }

    // ---------- kept on the device ----------

    private val diskLock = io.github.novatv.play.platform.Lock()
    private var loaded = false
    private fun file() = io.github.novatv.play.platform.cacheDir() / "addon-events.json"

    private fun load() {
        diskLock.locked {
            if (loaded) return
            loaded = true
            runCatching {
                val fs = okio.FileSystem.SYSTEM
                if (!fs.exists(file())) return
                val o = JSONObject(fs.read(file()) { readUtf8() })
                val byManifest = list().associateBy { it.manifest }
                o.optJSONArray("c")?.let { arr -> for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val a = byManifest[c.optString("m")] ?: continue
                    val evs = c.optJSONArray("e")?.let { ea -> (0 until ea.length()).mapNotNull { ea.optJSONObject(it) } }.orEmpty().map { e ->
                        Event(a, e.optString("c"), e.optString("i"), e.optString("t"), e.optString("h").ifBlank { null }, e.optString("a").ifBlank { null })
                    }
                    if (cache[c.optString("k")] == null) cache[c.optString("k")] = c.optLong("w") to evs
                } }
                o.optJSONArray("s")?.let { arr -> for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    if (byManifest[s.optString("k").substringBefore('|')] == null) continue
                    val list = s.optJSONArray("l")?.let { la -> (0 until la.length()).mapNotNull { la.optJSONObject(it) } }.orEmpty().map { x ->
                        val h = LinkedHashMap<String, String>()
                        x.optJSONObject("h")?.let { ho -> for (k in ho.keys()) h[k] = ho.optString(k) }
                        Stream(x.optString("b"), x.optString("d"), x.optString("u"), h)
                    }
                    if (streamCache[s.optString("k")] == null) streamCache[s.optString("k")] = s.optLong("w") to list
                } }
            }
        }
    }

    private fun save() {
        val c = JSONArray()
        cache.keys().forEach { k -> val (w, evs) = cache[k] ?: return@forEach
            c.put(JSONObject().put("k", k).put("m", k.substringBefore('|')).put("w", w).put("e", JSONArray().also { arr ->
                evs.forEach { e -> arr.put(JSONObject().put("c", e.catalog).put("i", e.id).put("t", e.title).put("h", e.home ?: "").put("a", e.away ?: "")) }
            }))
        }
        val s = JSONArray()
        streamCache.keys().forEach { k -> val (w, l) = streamCache[k] ?: return@forEach
            s.put(JSONObject().put("k", k).put("w", w).put("l", JSONArray().also { arr ->
                l.forEach { x -> arr.put(JSONObject().put("b", x.label).put("d", x.detail).put("u", x.url)
                    .put("h", JSONObject().also { h -> x.headers.forEach { (hk, hv) -> h.put(hk, hv) } })) }
            }))
        }
        val text = JSONObject().put("c", c).put("s", s).toString()
        diskLock.locked { okio.FileSystem.SYSTEM.write(file()) { writeUtf8(text) } }
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
        found[e.id]?.takeIf { nowMs() - it.first < FRESH_MS }?.let { return it.second.take(limit) }
        return verify(e, limit).also { found[e.id] = nowMs() to it }
    }

    /** [airings] already found for a match (checked within the last few minutes), or null: shown at once. */
    fun known(e: SportEvent): List<io.github.novatv.play.sports.Airing>? =
        if (list().isEmpty()) emptyList() else found[e.id]?.takeIf { nowMs() - it.first < FRESH_MS }?.second

    /**
     * Finds and checks the addon streams of [events] (the live matches) ahead of time, a few at once, so a match's
     * page lists them the moment it opens. Matches no addon lists cost nothing past the catalogs' one read.
     */
    fun prefetch(events: List<SportEvent>) {
        if (list().isEmpty()) return
        val todo = events.filter { found[it.id]?.let { f -> nowMs() - f.first >= FRESH_MS } ?: true }
        if (todo.isEmpty()) return
        val t0 = nowMs()
        val matching = runCatching { todo.filter { forMatch(it).isNotEmpty() } }.getOrElse { emptyList() }
        val t1 = nowMs()
        // All at once: each is a few requests and checks of a few seconds, mostly waiting on the network.
        kotlinx.coroutines.runBlocking(io.github.novatv.play.platform.IO) {
            matching.map { e -> async { runCatching { airings(e) } } }.forEach { it.await() }
        }
        io.github.novatv.play.platform.logW("addons: ${matching.size} of ${todo.size} live matches listed; catalogs ${t1 - t0} ms, streams checked ${nowMs() - t1} ms")
    }

    /** Addon streams found per match: (when checked, the streams that played). */
    private val found = io.github.novatv.play.platform.SafeMap<String, Pair<Long, List<io.github.novatv.play.sports.Airing>>>()
    private const val FRESH_MS = 4 * 60_000L

    /**
     * [e]'s addon streams as they're checked: each one that plays is added the moment its check passes (in the
     * sources' order), so the page lists the first within a second or so instead of after the slowest check.
     */
    fun airingsFlow(e: SportEvent, limit: Int = 20): kotlinx.coroutines.flow.Flow<List<io.github.novatv.play.sports.Airing>> =
        kotlinx.coroutines.flow.channelFlow {
            if (list().isEmpty()) { send(emptyList()); return@channelFlow }
            found[e.id]?.takeIf { nowMs() - it.first < FRESH_MS }?.let { send(it.second.take(limit)); return@channelFlow }
            val turns = candidates(e)
            val ok = arrayOfNulls<io.github.novatv.play.sports.Airing>(turns.size)
            val lock = kotlinx.coroutines.sync.Mutex()
            kotlinx.coroutines.coroutineScope {
                turns.forEachIndexed { i, a -> launch {
                    if (Http.opens(a.channel.url!!, StreamHeaders.forUrl(a.channel.url!!).orEmpty(), timeoutMs = 4_000L)) {
                        lock.lock(); try { ok[i] = a; send(ok.filterNotNull().take(limit)) } finally { lock.unlock() }
                    }
                } }
            }
            val all = ok.filterNotNull().take(limit)
            found[e.id] = nowMs() to all
            send(all)
            // Streams listed but none opened on this device: reported (once an hour at most), to find why one device
            // lists none where another does.
            if (turns.isNotEmpty() && all.isEmpty())
                Diag.report("addons", "No addon stream opened for ${e.title}: $lastLookup, none of ${turns.size} opened (${io.github.novatv.play.App.device})")
            else if (turns.isEmpty() && lastLookup.startsWith("matched 0").not())
                Diag.report("addons", "Addon events matched ${e.title} but listed no streams: $lastLookup (${io.github.novatv.play.App.device})")
        }

    /** What the last lookup found, for problem reports. */
    private var lastLookup = ""

    /** [e]'s streams from every source, unchecked, taken in turns (each source's first, then each one's second...). */
    private fun candidates(e: SportEvent): List<io.github.novatv.play.sports.Airing> {
        var n = 0
        val matched = forMatch(e)
        // Each source's streams read at once (stored ones are instant; a phone that hasn't stored them yet waits once).
        val read = kotlinx.coroutines.runBlocking(io.github.novatv.play.platform.IO) {
            matched.map { ev -> async { ev to runCatching { storedStreams(ev) }.getOrElse { emptyList() } } }.map { it.await() }
        }
        val bySource = read.map { (ev, streams) ->
            streams.map { s -> io.github.novatv.play.sports.Airing(channel(ev, s, n++), null, "${ev.title} · ${ev.catalog}") }
        }
        lastLookup = "matched ${matched.size} addon events, ${bySource.sumOf { it.size }} streams"
        return (0 until (bySource.maxOfOrNull { it.size } ?: 0)).flatMap { i -> bySource.mapNotNull { it.getOrNull(i) } }
            .distinctBy { it.channel.url }
    }

    private fun verify(e: SportEvent, limit: Int): List<io.github.novatv.play.sports.Airing> {
        var n = 0
        // Each source's streams, then taken in turns (each source's first, then each one's second...), so one with
        // many streams doesn't hide the others.
        val bySource = forMatch(e).map { ev ->
            runCatching { storedStreams(ev) }.getOrElse { emptyList() }.map { s ->
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
                a.takeIf { Http.opens(a.channel.url!!, StreamHeaders.forUrl(a.channel.url!!).orEmpty(), timeoutMs = 4_000L) }
            } }.mapNotNull { it.await() }
        }
        return live.take(limit)
    }

    /** What [check] found for an addon: its matches now, and of the streams tried how many play, are DRM-locked or down. */
    data class Health(val events: Int, val tried: Int, val plays: Int, val drm: Int, val down: Int, val error: String? = null) {
        val summary: String get() = when {
            error != null -> error
            events == 0 -> "No live events listed right now"
            tried == 0 -> "$events live events, no streams listed"
            else -> "$events live events · $plays of $tried streams play" +
                (if (drm > 0) " · $drm DRM-locked" else "") + (if (down > 0) " · $down down" else "")
        }
    }

    /**
     * Tries [a]'s streams now: its live events read afresh, and up to [sample] of their streams checked at once
     * (spread over the events), each as the match page checks them.
     */
    fun check(a: Addon, sample: Int = 30): Health {
        cache.keys().filter { it.startsWith("${a.manifest}|") }.forEach { cache.remove(it) }
        val evs = try { events(listOf(a)) } catch (e: Exception) { return Health(0, 0, 0, 0, 0, "Could not read the addon") }
        if (evs.isEmpty()) return Health(0, 0, 0, 0, 0)
        val streamsBy = kotlinx.coroutines.runBlocking(io.github.novatv.play.platform.IO) {
            evs.take(15).map { ev -> async { runCatching { streams(ev) }.getOrElse { emptyList() } } }.map { it.await() }
        }
        val picked = (0 until (streamsBy.maxOfOrNull { it.size } ?: 0)).flatMap { i -> streamsBy.mapNotNull { it.getOrNull(i) } }
            .distinctBy { it.url }.take(sample)
        val results = kotlinx.coroutines.runBlocking(io.github.novatv.play.platform.IO) {
            picked.map { s -> async { Http.probe(s.url, s.headers) } }.map { it.await() }
        }
        return Health(evs.size, picked.size, results.count { it == Http.Probe.PLAYS }, results.count { it == Http.Probe.DRM },
            results.count { it == Http.Probe.DOWN })
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
