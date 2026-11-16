package io.github.novatv.play.data

import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject

/**
 * DRM licences a playlist gives for its protected channels, in the lines Kodi reads above a channel:
 * `#KODIPROP:inputstream.adaptive.license_type=clearkey` and `...license_key=<kid>:<key>` (or a licence server's
 * address). Only the licence the playlist itself supplies is used: a channel without one is not decrypted.
 * Fire TV and Android phones play these (ExoPlayer with the device's DRM); Windows and iPhone cannot.
 */
object DrmLicences {
    /** A licence: [type] "clearkey" or "widevine"; [key] ClearKey's JSON keys, or a licence server's address. */
    data class Licence(val type: String, val key: String, val headers: Map<String, String> = emptyMap()) {
        /** ClearKey with its keys in the playlist (no server to ask). */
        val localKeys: Boolean get() = type == CLEARKEY && key.trimStart().startsWith("{")
    }

    const val CLEARKEY = "clearkey"
    const val WIDEVINE = "widevine"
    private const val PREFIX = "drm:"

    /** The licence from an entry's #KODIPROP properties (keys lower-cased), or null when it has none we can use. */
    fun fromKodiProps(props: Map<String, String>): Licence? {
        val rawType = props["inputstream.adaptive.license_type"]?.trim()?.lowercase() ?: props["inputstream.adaptive.drm_legacy"]?.substringBefore('|')?.trim()?.lowercase() ?: return null
        val rawKey = props["inputstream.adaptive.license_key"]?.trim() ?: props["inputstream.adaptive.drm_legacy"]?.substringAfter('|', "")?.trim() ?: return null
        if (rawKey.isEmpty()) return null
        val type = when {
            "clearkey" in rawType -> CLEARKEY
            "widevine" in rawType -> WIDEVINE
            else -> return null // PlayReady and others: no player here has them.
        }
        // A server's address may carry headers after '|' ("url|User-Agent=x&Referer=y|...").
        val parts = rawKey.split('|')
        val headers = parts.getOrNull(1).orEmpty().split('&').mapNotNull { h ->
            val k = h.substringBefore('=', "").trim(); if (k.isEmpty()) null else k to decode(h.substringAfter('='))
        }.toMap()
        val key = parts[0].trim()
        return when {
            key.startsWith("http", true) -> Licence(type, key, headers)
            type == CLEARKEY && key.startsWith("{") -> Licence(type, key)
            type == CLEARKEY -> clearKeyJson(key)?.let { Licence(type, it) }
            else -> null
        }
    }

    /**
     * "kid:key" pairs in hex (several separated by commas), as playlists write ClearKey, as the JSON a ClearKey licence
     * server returns: {"keys":[{"kty":"oct","kid":<base64url>,"k":<base64url>}],"type":"temporary"}.
     */
    internal fun clearKeyJson(pairs: String): String? {
        val keys = JSONArray()
        for (p in pairs.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val kid = hex(p.substringBefore(':')) ?: return null
            val k = hex(p.substringAfter(':', "")) ?: return null
            keys.put(JSONObject().put("kty", "oct").put("kid", b64url(kid)).put("k", b64url(k)))
        }
        if (keys.length() == 0) return null
        return JSONObject().put("keys", keys).put("type", "temporary").toString()
    }

    // ---------- kept with the channel (its cmd), and by stream address when played ----------

    fun encode(l: Licence): String = PREFIX + JSONObject().put("t", l.type).put("k", l.key)
        .put("h", JSONObject().also { o -> l.headers.forEach { (k, v) -> o.put(k, v) } }).toString()

    fun decode(cmd: String?): Licence? {
        if (cmd == null || !cmd.startsWith(PREFIX)) return null
        return runCatching {
            val o = JSONObject(cmd.removePrefix(PREFIX))
            val h = o.optJSONObject("h")
            Licence(o.optString("t"), o.optString("k"), h?.keys()?.asSequence()?.associateWith { h.optString(it) }.orEmpty())
        }.getOrNull()
    }

    private val byUrl = io.github.novatv.play.platform.SafeMap<String, Licence>()
    fun put(url: String, l: Licence) { byUrl[url] = l }
    fun forUrl(url: String): Licence? = byUrl[url]

    // ---------- helpers ----------

    private fun hex(s: String): ByteArray? {
        val t = s.trim().removePrefix("0x")
        if (t.length != 32 || t.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        return ByteArray(16) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    internal fun b64url(b: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i < b.size) {
            val n = (b[i].toInt() and 0xff shl 16) or ((b.getOrNull(i + 1)?.toInt() ?: 0) and 0xff shl 8) or ((b.getOrNull(i + 2)?.toInt() ?: 0) and 0xff)
            val chars = when (b.size - i) { 1 -> 2; 2 -> 3; else -> 4 }
            for (j in 0 until chars) out.append(ABC[(n shr (18 - 6 * j)) and 63])
            i += 3
        }
        return out.toString()
    }

    private fun decode(s: String): String = runCatching {
        val out = StringBuilder(); var i = 0
        val bytes = ArrayList<Byte>()
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length + 0 && i + 2 <= s.length - 1) { bytes.add(s.substring(i + 1, i + 3).toInt(16).toByte()); i += 3 }
            else { if (bytes.isNotEmpty()) { out.append(bytes.toByteArray().decodeToString()); bytes.clear() }; out.append(s[i]); i++ }
        }
        if (bytes.isNotEmpty()) out.append(bytes.toByteArray().decodeToString())
        out.toString()
    }.getOrElse { s }
}
