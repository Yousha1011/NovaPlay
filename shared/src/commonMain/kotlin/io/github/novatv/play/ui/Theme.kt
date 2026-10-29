package io.github.novatv.play.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.novatv.play.ui.phone.P

/** Whether the big-screen app wears the iPhone's look (themes, glass, lighter type). Off: the TV and Windows look below. */
object Look { var modern = false }

/**
 * NovaPlay's big-screen palette: graphite surfaces, one restrained cyan accent, red only for live. Text colours keep at
 * least 4.5:1 contrast on [bg] and [panel]; with [Look.modern], the iPhone theme's colours instead.
 */
object C {
    val bg get() = if (Look.modern) P.theme.bottom else Color(0xFF0B0E13)
    val bg2 get() = if (Look.modern) P.theme.top else Color(0xFF10141B)
    /** The navigation rail. */
    val rail get() = if (Look.modern) Color(0xE0000000) else Color(0xFF0F131A)
    val panel get() = if (Look.modern) Color(0x17FFFFFF) else Color(0xFF161B23)
    val panelHi get() = if (Look.modern) Color(0x33FFFFFF) else Color(0xFF1F2630)
    val line = Color(0x1FFFFFFF)
    val text = Color(0xFFF2F4F7)
    val dim = Color(0xFFA6AEBA)
    val faint = Color(0xFF7E8794)
    val accent get() = if (Look.modern) P.theme.accent else Color(0xFF2CC8E0)
    /** Text and icons drawn on [accent]. */
    val onAccent = Color(0xFF061317)
    val accent2 = Color(0xFF8A6BFF)
    val live get() = if (Look.modern) P.theme.highlight else Color(0xFFEF4A55)
    val win = Color(0xFF3DDC97)
    val gold = Color(0xFFFFC857)
}

/** Type: bold, tight headings and plain body text. Sizes are in the 1280-unit canvas, so they scale with the screen. */
object T {
    private val head get() = if (Look.modern) FontWeight.Normal else FontWeight.Bold
    /** The home hero's title. */
    val display get() = TextStyle(fontSize = 46.sp, fontWeight = head, color = C.text, letterSpacing = (-0.5).sp, lineHeight = 52.sp)
    val hero get() = TextStyle(fontSize = 34.sp, fontWeight = head, color = C.text, letterSpacing = (-0.3).sp)
    val h1 get() = TextStyle(fontSize = 28.sp, fontWeight = head, color = C.text)
    val h2 get() = TextStyle(fontSize = 21.sp, fontWeight = head, color = C.text)
    val body get() = TextStyle(fontSize = 16.sp, color = C.text)
    val small get() = TextStyle(fontSize = 14.sp, color = C.dim)
    val tiny get() = TextStyle(fontSize = 12.sp, color = C.faint, fontWeight = if (Look.modern) FontWeight.Normal else FontWeight.Medium)
    /** Small spaced capitals over a title ("CONTINUE WATCHING", "MOVIE · HORROR"). */
    val eyebrow get() = TextStyle(fontSize = 12.sp, color = C.accent, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
}

/** Spacing and sizes shared by the big-screen screens. */
object S {
    /** The rail's width while closed; pages start after it. */
    val rail = 64.dp
    /** The rail's width while it has focus (labels showing). */
    val railOpen = 210.dp
    /** Space between a page's edge and its content. */
    val gutter = 40.dp
    val rowGap = 28.dp
    val cardGap = 14.dp
    val focusWidth = 2.5.dp
}
