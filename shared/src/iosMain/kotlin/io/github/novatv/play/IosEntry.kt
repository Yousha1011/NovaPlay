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
    /** Live TV: the channel's name without its number, what is on now and next, and its category. */
    val channelName: String,
    val nowTitle: String,
    val nextTitle: String,
    /** Progress through the programme on now, 0 to 1 (-1 when unknown), and its times ("14:00 – 15:30"). */
    val nowProgress: Float,
    val nowTimes: String,
    val categoryId: String,
    val categoryName: String,
    val favourite: Boolean,
)

/** One programme in a channel's guide, for the Swift player's list. */
class GuideRow(val title: String, val times: String, val desc: String, val live: Boolean)

/** A channel card in "More from..." and the More Channels drawer. */
class ChannelCard(val id: String, val name: String, val logo: String?, val now: String, val playing: Boolean)

class CategoryTab(val id: String, val name: String)

/**
 * The bridge to the Swift player: Kotlin resolves streams (Stalker portals hand out links on request), keeps watch
 * progress and zaps through channels; Swift shows the picture and the controls. Blocking calls (guide, lists) are
 * meant for a background queue.
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

    private fun channel() = (item as? PlayItem.Live)?.let { it.channels.getOrNull(index) }

    /** Today's and tomorrow's programmes on the channel playing. */
    fun guide(): List<GuideRow> {
        val c = channel() ?: return emptyList()
        val key = App.repo.epg.key(c) ?: return emptyList()
        val now = nowMs()
        return App.repo.epg.programmes(key, now - 3600_000L, now + 24 * 3600_000L).filter { it.stop > now - 60_000L }.map { p ->
            GuideRow(p.title, "${clock(p.start)} – ${clock(p.stop)}", p.desc.orEmpty(), now in p.start until p.stop)
        }
    }

    /** The live categories, in the playlist's order. */
    fun categories(): List<CategoryTab> = App.repo.state.value.lib.liveCategories.map { CategoryTab(it.id, it.name) }

    /** Channels of [categoryId], with what is on now (the first [withGuide] look up the guide). */
    fun channelsIn(categoryId: String, withGuide: Int = 40): List<ChannelCard> {
        val list = App.repo.state.value.index?.channelsByCat?.get(categoryId).orEmpty()
        val playing = channel()?.id
        return list.mapIndexed { i, c ->
            val now = if (i < withGuide) try { App.repo.nowNext(c).firstOrNull()?.title.orEmpty() } catch (e: Exception) { "" } else ""
            ChannelCard(c.id, c.name, c.logo, now, c.id == playing)
        }
    }

    /** Plays channel [channelId] of [categoryId]; zapping then moves through that category. */
    fun playIn(categoryId: String, channelId: String) {
        val list = App.repo.state.value.index?.channelsByCat?.get(categoryId) ?: return
        val i = list.indexOfFirst { it.id == channelId }
        if (i < 0) return
        item = PlayItem.Live(list, i)
        index = i
        resolve()
    }

    /** Adds or removes the channel playing from favourites; returns whether it is one now. */
    fun toggleFavourite(): Boolean {
        val c = channel() ?: return false
        return App.store.toggleFavourite(App.repo.channelKey(c))
    }

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
                    val p = nn.firstOrNull(); val n = nn.getOrNull(1)
                    val now = nowMs()
                    val cat = App.repo.state.value.lib.liveCategories.firstOrNull { k -> k.id == c.categoryId }?.name.orEmpty()
                    PlayRequest("${c.number}  ${c.name}", p?.let { q -> "${clock(q.start)} – ${clock(q.stop)}  ${q.title}" } ?: "No information",
                        url, true, 0L, Http.UA, c.logo, App.store.bufferMs,
                        c.name, p?.title ?: "No information", n?.let { q -> "Next  ${clock(q.start)}  ${q.title}" } ?: "No information",
                        p?.let { q -> ((now - q.start).toFloat() / (q.stop - q.start).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: -1f,
                        p?.let { q -> "${clock(q.start)} – ${clock(q.stop)}" }.orEmpty(),
                        c.categoryId, cat, App.store.isFavourite(App.repo.channelKey(c)))
                }
                is PlayItem.Vod -> PlayRequest(it.title, it.series?.name ?: "", url, false, App.store.progress(it.key)?.first ?: 0L,
                    Http.UA, it.movie?.poster ?: it.series?.poster, App.store.bufferMs,
                    it.title, it.series?.name ?: "", "", -1f, "", "", "", false)
            }
            presenter?.invoke(req)
        }
    }
}
