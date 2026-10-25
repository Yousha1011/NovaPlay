package io.github.novatv.play.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The TV guide: XMLTV imported into SQLite, kept from three hours ago to four days ahead. Channels are matched by
 * their guide id, or failing that by name ([Epg.key]).
 */
class Epg(ctx: Context) : SQLiteOpenHelper(ctx, "epg.db", null, 5) {
    init {
        // Write-ahead logging: screens keep reading the old guide while a new one imports (a big Xtream guide takes
        // minutes); without it every read waited for the import to finish.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE prog (ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT, descr TEXT, cat TEXT)")
        db.execSQL("CREATE INDEX prog_ch ON prog (ch, start)")
        db.execSQL("CREATE INDEX prog_time ON prog (start, stop)")
        // One row per name: a guide channel has several display names, and each is a way to match it.
        db.execSQL("CREATE TABLE chan (id TEXT NOT NULL, norm TEXT NOT NULL)")
        db.execSQL("CREATE INDEX chan_norm ON chan (norm)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        listOf("prog", "chan", "meta").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

    /** Guide ids by normalised channel name, for channels whose playlist gives no usable guide id. */
    @Volatile private var byName: Map<String, String> = emptyMap()
    @Volatile private var ids: Set<String> = emptySet()

    fun meta(k: String): String? = readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun setMeta(db: SQLiteDatabase, k: String, v: String) = db.execSQL("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)", arrayOf(k, v))

    /** True when [url] was imported less than [maxAgeMs] ago. */
    fun fresh(url: String, maxAgeMs: Long = 12 * 3600_000L): Boolean =
        meta("url") == url && (meta("at")?.toLongOrNull() ?: 0L) > System.currentTimeMillis() - maxAgeMs

    /**
     * Replaces the guide with [urls] merged: the playlist's own guide and any extra ones. A guide that fails is
     * skipped; the import fails only when none of them loads.
     */
    fun import(urls: List<String>, progress: (String) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("prog", null, null); db.delete("chan", null, null)
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
            setMeta(db, "at", System.currentTimeMillis().toString())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        loadIndex()
    }

    fun fresh(urls: List<String>, maxAgeMs: Long = 12 * 3600_000L) = fresh(urls.joinToString("|"), maxAgeMs)

    private fun importOne(db: SQLiteDatabase, url: String, progress: (String) -> Unit) {
        val from = System.currentTimeMillis() - 3 * 3600_000L
        val to = System.currentTimeMillis() + 4 * 24 * 3600_000L
        Http.stream(url) { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            run {
                val ins = db.compileStatement("INSERT INTO prog (ch, start, stop, title, descr, cat) VALUES (?, ?, ?, ?, ?, ?)")
                val insCh = db.compileStatement("INSERT INTO chan (id, norm) VALUES (?, ?)")
                var n = 0
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) when (p.name) {
                        "channel" -> {
                            val id = p.getAttributeValue(null, "id") ?: ""
                            val names = ArrayList<String>()
                            while (!(p.next() == XmlPullParser.END_TAG && p.name == "channel")) {
                                if (p.eventType == XmlPullParser.START_TAG && p.name == "display-name") names.add(p.nextText())
                            }
                            for (nm in (names + id).map { norm(it) }.filter { it.isNotEmpty() }.distinct()) { insCh.bindString(1, id); insCh.bindString(2, nm); insCh.executeInsert() }
                        }
                        "programme" -> {
                            val ch = p.getAttributeValue(null, "channel") ?: ""
                            val start = time(p.getAttributeValue(null, "start"))
                            val stop = time(p.getAttributeValue(null, "stop")).let { if (it <= start) start + 3600_000L else it }
                            var title = ""; var desc: String? = null; var cat: String? = null
                            while (!(p.next() == XmlPullParser.END_TAG && p.name == "programme")) {
                                if (p.eventType != XmlPullParser.START_TAG) continue
                                when (p.name) {
                                    "title" -> if (title.isEmpty()) title = p.nextText()
                                    "desc" -> if (desc == null) desc = p.nextText()
                                    "category" -> if (cat == null) cat = p.nextText()
                                }
                            }
                            if (stop > from && start < to) {
                                ins.bindString(1, ch); ins.bindLong(2, start); ins.bindLong(3, stop); ins.bindString(4, title)
                                if (desc != null) ins.bindString(5, desc) else ins.bindNull(5)
                                if (cat != null) ins.bindString(6, cat) else ins.bindNull(6)
                                ins.executeInsert()
                                if (++n % 20000 == 0) progress("Reading guide: $n programmes")
                            }
                        }
                    }
                    ev = p.next()
                }
            }
        }
    }

