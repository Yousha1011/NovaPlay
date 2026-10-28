package io.github.novatv.play.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlin.random.Random

/** Bumped whenever Continue watching or Recently watched is cleared, so the screens that show them redraw. */
object History { var rev by mutableIntStateOf(0) }

private class Grain(val x: Float, val y: Float, val speed: Float, val phase: Float, val r: Float, val shade: Float)

/**
 * Crumbles the thing into dust when [go] turns true: it is eaten away from left to right while the grains drift up and
 * away on the wind, then [onGone] is called (to take it out of the list).
 */
@Composable
fun Modifier.dust(go: Boolean, onGone: () -> Unit): Modifier {
    val t = remember { Animatable(0f) }
    val gone by rememberUpdatedState(onGone)
    LaunchedEffect(go) {
        if (go) { t.animateTo(1f, tween(1100, easing = LinearEasing)); gone() } else t.snapTo(0f)
    }
    val grains = remember {
        List(260) { Grain(Random.nextFloat(), Random.nextFloat(), 0.6f + Random.nextFloat(), Random.nextFloat() * 6.28f,
            0.8f + Random.nextFloat() * 1.8f, Random.nextFloat()) }
    }
    return drawWithContent {
        val p = t.value
        if (p <= 0f) { drawContent(); return@drawWithContent }
        val edge = p * 1.8f
        clipRect(left = (edge * size.width).coerceAtMost(size.width)) { this@drawWithContent.drawContent() }
        for (g in grains) {
            val age = edge - g.x
            if (age <= 0f) continue
            val a = (1f - age * 1.3f).coerceIn(0f, 1f)
            if (a <= 0f) continue
            val dx = age * size.width * 0.4f * g.speed + sin(g.phase + age * 9f) * 5f * density
            val dy = -age * size.height * 0.35f * g.speed
            val c = 0.62f + g.shade * 0.33f
            drawCircle(Color(c, c * 0.96f, c * 0.9f, a), radius = g.r * density, center = Offset(g.x * size.width + dx, g.y * size.height + dy))
        }
    }
}

/** The big-screen question when a title or channel is held: remove it, or not. */
@Composable
fun RemoveDialog(name: String, from: String, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(22.dp)).background(C.bg2).padding(28.dp)) {
            Text(name, style = T.h2, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            Text("Remove it from $from?", style = T.small)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Remove", onClick = { onDismiss(); onRemove() }, primary = true, modifier = Modifier.focusRequester(first))
                Pill("Cancel", onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
