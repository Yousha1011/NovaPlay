package io.github.novatv.play.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Windows: the arrow keys move focus, as the D-pad does on Android TV. Desktop Compose only moves focus with Tab.
 *
 * Put on the window's content. It sees a key only after the focused control and the screens around it have
 * let it pass (rail Right, Up to the rail, guide paging, text fields' Left and Right keep working as before), and
 * does nothing while [enabled] is false (the player and multiview handle their own keys).
 */
fun Modifier.arrowKeysMoveFocus(fm: FocusManager, enabled: () -> Boolean = { true }): Modifier = onKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown || !enabled()) return@onKeyEvent false
    if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed || e.isShiftPressed) return@onKeyEvent false
    val dir = when (e.key) {
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        else -> return@onKeyEvent false
    }
    fm.moveFocus(dir)
}
