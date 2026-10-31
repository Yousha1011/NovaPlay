package io.github.novatv.play.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusGroup
import io.github.novatv.play.data.Parental

/**
 * Asking for a PIN: [ask] puts the pad up over everything; [done] gets the digits typed, or null when cancelled.
 * Only one is shown at a time.
 */
object Pin {
    class Request(val title: String, val hint: String?, val done: (String?) -> Unit)
    var request by mutableStateOf<Request?>(null)
        private set

    fun ask(title: String, hint: String? = null, done: (String?) -> Unit) { request = Request(title, hint, done) }

    /** Asks for the saved PIN; [ok] runs once it's typed correctly (a wrong one says so and asks again). */
    fun check(title: String, ok: () -> Unit) {
        ask(title) { pin ->
            if (pin == null) return@ask
            if (Parental.hash(pin) == io.github.novatv.play.App.store.parentalPin) ok() else { io.github.novatv.play.toast("Wrong PIN"); check(title, ok) }
        }
    }

    /** Asks for a new PIN twice; [set] gets it when both match. */
    fun choose(set: (String) -> Unit) {
        ask("Choose a PIN", "4 to 8 digits") { a ->
            if (a == null) return@ask
            if (!Parental.validPin(a)) { io.github.novatv.play.toast("Use 4 to 8 digits"); choose(set); return@ask }
            ask("Type it again", null) { b ->
                if (b == null) return@ask
                if (a == b) set(a) else { io.github.novatv.play.toast("The PINs didn't match"); choose(set) }
            }
        }
    }

    internal fun finish(pin: String?) { val r = request; request = null; r?.done?.invoke(pin) }
}

/** The pad, drawn by Root over the whole app while [Pin.request] is set. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PinPad() {
    val r = Pin.request ?: return
    var digits by remember(r) { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val type = { d: Char -> if (digits.length < 8) digits += d }
    Box(Modifier.fillMaxSize().background(Color(0xE6000000))
        // Focus stays on the pad until it's closed; Back closes it, number keys type.
        .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
        .onPreviewKeyEvent { e ->
            // Back closes the pad on release, so the screen underneath never sees it.
            if (e.type == KeyEventType.KeyUp && (e.key == Key.Back || e.key == Key.Escape)) { Pin.finish(null); return@onPreviewKeyEvent true }
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val n = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
                Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9)
                .indexOf(e.key)
            when {
                n >= 0 -> { type('0' + n % 10); true }
                e.key == Key.Back || e.key == Key.Escape -> true
                e.key == Key.Backspace || e.key == Key.Delete -> { digits = digits.dropLast(1); true }
                else -> false
            }
        }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp).clip(RoundedCornerShape(18.dp)).background(C.panel).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(r.title, style = T.h2, textAlign = TextAlign.Center)
            r.hint?.let { Text(it, style = T.small) }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(18.dp)) {
                repeat(maxOf(4, digits.length)) { i ->
                    Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < digits.length) C.accent else C.line))
                }
            }
            Spacer(Modifier.height(22.dp))
            for (row in listOf("123", "456", "789", "<0>")) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    for (k in row) {
                        val label = when (k) { '<' -> "⌫"; '>' -> "OK"; else -> "$k" }
                        Pill(label, onClick = {
                            when (k) { '<' -> digits = digits.dropLast(1); '>' -> Pin.finish(digits); else -> type(k) }
                        }, primary = k == '>', modifier = Modifier.width(110.dp).let { if (k == '1') it.focusRequester(first) else it })
                    }
                }
            }
            Text("Number buttons on the remote type too. BACK cancels.", style = T.tiny)
        }
    }
    LaunchedEffect(r) { try { first.requestFocus() } catch (e: Exception) { } }
}
