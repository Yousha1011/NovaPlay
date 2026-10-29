package io.github.novatv.play

import android.app.Application
import android.content.Intent
import android.widget.Toast
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.platform.Device
import io.github.novatv.play.platform.Host
import io.github.novatv.play.platform.Prefs
import io.github.novatv.play.platform.appContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The Fire TV or phone app: starts the shared app with the Android player and messages. */
class NovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        migratePrefs()
        // TVs use the big-screen look (graphite and cyan); the phone draws its own screens with its themes.
        io.github.novatv.play.ui.Look.modern = false
        App.start(object : Host {
            override val device = if (PHONE) Device.PHONE else Device.TV
            override fun play(item: PlayItem) {
                startActivity(Intent(this@NovaApp, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            override fun toast(msg: String) { Toast.makeText(this@NovaApp, msg, Toast.LENGTH_SHORT).show() }
        })
    }

    /** Earlier versions kept favourites and Sports picks as Android string sets: moved once to the shared format. */
    private fun migratePrefs() {
        val p = Prefs()
        for (k in listOf("favs", "sportsLeagues", "favTeams", "sportsEnabled", "sportsComps")) {
            val old = p.legacySet(k) ?: continue
            if (p.getString("$k.json", null) == null) p.putString("$k.json", JSONArray(old.toList()).toString())
            p.remove(k)
        }
    }
}

/** The player's own HTTP client (ExoPlayer streams through OkHttp). */
object TvHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}
