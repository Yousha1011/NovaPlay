package io.github.novatv.play.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.novatv.play.platform.Time
import io.github.novatv.play.platform.pad2

@Composable
fun Icon(v: ImageVector, size: Dp = 22.dp, tint: Color = C.text, modifier: Modifier = Modifier) {
    Image(v, null, modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/** A channel's logo on a soft plate, or its initials when there is none. */
@Composable
fun Logo(url: String?, name: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)), contentAlignment = Alignment.Center) {
        Text(initials(name), style = T.h2.copy(color = C.dim))
        if (!url.isNullOrBlank()) AsyncImage(url, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
    }
}

/** A poster (2:3) with title underneath and an optional watch-progress bar. */
@Composable
fun PosterCard(title: String, image: String?, sub: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
               progress: Float? = null, onLongClick: (() -> Unit)? = null) {
    Column(modifier) {
        Tile(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), onLongClick = onLongClick, scale = 1.07f) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.panelHi, C.panel))), contentAlignment = Alignment.Center) {
                Text(initials(title), style = T.hero.copy(color = C.faint))
            }
            if (!image.isNullOrBlank()) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (progress != null) Bar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!sub.isNullOrBlank()) Text(sub, style = T.small, maxLines = 1)
    }
}

@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier, color: Color = C.accent) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(color))
    }
}

@Composable
fun Badge(text: String, color: Color = C.live) {
    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(color).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, style = T.tiny.copy(color = Color.White))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = T.h2)
        if (trailing != null) Text(trailing, style = T.small)
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Canvas(modifier.size(size).rotate(a)) {
        drawArc(Brush.sweepGradient(listOf(Color.Transparent, C.accent)), 0f, 300f, false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun Message(title: String, body: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = T.h1)
        if (body != null) { Spacer(Modifier.height(8.dp)); Text(body, style = T.body.copy(color = C.dim)) }
        Spacer(Modifier.height(20.dp))
        content()
    }
}

fun initials(name: String): String = name.split(' ', '-', '|', ':').filter { it.isNotBlank() && it[0].isLetterOrDigit() }.take(2)
    .joinToString("") { it.take(1).uppercase() }.ifEmpty { "•" }

fun clock(t: Long): String = Time.hm(t)
fun dayLabel(t: Long): String {
    val d0 = Time.dayStart()
    return when ((t - d0).floorDiv(86_400_000L)) {
        0L -> "Today"; 1L -> "Tomorrow"; -1L -> "Yesterday"
        else -> Time.dayMonth(t)
    }
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "${s / 3600}:${pad2(s / 60 % 60)}:${pad2(s % 60)}" else "${s / 60}:${pad2(s % 60)}"
}

/** A glow behind the screen content: the app's soft blue-violet backdrop. */
@Composable
fun Backdrop(modifier: Modifier = Modifier) {
    // Drawn into its own layer once and reused: two full-screen gradients redrawn every frame were costly on a Fire TV.
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        drawRect(C.bg)
        drawCircle(Brush.radialGradient(listOf(C.accent.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.85f, 0f), radius = size.width * 0.6f),
            radius = size.width * 0.6f, center = Offset(size.width * 0.85f, 0f))
        drawCircle(Brush.radialGradient(listOf(C.accent2.copy(alpha = 0.10f), Color.Transparent), center = Offset(0f, size.height), radius = size.width * 0.5f),
            radius = size.width * 0.5f, center = Offset(0f, size.height))
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))
