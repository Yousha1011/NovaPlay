package io.github.novatv.play.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object SportsLook {
    val Card = Color(0xAD1E212C)
    val CardRaised = Color(0xB8282C3A)
    val Border = Color.White.copy(alpha = 0.20f)
    val Divider = Color.White.copy(alpha = 0.08f)
    val Dim = Color(0xFF8B90A0)
    val Faint = Color(0xFF5D6170)
    val Live = Color(0xFFE53935)
    val Flash = Color(0xFFFFD54A)
    val Ink = Color(0xFF14171E)
    val Gold = Color(0xFFF5C400)
    val Shape = RoundedCornerShape(14.dp)
    val SmallShape = RoundedCornerShape(10.dp)
}

/** Converts a team hex string like "a40227" into a readable Color on dark backgrounds. */
fun parseTeamColor(hex: String?): Color {
    if (hex.isNullOrBlank()) return Color(0xFF2E3440)
    val clean = hex.removePrefix("#").trim()
    val raw = clean.toLongOrNull(16) ?: return Color(0xFF2E3440)
    val argb = if (clean.length <= 6) 0xFF000000L or raw else raw
    val c = Color(argb)
    return if (c.luminance() > 0.70f) {
        lerp(c, Color.Black, 0.40f)
    } else c
}

/** Converts a team Long color value into a readable Color. */
fun parseTeamColor(colorLong: Long?): Color {
    if (colorLong == null) return Color(0xFF2E3440)
    val c = Color(colorLong)
    return if (c.luminance() > 0.70f) {
        lerp(c, Color.Black, 0.40f)
    } else c
}

/** Tints a team's color towards dark background so it reads as a rich background band. */
fun bandColor(c: Color): Color =
    lerp(c, SportsLook.Ink, if (c.luminance() > 0.5f) 0.55f else if (c.luminance() > 0.25f) 0.28f else 0.10f)

/** Card frame matching NuvioPlus: dark translucent surface, rounded corners, focus scale and ring. */
fun Modifier.sportsFrame(
    focused: Boolean,
    playing: Boolean = false,
    accent: Color = SportsLook.Gold,
    radius: Dp = 14.dp,
    background: Color = SportsLook.Card
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .graphicsLayer {
            val s = if (focused) 1.02f else 1f
            scaleX = s
            scaleY = s
        }
        .clip(shape)
        .background(background)
        .border(
            width = if (focused) 2.dp else if (playing) 1.5.dp else 1.dp,
            color = if (focused) accent else if (playing) Color.White.copy(alpha = 0.8f) else SportsLook.Border,
            shape = shape
        )
}
