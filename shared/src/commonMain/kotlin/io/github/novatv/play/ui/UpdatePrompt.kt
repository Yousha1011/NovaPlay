package io.github.novatv.play.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import io.github.novatv.play.data.Updates

/** "NovaPlay 1.3 is available": Update or Not now, over everything while [Updates.offer] is set. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun UpdatePrompt() {
    val r = Updates.offer ?: return
    val progress = Updates.progress
    val first = remember { FocusRequester() }
    // A phone's back gesture.
    BackHandler(enabled = true) { Updates.cancel() }
    Box(Modifier.fillMaxSize().background(Color(0xE6000000))
        .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
        .onPreviewKeyEvent { e ->
            // Back is "Not now", on release so the screen underneath never sees it.
            if (e.key == Key.Back || e.key == Key.Escape) { if (e.type == KeyEventType.KeyUp) Updates.cancel(); true } else false
        }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).widthIn(max = 560.dp).clip(RoundedCornerShape(18.dp)).background(C.panel).padding(28.dp)) {
            Text("NovaPlay ${r.version} is available", style = T.h2)
            Text("You have ${Updates.CURRENT}." + when {
                r.fileUrl == null -> " Update opens the download page."
                r.size > 0 -> " The download is ${r.size / (1024 * 1024)} MB; your playlists and settings are kept."
                else -> " Your playlists and settings are kept."
            }, style = T.small)
            if (r.notes.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(r.notes.replace("**", "").replace("`", "").lines().filter { !it.startsWith("|") }.joinToString("\n").trim(),
                    style = T.small.copy(color = C.text), modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
            }
            Spacer(Modifier.height(20.dp))
            if (progress != null) {
                Text("Downloading… ${(progress * 100).toInt()}%", style = T.body)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
                    Box(Modifier.fillMaxWidth(progress).height(6.dp).background(C.accent))
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Update", onClick = { Updates.update() }, primary = true, modifier = Modifier.focusRequester(first))
                Pill("Not now", onClick = { Updates.cancel() })
            }
        }
    }
    LaunchedEffect(r, progress == null) { if (progress == null) try { first.requestFocus() } catch (e: Exception) { } }
}

/** Looks for a new release once the app is up. */
@Composable
fun UpdateCheck() {
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(3000); Updates.checkAtStart() }
}
