package io.github.novatv.play.data

import io.github.novatv.play.platform.nowMs
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * A loaded library on disk, so the app opens at once and refreshes in the background. A plain binary format:
 * playlists of 100,000 entries read back in a second or two on a Fire TV.
 */
object LibraryCache {
    private const val VERSION = 4
    private val fs = FileSystem.SYSTEM

    fun file(dir: Path, sourceId: String): Path = dir / "lib_${sourceId.filter { it.isLetterOrDigit() }}.bin"

    fun write(f: Path, lib: Library) {
        val tmp = f.parent!! / (f.name + ".tmp")
        fs.sink(tmp).buffer().use { o ->
            o.writeInt(VERSION)
            o.writeLong(nowMs())
            o.str(lib.epgUrl)
            o.cats(lib.liveCategories); o.cats(lib.movieCategories); o.cats(lib.seriesCategories)
            o.writeInt(lib.channels.size)
            for (c in lib.channels) { o.str(c.id); o.str(c.name); o.str(c.logo); o.str(c.categoryId); o.str(c.epgId); o.str(c.url); o.writeInt(c.number); o.bool(c.catchup); o.str(c.cmd) }
            o.writeInt(lib.movies.size)
            for (m in lib.movies) { o.str(m.id); o.str(m.name); o.str(m.poster); o.str(m.categoryId); o.str(m.rating); o.str(m.year); o.str(m.url); o.str(m.ext); o.writeLong(m.added); o.str(m.cmd) }
            o.writeInt(lib.series.size)
            for (s in lib.series) {
                o.str(s.id); o.str(s.name); o.str(s.poster); o.str(s.categoryId); o.str(s.rating); o.str(s.plot); o.str(s.year); o.writeLong(s.added)
                o.writeInt(s.episodes.size)
                for (e in s.episodes) { o.str(e.id); o.writeInt(e.season); o.writeInt(e.number); o.str(e.title); o.str(e.plot); o.str(e.image); o.writeInt(e.durationSec); o.str(e.url); o.str(e.ext); o.str(e.cmd) }
            }
        }
        fs.atomicMove(tmp, f)
    }

    /** The library and when it was saved, or null when there is none (or it is from an older version). */
    fun read(f: Path): Pair<Library, Long>? {
        if (!fs.exists(f)) return null
        return try {
            fs.source(f).buffer().use { i ->
                if (i.readInt() != VERSION) return null
                val at = i.readLong()
                val epg = i.str()
                val lc = i.cats(); val mc = i.cats(); val sc = i.cats()
                val channels = List(i.readInt()) { Channel(i.str()!!, i.str()!!, i.str(), i.str()!!, i.str(), i.str(), i.readInt(), i.bool(), i.str()) }
                val movies = List(i.readInt()) { Movie(i.str()!!, i.str()!!, i.str(), i.str()!!, i.str(), i.str(), i.str(), i.str(), i.readLong(), i.str()) }
                val series = List(i.readInt()) {
                    val id = i.str()!!; val name = i.str()!!; val poster = i.str(); val cat = i.str()!!; val rating = i.str(); val plot = i.str(); val year = i.str(); val added = i.readLong()
                    val eps = List(i.readInt()) { Episode(i.str()!!, i.readInt(), i.readInt(), i.str()!!, i.str(), i.str(), i.readInt(), i.str(), i.str(), i.str()) }
                    Series(id, name, poster, cat, rating, plot, year, added, eps)
                }
                Library(lc, channels, mc, movies, sc, series, epg) to at
            }
        } catch (e: Exception) { null }
    }

    fun delete(f: Path) = fs.delete(f, mustExist = false)

    private fun BufferedSink.bool(b: Boolean) { writeByte(if (b) 1 else 0) }
    private fun BufferedSource.bool(): Boolean = readByte().toInt() != 0
    private fun BufferedSink.str(s: String?) {
        if (s == null) { writeInt(-1); return }
        val b = s.take(16000).encodeToByteArray()
        writeInt(b.size); write(b)
    }
    private fun BufferedSource.str(): String? { val n = readInt(); return if (n < 0) null else readUtf8(n.toLong()) }
    private fun BufferedSink.cats(c: List<Category>) { writeInt(c.size); c.forEach { str(it.id); str(it.name) } }
    private fun BufferedSource.cats() = List(readInt()) { Category(str()!!, str()!!) }
}
