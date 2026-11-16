package io.github.novatv.play

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Http
import io.github.novatv.play.data.M3uProvider
import io.github.novatv.play.data.Source

/**
 * Debug only. `adb shell am start -n <app>/io.github.novatv.play.DrmProbeActivity --es m3u /sdcard/x.m3u`: reads the
 * playlist with the app's own M3U reader, then plays each channel through the app's own player setup (novaSources and
 * mediaItem, so the playlist's DRM licences) for up to 20 s, logging "DRMPROBE <name>: PLAYS|FAILS ..." (no addresses).
 */
@SuppressLint("UnsafeOptInUsageError")
class DrmProbeActivity : Activity() {
    private lateinit var player: ExoPlayer
    private var channels: List<Channel> = emptyList()
    private var i = -1
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val view = PlayerView(this); setContentView(view)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(novaSources(OkHttpDataSource.Factory(TvHttp.client).setUserAgent(Http.UA))).build()
        view.player = player
        // --es open "Prime Video": opens that service exactly as a match page's button does, and logs what opened.
        intent.getStringExtra("open")?.let { name ->
            val svc = io.github.novatv.play.sports.OfficialApps.all.first { it.name == name }
            val ok = openOfficialApp(this, svc.packages, svc.web, svc.name, tv = intent.getBooleanExtra("tv", true))
            Log.i("DRMPROBE", "open $name: ${if (ok) "opened" else "not installed: says Install ${svc.name} from your app store"}"); finish(); return
        }
        val path = intent.getStringExtra("m3u") ?: return finish()
        Thread {
            val prov = M3uProvider(Source(id = "drmprobe", name = "probe", type = io.github.novatv.play.data.SourceType.M3U, url = path))
            channels = prov.load {}.channels
            // Resolved as the app resolves them: this registers each channel's licence.
            val urls = channels.map { prov.liveUrl(it) }
            val wv = android.media.MediaDrm.isCryptoSchemeSupported(androidx.media3.common.C.WIDEVINE_UUID)
            val level = runCatching { android.media.MediaDrm(androidx.media3.common.C.WIDEVINE_UUID).use { it.getPropertyString("securityLevel") } }.getOrElse { "-" }
            ui.post { Log.i("DRMPROBE", "channels ${channels.size}; device Widevine $wv ($level)"); next(urls) }
        }.start()
    }

    private fun next(urls: List<String>) {
        i++
        if (i >= channels.size) { Log.i("DRMPROBE", "DONE"); return }
        val ch = channels[i]
        val started = android.os.SystemClock.elapsedRealtime()
        var reported = false
        fun report(ok: Boolean, why: String) {
            if (reported) return; reported = true
            Log.i("DRMPROBE", "${ch.name}: ${if (ok) "PLAYS" else "FAILS"} $why")
            player.stop(); ui.postDelayed({ next(urls) }, 500)
        }
        player.clearMediaItems()
        player.setMediaItem(mediaItem(urls[i])); player.prepare(); player.playWhenReady = true
        val l = object : Player.Listener {
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) { player.removeListener(this); report(false, e.errorCodeName) }
        }
        player.addListener(l)
        fun poll() {
            if (reported) return
            val pos = player.currentPosition
            val vs = player.videoSize
            if (player.isPlaying && pos >= 4000 && vs.width > 0) { player.removeListener(l); report(true, "${vs.width}x${vs.height} at ${pos / 1000}s"); return }
            if (android.os.SystemClock.elapsedRealtime() - started > 20_000) { player.removeListener(l)
                report(false, "no picture in 20 s (state ${player.playbackState}, playing ${player.isPlaying}, at ${pos}ms, video ${vs.width}x${vs.height})"); return }
            ui.postDelayed({ poll() }, 500)
        }
        poll()
    }

    override fun onDestroy() { player.release(); super.onDestroy() }
}
