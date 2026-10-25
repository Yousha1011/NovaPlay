package io.github.novatv.play.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A focusable surface: lifts a little and gains a bright rim when focused. The base of every tile and button. */
@Composable
fun Tile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    scale: Float = 1.05f,
    color: Color = C.panel,
    focusedColor: Color = C.panelHi,
    focusedContent: Color = C.text,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = scale),
        colors = ClickableSurfaceDefaults.colors(containerColor = color, contentColor = C.text, focusedContainerColor = focusedColor,
            focusedContentColor = focusedContent, pressedContainerColor = focusedColor, pressedContentColor = focusedContent),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = shape)),
        content = content,
    )
}

/** A pill button: white with dark text when focused, like the TV's own buttons. */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, selected: Boolean = false, primary: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when { primary -> C.accent; selected -> C.accent.copy(alpha = 0.28f); else -> C.panel },
            contentColor = C.text, focusedContainerColor = Color.White, focusedContentColor = Color(0xFF0B0E14),
        ),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, size = 18.dp, tint = androidx.tv.material3.LocalContentColor.current); Spacer(Modifier.width(8.dp)) }
            Text(text, style = T.body.copy(color = androidx.tv.material3.LocalContentColor.current), maxLines = 1)
        }
    }
}

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

/**
 * A text box typed with the Fire TV's own keyboard (an EditText: fast, voice dictation with the remote's mic,
 * and typing from the Fire TV phone app). OK opens the keyboard.
 */
@Composable
fun InputField(value: String, hint: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, password: Boolean = false, onDone: () -> Unit = {}) {
    AndroidView(
        modifier = modifier.height(54.dp),
        factory = { ctx ->
            EditText(ctx).apply {
                setText(value)
                this.hint = hint
                isSingleLine = true
                textSize = 18f
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(0x80FFFFFF.toInt())
                setPadding(36, 0, 36, 0)
                inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                val bg = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 28f }
                fun paint(f: Boolean) { bg.setColor(if (f) 0x40FFFFFF else 0x1AFFFFFF); bg.setStroke(if (f) 4 else 2, if (f) 0xFFFFFFFF.toInt() else 0x40FFFFFF) }
                paint(false)
                background = bg
                setOnFocusChangeListener { _, f -> paint(f) }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { onChange(s?.toString() ?: "") }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                setOnEditorActionListener { v, action, _ ->
                    if (action == EditorInfo.IME_ACTION_DONE) {
                        (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                            .hideSoftInputFromWindow(v.windowToken, 0)
                        onDone(); true
                    } else false
                }
            }
        },
        // The same box can be reused for another field (Xtream URL, then M3U URL): keep its hint current.
        update = { it.hint = hint },
    )
}

fun initials(name: String): String = name.split(' ', '-', '|', ':').filter { it.isNotBlank() && it[0].isLetterOrDigit() }.take(2)
    .joinToString("") { it.take(1).uppercase() }.ifEmpty { "•" }

private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
fun clock(t: Long): String = synchronized(hm) { hm.format(Date(t)) }
fun dayLabel(t: Long): String {
    val d0 = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
    return when ((t - d0).floorDiv(86_400_000L)) {
        0L -> "Today"; 1L -> "Tomorrow"; -1L -> "Yesterday"
        else -> synchronized(dayFmt) { dayFmt.format(Date(t)) }
    }
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
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
