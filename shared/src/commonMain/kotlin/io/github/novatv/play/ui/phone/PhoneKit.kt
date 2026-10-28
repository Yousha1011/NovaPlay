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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import coil3.compose.AsyncImage
import io.github.novatv.play.res.Res
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Check
import io.github.novatv.play.res.logo_mark
import io.github.novatv.play.res.playfair_display_600
import io.github.novatv.play.res.playfair_display_700
import io.github.novatv.play.res.manrope_400
import io.github.novatv.play.res.manrope_500
import io.github.novatv.play.res.manrope_600
import io.github.novatv.play.res.manrope_700
import io.github.novatv.play.data.Movie
import io.github.novatv.play.data.Series
import io.github.novatv.play.ui.Icon
import io.github.novatv.play.ui.Text
import io.github.novatv.play.ui.initials

/** A colour theme: the background's gradient, the cards, and two accents (buttons and highlights). */
class PhoneTheme(val id: String, val name: String, val top: Color, val bottom: Color, val card: Color, val card2: Color, val accent: Color, val highlight: Color)

/**
 * The iPhone's look: a themed gradient behind everything, cards a shade lighter, an accent for buttons and one for
 * highlights; Playfair Display for titles, Manrope for the rest.
 */
object P {
    val themes = listOf(
        PhoneTheme("midnight", "Midnight Blue", Color(0xFF101A3D), Color(0xFF05060F), Color(0xFF161D38), Color(0xFF232B4D), Color(0xFF5B8CFF), Color(0xFFFF5A6E)),
        PhoneTheme("onyx", "Onyx", Color(0xFF141416), Color(0xFF000000), Color(0xFF1C1C1E), Color(0xFF2C2C2E), Color(0xFF0A84FF), Color(0xFFFF453A)),
        PhoneTheme("plum", "Royal Plum", Color(0xFF2A1248), Color(0xFF09040F), Color(0xFF241638), Color(0xFF33214F), Color(0xFFB388FF), Color(0xFFFF7AB6)),
        PhoneTheme("emerald", "Emerald Night", Color(0xFF07342A), Color(0xFF010A08), Color(0xFF0E2A23), Color(0xFF173A31), Color(0xFF34D399), Color(0xFFFFC857)),
        PhoneTheme("wine", "Wine & Gold", Color(0xFF3A0A18), Color(0xFF0B0205), Color(0xFF2C0F18), Color(0xFF3D1824), Color(0xFFE8C27A), Color(0xFFFF8A65)),
        PhoneTheme("ocean", "Deep Ocean", Color(0xFF033B57), Color(0xFF010D14), Color(0xFF0A2C3E), Color(0xFF123A50), Color(0xFF4FC3F7), Color(0xFFFF7043)),
    )
    var theme by androidx.compose.runtime.mutableStateOf(themes[0])

    val bg get() = theme.bottom
    val background get() = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(theme.top, theme.bottom))
    val card get() = theme.card
    val card2 get() = theme.card2
    val line = Color(0x22FFFFFF)
    val text = Color.White
    val dim = Color(0xFFA6A8B4)
    val blue get() = theme.accent
    val red get() = theme.highlight
    val gold = Color(0xFFFFCC00)

    var serif: FontFamily = FontFamily.Serif
    var sans: FontFamily = FontFamily.Default

    val large get() = TextStyle(fontFamily = serif, fontSize = 30.sp, fontWeight = FontWeight.Normal, color = text)
    val title get() = TextStyle(fontFamily = serif, fontSize = 23.sp, fontWeight = FontWeight.Normal, color = text)
    val head get() = TextStyle(fontFamily = sans, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = text)
    val body get() = TextStyle(fontFamily = sans, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = text, lineHeight = 23.sp)
    val small get() = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.Normal, color = dim)
    val caps get() = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.Normal, color = dim, letterSpacing = 1.8.sp)
}

