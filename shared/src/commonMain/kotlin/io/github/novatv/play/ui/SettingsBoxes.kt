package io.github.novatv.play.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Collects a Settings pane's rows, split by its section headings, and lays each section out as one rounded box under
 * its heading. The panes stay plain lists of rows; only the drawing around them changes.
 */
internal class SettingsBoxes : LazyListScope {
    private class Row(val key: Any?, val type: Any?, val content: @Composable LazyItemScope.() -> Unit)
    private class Group(val title: String?) { val rows = mutableListOf<Row>() }
    private val groups = mutableListOf<Group>()

    fun start(title: String) { groups += Group(title) }
    private fun current() = groups.lastOrNull() ?: Group(null).also { groups += it }

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        current().rows += Row(key, contentType, content)
    }

    override fun items(count: Int, key: ((index: Int) -> Any)?, contentType: (index: Int) -> Any?, itemContent: @Composable LazyItemScope.(index: Int) -> Unit) {
        for (i in 0 until count) current().rows += Row(key?.invoke(i), contentType(i)) { itemContent(i) }
    }

    fun emit(into: LazyListScope) {
        for ((g, group) in groups.withIndex()) {
            if (group.rows.isEmpty()) continue
            if (group.title != null) into.item(key = "box-title-$g-${group.title}") { BoxTitle(group.title, first = g == 0) }
            else if (g > 0) into.item(key = "box-gap-$g") { Spacer(Modifier.height(18.dp)) }
            val last = group.rows.lastIndex
            group.rows.forEachIndexed { i, r ->
                into.item(key = r.key, contentType = r.type) {
                    val top = if (i == 0) 16.dp else 0.dp
                    val bottom = if (i == last) 16.dp else 0.dp
                    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
                    Column(Modifier.fillMaxWidth().clip(shape).background(BoxFill)
                        .padding(start = 8.dp, end = 8.dp, top = if (i == 0) 8.dp else 0.dp, bottom = if (i == last) 8.dp else 0.dp)) {
                        Box(Modifier.padding(vertical = 3.dp)) { r.content(this@item) }
                    }
                }
            }
        }
    }
}

private val BoxFill = Color(0xFF151A22)

/** A section heading above its box: an accent tick, the name in small capitals. */
@Composable
private fun BoxTitle(t: String, first: Boolean) {
    Row(Modifier.padding(top = if (first) 4.dp else 22.dp, bottom = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(Brush.verticalGradient(listOf(C.accent, C.accent.copy(alpha = 0.4f)))))
        Spacer(Modifier.width(10.dp))
        Text(t.uppercase(), style = T.small.copy(color = C.text, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp))
    }
}

/** Lays [build]'s rows out in boxes, one per section ([section] headings). */
internal fun LazyListScope.boxed(build: LazyListScope.() -> Unit) {
    val b = SettingsBoxes(); b.build(); b.emit(this)
}
