package io.github.novatv.play.json

import okio.BufferedSource

enum class JsonToken { BEGIN_ARRAY, END_ARRAY, BEGIN_OBJECT, END_OBJECT, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

/**
 * Reads JSON a token at a time straight from a file or the network, keeping nothing but the current value in memory
 * (the Android JsonReader's API). Lenient: unquoted names and values are read as strings.
 */
class JsonReader(private val src: BufferedSource) {
    /** What each open array or object expects next. */
    private val stack = ArrayList<Int>()
    private var peeked: JsonToken? = null

    private companion object {
        const val ARRAY_FIRST = 0; const val ARRAY_NEXT = 1
        const val OBJ_FIRST = 2; const val OBJ_NAME = 3; const val OBJ_VALUE = 4
    }

    private fun byte(): Int = if (src.exhausted()) -1 else src.readByte().toInt() and 0xff
    private fun peekByte(): Int = if (src.request(1)) src.buffer[0].toInt() and 0xff else -1

    private fun skipWs(): Int {
        while (true) {
            val c = peekByte()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) src.skip(1) else return c
        }
    }

    fun peek(): JsonToken {
        peeked?.let { return it }
        val t = next()
        peeked = t
        return t
    }

    private fun next(): JsonToken {
        val top = stack.lastOrNull()
        var c = skipWs()
        when (top) {
            ARRAY_NEXT -> {
                if (c == ','.code) { src.skip(1); c = skipWs() }
                else if (c == ']'.code) return JsonToken.END_ARRAY
            }
            ARRAY_FIRST -> { if (c == ']'.code) return JsonToken.END_ARRAY; stack[stack.lastIndex] = ARRAY_NEXT }
            OBJ_FIRST, OBJ_NAME -> {
                if (top == OBJ_NAME && c == ','.code) { src.skip(1); c = skipWs() }
                if (c == '}'.code) return JsonToken.END_OBJECT
                return JsonToken.NAME
            }
            OBJ_VALUE -> { if (c == ':'.code) { src.skip(1); c = skipWs() } }
        }
        return when (c) {
            -1 -> JsonToken.END_DOCUMENT
            '['.code -> JsonToken.BEGIN_ARRAY
            '{'.code -> JsonToken.BEGIN_OBJECT
            '"'.code, '\''.code -> JsonToken.STRING
            't'.code, 'f'.code -> JsonToken.BOOLEAN
            'n'.code -> JsonToken.NULL
            else -> if (c == '-'.code || c in '0'.code..'9'.code) JsonToken.NUMBER else JsonToken.STRING
        }
    }

    private fun expect(t: JsonToken) { val p = peek(); if (p != t) throw JSONException("Expected $t but was $p"); peeked = null }

    /** After a value: an object's next entry is a name again. */
    private fun valueDone() { if (stack.lastOrNull() == OBJ_VALUE) stack[stack.lastIndex] = OBJ_NAME }

    fun beginArray() { expect(JsonToken.BEGIN_ARRAY); src.skip(1); stack.add(ARRAY_FIRST) }
    fun endArray() { expect(JsonToken.END_ARRAY); src.skip(1); stack.removeAt(stack.lastIndex); valueDone() }
    fun beginObject() { expect(JsonToken.BEGIN_OBJECT); src.skip(1); stack.add(OBJ_FIRST) }
    fun endObject() { expect(JsonToken.END_OBJECT); src.skip(1); stack.removeAt(stack.lastIndex); valueDone() }

    fun hasNext(): Boolean = peek().let { it != JsonToken.END_ARRAY && it != JsonToken.END_OBJECT && it != JsonToken.END_DOCUMENT }

    fun nextName(): String {
        expect(JsonToken.NAME)
        val c = skipWs()
        val name = if (c == '"'.code || c == '\''.code) { src.skip(1); quoted(c) } else literal()
        stack[stack.lastIndex] = OBJ_VALUE
        return name
    }

    fun nextString(): String {
        val p = peek()
        peeked = null
        val c = skipWs()
        val v = when (p) {
            JsonToken.STRING -> if (c == '"'.code || c == '\''.code) { src.skip(1); quoted(c) } else literal()
            JsonToken.NUMBER, JsonToken.BOOLEAN -> literal()
            else -> throw JSONException("Expected a string but was $p")
        }
        valueDone()
        return v
    }

    fun nextBoolean(): Boolean { val p = peek(); if (p != JsonToken.BOOLEAN) throw JSONException("Expected a boolean"); peeked = null; val v = literal() == "true"; valueDone(); return v }

    fun nextNull() { expect(JsonToken.NULL); literal(); valueDone() }

    fun skipValue() {
        when (peek()) {
            JsonToken.BEGIN_ARRAY -> { beginArray(); while (hasNext()) skipValue(); endArray() }
            JsonToken.BEGIN_OBJECT -> { beginObject(); while (hasNext()) { nextName(); skipValue() }; endObject() }
            JsonToken.NAME -> { nextName(); skipValue() }
            JsonToken.STRING, JsonToken.NUMBER, JsonToken.BOOLEAN -> nextString()
            JsonToken.NULL -> nextNull()
            else -> { peeked = null }
        }
    }

    /** An unquoted token: a number, true/false/null or a lenient bare word. */
    private fun literal(): String {
        val sb = StringBuilder()
        while (true) {
            val c = peekByte()
            if (c == -1 || c == ','.code || c == ']'.code || c == '}'.code || c == ':'.code || c == ' '.code || c == '\n'.code ||
                c == '\r'.code || c == '\t'.code) break
            sb.append(c.toChar()); src.skip(1)
        }
        return sb.toString()
    }

    /** The rest of a quoted string (the opening quote already read), escapes decoded, UTF-8 kept whole. */
    private fun quoted(q: Int): String {
        val bytes = okio.Buffer()
        val out = StringBuilder()
        fun flush() { if (bytes.size > 0) out.append(bytes.readUtf8()) }
        while (true) {
            val c = byte()
            if (c == -1 || c == q) break
            if (c != '\\'.code) { bytes.writeByte(c); continue }
            flush()
            when (val e = byte()) {
                'n'.code -> out.append('\n'); 't'.code -> out.append('\t'); 'r'.code -> out.append('\r')
                'b'.code -> out.append('\b'); 'f'.code -> out.append('\u000C')
                'u'.code -> {
                    val hex = buildString { repeat(4) { append(byte().toChar()) } }
                    out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                }
                -1 -> break
                else -> out.append(e.toChar())
            }
        }
        flush()
        return out.toString()
    }
}
