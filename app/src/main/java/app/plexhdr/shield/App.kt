package app.plexhdr.shield

import android.app.Application
import app.plexhdr.shield.plex.PlexClient
import app.plexhdr.shield.services.Online
import app.plexhdr.shield.services.Settings
import app.plexhdr.shield.services.TitleMemory
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class App : Application(), SingletonImageLoader.Factory {
    lateinit var settings: Settings; private set
    lateinit var plex: PlexClient; private set
    lateinit var online: Online; private set
    lateinit var memory: TitleMemory; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        plex = PlexClient(this)
        online = Online(this, settings, plex)
        memory = TitleMemory(this, plex)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { plex.http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(512L shl 20).build() }
        .crossfade(true)
        .build()

    companion object {
        lateinit var instance: App; private set
        val settings get() = instance.settings
        val plex get() = instance.plex
        val online get() = instance.online
        val memory get() = instance.memory
    }
}
