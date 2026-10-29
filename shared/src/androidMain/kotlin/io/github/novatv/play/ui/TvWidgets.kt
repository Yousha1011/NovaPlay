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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
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
 *
 * The remote moves over it as over any control: the box itself takes focus, and the EditText only gets focus while
 * typing (OK). An EditText that could take focus by itself sat outside Compose's sense of where things are, so Up
 * from the top of a page landed on it and nothing showed as focused. While typing, Up and Down leave the box for
 * the controls around it, Back stops typing, and Left and Right move the cursor.
 */
@Composable
actual fun InputField(value: String, hint: String, onChange: (String) -> Unit, modifier: Modifier, password: Boolean, onDone: () -> Unit) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val boxFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val ui = androidx.compose.runtime.remember { FieldState() }
    androidx.compose.foundation.layout.Box(
        modifier.height(54.dp)
            .focusRequester(boxFocus)
            .onFocusChanged { ui.boxFocused = it.isFocused; ui.paint() }
            .onKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.action == android.view.KeyEvent.ACTION_DOWN && (k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        k.keyCode == android.view.KeyEvent.KEYCODE_ENTER || k.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) { ui.start(); true }
                else false
            }
            .focusable(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
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
                    // Not focusable until OK is pressed on the box.
                    isFocusable = false; isFocusableInTouchMode = false
                    val bg = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 28f }
                    background = bg
                    val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    ui.edit = this
                    ui.paint = {
                        val f = ui.boxFocused || ui.editing
                        bg.setColor(if (f) 0x40FFFFFF else 0x1AFFFFFF); bg.setStroke(if (f) 4 else 2, if (f) 0xFFFFFFFF.toInt() else 0x40FFFFFF)
                    }
                    ui.start = {
                        ui.editing = true
                        isFocusable = true; isFocusableInTouchMode = true
                        requestFocus(); setSelection(text.length)
                        imm.showSoftInput(this, 0)
                        ui.paint()
                    }
                    ui.stop = {
                        imm.hideSoftInputFromWindow(windowToken, 0)
                        ui.editing = false
                        clearFocus()
                        isFocusable = false; isFocusableInTouchMode = false
                        try { boxFocus.requestFocus() } catch (e: Exception) { }
                        ui.paint()
                    }
                    ui.paint()
                    setOnKeyListener { _, code, e ->
                        when (fieldKey(code, e.action)) {
                            FieldKey.UP -> { ui.stop(); focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Up); true }
                            FieldKey.DOWN -> { ui.stop(); focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down); true }
                            // Back stops typing and leaves focus on the box; the next Back is the screen's own.
                            FieldKey.BACK -> { if (e.action == android.view.KeyEvent.ACTION_UP) ui.stop(); true }
                            FieldKey.SWALLOW -> true
                            FieldKey.EDIT -> false
                        }
                    }
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(s: android.text.Editable?) { onChange(s?.toString() ?: "") }
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    })
                    setOnEditorActionListener { _, action, _ ->
                        if (action == EditorInfo.IME_ACTION_DONE) { ui.stop(); onDone(); true } else false
                    }
                }
            },
            // The same box can be reused for another field (Xtream URL, then M3U URL): keep its hint current.
            update = { it.hint = hint },
        )
    }
}

/** A TV text box's state, shared between the Compose box and its EditText. */
private class FieldState {
    var boxFocused = false
    var editing = false
    var edit: EditText? = null
    var paint: () -> Unit = {}
    var start: () -> Unit = {}
    var stop: () -> Unit = {}
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

/** What a key does in a TV text box. */
enum class FieldKey { EDIT, UP, DOWN, BACK, SWALLOW }

/** Up/Down leave the box when pressed (their release is swallowed), Back lets go of it, everything else edits text. */
fun fieldKey(keyCode: Int, action: Int): FieldKey = when (keyCode) {
    android.view.KeyEvent.KEYCODE_DPAD_UP -> if (action == android.view.KeyEvent.ACTION_DOWN) FieldKey.UP else FieldKey.SWALLOW
    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (action == android.view.KeyEvent.ACTION_DOWN) FieldKey.DOWN else FieldKey.SWALLOW
    android.view.KeyEvent.KEYCODE_BACK -> FieldKey.BACK
    else -> FieldKey.EDIT
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)
