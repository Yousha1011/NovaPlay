package io.github.novatv.play.desktop

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.Collections

/**
 * Development only: while a network scenario runs (see AutoTest), the players' streams come through this relay on
 * 127.0.0.1, so the driver can cut them as a dropped connection would and bring them back, without touching the
 * computer's own network. Nothing else ever uses it.
 *
 * Stream addresses carry the playlist's login, so they're never logged; the relay only listens on the loopback address.
 */
class StreamRelay(private val userAgent: String, private val log: (String) -> Unit = {}) {
    private val ids = java.util.concurrent.atomic.AtomicInteger()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    private val open: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    @Volatile var cut = false
        private set
    @Volatile var served = 0
        private set
    @Volatile var refused = 0
        private set

    init {
        Thread({ while (!server.isClosed) runCatching { val s = server.accept(); Thread({ serve(s) }, "relay-conn").apply { isDaemon = true }.start() } },
            "relay").apply { isDaemon = true }.start()
    }

    fun wrap(url: String): String = "http://127.0.0.1:$port/s/" + Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray())

    /** Drops every stream now and refuses new ones until [restore]: what a lost connection looks like to VLC. */
    fun cutAll() { cut = true; synchronized(open) { open.toList() }.forEach { runCatching { it.close() } } }
    fun restore() { cut = false }
    fun streams(): Int = open.size

    fun close() { runCatching { server.close() }; cutAll() }

    private fun serve(client: Socket) {
        open.add(client)
        val id = ids.incrementAndGet(); val t0 = System.currentTimeMillis(); var bytes = 0L; var how = "?"
        try {
            val input = client.getInputStream()
            val line = readLine(input) ?: return
            while (readLine(input)?.isNotEmpty() == true) { /* headers: not needed */ }
            val path = line.split(' ').getOrNull(1) ?: return
            if (cut || !path.startsWith("/s/")) { refused++; how = "refused"; return }
            val target = String(Base64.getUrlDecoder().decode(path.removePrefix("/s/").substringBefore('?')))
            // Redirects are followed here (Java won't follow one that switches between http and https).
            var at = URI(target)
            var c: HttpURLConnection
            var hops = 0
            while (true) {
                c = (at.toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false; connectTimeout = 10_000; readTimeout = 20_000
                    setRequestProperty("User-Agent", userAgent)
                }
                val loc = c.getHeaderField("Location")
                if (c.responseCode in 300..399 && loc != null && hops++ < 6) { c.disconnect(); at = at.resolve(loc); continue }
                break
            }
            val code = c.responseCode
            val out = client.getOutputStream()
            if (code !in 200..299) { how = "upstream $code"; out.write("HTTP/1.1 $code Upstream\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
            served++
            val type = c.contentType ?: "application/octet-stream"
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nConnection: close\r\n\r\n".toByteArray())
            val body = c.inputStream
            if (type.contains("mpegurl", ignoreCase = true) || target.substringBefore('?').endsWith(".m3u8")) {
                // HLS: every entry in the list comes through the relay too, so cutting stops the segments as well.
                val base = at
                val text = body.readBytes().decodeToString().lineSequence().joinToString("\n") { l ->
                    if (l.isBlank() || l.startsWith("#")) l else wrap(base.resolve(l.trim()).toString())
                }
                out.write(text.toByteArray()); bytes = text.length.toLong(); how = "playlist"
            } else { bytes = pipe(body, out); how = if (cut) "cut" else "upstream ended" }
        } catch (e: Exception) { how = "${e::class.simpleName}: ${e.message?.take(60)}"
        } finally {
            open.remove(client); runCatching { client.close() }
            log("relay #$id: $how after ${(System.currentTimeMillis() - t0) / 1000}s, ${bytes / 1024} KB")
        }
    }

    private fun pipe(from: InputStream, to: OutputStream): Long {
        val buf = ByteArray(64 * 1024); var total = 0L
        try { while (!cut) { val n = from.read(buf); if (n < 0) break; to.write(buf, 0, n); total += n } }
        finally { runCatching { from.close() } }
        return total
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }
}
