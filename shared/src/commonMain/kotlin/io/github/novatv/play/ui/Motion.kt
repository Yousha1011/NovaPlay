package io.github.novatv.play.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Quick to start, gentle to land: the curve phones with fast screens use for things arriving. */
val Settle = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Plays a short arrival each time [key] changes while [active]: the content fades in and glides [shift] into place,
 * along x when [sideways], else up. It only moves an existing drawing layer, so nothing is laid out or composed again
 * per frame, and it costs nothing at rest.
 */
@Composable
fun Modifier.arrive(key: Any?, active: Boolean = true, shift: Dp = 18.dp, sideways: Boolean = false, ms: Int = 240): Modifier {
    val p = remember { Animatable(1f) }
    LaunchedEffect(key, active) {
        if (!active) return@LaunchedEffect
        p.snapTo(0f)
        p.animateTo(1f, tween(ms, easing = Settle))
    }
    val px = with(LocalDensity.current) { shift.toPx() }
    return graphicsLayer {
        val v = p.value
        alpha = 0.15f + 0.85f * v
        if (sideways) translationX = (1f - v) * px else translationY = (1f - v) * px
    }
}
