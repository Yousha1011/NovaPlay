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
        // Errors nothing caught: written down (CrashLog, shown in Settings › About), then Android closes the app as usual.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            io.github.novatv.play.data.CrashLog.record(e, "${android.os.Build.MODEL}, ${t.name}")
            previous?.uncaughtException(t, e)
        }
        migratePrefs()
        // TVs use the big-screen look (graphite and cyan); the phone draws its own screens with its themes.
        io.github.novatv.play.ui.Look.modern = false
        val recorder = AndroidRecorder(this)
        val preview = if (ExoPreviewEngine.supported(this)) ExoPreviewEngine(this) else null
        App.start(object : Host {
            override val device = if (PHONE) Device.PHONE else Device.TV
            override val recorder = recorder
            override val previewEngine = preview
            override fun play(item: PlayItem) {
                // Full screen grows out of the TV window at the top right (PlayerActivity shrinks back into it).
                val grow = android.app.ActivityOptions.makeCustomAnimation(this@NovaApp, R.anim.player_grow, R.anim.player_stay).toBundle()
                startActivity(Intent(this@NovaApp, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), grow)
            }
            override fun toast(msg: String) { Toast.makeText(this@NovaApp, msg, Toast.LENGTH_SHORT).show() }
            override fun openUrl(url: String) { openApp(emptyList(), url) }
            override fun openApp(packages: List<String>, web: String, name: String) = openOfficialApp(this@NovaApp, packages, web, name, tv = !PHONE)
            override val backgroundSync = true
            override fun scheduleOvernight(enabled: Boolean, hour: Int) = Overnight.schedule(this@NovaApp, enabled, hour)
            override fun runOvernightNow() = SyncService.start(this@NovaApp)
            override val updateAsset = Installer.asset
            override val lowMemory = android.app.ActivityManager.MemoryInfo().also {
                getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
            }.totalMem < 2048L * 1024 * 1024
            override fun installUpdate(file: String) = Installer.install(this@NovaApp, java.io.File(file))
        })
        Diagnostics.start(this)
        // Re-armed at every start: alarms don't survive an update or a restart (the boot receiver starts the app).
        Overnight.schedule(this, App.store.overnightSync, App.store.overnightHour)
        // Alarms are lost when the app is updated or the device restarts.
        recorder.rearm()
    }

    /** Android asks apps to give memory back: the preview's idle player goes (nothing playing is touched). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        io.github.novatv.play.data.Diag.note("Android asked for memory back (level $level)")
        if (level < TRIM_MEMORY_RUNNING_LOW) return
        val released = (App.host.previewEngine as? ExoPreviewEngine)?.holder?.releaseIfIdle() ?: false
        if (released) android.util.Log.i("NovaPreview", "memory trim (level $level): idle preview player released")
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
        // Addon streams that need their own Referer or User-Agent (see StreamHeaders): added to every request to
        // their host, the playlist's segments included.
        .addInterceptor { chain ->
            val req = chain.request()
            val extra = io.github.novatv.play.data.StreamHeaders.forUrl(req.url.toString())
            if (extra.isNullOrEmpty()) chain.proceed(req)
            else chain.proceed(req.newBuilder().apply { extra.forEach { (k, v) -> header(k, v) } }.build())
        }
        .build()
}

/**
 * Opens the first of [packages] installed (its TV entry first), else on a phone [web]. False when nothing opened (on a
 * TV, an app that isn't installed): the caller then names [name] to install.
 */
fun openOfficialApp(ctx: android.content.Context, packages: List<String>, web: String, name: String, tv: Boolean): Boolean {
    val pm = ctx.packageManager
    for (p in packages) {
        val i = pm.getLeanbackLaunchIntentForPackage(p) ?: pm.getLaunchIntentForPackage(p) ?: continue
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
    }
    // Not installed. A TV says so (the caller names the app to install): a Fire TV has no browser (a web page opens
    // blank) and its Appstore lacks many of these apps or drops back home. A phone opens the service's site.
    if (tv) return false
    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (i.resolveActivity(pm) == null) return false
    return runCatching { ctx.startActivity(i) }.isSuccess
}
