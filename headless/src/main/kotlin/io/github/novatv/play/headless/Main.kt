package io.github.novatv.play.headless

import io.github.novatv.play.App
import io.github.novatv.play.data.Addons
import io.github.novatv.play.data.ChannelVersions
import io.github.novatv.play.data.LibraryCache
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.data.SourceType
import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.Device
import io.github.novatv.play.platform.Host
import io.github.novatv.play.sports.Airing
import io.github.novatv.play.sports.Broadcasters
import io.github.novatv.play.sports.SportEvent
import io.github.novatv.play.sports.State
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path.Companion.toPath
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * NovaPlay without a screen, run by the NovaPlay server next to its web side (server/app.py). With the apps' own code:
 *  - your playlist is loaded (and refreshed), and written out for the apps to download ([LIB]);
 *  - the guide is the server's own, and the addon is the server's;
 *  - every game in the Sports picks the apps send ([FOLLOW]) gets its "Watch on your channels" list worked out, the
 *    way a match page does, and all of them are written out together ([MATCHES]), refreshed every 10 minutes.
 * Settings come from the environment: IPTV_HOST, IPTV_USER, IPTV_PASS, NOVAPLAY_BASE (this server's own address with
 * its key, ".../k/<key>"), NOVAPLAY_OUT (where files go).
 */
private val OUT = (System.getenv("NOVAPLAY_OUT") ?: "/tmp/novaplay").toPath()
private val LIB = OUT / "lib.bin"
private val MATCHES = OUT / "matches.json"
private val FOLLOW = OUT / "follow.json"
private val STATUS = OUT / "headless.json"
private val fs = FileSystem.SYSTEM
private val status = JSONObject()

private fun log(msg: String) { println("[novaplay] $msg"); System.out.flush() }

private fun note(k: String, v: Any?) {
    status.put(k, v).put("at", System.currentTimeMillis())
    runCatching { write(STATUS, status.toString()) }
}

private fun write(f: okio.Path, text: String) {
    val tmp = f.parent!! / (f.name + ".tmp")
    fs.write(tmp) { writeUtf8(text) }
    fs.atomicMove(tmp, f)
}

private object HeadlessHost : Host {
    override val device = Device.DESKTOP
    override fun play(item: PlayItem) {}
    override fun toast(msg: String) {}
}

private fun waitFor(ms: Long, what: String, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (runCatching(cond).getOrDefault(false)) return true; Thread.sleep(1000) }
    log("gave up waiting for $what"); return false
}

fun main() {
    fs.createDirectories(OUT)
    val base = System.getenv("NOVAPLAY_BASE")?.trimEnd('/') ?: error("NOVAPLAY_BASE is not set")
    App.start(HeadlessHost)
    val store = App.store
    // Your playlist, once (it is kept in this program's own settings afterwards).
    if (store.sources.isEmpty()) {
        App.repo.addSource("My TV", SourceType.XTREAM, System.getenv("IPTV_HOST") ?: error("IPTV_HOST is not set"),
            System.getenv("IPTV_USER").orEmpty(), System.getenv("IPTV_PASS").orEmpty(), "", "")
        log("playlist added")
    }
    // The server's own guide only, and the server's addon.
    store.extraEpg = listOf("$base/guide.xml.gz")
    store.universalGuideOnly = true
    Addons.list().filter { !it.manifest.startsWith(base) }.forEach { Addons.remove(it) }
    if (waitFor(30 * 60_000L, "the playlist") { App.repo.state.value.lib.channels.isNotEmpty() && !App.repo.state.value.loading }) saveLibrary()
    App.repo.reloadGuide()
    waitFor(30 * 60_000L, "the guide") { App.repo.state.value.epgStatus == null && App.repo.state.value.epgVersion > 0 }

    var lastLibrary = System.currentTimeMillis()
    var lastGuide = System.currentTimeMillis()
    var lastSlot: String? = null
    while (true) {
        runCatching {
            if (Addons.list().isEmpty()) runCatching { Addons.add("$base/manifest.json"); log("server addon added") }
            applyFollow()
            matchesRound()
        }.onFailure { log("round failed: ${it::class.simpleName}: ${it.message}"); it.printStackTrace() }
        // The playlist again at 5 am (New York time, like the guide), and the guide after each of the server's builds.
        val now = ZonedDateTime.now(ZoneId.of("America/New_York"))
        val slot = "${now.toLocalDate()}-${now.hour}"
        if (now.hour == 5 && slot != lastSlot) { lastSlot = slot; refreshLibrary() }
        if (System.currentTimeMillis() - lastGuide > 2 * 3_600_000L) { lastGuide = System.currentTimeMillis(); App.repo.reloadGuide() }
        if (System.currentTimeMillis() - lastLibrary > 26 * 3_600_000L) { lastLibrary = System.currentTimeMillis(); refreshLibrary() }
        Thread.sleep(10 * 60_000L)
    }
}

