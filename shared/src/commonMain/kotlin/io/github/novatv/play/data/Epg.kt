package io.github.novatv.play.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.novatv.play.json.XmlReader
import io.github.novatv.play.platform.Lock
import io.github.novatv.play.platform.Time
import io.github.novatv.play.platform.dataDir
import io.github.novatv.play.platform.grouped
import io.github.novatv.play.platform.locked
import io.github.novatv.play.platform.nowMs
import kotlin.concurrent.Volatile

/**
 * The TV guide: XMLTV imported into SQLite, kept from three hours ago to four days ahead. Channels are matched by
 * their guide id, or failing that by name ([Epg.key]).
 */
class Epg {
    /**
     * Two connections in write-ahead mode: screens keep reading the old guide on one while a new one imports on the
     * other (a big guide takes minutes); without that every read waited for the import to finish.
     */
    private val path = (dataDir() / "epg.db").toString()
    private val driver = BundledSQLiteDriver()
    private val writer: SQLiteConnection = driver.open(path).also { setUp(it) }
    private val reader: SQLiteConnection = driver.open(path).also { it.execSQL("PRAGMA query_only=1") }
    private val wLock = Lock()
    private val rLock = Lock()

    private fun setUp(db: SQLiteConnection) {
        db.execSQL("PRAGMA journal_mode=WAL")
        db.execSQL("PRAGMA synchronous=NORMAL")
        val version = db.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0).toInt() else 0 }
        if (version != VERSION) {
            listOf("prog", "chan", "meta").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            db.execSQL("CREATE TABLE prog (ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT, descr TEXT, cat TEXT)")
            db.execSQL("CREATE INDEX prog_ch ON prog (ch, start)")
            db.execSQL("CREATE INDEX prog_time ON prog (start, stop)")
            // One row per name: a guide channel has several display names, and each is a way to match it.
            db.execSQL("CREATE TABLE chan (id TEXT NOT NULL, norm TEXT NOT NULL)")
            db.execSQL("CREATE INDEX chan_norm ON chan (norm)")
            db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
            db.execSQL("PRAGMA user_version=$VERSION")
        }
    }

    private fun <T> read(block: (SQLiteConnection) -> T): T = rLock.locked { block(reader) }

    /** Runs [block] in one transaction on the writer. */
    private fun <T> write(block: (SQLiteConnection) -> T): T = wLock.locked {
        writer.execSQL("BEGIN IMMEDIATE")
        try { block(writer).also { writer.execSQL("COMMIT") } } catch (e: Throwable) { writer.execSQL("ROLLBACK"); throw e }
    }

    /** Guide ids by normalised channel name, for channels whose playlist gives no usable guide id. */
    @Volatile private var byName: Map<String, String> = emptyMap()
    @Volatile private var ids: Set<String> = emptySet()

    fun meta(k: String): String? = read { db -> db.prepare("SELECT v FROM meta WHERE k=?").use { it.bindText(1, k); if (it.step()) it.getText(0) else null } }

    private fun setMeta(db: SQLiteConnection, k: String, v: String) =
        db.prepare("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)").use { it.bindText(1, k); it.bindText(2, v); it.step() }

    /** True when [url] was imported less than [maxAgeMs] ago. */
    fun fresh(url: String, maxAgeMs: Long = 12 * 3600_000L): Boolean =
        meta("url") == url && (meta("at")?.toLongOrNull() ?: 0L) > nowMs() - maxAgeMs

    fun fresh(urls: List<String>, maxAgeMs: Long = 12 * 3600_000L) = fresh(urls.joinToString("|"), maxAgeMs)

    /**
     * Replaces the guide with [urls] merged: the playlist's own guide and any extra ones. A guide that fails is
     * skipped; the import fails only when none of them loads.
     */
    fun import(urls: List<String>, progress: (String) -> Unit) {
        write { db ->
            db.execSQL("DELETE FROM prog"); db.execSQL("DELETE FROM chan")
            var loaded = 0
            var last: Throwable? = null
            for ((i, url) in urls.withIndex()) {
                try {
                    if (urls.size > 1) progress("Loading TV guide ${i + 1} of ${urls.size}")
                    importOne(db, url, progress); loaded++
                } catch (e: Exception) { last = e }
            }
            if (loaded == 0) throw last ?: IllegalStateException("No guide")
            setMeta(db, "url", urls.joinToString("|"))
            setMeta(db, "at", nowMs().toString())
        }
        loadIndex()
    }

    private fun importOne(db: SQLiteConnection, url: String, progress: (String) -> Unit) {
        val from = nowMs() - 3 * 3600_000L
        val to = nowMs() + 4 * 24 * 3600_000L
        Http.stream(url) { input ->
            val x = XmlReader(input)
            db.prepare("INSERT INTO prog (ch, start, stop, title, descr, cat) VALUES (?, ?, ?, ?, ?, ?)").use { ins ->
                db.prepare("INSERT INTO chan (id, norm) VALUES (?, ?)").use { insCh ->
                    var n = 0
                    while (true) {
                        val ev = x.next()
                        if (ev == XmlReader.Event.EOF) break
                        if (ev != XmlReader.Event.START) continue
                        when (x.name) {
                            "channel" -> {
                                val id = x.attr("id") ?: ""
                                val names = ArrayList<String>()
                                while (true) {
                                    val e = x.next()
                                    if (e == XmlReader.Event.EOF || (e == XmlReader.Event.END && x.name == "channel")) break
                                    if (e == XmlReader.Event.START) { if (x.name == "display-name") names.add(x.nextText()) else x.skipElement() }
                                }
                                for (nm in (names + id).map { norm(it) }.filter { it.isNotEmpty() }.distinct()) {
                                    insCh.bindText(1, id); insCh.bindText(2, nm); insCh.step(); insCh.reset()
                                }
                            }
                            "programme" -> {
                                val ch = x.attr("channel") ?: ""
                                val start = time(x.attr("start"))
                                val stop = time(x.attr("stop")).let { if (it <= start) start + 3600_000L else it }
                                var title = ""; var desc: String? = null; var cat: String? = null
                                while (true) {
                                    val e = x.next()
                                    if (e == XmlReader.Event.EOF || (e == XmlReader.Event.END && x.name == "programme")) break
                                    if (e != XmlReader.Event.START) continue
                                    when (x.name) {
                                        "title" -> x.nextText().let { if (title.isEmpty()) title = it }
                                        "desc" -> x.nextText().let { if (desc == null) desc = it }
                                        "category" -> x.nextText().let { if (cat == null) cat = it }
                                        else -> x.skipElement()
                                    }
                                }
                                if (stop > from && start < to) {
                                    ins.bindText(1, ch); ins.bindLong(2, start); ins.bindLong(3, stop); ins.bindText(4, title)
                                    ins.text(5, desc); ins.text(6, cat)
                                    ins.step(); ins.reset()
                                    if (++n % 20000 == 0) progress("Reading guide: ${grouped(n)} programmes")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun SQLiteStatement.text(i: Int, v: String?) { if (v != null) bindText(i, v) else bindNull(i) }

    /**
     * Listings for event channels, which no guide carries but whose names are the listing: "DE: Al-Fateh - Al-Ahli |
     * Saudi Pro League | 2026-10-09 | 14:55 (GMT) | 8K EXCLUSIVE | DE: DAZN PPV 22". Replaces the previous ones.
     */
    fun addEvents(channels: List<Channel>) {
        write { db ->
            db.execSQL("DELETE FROM prog WHERE ch LIKE 'ev:%'"); db.execSQL("DELETE FROM chan WHERE id LIKE 'ev:%'")
            db.prepare("INSERT INTO prog (ch, start, stop, title, descr, cat) VALUES (?, ?, ?, ?, ?, 'Sport')").use { ins ->
                db.prepare("INSERT INTO chan (id, norm) VALUES (?, ?)").use { insCh ->
                    val from = nowMs() - 6 * 3600_000L
                    for (c in channels) {
                        if (!c.name.contains(" | ")) continue
                        val e = event(c.name) ?: continue
                        if (e.second < from) continue
                        val id = "ev:" + c.id
                        val n = norm(c.name)
                        if (n.isEmpty()) continue
                        insCh.bindText(1, id); insCh.bindText(2, n); insCh.step(); insCh.reset()
                        ins.bindText(1, id); ins.bindLong(2, e.second); ins.bindLong(3, e.second + 150 * 60_000L); ins.bindText(4, e.first)
                        ins.text(5, e.third)
                        ins.step(); ins.reset()
                    }
                }
            }
        }
        loadIndex()
    }

    /** Reads the channel ids and names once, so [key] needs no query. */
    fun loadIndex() {
        val m = HashMap<String, String>(); val s = HashSet<String>()
        read { db -> db.prepare("SELECT id, norm FROM chan").use { while (it.step()) { val id = it.getText(0); s.add(id); if (it.getText(1) !in m) m[it.getText(1)] = id } } }
        byName = m; ids = s
    }

    /** The guide's channel id for [ch]: its own guide id when the guide has it, else a name match. */
    fun key(ch: Channel): String? {
        ch.epgId?.let { if (it in ids) return it; it.substringBefore('@').let { base -> if (base in ids) return base } }
        return byName[norm(ch.name)] ?: ch.epgId?.let { byName[norm(it)] }
    }

    fun programmes(key: String, from: Long, to: Long): List<Programme> = query(
        "SELECT ch, start, stop, title, descr, cat FROM prog WHERE ch=? AND stop>? AND start<? ORDER BY start", listOf(key, from, to))

    fun nowNext(key: String): List<Programme> =
        query("SELECT ch, start, stop, title, descr, cat FROM prog WHERE ch=? AND stop>? ORDER BY start LIMIT 2", listOf(key, nowMs()))

    /** Programmes between [from] and [to] whose title contains every word in [words]. */
    fun find(words: List<String>, from: Long, to: Long, limit: Int = 60): List<Programme> {
        if (words.isEmpty()) return emptyList()
        val where = words.joinToString(" AND ") { "title LIKE ?" }
        // Bounding start both ways keeps the search on the time index; a programme covering kick-off started at most 6 h before.
        val args = listOf<Any>(from - 6 * 3600_000L, to, from) + words.map { "%$it%" }
        return query("SELECT ch, start, stop, title, descr, cat FROM prog WHERE start>? AND start<? AND stop>? AND $where ORDER BY start LIMIT $limit", args)
    }

    /** Sports programmes on now or starting soon, by guide category or by title. */
    fun sportsNow(from: Long, to: Long, limit: Int = 200): List<Programme> = query(
        "SELECT ch, start, stop, title, descr, cat FROM prog WHERE stop>? AND start<? AND (cat LIKE '%sport%' OR title LIKE '% vs %' OR title LIKE '% v %' OR title LIKE '%live:%') ORDER BY start LIMIT $limit",
        listOf(from, to))

    private fun query(sql: String, args: List<Any>): List<Programme> = read { db ->
        db.prepare(sql).use { st ->
            args.forEachIndexed { i, a -> if (a is Long) st.bindLong(i + 1, a) else st.bindText(i + 1, a.toString()) }
            val out = ArrayList<Programme>()
            while (st.step()) out.add(Programme(st.getText(0), st.getLong(1), st.getLong(2), if (st.isNull(3)) "" else st.getText(3),
                if (st.isNull(4)) null else st.getText(4), if (st.isNull(5)) null else st.getText(5)))
            out
        }
    }

    val hasData: Boolean get() = ids.isNotEmpty()

    /** "754 channels, 53,427 programmes, updated 25 Oct 23:50" for Settings. */
    fun summary(): String {
        val ch = ids.size
        val n = read { db -> db.prepare("SELECT COUNT(*) FROM prog").use { if (it.step()) it.getLong(0) else 0L } }
        val at = meta("at")?.toLongOrNull()
        return if (ch == 0) "No guide loaded" else "${grouped(ch)} channels, ${grouped(n)} programmes" + (at?.let { ", updated " + Time.dateTime(it) } ?: "")
    }

    companion object {
        /** Bumped when the tables change; the guide is then imported again. */
        private const val VERSION = 5

        fun time(v: String?): Long = Time.parseXmltv(v)

        private val status = setOf("next", "live", "ended", "all", "now", "upcoming")

        /** (title, start, competition) from an event channel's name, or null when it names no date and GMT time. */
        fun event(name: String): Triple<String, Long, String?>? {
            val parts = name.split(" | ").map { it.trim() }
            val d = parts.indexOfFirst { it.length == 10 && it[4] == '-' && it[7] == '-' || it.length == 10 && it[2] == '-' && it[5] == '-' }
            if (d < 1 || d + 1 >= parts.size) return null
            val time = parts[d + 1].takeIf { it.endsWith("(GMT)") }?.substringBefore(' ') ?: return null
            val start = Time.parseDayTime(parts[d], time) ?: return null
            val text = parts.subList(0, d).map { p ->
                // "DE: Al-Fateh - Al-Ahli": drop the country prefix.
                val i = p.indexOf(": "); if (i in 1..8 && p.substring(0, i).all { it.isLetter() || it == ' ' }) p.substring(i + 2) else p
            }.filter { it.isNotBlank() && it.lowercase() !in status }
            if (text.isEmpty()) return null
            return Triple(text.first(), start, text.drop(1).joinToString(" · ").ifBlank { null })
        }

        /** Abbreviations guides use for channel names: "SkySp Action" is Sky Sports Action. */
        private val abbrev = mapOf("skysp" to "skysports", "sp" to "sports", "spts" to "sports", "ent" to "entertainment",
            "skyspbox" to "skysportsbox", "skyspcricket" to "skysportscricket", "skyspoff" to "skysportsboxoffice",
            // BBC regions as UK guides shorten them ("BBC One Lon HD").
            "lon" to "london", "wal" to "wales", "scot" to "scotland", "sth" to "south", "yorks" to "yorkshire",
            "mid" to "midlands", "wm" to "westmidlands")

        /** "BBC 1" is BBC One: playlists use numbers, guides words. */
        private val bbcNumbers = mapOf("1" to "one", "2" to "two", "3" to "three", "4" to "four")

        private val tags = setOf("uhd", "fhd", "hd", "sd", "4k", "hevc", "h265", "raw", "backup")

        /**
         * Lower case letters and digits only, without HD/FHD/4K/720p tags, bracketed parts, "UK: " style prefixes and
         * "@SD" suffixes: "UK: BBC One HD (backup)" and "bbc.one" match. A plain loop: playlists have 50,000+ channels
         * and regular expressions took most of a minute on a Fire TV.
         */
        fun norm(s: String): String {
            var t = s
            t.lastIndexOf('@').let { if (it > 0) t = t.substring(0, it) }
            // Guide ids end with a country: "SkySp.Racing.HD.uk".
            t.lastIndexOf('.').let { if (it > 0 && t.length - it in 3..4 && t.substring(it + 1).all { c -> c.isLetter() }) t = t.substring(0, it) }
            // "UK: ", "US| ", "SPORTS: ", "CA FR: ", "KU: AR: " prefixes: a short word or two, then a separator.
            repeat(2) {
                var i = 0
                while (i < t.length && i < 8 && (t[i].isLetter() || t[i] == ' ')) i++
                if (i >= 2 && i < t.length && (t[i] == ':' || t[i] == '|')) t = t.substring(i + 1).trimStart()
            }
            val out = StringBuilder(t.length)
            val word = StringBuilder()
            var depth = 0
            fun flush() {
                if (word.isEmpty()) return
                var w = word.toString()
                // "ActionHD" written as one word.
                if (w.length > 5 && (w.endsWith("hd") && w != "uhd")) w = w.dropLast(2)
                val quality = w.length in 4..5 && w.endsWith('p') && w.dropLast(1).all { it.isDigit() }
                if (out.endsWith("bbc")) bbcNumbers[w]?.let { w = it }
                if (w !in tags && !quality) out.append(abbrev[w] ?: w)
                word.setLength(0)
            }
            for (ch in t) {
                when {
                    ch == '(' || ch == '[' -> { flush(); depth++ }
                    ch == ')' || ch == ']' -> { if (depth > 0) depth--; flush() }
                    depth > 0 -> {}
                    ch.isLetterOrDigit() -> word.append(ch.lowercaseChar())
                    else -> flush()
                }
            }
            flush()
            return out.toString()
        }
    }
}
