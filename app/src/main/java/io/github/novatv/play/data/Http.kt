package io.github.novatv.play.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object Http {
    /** IPTV panels often reject unknown clients; this is what most players send. */
    const val UA = "Mozilla/5.0 (Linux; Android 11; AFTKRT) AppleWebKit/537.36 (KHTML, like Gecko) NovaPlay/1.0 Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /** Streams a (possibly gzipped) body to [read]; for large playlists and guides. */
    fun <T> stream(url: String, read: (InputStream) -> T): T {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val raw = r.body!!.byteStream().buffered()
            raw.mark(2)
            val b0 = raw.read(); val b1 = raw.read()
            raw.reset()
            val gz = b0 == 0x1f && b1 == 0x8b
            return read(if (gz) GZIPInputStream(raw, 64 * 1024) else raw)
        }
    }
}
