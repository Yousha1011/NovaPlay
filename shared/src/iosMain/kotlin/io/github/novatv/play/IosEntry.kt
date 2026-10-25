package io.github.novatv.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import io.github.novatv.play.data.Http
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.data.Programme
import io.github.novatv.play.platform.Device
import io.github.novatv.play.platform.Host
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.ui.NovaTheme
import io.github.novatv.play.ui.T
import io.github.novatv.play.ui.Text
import io.github.novatv.play.ui.clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.UIKit.UIViewController

/** Called once by the iPhone app before it shows anything. */
fun start() = App.start(IosHost)

/** The whole app as one view controller; the Swift side shows it full screen. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController {
    NovaTheme {
        Root()
        IosHost.message?.let { (msg, at) ->
            LaunchedEffect(at) { delay(2500); if (IosHost.message?.second == at) IosHost.message = null }
            Box(Modifier.fillMaxSize().padding(bottom = 30.dp), contentAlignment = Alignment.BottomCenter) {
                Text(msg, style = T.body, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xE0141A28)).padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}

object IosHost : Host {
    override val device = Device.PHONE
    var message by mutableStateOf<Pair<String, Long>?>(null)
    override fun play(item: PlayItem) = IosPlayback.open(item)
    override fun toast(msg: String) { message = msg to nowMs() }
}

/** What the Swift player is asked to play. */
class PlayRequest(
    val title: String,
    /** Now and next for live TV, the series for an episode. */
    val subtitle: String,
    val url: String,
    val live: Boolean,
    /** Where to resume a film or episode, in milliseconds. */
    val startMs: Long,
    val userAgent: String,
    val logo: String?,
    val bufferMs: Int,
)

/**
 * The bridge to the Swift player: Kotlin resolves streams (Stalker portals hand out links on request), keeps watch
 * progress and zaps through channels; Swift shows the picture.
 */
object IosPlayback {
    /** Set by Swift: shows (or switches) the player. */
    var presenter: ((PlayRequest) -> Unit)? = null
    /** Set by Swift: a stream could not be opened. */
    var failed: ((String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var item: PlayItem? = null
    private var index = 0

    fun open(it: PlayItem) {
        item = it
        index = (it as? PlayItem.Live)?.channelIndex ?: 0
        resolve()
    }

    /** Next (+1) or previous (-1) channel. */
    fun zap(delta: Int) {
        val l = item as? PlayItem.Live ?: return
        index = (index + delta).mod(l.channels.size)
        resolve()
    }

    fun isLive(): Boolean = item is PlayItem.Live

    fun saveProgress(posMs: Long, durMs: Long) {
        val v = item as? PlayItem.Vod ?: return
        if (durMs <= 0) return
        App.store.saveProgress(v.key, v.title, v.movie?.poster ?: v.series?.poster, posMs, durMs)
    }

    /** A film or episode reached its end: the next episode starts, if there is one. Returns false when nothing follows. */
    fun ended(durMs: Long): Boolean {
        val v = item as? PlayItem.Vod ?: return false
        saveProgress(durMs, durMs)
        val next = v.nextEpisodes.firstOrNull() ?: return false
        val s = v.series ?: return false
        open(PlayItem.Vod(App.repo.episodeKey(s, next), "${s.name} · S${next.season} E${next.number}", episode = next, series = s,
            nextEpisodes = v.nextEpisodes.drop(1)))
        return true
    }

    private fun resolve() {
        val it = item ?: return
        val i = index
        scope.launch {
            val url = withContext(IO) {
                try {
                    val p = App.repo.provider ?: return@withContext null
                    when (it) {
                        is PlayItem.Live -> p.liveUrl(it.channels[i])
                        is PlayItem.Vod -> it.movie?.let { m -> p.movieUrl(m) } ?: p.episodeUrl(it.series!!, it.episode!!)
                    }
                } catch (e: Exception) { null }
            }
            if (url == null) { failed?.invoke("This stream could not be opened"); return@launch }
            val req = when (it) {
                is PlayItem.Live -> {
                    val c = it.channels[i]
                    App.store.addRecentChannel(App.repo.channelKey(c)); App.store.lastChannel = App.repo.channelKey(c)
                    val nn: List<Programme> = withContext(IO) { try { App.repo.nowNext(c) } catch (e: Exception) { emptyList() } }
                    val sub = nn.firstOrNull()?.let { p -> "${clock(p.start)} – ${clock(p.stop)}  ${p.title}" } ?: "No guide information"
                    PlayRequest("${c.number}  ${c.name}", sub, url, true, 0L, Http.UA, c.logo, App.store.bufferMs)
                }
                is PlayItem.Vod -> PlayRequest(it.title, it.series?.name ?: "", url, false, App.store.progress(it.key)?.first ?: 0L,
                    Http.UA, it.movie?.poster ?: it.series?.poster, App.store.bufferMs)
            }
            presenter?.invoke(req)
        }
    }
}
