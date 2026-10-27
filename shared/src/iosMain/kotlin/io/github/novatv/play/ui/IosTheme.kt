package io.github.novatv.play.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * The iPhone shows the TV's canvas 960 units wide in landscape (a little larger than the TV's 1280, as the screen is
 * held closer) and 600 upright, clear of the notch and the home bar.
 */
@Composable
actual fun NovaTheme(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        val d = LocalDensity.current
        // Landscape: 960 units across. Upright: 600, so text stays readable on the narrow screen.
        val canvas = if (constraints.maxWidth > constraints.maxHeight) 960f else 600f
        CompositionLocalProvider(LocalDensity provides Density(constraints.maxWidth / canvas, d.fontScale)) { content() }
    }
}

/** The iPhone has no Back key: screens opened over a tab show a Back button instead. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {}
