package io.github.novatv.play.desktop

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The dev test driver's relay: passes streams through, follows redirects, routes HLS segments, cuts and restores. */
class StreamRelayTest {
    private val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/live.ts") { x -> val b = ByteArray(200_000) { (it % 251).toByte() }; x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.use { it.write(b) } }
        createContext("/moved.ts") { x -> x.responseHeaders.add("Location", "/live.ts"); x.sendResponseHeaders(302, -1); x.close() }
        createContext("/list.m3u8") { x ->
            val b = "#EXTM3U\n#EXTINF:4,\nseg1.ts\n#EXTINF:4,\n/abs/seg2.ts\n".toByteArray()
            x.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl"); x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.use { it.write(b) }
        }
        createContext("/stall.ts") { x ->
            x.sendResponseHeaders(200, 0)
            runCatching { x.responseBody.use { o -> repeat(400) { o.write(ByteArray(1000)); o.flush(); Thread.sleep(20) } } }
        }
        start()
    }
    private val base = "http://127.0.0.1:${origin.address.port}"
    private val relay = StreamRelay("test")

    @AfterTest fun stop() { relay.close(); origin.stop(0) }

    private fun get(url: String): Pair<Int, ByteArray> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 5000
        val code = c.responseCode
        return code to (if (code == 200) c.inputStream.use { it.readBytes() } else ByteArray(0))
    }

    @Test fun passesAStreamThroughUnchanged() {
        val (code, body) = get(relay.wrap("$base/live.ts"))
        assertEquals(200, code)
        assertEquals(200_000, body.size)
        assertEquals((1234 % 251).toByte(), body[1234])
    }

    @Test fun followsRedirects() {
        val (code, body) = get(relay.wrap("$base/moved.ts"))
        assertEquals(200, code); assertEquals(200_000, body.size)
    }

    @Test fun routesHlsSegmentsThroughTheRelayToo() {
        val text = get(relay.wrap("$base/list.m3u8")).second.decodeToString()
        val entries = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.startsWith("http://127.0.0.1:${relay.port}/s/") }, text)
        assertTrue(text.contains("#EXTINF:4,"))
    }

    @Test fun cutDropsOpenStreamsAndRefusesNewOnesUntilRestored() {
        val c = URI(relay.wrap("$base/stall.ts")).toURL().openConnection() as HttpURLConnection
        c.readTimeout = 5000
        val input = c.inputStream
        input.read(ByteArray(1000))
        relay.cutAll()
        val cutOff = runCatching { while (input.read(ByteArray(4096)) >= 0) { } }.let { true }
        assertTrue(cutOff)
        try { get(relay.wrap("$base/live.ts")).let { (code, body) -> if (code == 200 && body.isNotEmpty()) fail("served while cut") } } catch (_: Exception) { }
        assertTrue(relay.refused >= 1)
        relay.restore()
        assertEquals(200_000, get(relay.wrap("$base/live.ts")).second.size)
    }
}
