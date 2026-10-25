package io.github.novatv.play.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The few pieces each platform draws its own way: on Fire TV the TV library's focus-lit surfaces and the TV keyboard,
 * on Windows and iPhone versions that also answer the mouse, touch and hover.
 */

/** A focusable surface: lifts a little and gains a bright rim when focused. The base of every tile and button. */
@Composable
expect fun Tile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    scale: Float = 1.05f,
    color: Color = C.panel,
    focusedColor: Color = C.panelHi,
    focusedContent: Color = C.text,
    content: @Composable BoxScope.() -> Unit,
)

/** A pill button: white with dark text when focused, like the TV's own buttons. */
@Composable
expect fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, selected: Boolean = false, primary: Boolean = false)

/** A one-line text box. */
@Composable
expect fun InputField(value: String, hint: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, password: Boolean = false, onDone: () -> Unit = {})

/** Colours and the app's 1280-unit-wide canvas. */
@Composable
expect fun NovaTheme(content: @Composable () -> Unit)

/** Back (the remote's button, Esc on a keyboard) while [enabled]. */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)

/** Text in the app's styles. */
@Composable
fun Text(text: String, modifier: Modifier = Modifier, style: TextStyle = T.body, maxLines: Int = Int.MAX_VALUE,
         overflow: TextOverflow = TextOverflow.Clip, textAlign: TextAlign? = null) {
    BasicText(text, modifier, style = if (textAlign != null) style.copy(textAlign = textAlign) else style, maxLines = maxLines, overflow = overflow)
}