    /**
     * Listings for event channels, which no guide carries but whose names are the listing: "DE: Al-Fateh - Al-Ahli |
     * Saudi Pro League | 2026-10-09 | 14:55 (GMT) | 8K EXCLUSIVE | DE: DAZN PPV 22". Replaces the previous ones.
     */
    fun addEvents(channels: List<Channel>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("prog", "ch LIKE 'ev:%'", null); db.delete("chan", "id LIKE 'ev:%'", null)
            val ins = db.compileStatement("INSERT INTO prog (ch, start, stop, title, descr, cat) VALUES (?, ?, ?, ?, ?, 'Sport')")
            val insCh = db.compileStatement("INSERT INTO chan (id, norm) VALUES (?, ?)")
            val from = System.currentTimeMillis() - 6 * 3600_000L
            for (c in channels) {
                if (!c.name.contains(" | ")) continue
                val e = event(c.name) ?: continue
                if (e.second < from) continue
                val id = "ev:" + c.id
                val n = norm(c.name)
                if (n.isEmpty()) continue
                insCh.bindString(1, id); insCh.bindString(2, n); insCh.executeInsert()
                ins.bindString(1, id); ins.bindLong(2, e.second); ins.bindLong(3, e.second + 150 * 60_000L); ins.bindString(4, e.first)
                if (e.third != null) ins.bindString(5, e.third) else ins.bindNull(5)
                ins.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        loadIndex()
    }

    /** Reads the channel ids and names once, so [key] needs no query. */
    fun loadIndex() {
        val m = HashMap<String, String>(); val s = HashSet<String>()
        readableDatabase.rawQuery("SELECT id, norm FROM chan", null).use { while (it.moveToNext()) { s.add(it.getString(0)); m.putIfAbsent(it.getString(1), it.getString(0)) } }
        byName = m; ids = s
    }

    /** The guide's channel id for [ch]: its own guide id when the guide has it, else a name match. */
    fun key(ch: Channel): String? {
        ch.epgId?.let { if (it in ids) return it; it.substringBefore('@').let { base -> if (base in ids) return base } }
        return byName[norm(ch.name)] ?: ch.epgId?.let { byName[norm(it)] }
    }

    fun programmes(key: String, from: Long, to: Long): List<Programme> = query(
        "SELECT ch, start, stop, title, descr, cat FROM prog WHERE ch=? AND stop>? AND start<? ORDER BY start", arrayOf(key, "$from", "$to"))

    fun nowNext(key: String): List<Programme> {
        val now = System.currentTimeMillis()
        return query("SELECT ch, start, stop, title, descr, cat FROM prog WHERE ch=? AND stop>? ORDER BY start LIMIT 2", arrayOf(key, "$now"))
    }

    /** Programmes between [from] and [to] whose title or description contains every word in [words]. */
    fun find(words: List<String>, from: Long, to: Long, limit: Int = 60): List<Programme> {
        if (words.isEmpty()) return emptyList()
        val where = words.joinToString(" AND ") { "title LIKE ?" }
        // Bounding start both ways keeps the search on the time index; a programme covering kick-off started at most 6 h before.
        val args = listOf("${from - 6 * 3600_000L}", "$to", "$from") + words.map { "%$it%" }
        return query("SELECT ch, start, stop, title, descr, cat FROM prog WHERE start>? AND start<? AND stop>? AND $where ORDER BY start LIMIT $limit",
            args.toTypedArray())
    }

    /** Sports programmes on now or starting soon, by guide category or by title. */
    fun sportsNow(from: Long, to: Long, limit: Int = 200): List<Programme> = query(
        "SELECT ch, start, stop, title, descr, cat FROM prog WHERE stop>? AND start<? AND (cat LIKE '%sport%' OR title LIKE '% vs %' OR title LIKE '% v %' OR title LIKE '%live:%') ORDER BY start LIMIT $limit",
        arrayOf("$from", "$to"))

    private fun query(sql: String, args: Array<String>): List<Programme> = readableDatabase.rawQuery(sql, args).use { c ->
        val out = ArrayList<Programme>(c.count)
        while (c.moveToNext()) out.add(Programme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "", c.getString(4), c.getString(5)))
        out
    }

    val hasData: Boolean get() = ids.isNotEmpty()

    /** "754 channels, 53,427 programmes, updated 23:50" for Settings. */
    fun summary(): String {
        val ch = ids.size
        val n = readableDatabase.rawQuery("SELECT COUNT(*) FROM prog", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val at = meta("at")?.toLongOrNull()
        return if (ch == 0) "No guide loaded" else "%,d channels, %,d programmes%s".format(ch, n,
            at?.let { ", updated " + java.text.SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(java.util.Date(it)) } ?: "")
    }

    companion object {
        private val fmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US) }
        private val fmtNoZone = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss", Locale.US) }

        fun time(v: String?): Long {
            if (v.isNullOrBlank()) return 0L
            val t = v.trim()
            return try { if (t.length > 14) fmt.get()!!.parse(t)!!.time else fmtNoZone.get()!!.parse(t.take(14))!!.time } catch (e: Exception) { 0L }
        }

        private val ymd = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") } }
        private val dmy = ThreadLocal.withInitial { SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") } }
        private val status = setOf("next", "live", "ended", "all", "now", "upcoming")

        /** (title, start, competition) from an event channel's name, or null when it names no date and GMT time. */
        fun event(name: String): Triple<String, Long, String?>? {
            val parts = name.split(" | ").map { it.trim() }
            val d = parts.indexOfFirst { it.length == 10 && it[4] == '-' && it[7] == '-' || it.length == 10 && it[2] == '-' && it[5] == '-' }
            if (d < 1 || d + 1 >= parts.size) return null
            val time = parts[d + 1].takeIf { it.endsWith("(GMT)") }?.substringBefore(' ') ?: return null
            val start = try { (if (parts[d][4] == '-') ymd else dmy).get()!!.parse(parts[d] + " " + time)!!.time } catch (e: Exception) { return null }
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
