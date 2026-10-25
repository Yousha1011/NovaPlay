package io.github.novatv.play.json

import okio.BufferedSource

/**
 * A pull reader for XMLTV guides, read straight from the file: start tags (with attributes), end tags and text.
 * Enough XML for guides: comments, the declaration, DOCTYPE and CDATA are handled; namespaces are not.
 */
class XmlReader(private val src: BufferedSource) {
    enum class Event { START, END, TEXT, EOF }

    var name: String = ""
        private set
    var text: String = ""
        private set
    private val attrs = HashMap<String, String>()
    /** A self-closing tag gives START then END. */
    private var pendingEnd = false

    fun attr(k: String): String? = attrs[k]

    fun next(): Event {
        if (pendingEnd) { pendingEnd = false; return Event.END }
        while (true) {
            if (src.exhausted()) return Event.EOF
            if (src.buffer[0] != '<'.code.toByte()) {
                text = decode(readUntil('<'))
                if (text.isNotBlank()) return Event.TEXT
                continue
            }
            src.skip(1)
            if (src.exhausted()) return Event.EOF
            when (src.buffer[0].toInt().toChar()) {
                '/' -> { src.skip(1); name = readUntil('>').trim(); src.skip(1); return Event.END }
                '?' -> { skipPast("?>") }
                '!' -> when {
                    src.rangeEquals(0, okio.ByteString.of(*"!--".encodeToByteArray())) -> skipPast("-->")
                    src.rangeEquals(0, okio.ByteString.of(*"![CDATA[".encodeToByteArray())) -> {
                        src.skip(8)
                        text = readPast("]]>")
                        return Event.TEXT
                    }
                    else -> skipPast(">")
                }
                else -> { readTag(); return Event.START }
            }
        }
    }

    /** The text inside the current element, up to its end tag (which is consumed). */
    fun nextText(): String {
        val sb = StringBuilder()
        while (true) {
            when (next()) {
                Event.TEXT -> sb.append(text)
                Event.START -> skipElement()
                Event.END, Event.EOF -> return sb.toString().trim()
            }
        }
    }

    /** Skips the rest of the current element, children included (its START already read). */
    fun skipElement() {
        var depth = 1
        while (depth > 0) when (next()) {
            Event.START -> depth++
            Event.END -> depth--
            Event.EOF -> return
            Event.TEXT -> {}
        }
    }

    private fun readTag() {
        attrs.clear()
        val raw = readUntil('>')
        src.skip(1)
        var body = raw
        if (body.endsWith("/")) { body = body.dropLast(1); pendingEnd = true }
        var i = 0
        while (i < body.length && !body[i].isWhitespace()) i++
        name = body.substring(0, i)
        // name="value" pairs.
        while (i < body.length) {
            while (i < body.length && body[i].isWhitespace()) i++
            val eq = body.indexOf('=', i)
            if (eq < 0) break
            val key = body.substring(i, eq).trim()
            var j = eq + 1
            while (j < body.length && body[j].isWhitespace()) j++
            if (j >= body.length) break
            val q = body[j]
            if (q == '"' || q == '\'') {
                val end = body.indexOf(q, j + 1).let { if (it < 0) body.length else it }
                attrs[key] = decode(body.substring(j + 1, end))
                i = end + 1
            } else {
                var end = j
                while (end < body.length && !body[end].isWhitespace()) end++
                attrs[key] = decode(body.substring(j, end))
                i = end
            }
        }
    }

    /** Everything before [c] (not consumed), as UTF-8 text. */
    private fun readUntil(c: Char): String {
        val idx = src.indexOf(c.code.toByte())
        return if (idx < 0) src.readUtf8() else src.readUtf8(idx)
    }

    private fun readPast(end: String): String {
        val bs = okio.ByteString.of(*end.encodeToByteArray())
        val idx = src.indexOf(bs)
        if (idx < 0) return src.readUtf8()
        val s = src.readUtf8(idx)
        src.skip(bs.size.toLong())
        return s
    }

    private fun skipPast(end: String) { readPast(end) }

    private fun decode(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { out.append(c); i++; continue }
            val semi = s.indexOf(';', i)
            if (semi < 0 || semi - i > 10) { out.append(c); i++; continue }
            val ent = s.substring(i + 1, semi)
            val rep = when {
                ent == "amp" -> "&"; ent == "lt" -> "<"; ent == "gt" -> ">"; ent == "quot" -> "\""; ent == "apos" -> "'"
                ent.startsWith("#x") || ent.startsWith("#X") -> ent.drop(2).toIntOrNull(16)?.let(::codePoint)
                ent.startsWith("#") -> ent.drop(1).toIntOrNull()?.let(::codePoint)
                else -> null
            }
            if (rep == null) { out.append(c); i++ } else { out.append(rep); i = semi + 1 }
        }
        return out.toString()
    }

    private fun codePoint(cp: Int): String = if (cp < 0x10000) cp.toChar().toString() else {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
}
