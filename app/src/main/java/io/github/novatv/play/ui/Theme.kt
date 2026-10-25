package io.github.novatv.play.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** NovaPlay's palette: deep blue-black, one blue accent, red for live. */
object C {
    val bg = Color(0xFF080B12)
    val bg2 = Color(0xFF0E1320)
    val panel = Color(0xFF141A28)
    val panelHi = Color(0xFF1C2436)
    val line = Color(0x1FFFFFFF)
    val text = Color(0xFFF2F4F8)
    val dim = Color(0xFFA3ABBD)
    val faint = Color(0xFF6B7387)
    val accent = Color(0xFF5B8CFF)
    val accent2 = Color(0xFF8A6BFF)
    val live = Color(0xFFFF4D5E)
    val win = Color(0xFF3DDC97)
    val gold = Color(0xFFFFC857)
}

object T {
    val hero = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = C.text)
    val h1 = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = C.text)
    val h2 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = C.text)
    val body = TextStyle(fontSize = 17.sp, color = C.text)
    val small = TextStyle(fontSize = 15.sp, color = C.dim)
    val tiny = TextStyle(fontSize = 13.sp, color = C.faint, fontWeight = FontWeight.Medium)
}

/**
 * Lays the app out on a canvas 1280 units wide, whatever the TV reports: Fire TV says 1080p is "xhdpi" (960 units),
 * which made everything half again too large.
 */
@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val w = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
        androidx.compose.ui.unit.Density(w / 1280f, d.fontScale)) { Themed(content) }
}

@Composable
private fun Themed(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = Color.White, surface = C.panel, onSurface = C.text,
            background = C.bg, onBackground = C.text, surfaceVariant = C.panelHi, onSurfaceVariant = C.dim,
            border = C.line,
        ),
        content = content,
    )
}
