package io.github.novatv.play

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import io.github.novatv.play.data.Epg
import io.github.novatv.play.data.Http
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.data.Repo
import io.github.novatv.play.data.Store

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        repo = Repo(this, store, Epg(this))
        repo.start()
    }

    /** Logos and posters: a memory cache sized for a TV and a 250 MB disk cache. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Http.client)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L * 1024 * 1024).build() }
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()

    companion object {
        lateinit var instance: App
            private set
        lateinit var store: Store
            private set
        lateinit var repo: Repo
            private set
        /** What PlayerActivity plays next (lists of channels are too big for an Intent). */
        @Volatile var pending: PlayItem? = null
    }
}
