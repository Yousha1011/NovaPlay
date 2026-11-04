package io.github.novatv.play.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.novatv.play.data.AccountSync
import io.github.novatv.play.res.logo
import kotlinx.coroutines.launch

/**
 * The page before everything else until this device is logged in: Log in to an existing NovaPlay account (its
 * playlists and settings come with it), or Sign up, which makes one holding what this device has.
 */
@Composable
fun LoginScreen() {
    var signUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    fun submit() {
        if (busy) return
        if (signUp && password != again) { message = "The passwords don't match"; return }
        busy = true
        message = if (signUp) "Creating your account…" else "Logging in…"
        scope.launch {
            message = if (signUp) AccountSync.signUp(name, password) else AccountSync.logIn(name, password)
            busy = false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(620.dp)) {
            androidx.compose.foundation.Image(org.jetbrains.compose.resources.painterResource(io.github.novatv.play.res.Res.drawable.logo), "NovaPlay",
                Modifier.size(200.dp))
            Spacer(Modifier.height(6.dp))
            Text(if (signUp) "Create your NovaPlay account" else "Log in to NovaPlay", style = T.h1)
            Spacer(Modifier.height(6.dp))
            Text(if (signUp) "Your playlists, favourites and settings on this device go into the new account, and follow you to every device you log in on."
                else "Your playlists, favourites and settings come with your account.", style = T.small)
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InputField(name, "Username", { name = it }, Modifier.fillMaxWidth().focusRequester(first))
                InputField(password, "Password", { password = it }, Modifier.fillMaxWidth(), password = true,
                    onDone = { if (!signUp) submit() })
                if (signUp) InputField(again, "Password again", { again = it }, Modifier.fillMaxWidth(), password = true, onDone = { submit() })
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(if (signUp) "Sign up" else "Log in", onClick = { submit() }, primary = true)
                Pill(if (signUp) "I have an account" else "Create an account", onClick = {
                    signUp = !signUp; message = null; again = ""
                })
            }
            message?.let { Spacer(Modifier.height(14.dp)); Text(it, style = T.body.copy(color = C.dim)) }
        }
    }
    AutoFocus(first)
}
