package io.github.novatv.play.data

import io.github.novatv.play.platform.cacheDir
import io.github.novatv.play.platform.gunzip
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import okio.BufferedSource
import okio.FileSystem
import okio.IOException
import okio.buffer
import okio.use
import kotlin.random.Random

object Http {
    /** IPTV panels often reject unknown clients; this is what most players send. */
    const val UA = "Mozilla/5.0 (Linux; Android 11; AFTKRT) AppleWebKit/537.36 (KHTML, like Gecko) NovaPlay/1.0 Safari/537.36"

    val client: HttpClient = HttpClient {
        followRedirects = true
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }

    /**
     * The picture loader's client: every request names the app ([UA]). Wikimedia refuses pictures to unnamed or
     * generic clients ("okhttp", "Java"), which left players' photos blank.
     */
    val imageClient: HttpClient by lazy {
        HttpClient {
            followRedirects = true
            expectSuccess = false
            install(io.ktor.client.plugins.UserAgent) { agent = UA }
            install(HttpTimeout) { connectTimeoutMillis = 15_000; socketTimeoutMillis = 60_000 }
        }
    }

    /** A picture's HTTP status as the picture loader gets it (checks and tests). Blocking. */
    fun imageStatus(url: String): Int = runBlocking { imageClient.prepareGet(url).execute { r -> r.status.value } }

    /** Blocking: call on [io.github.novatv.play.platform.IO]. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String = runBlocking {
        client.prepareGet(url) {
            header("User-Agent", UA)
            headers.forEach { (k, v) -> header(k, v) }
        }.execute { r ->
            if (r.status.value !in 200..299) throw IOException("HTTP ${r.status.value}")
            r.bodyAsText()
        }
    }

    /**
     * Whether [url] opens: its status only (the body is never read, so a live stream doesn't keep it busy), within
     * [timeoutMs]. False on any error or a status of 400 and up.
     */
    suspend fun opens(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 6_000L): Boolean =
        probe(url, headers, timeoutMs) == Probe.PLAYS

    /** What a stream check found: it plays, it is DRM-locked (opens, but no player here decrypts it), or it is down. */
    enum class Probe { PLAYS, DRM, DOWN }

    /** [opens], saying why not: a DRM-locked stream apart from one that is down. */
    suspend fun probe(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 6_000L): Probe = try {
        var variant: String? = null
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            client.prepareGet(url) {
                header("User-Agent", headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: UA)
                headers.filterKeys { !it.equals("User-Agent", true) }.forEach { (k, v) -> header(k, v) }
            }.execute { r ->
                if (r.status.value !in 200..399) return@execute Probe.DOWN
                // An HLS playlist that lists others (qualities): those must open too. Some send players on to a
                // second server that refuses (only in its own country) while the first still answers.
                val type = r.headers["Content-Type"].orEmpty().lowercase()
                // A playlist: by its type, its name, or plain text (a live video stream is never text, so reading it is safe).
                val hls = "mpegurl" in type || type.startsWith("text/") || r.call.request.url.encodedPath.endsWith(".m3u8")
                if (!hls) return@execute Probe.PLAYS
                val text = r.bodyAsText().take(200_000)
                // DRM-locked video (FanCode's SAMPLE-AES-CTR): it downloads, but no player here can decrypt it.
                // Plain AES-128 the players decrypt themselves.
                if (drm(text)) return@execute Probe.DRM
                if (!text.contains("#EXT-X-STREAM-INF")) return@execute Probe.PLAYS
                val next = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@execute Probe.PLAYS
                variant = if (next.startsWith("http")) next else r.call.request.url.toString().substringBeforeLast('/') + "/" + next
                Probe.PLAYS
            }
        }?.let { p -> if (p == Probe.PLAYS && variant != null) probe(variant!!, headers, timeoutMs) else p } ?: Probe.DOWN
    } catch (e: Exception) { Probe.DOWN }

    /** An HLS playlist whose video is DRM-encrypted (any key method but NONE or AES-128). */
    internal fun drm(playlist: String): Boolean = Regex("""#EXT-X-(?:SESSION-)?KEY:[^\n]*METHOD=([A-Z0-9-]+)""").findAll(playlist)
        .any { it.groupValues[1] != "NONE" && it.groupValues[1] != "AES-128" }

    /** Blocking: a request with a JSON [body] (GitHub's API); returns the status code and the reply. */
    fun send(method: String, url: String, headers: Map<String, String>, body: String? = null): Pair<Int, String> = runBlocking {
        client.prepareRequest(url) {
            this.method = io.ktor.http.HttpMethod.parse(method)
            header("User-Agent", "NovaPlay")
            headers.forEach { (k, v) -> header(k, v) }
            if (body != null) setBody(io.ktor.http.content.TextContent(body, io.ktor.http.ContentType.Application.Json))
        }.execute { r -> r.status.value to r.bodyAsText() }
    }

    /**
     * Downloads [url] to a file (unpacked when gzipped) and hands [read] a stream of it; for large playlists and guides,
     * which never need to fit in memory.
     */
    fun <T> stream(url: String, read: (BufferedSource) -> T): T {
        val fs = FileSystem.SYSTEM
        val dir = cacheDir().also { fs.createDirectories(it) }
        val raw = dir / "dl_${Random.nextLong().toULong()}.tmp"
        val plain = dir / "dl_${Random.nextLong().toULong()}.txt"
        try {
            runBlocking {
                client.prepareGet(url) { header("User-Agent", UA) }.execute { r ->
                    if (r.status.value !in 200..299) throw IOException("HTTP ${r.status.value}")
                    val ch = r.bodyAsChannel()
                    fs.sink(raw).buffer().use { sink ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ch.readAvailable(buf, 0, buf.size)
                            if (n < 0) break
                            if (n > 0) sink.write(buf, 0, n)
                            else if (ch.isClosedForRead) break
                        }
                    }
                }
            }
            val gz = fs.source(raw).buffer().use { s -> s.request(2) && s.buffer[0] == 0x1f.toByte() && s.buffer[1] == 0x8b.toByte() }
            val file = if (gz) { gunzip(raw, plain); fs.delete(raw, mustExist = false); plain } else raw
            return fs.source(file).buffer().use { read(it) }
        } finally {
            fs.delete(raw, mustExist = false)
            fs.delete(plain, mustExist = false)
        }
    }
}
