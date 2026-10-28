package io.github.novatv.play.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.novatv.play.ui.phone.P

/** Whether the big-screen app wears the iPhone's look (themes, glass, lighter type). On for the Fire TV. */
object Look { var modern = false }

/** NovaPlay's palette: deep blue-black, one blue accent, red for live; with [Look.modern], the chosen theme's. */
object C {
    val bg get() = if (Look.modern) P.theme.bottom else Color(0xFF080B12)
    val bg2 get() = if (Look.modern) P.theme.top else Color(0xFF0E1320)
    val panel get() = if (Look.modern) Color(0x17FFFFFF) else Color(0xFF141A28)
    val panelHi get() = if (Look.modern) Color(0x33FFFFFF) else Color(0xFF1C2436)
    val line = Color(0x1FFFFFFF)
    val text = Color(0xFFF2F4F8)
    val dim = Color(0xFFA3ABBD)
    val faint = Color(0xFF6B7387)
    val accent get() = if (Look.modern) P.theme.accent else Color(0xFF5B8CFF)
    val accent2 = Color(0xFF8A6BFF)
    val live get() = if (Look.modern) P.theme.highlight else Color(0xFFFF4D5E)
    val win = Color(0xFF3DDC97)
    val gold = Color(0xFFFFC857)
}

object T {
    private val head get() = if (Look.modern) FontWeight.Normal else FontWeight.SemiBold
    val hero get() = TextStyle(fontSize = 40.sp, fontWeight = head, color = C.text)
    val h1 get() = TextStyle(fontSize = 30.sp, fontWeight = head, color = C.text)
    val h2 get() = TextStyle(fontSize = 22.sp, fontWeight = head, color = C.text)
    val body get() = TextStyle(fontSize = 17.sp, color = C.text)
    val small get() = TextStyle(fontSize = 15.sp, color = C.dim)
    val tiny get() = TextStyle(fontSize = 13.sp, color = C.faint, fontWeight = if (Look.modern) FontWeight.Normal else FontWeight.Medium)
}
