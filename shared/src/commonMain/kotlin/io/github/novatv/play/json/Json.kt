package io.github.novatv.play.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray as KArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject as KObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A small JSON tree with org.json's API (optString, optJSONObject, put...), so the same code reads panels' and
 * sports feeds' answers on every platform. Values are JSONObject, JSONArray, String, Boolean, Long, Double or null.
 */
class JSONObject internal constructor(private val m: LinkedHashMap<String, Any?>) {
    constructor() : this(LinkedHashMap())
    constructor(text: String) : this(parseObject(text))

    fun opt(k: String): Any? = m[k]
    fun has(k: String) = m.containsKey(k)
    fun keys(): Iterator<String> = m.keys.toList().iterator()
    fun length() = m.size
    fun remove(k: String) { m.remove(k) }
    fun put(k: String, v: Any?): JSONObject { m[k] = v; return this }

    fun optString(k: String, def: String = ""): String = str(m[k]) ?: def
    fun getString(k: String): String = str(m[k]) ?: throw JSONException("No string for $k")
    fun optInt(k: String, def: Int = 0): Int = num(m[k])?.toInt() ?: def
    fun optLong(k: String, def: Long = 0L): Long = num(m[k])?.toLong() ?: def
    fun optDouble(k: String, def: Double = Double.NaN): Double = num(m[k]) ?: def
    fun optBoolean(k: String, def: Boolean = false): Boolean = bool(m[k]) ?: def
    fun optJSONObject(k: String): JSONObject? = m[k] as? JSONObject
    fun optJSONArray(k: String): JSONArray? = m[k] as? JSONArray
    fun getJSONObject(k: String): JSONObject = optJSONObject(k) ?: throw JSONException("No object for $k")
    fun getJSONArray(k: String): JSONArray = optJSONArray(k) ?: throw JSONException("No array for $k")

    override fun toString(): String = write(this)
}

class JSONArray internal constructor(private val l: ArrayList<Any?>) {
    constructor() : this(ArrayList())
    constructor(text: String) : this(parseArray(text))
    constructor(items: Collection<Any?>) : this(ArrayList(items))

    fun length() = l.size
    fun opt(i: Int): Any? = l.getOrNull(i)
    fun put(v: Any?): JSONArray { l.add(v); return this }
    fun optString(i: Int, def: String = ""): String = str(l.getOrNull(i)) ?: def
    fun getString(i: Int): String = str(l.getOrNull(i)) ?: throw JSONException("No string at $i")
    fun optInt(i: Int, def: Int = 0): Int = num(l.getOrNull(i))?.toInt() ?: def
    fun getLong(i: Int): Long = num(l.getOrNull(i))?.toLong() ?: throw JSONException("No number at $i")
    fun optJSONObject(i: Int): JSONObject? = l.getOrNull(i) as? JSONObject
    fun optJSONArray(i: Int): JSONArray? = l.getOrNull(i) as? JSONArray
    fun getJSONObject(i: Int): JSONObject = optJSONObject(i) ?: throw JSONException("No object at $i")

    override fun toString(): String = write(this)
}

class JSONException(msg: String) : RuntimeException(msg)

private val parser = Json { isLenient = true }

private fun parseObject(text: String): LinkedHashMap<String, Any?> {
    val e = try { parser.parseToJsonElement(text) } catch (ex: Exception) { throw JSONException(ex.message ?: "Bad JSON") }
    return (convert(e) as? JSONObject)?.let { o -> LinkedHashMap<String, Any?>().also { m -> o.keys().forEach { k -> m[k] = o.opt(k) } } }
        ?: throw JSONException("Not a JSON object")
}

private fun parseArray(text: String): ArrayList<Any?> {
    val e = try { parser.parseToJsonElement(text) } catch (ex: Exception) { throw JSONException(ex.message ?: "Bad JSON") }
    val a = convert(e) as? JSONArray ?: throw JSONException("Not a JSON array")
    return ArrayList<Any?>().also { l -> for (i in 0 until a.length()) l.add(a.opt(i)) }
}

private fun convert(e: JsonElement): Any? = when (e) {
    is JsonNull -> null
    is KObject -> JSONObject(LinkedHashMap<String, Any?>().also { m -> e.forEach { (k, v) -> m[k] = convert(v) } })
    is KArray -> JSONArray(ArrayList<Any?>().also { l -> e.forEach { l.add(convert(it)) } })
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.content == "true" -> true
        e.content == "false" -> false
        else -> e.content.toLongOrNull() ?: e.content.toDoubleOrNull() ?: e.content
    }
}

private fun str(v: Any?): String? = when (v) {
    null -> null
    is String -> v
    is Double -> if (v == kotlin.math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    is JSONObject, is JSONArray -> v.toString()
    else -> v.toString()
}

private fun num(v: Any?): Double? = when (v) {
    is Long -> v.toDouble()
    is Int -> v.toDouble()
    is Double -> v
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    is Boolean -> if (v) 1.0 else 0.0
    else -> null
}

private fun bool(v: Any?): Boolean? = when (v) {
    is Boolean -> v
    is String -> when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }
    is Number -> v.toDouble() != 0.0
    else -> null
}

private fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

private fun write(sb: StringBuilder, v: Any?) {
    when (v) {
        null -> sb.append("null")
        is JSONObject -> {
            sb.append('{')
            var first = true
            for (k in v.keys()) { if (!first) sb.append(','); first = false; quote(sb, k); sb.append(':'); write(sb, v.opt(k)) }
            sb.append('}')
        }
        is JSONArray -> {
            sb.append('[')
            for (i in 0 until v.length()) { if (i > 0) sb.append(','); write(sb, v.opt(i)) }
            sb.append(']')
        }
        is String -> quote(sb, v)
        is Boolean, is Number -> sb.append(v.toString())
        else -> quote(sb, v.toString())
    }
}

private fun quote(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) when (c) {
        '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
    }
    sb.append('"')
}