/** Loads the app's fonts and the saved theme; called once at the top of the iPhone app. */
@Composable
fun PhoneStyle() {
    // The iPhone's own San Francisco, as in IM Player, for headings and text alike.
    P.serif = FontFamily.Default
    P.sans = FontFamily.Default
    androidx.compose.runtime.LaunchedEffect(Unit) {
        P.themes.firstOrNull { it.id == io.github.novatv.play.App.store.phoneTheme }?.let { P.theme = it }
    }
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
fun Modifier.tap(onClick: () -> Unit): Modifier {
    // Pressed things squish a little and spring back, like pressing a drop of water.
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val k by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.95f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f), label = "squish")
    return graphicsLayer { scaleX = k; scaleY = k }.clickable(src, null, onClick = onClick)
}

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
    LazyRow(modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(items.take(30), key = { (it as? Movie)?.let { m -> "m" + m.id } ?: ("s" + (it as Series).id) }) { m ->
                Poster(nameOf(m), posterOf(m), ratingOf(m), onClick = { onOpen(m) }, modifier = Modifier.width(width))
            }
        }
    }
}

@Composable
fun RowTitle(title: String, onSeeAll: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
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

/** The NovaPlay logo and name, in bold, as at the top of every page. */
@Composable
fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(org.jetbrains.compose.resources.painterResource(Res.drawable.logo_mark), "NovaPlay", Modifier.size(34.dp))
        Spacer(Modifier.width(8.dp))
        Text("NovaPlay", style = P.title.copy(fontSize = 25.sp, fontWeight = FontWeight.Bold))
    }
}

/** A page's heading, the same size on every page. */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = P.large.copy(fontSize = 30.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))

/** A section label over a group of cards. */
@Composable
fun SectionLabel(text: String) = Text(text.uppercase(), style = P.caps, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))

class MenuOption(val label: String, val selected: Boolean = false, val icon: ImageVector? = null, val onClick: () -> Unit)

/** A round glass button that opens a frosted menu of options under it. */
@Composable
fun MenuButton(icon: ImageVector, options: List<MenuOption>) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        RoundIcon(icon, onClick = { open = true })
        if (open) androidx.compose.ui.window.Popup(alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(0, 140),
            onDismissRequest = { open = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
            Column(Modifier.width(260.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xF0262628)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp))
                .padding(vertical = 10.dp)) {
                for (o in options) Row(Modifier.fillMaxWidth().tap { open = false; o.onClick() }.padding(horizontal = 18.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(30.dp)) {
                        if (o.icon != null) Icon(o.icon, size = 22.dp) else if (o.selected) Icon(androidx.compose.material.icons.Icons.Rounded.Check, size = 20.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(o.label, style = P.body.copy(fontSize = 18.sp, color = if (o.icon != null && o.selected) P.blue else Color.White), maxLines = 1)
                }
            }
        }
    }
}

enum class SortBy(val label: String) { AZ("Sort A -> Z"), ZA("Sort Z -> A"), RATING("Sort by Rating"), ADDED("Sort by Recently Added"), PROVIDER("Sort by Provider Order") }

/** How the phone lists titles and channels: in a list or a grid. Kept for the session. */
object View {
    var grid by androidx.compose.runtime.mutableStateOf(true)
    var liveGrid by androidx.compose.runtime.mutableStateOf(false)
    var liveSort by androidx.compose.runtime.mutableStateOf(SortBy.PROVIDER)
}

@Composable
fun LayoutButton(grid: Boolean, set: (Boolean) -> Unit) = MenuButton(if (grid) androidx.compose.material.icons.Icons.Rounded.GridView else androidx.compose.material.icons.Icons.Rounded.ViewAgenda, listOf(
    MenuOption("List", !grid, androidx.compose.material.icons.Icons.Rounded.ViewAgenda) { set(false) },
    MenuOption("Grid", grid, androidx.compose.material.icons.Icons.Rounded.GridView) { set(true) },
))

@Composable
fun SortButton(current: SortBy, choices: List<SortBy>, set: (SortBy) -> Unit) =
    MenuButton(androidx.compose.material.icons.Icons.Rounded.SwapVert, choices.map { s -> MenuOption(s.label, s == current) { set(s) } })
