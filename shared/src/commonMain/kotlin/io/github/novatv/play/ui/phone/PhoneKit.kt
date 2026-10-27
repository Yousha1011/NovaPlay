package io.github.novatv.play.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.novatv.play.data.Movie
import io.github.novatv.play.data.Series
import io.github.novatv.play.ui.Icon
import io.github.novatv.play.ui.Text
import io.github.novatv.play.ui.initials

/** The iPhone's look: black, grey cards, one blue and one red accent, the phone's own type sizes. */
object P {
    val bg = Color.Black
    val card = Color(0xFF1C1C1E)
    val card2 = Color(0xFF2C2C2E)
    val line = Color(0x22FFFFFF)
    val text = Color.White
    val dim = Color(0xFF9A9AA0)
    val blue = Color(0xFF0A84FF)
    val red = Color(0xFFFF453A)
    val gold = Color(0xFFFFCC00)

    val large = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = text)
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = text)
    val head = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = text)
    val body = TextStyle(fontSize = 16.sp, color = text)
    val small = TextStyle(fontSize = 13.sp, color = dim)
    val caps = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = dim, letterSpacing = 1.5.sp)
}

/**
 * Glass, as on iOS: a translucent fill that is lighter at the top, and a bright rim fading towards the bottom. Drawn
 * (Compose cannot blur what lies behind it), over the black background it reads as frosted glass.
 */
fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape, strength: Float = 1f): Modifier = this
    .clip(shape)
    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f * strength), Color.White.copy(alpha = 0.06f * strength))))
    .border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.06f))), shape)

/** A tap without the Android ripple (the phone's own look has none). */
@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier = clickable(remember { MutableInteractionSource() }, null, onClick = onClick)

/** A round dark button with an icon, as at the top of every iPhone screen. */
@Composable
fun RoundIcon(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = Color.White) {
    Box(modifier.size(size).glass(CircleShape).tap(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, size = size * 0.48f, tint = tint)
    }
}

/** A row of choice chips: the picked one white with red text. */
@Composable
fun Chips(labels: List<Pair<String, String>>, picked: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(labels, key = { it.first }) { (id, label) ->
            val on = id == picked
            Box((if (on) Modifier.clip(RoundedCornerShape(50)).background(Color.White) else Modifier.glass(RoundedCornerShape(50)))
                .tap { onPick(id) }.padding(horizontal = 18.dp, vertical = 9.dp)) {
                Text(label, style = P.head.copy(color = if (on) P.red else P.text), maxLines = 1)
            }
        }
    }
}

/** A poster with its rating in a blue corner tab. */
@Composable
fun Poster(title: String, image: String?, rating: String?, onClick: () -> Unit, modifier: Modifier = Modifier, progress: Float? = null) {
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)).background(P.card).tap(onClick)) {
        Text(initials(title), style = P.title.copy(color = P.dim), modifier = Modifier.align(Alignment.Center))
        if (!image.isNullOrBlank()) AsyncImage(image, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val r = rating?.toFloatOrNull()
        if (r != null && r > 0f) Box(Modifier.clip(RoundedCornerShape(bottomEnd = 14.dp)).background(P.blue).padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text(if (r >= 10) r.toInt().toString() else ((r * 10).toInt() / 10f).toString(), style = P.head.copy(fontSize = 15.sp))
        }
        if (progress != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(P.blue))
        }
    }
}

fun posterOf(it: Any) = (it as? Movie)?.poster ?: (it as? Series)?.poster
fun nameOf(it: Any) = (it as? Movie)?.name ?: (it as? Series)?.name.orEmpty()
fun ratingOf(it: Any) = (it as? Movie)?.rating ?: (it as? Series)?.rating

/** "Title (count)" with a red See All, then a row of posters. */
@Composable
fun PosterRow(title: String, items: List<Any>, onOpen: (Any) -> Unit, onSeeAll: (() -> Unit)?, width: Dp = 126.dp) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        RowTitle(title, onSeeAll)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(items.take(30), key = { (it as? Movie)?.let { m -> "m" + m.id } ?: ("s" + (it as Series).id) }) { m ->
                Poster(nameOf(m), posterOf(m), ratingOf(m), onClick = { onOpen(m) }, modifier = Modifier.width(width))
            }
        }
    }
}

@Composable
fun RowTitle(title: String, onSeeAll: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = P.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onSeeAll != null) Text("See All", style = P.head.copy(color = P.red), modifier = Modifier.padding(start = 12.dp).tap(onSeeAll))
    }
}

/** A channel's logo on a light plate (logos are drawn for light backgrounds). */
@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)), contentAlignment = Alignment.Center) {
        Text(initials(name), style = P.head.copy(color = P.dim))
        if (!url.isNullOrBlank()) AsyncImage(url, name, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
    }
}

/** A centred empty state: icon, title, a line of help. */
@Composable
fun Empty(icon: ImageVector, title: String, body: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 60.dp, horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, size = 52.dp, tint = P.dim)
        Spacer(Modifier.height(14.dp))
        Text(title, style = P.title)
        if (body != null) { Spacer(Modifier.height(6.dp)); Text(body, style = P.body.copy(color = P.dim), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
}

/**
 * The TV screens (Sports, Settings, Choose sports) laid out on their own canvas, 600 units across upright and 960
 * turned, so they keep their proportions inside the phone app.
 */
@Composable
fun TvCanvas(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val canvas = if (constraints.maxWidth > constraints.maxHeight) 960f else 600f
        CompositionLocalProvider(LocalDensity provides Density(constraints.maxWidth / canvas, d.fontScale)) { content() }
    }
}
