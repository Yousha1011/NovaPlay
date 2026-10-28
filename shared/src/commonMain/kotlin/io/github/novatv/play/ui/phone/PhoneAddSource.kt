package io.github.novatv.play.ui.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.data.SourceType
import io.github.novatv.play.res.Res
import io.github.novatv.play.res.logo
import io.github.novatv.play.toast
import io.github.novatv.play.ui.Icon
import io.github.novatv.play.ui.Text
import org.jetbrains.compose.resources.painterResource

/** The first screen on a new iPhone install: the logo and one button to add a playlist. */
@Composable
fun PhoneWelcome(nav: Nav) {
    Column(Modifier.fillMaxSize().background(P.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(Res.drawable.logo), "NovaPlay", Modifier.size(240.dp))
        Spacer(Modifier.height(18.dp))
        Text("Live TV, a TV guide, movies, series and a Sports centre for the playlists you already have.",
            style = P.body.copy(color = P.dim, fontSize = 17.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("NovaPlay comes with no channels: add your Xtream Codes login, M3U playlist or Stalker portal.",
            style = P.small, textAlign = TextAlign.Center)
        Spacer(Modifier.height(30.dp))
        BigButton("Add a playlist", Modifier.fillMaxWidth()) { nav.push(Route.AddSource) }
    }
}

/** Adding a playlist: its type from a drop-down, then the fields that type needs, at the phone's own size. */
@Composable
fun PhoneAddSource(nav: Nav) {
    var type by remember { mutableStateOf(SourceType.XTREAM) }
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("00:1A:79:") }
    var epg by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(P.background).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 62.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Add a playlist", style = P.large)
            Text("Use the details your IPTV provider gave you.", style = P.body.copy(color = P.dim))

            // The playlist type, as a drop-down.
            Column {
                FieldLabel("Playlist type")
                Row(Modifier.fillMaxWidth().height(54.dp).glass(RoundedCornerShape(16.dp)).tap { open = !open }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(type.label, style = P.head.copy(fontSize = 17.sp), modifier = Modifier.weight(1f))
                    Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, size = 24.dp)
                }
                if (open) Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.card)) {
                    SourceType.entries.forEachIndexed { i, t ->
                        if (i > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(P.line))
                        Row(Modifier.fillMaxWidth().tap { type = t; open = false }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.label, style = P.body.copy(fontSize = 17.sp), modifier = Modifier.weight(1f))
                            if (t == type) Icon(Icons.Rounded.Check, size = 22.dp, tint = P.blue)
                        }
                    }
                }
            }

            Field("Name (optional)", name, "My TV") { name = it }
            Field(when (type) { SourceType.XTREAM -> "Server URL"; SourceType.M3U -> "Playlist URL"; SourceType.STALKER -> "Portal URL" }, url,
                when (type) { SourceType.XTREAM -> "http://example.com:8080"; SourceType.M3U -> "http://example.com/playlist.m3u"; SourceType.STALKER -> "http://example.com:8080/c/" },
                KeyboardType.Uri) { url = it }
            if (type == SourceType.XTREAM) {
                Field("Username", user, "Username") { user = it }
                // Shown as typed, so a pasted password can be checked.
                Field("Password", pass, "Password") { pass = it }
            }
            if (type == SourceType.STALKER) Field("MAC address", mac, "00:1A:79:XX:XX:XX") { mac = it }
            Field(if (type == SourceType.XTREAM) "TV guide URL (optional)" else "TV guide (XMLTV) URL (optional)", epg, "http://example.com/epg.xml.gz", KeyboardType.Uri) { epg = it }
            if (type == SourceType.XTREAM) Text("The server's own TV guide is used when this is empty.", style = P.small)

            Spacer(Modifier.height(4.dp))
            BigButton("Save and load", Modifier.fillMaxWidth()) {
                val missing = when {
                    url.isBlank() -> "the URL"
                    type == SourceType.XTREAM && (user.isBlank() || pass.isBlank()) -> "the username and password"
                    type == SourceType.STALKER && mac.length < 17 -> "the full MAC address"
                    else -> null
                }
                if (missing != null) toast("Please enter $missing")
                else { App.repo.addSource(name, type, url, user, pass, mac, epg); nav.stack.clear() }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).height(46.dp)) {
            RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, onClick = { nav.pop() }, modifier = Modifier.align(Alignment.CenterStart))
            Brand(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
internal fun FieldLabel(text: String) = Text(text, style = P.head.copy(color = P.dim), modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))

/** A labelled text box at the phone's size (press and hold in it to paste). */
@Composable
internal fun Field(label: String, value: String, hint: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    Column {
        FieldLabel(label)
        Row(Modifier.fillMaxWidth().height(54.dp).glass(RoundedCornerShape(16.dp)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(hint, style = P.body.copy(color = Color(0x66FFFFFF), fontSize = 17.sp), maxLines = 1)
                BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, textStyle = P.body.copy(fontSize = 17.sp),
                    cursorBrush = SolidColor(P.blue), keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false))
            }
        }
    }
}

/** The blue capsule for a screen's main action. */
@Composable
fun BigButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.height(54.dp).clip(RoundedCornerShape(27.dp)).background(P.blue).tap(onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Add, size = 22.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = P.head.copy(fontSize = 18.sp))
    }
}
