package io.github.novatv.play.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * The window shows the same 1280-unit-wide canvas as the TV, scaled to its width (never smaller than 70% of normal,
 * so a narrow window scrolls rather than shrinking text out of reach).
 */
@Composable
actual fun NovaTheme(content: @Composable () -> Unit) {
    BoxWithConstraints {
        val d = LocalDensity.current
        val scale = (constraints.maxWidth / 1280f).coerceAtLeast(d.density * 0.7f)
        CompositionLocalProvider(LocalDensity provides Density(scale, d.fontScale)) { content() }
    }
}

/** Esc (or the mouse's back button) runs the newest enabled handler, as Back does on the TV. */
object BackKeys {
    private class Entry(val enabled: () -> Boolean, val run: () -> Unit)
    private val stack = mutableStateListOf<Entry>()

    fun back(): Boolean {
        val e = stack.lastOrNull { it.enabled() } ?: return false
        e.run(); return true
    }

    @Composable
    internal fun Register(enabled: Boolean, onBack: () -> Unit) {
        val on = rememberUpdatedState(enabled)
        val cb = rememberUpdatedState(onBack)
        DisposableEffect(Unit) {
            val e = Entry({ on.value }, { cb.value() })
            stack.add(e)
            onDispose { stack.remove(e) }
        }
    }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = BackKeys.Register(enabled, onBack)
