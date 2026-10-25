package io.github.novatv.play.data

import io.github.novatv.play.platform.cacheDir
import io.github.novatv.play.platform.gunzip
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
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
            val file = if (gz) { gunzip(raw, plain); plain } else raw
            return fs.source(file).buffer().use { read(it) }
        } finally {
            fs.delete(raw, mustExist = false)
            fs.delete(plain, mustExist = false)
        }
    }
}
