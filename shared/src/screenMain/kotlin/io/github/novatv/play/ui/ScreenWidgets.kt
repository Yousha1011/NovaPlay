package io.github.novatv.play.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Windows and iPhone: the same look as on the TV, lit by focus (keyboard), hover (mouse) or press (touch). */
@OptIn(ExperimentalFoundationApi::class)
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
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val hovered by src.collectIsHoveredAsState()
    val lit = focused || hovered
    Box(
        modifier
            .graphicsLayer { val s = if (lit) scale else 1f; scaleX = s; scaleY = s }
            .clip(shape)
            .background(if (lit) focusedColor else color)
            .then(if (focused) Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), shape) else Modifier)
            .hoverable(src)
            .combinedClickable(interactionSource = src, indication = null, onLongClick = onLongClick, onClick = onClick),
        content = content,
    )
}

@Composable
actual fun Pill(text: String, onClick: () -> Unit, modifier: Modifier, icon: ImageVector?, selected: Boolean, primary: Boolean) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val hovered by src.collectIsHoveredAsState()
    val lit = focused || hovered
    val fg = if (lit) Color(0xFF0B0E14) else C.text
    Row(
        modifier
            .graphicsLayer { val s = if (lit) 1.06f else 1f; scaleX = s; scaleY = s }
            .clip(RoundedCornerShape(50))
            .background(when { lit -> Color.White; primary -> C.accent; selected -> C.accent.copy(alpha = 0.28f); else -> C.panel })
            .hoverable(src)
            .combinedClickableCompat(src, onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, size = 18.dp, tint = fg); Spacer(Modifier.width(8.dp)) }
        Text(text, style = T.body.copy(color = fg), maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(src: MutableInteractionSource, onClick: () -> Unit) =
    combinedClickable(interactionSource = src, indication = null, onClick = onClick)

@Composable
actual fun InputField(value: String, hint: String, onChange: (String) -> Unit, modifier: Modifier, password: Boolean, onDone: () -> Unit) {
    var text by remember { mutableStateOf(value) }
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    BasicTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        modifier = modifier.height(54.dp),
        singleLine = true,
        textStyle = T.body,
        cursorBrush = SolidColor(Color.White),
        interactionSource = src,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
            imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp))
                    .background(if (focused) Color(0x40FFFFFF) else Color(0x1AFFFFFF))
                    .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x40FFFFFF), RoundedCornerShape(27.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text(hint, style = T.body.copy(color = Color(0x80FFFFFF)), maxLines = 1)
                inner()
            }
        },
    )
}

/** Keeps [Modifier.focusable] reachable for screens that need a focus stop without a click. */
@Suppress("unused")
fun Modifier.focusStop(): Modifier = focusable()
