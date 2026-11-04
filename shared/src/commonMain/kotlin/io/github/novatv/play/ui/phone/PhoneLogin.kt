package io.github.novatv.play.ui.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.novatv.play.data.AccountSync
import io.github.novatv.play.res.logo
import io.github.novatv.play.ui.Text
import kotlinx.coroutines.launch

/** The phone's first page until it's logged in: Log in to a NovaPlay account, or Sign up for one. */
@Composable
fun PhoneLogin() {
    var signUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(30.dp))
        Image(org.jetbrains.compose.resources.painterResource(io.github.novatv.play.res.Res.drawable.logo), "NovaPlay", Modifier.size(170.dp))
        Text(if (signUp) "Create your account" else "Log in", style = P.head.copy(fontSize = 26.sp))
        Text(if (signUp) "Your playlists, favourites and settings on this phone go into the new account, and follow you to every device."
            else "Your playlists, favourites and settings come with your account.",
            style = P.small, textAlign = TextAlign.Center)
        Field("Username", name, "Username") { name = it }
        Field("Password", password, "Password", password = true) { password = it }
        if (signUp) Field("Password again", again, "Password again", password = true) { again = it }
        Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp)).background(P.blue).tap { submit() },
            contentAlignment = Alignment.Center) {
            Text(if (signUp) "Sign up" else "Log in", style = P.head.copy(fontSize = 18.sp))
        }
        Text(if (signUp) "I have an account — log in" else "New here? Create an account",
            style = P.body.copy(color = P.blue), modifier = Modifier.tap { signUp = !signUp; message = null; again = "" }.padding(8.dp))
        message?.let { Text(it, style = P.small, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(30.dp))
    }
}