/** Loads the playlist afresh and writes it out. */
private fun refreshLibrary() {
    val src = App.repo.state.value.source ?: return
    App.repo.open(src, force = true)
    Thread.sleep(2000)
    if (waitFor(30 * 60_000L, "the playlist refresh") { !App.repo.state.value.loading }) saveLibrary()
}

/** The library as the apps keep it on disk (LibraryCache's format), for them to download instead of the panel's lists. */
private fun saveLibrary() {
    val lib = App.repo.state.value.lib
    LibraryCache.write(LIB, lib)
    note("library", JSONObject().put("channels", lib.channels.size).put("movies", lib.movies.size).put("series", lib.series.size)
        .put("bytes", fs.metadata(LIB).size ?: 0))
    log("library written: ${lib.channels.size} channels, ${lib.movies.size} movies, ${lib.series.size} series")
}

/** The Sports picks the apps sent (follow.json), applied when they changed. */
private var followSeen: String? = null
private fun applyFollow() {
    if (!fs.exists(FOLLOW)) return
    val text = fs.read(FOLLOW) { readUtf8() }
    if (text == followSeen) return
    followSeen = text
    val o = JSONObject(text)
    fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    val s = App.store
    set("sportsLeagues")?.let { s.sportsLeagues = it }
    set("sportsLeagueTeams")?.let { s.sportsLeagueTeams = it }
    set("favouriteTeams")?.let { s.favouriteTeams = it }
    set("sportsEnabled")?.let { s.sportsEnabled = it }
    set("sportsComps")?.let { s.sportsComps = it }
    o.optString("sportsCountry").takeIf { o.has("sportsCountry") }?.let { s.sportsCountry = it }
    o.optString("sportsDbKey").takeIf { o.has("sportsDbKey") }?.let { s.sportsDbKey = it }
    o.optString("sportMonksKey").takeIf { o.has("sportMonksKey") }?.let { s.sportMonksKey = it }
    log("sports picks applied: ${s.sportsLeagues.size} competitions")
}

/** Every coming and live game: its channels as a match page lists them, written out together. */
private fun matchesRound() {
    val repo = App.repo
    val before = repo.sports.value.updatedAt
    repo.refreshSports(0)
    waitFor(5 * 60_000L, "Sports") { repo.sports.value.updatedAt != before && !repo.sports.value.loading }
    val now = System.currentTimeMillis()
    val events = repo.sports.value.events.filter { it.state == State.LIVE || (it.state == State.PRE && it.start < now + 2 * 86_400_000L) }
    val channels = repo.state.value.lib.channels
    ChannelVersions.prepare(channels)
    val out = JSONObject()
    val t0 = System.currentTimeMillis()
    var rows = 0
    for (e in events) {
        val list = runCatching { channelsFor(e, channels) }.getOrNull() ?: continue
        val a = JSONArray()
        list.filter { it.channel.categoryId != Addons.CATEGORY }.forEach { x ->
            val r = JSONObject().put("id", x.channel.id).put("why", x.why)
            x.programme?.let { p -> r.put("title", p.title).put("start", p.start).put("stop", p.stop) }
            a.put(r); rows++
        }
        out.put(e.id, a)
    }
    write(MATCHES, JSONObject().put("at", System.currentTimeMillis()).put("guide", repo.state.value.epgVersion).put("games", out).toString())
    note("matches", JSONObject().put("games", events.size).put("rows", rows).put("secs", (System.currentTimeMillis() - t0) / 1000))
    log("matches: ${events.size} games, $rows channel rows in ${(System.currentTimeMillis() - t0) / 1000}s")
}

/** One game's channels, the way the match page finds them: guide and names, broadcasters, addon-named, all versions. */
private fun channelsFor(e: SportEvent, channels: List<io.github.novatv.play.data.Channel>): List<Airing> = runBlocking {
    val m = App.repo.matcher()
    val local = m.airings(e, limit = 40)
    val online = withTimeoutOrNull(15_000L) { runCatching { m.airings(e, Broadcasters.forEvent(e), limit = 40) }.getOrDefault(emptyList()) }.orEmpty()
    val found = (local + online).distinctBy { it.channel.id }
    var n = 0
    val fromAddons = Addons.forMatch(e).flatMap { ev ->
        runCatching { Addons.streams(ev) }.getOrDefault(emptyList()).map { s -> Airing(Addons.channel(ev, s, n++), null, ev.title) }
    }
    ChannelVersions.forMatch(found, fromAddons, channels)
}
