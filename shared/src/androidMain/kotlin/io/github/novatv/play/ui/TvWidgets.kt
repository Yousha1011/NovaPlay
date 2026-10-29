package io.github.novatv.play.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme

@Composable
actual fun Tile(
    onClick: () -> Unit,
    modifier: Modifier,
    onLongClick: (() -> Unit)?,
    shape: Shape,
    scale: Float,
    color: Color,
    focusedColor: Color,
    focusedContent: Color,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        // A small lift: the cyan ring does the work, so big posters don't crowd their neighbours.
        scale = ClickableSurfaceDefaults.scale(focusedScale = minOf(scale, 1.04f)),
        colors = ClickableSurfaceDefaults.colors(containerColor = color, contentColor = C.text, focusedContainerColor = focusedColor,
            focusedContentColor = focusedContent, pressedContainerColor = focusedColor, pressedContentColor = focusedContent),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = if (Look.modern) 0.14f else 0.06f)), shape = shape),
            focusedBorder = Border(BorderStroke(S.focusWidth, if (Look.modern) Color.White.copy(alpha = 0.9f) else C.accent), inset = (-3).dp, shape = shape)),
        content = content,
    )
}

@Composable
actual fun Pill(text: String, onClick: () -> Unit, modifier: Modifier, icon: ImageVector?, selected: Boolean, primary: Boolean) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        // Buttons light up cyan with dark text when focused; the primary one is cyan already and gains a white ring.
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when { primary -> C.accent; selected -> C.accent.copy(alpha = 0.22f); else -> C.panel },
            contentColor = if (primary) C.onAccent else C.text, focusedContainerColor = C.accent, focusedContentColor = C.onAccent,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (primary) Color.Transparent else C.line), shape = RoundedCornerShape(50)),
            focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = if (primary) 0.95f else 0f)), inset = (-3).dp, shape = RoundedCornerShape(50))),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, size = 18.dp, tint = LocalContentColor.current); Spacer(Modifier.width(8.dp)) }
            Text(text, style = T.body.copy(color = LocalContentColor.current), maxLines = 1)
        }
    }
}

/**
 * A text box typed with the Fire TV's own keyboard (an EditText: fast, voice dictation with the remote's mic,
 * and typing from the Fire TV phone app). OK opens the keyboard.
 */
@Composable
actual fun InputField(value: String, hint: String, onChange: (String) -> Unit, modifier: Modifier, password: Boolean, onDone: () -> Unit) {
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

/**
 * Lays the app out on a canvas 1280 units wide, whatever the TV reports: Fire TV says 1080p is "xhdpi" (960 units),
 * which made everything half again too large.
 */
@Composable
actual fun NovaTheme(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val w = LocalContext.current.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
    CompositionLocalProvider(LocalDensity provides Density(w / 1280f, d.fontScale)) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = C.accent, onPrimary = Color.White, surface = C.panel, onSurface = C.text,
                background = C.bg, onBackground = C.text, surfaceVariant = C.panelHi, onSurfaceVariant = C.dim,
                border = C.line,
            ),
            content = content,
        )
    }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)
