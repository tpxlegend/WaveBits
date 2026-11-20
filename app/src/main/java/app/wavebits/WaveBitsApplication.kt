// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.wavebits

import android.app.Application
import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import app.wavebits.host.android.MediaLibrary
import app.wavebits.host.android.PlaybackService
import com.music.spotui.di.AutoBrowser
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Starts the Spotify session, the UI singletons and the playback service bindings. */
class WaveBitsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A crash or a freeze of the last run, back in this run's diagnostic report.
        runCatching { app.wavebits.host.android.LastExit.install(this) }
        runCatching { app.wavebits.power.EnergySettings.init(this) }
        runCatching { com.music.spotui.data.preferences.AppIconManager.init(this) }
        // Spotify session (hidden Web Player + API), caches, downloads, data saver.
        runCatching { Graph.init(this) }
        runCatching { SongPlayer.attachEngine(Graph.currentSongState) }
        // Headphones or Bluetooth gone: pause rather than play on the speaker.
        runCatching { com.music.spotui.di.NoisyAudio.register(this) }
        // Downloaded songs are no longer reported to Spotify: drop the old report queue,
        // whose replays sent play commands that took over the Web Player.
        runCatching { java.io.File(filesDir, "wrapped_queue.json").delete() }
        runCatching {
            PlaybackService.library = object : MediaLibrary {
                override suspend fun children(context: Context, parentId: String): List<MediaBrowserCompat.MediaItem> =
                    AutoBrowser.children(context, parentId)
                override suspend fun search(query: String): List<MediaBrowserCompat.MediaItem> = AutoBrowser.search(query)
            }
        }
        // The report's header: the extensions installed, with their versions.
        runCatching {
            app.wavebits.host.android.Logbook.headerExtras = {
                val all = com.music.spotui.extensions.Extensions.registry.value.extensions
                listOf("extensions" to (all.joinToString { "${it.id} ${it.version}${if (it.enabled) "" else " (off)"}" }.ifEmpty { "none" }))
            }
        }
        runCatching { prefetch() }
    }

    /**
     * Once the Web Player is signed in, the library and Liked Songs load in the background
     * (the home feed is loaded by the first screen): the tabs open on fresh content.
     */
    private fun prefetch() {
        if (!app.wavebits.power.EnergySettings.allowBackgroundPrefetch) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            app.wavebits.host.android.WaveBitsRuntime.api.ready.first { it }
            if (!app.wavebits.power.EnergySettings.allowBackgroundPrefetch) return@launch
            if (!getSharedPreferences("myspoti_prefs", MODE_PRIVATE).getBoolean("LoggedIn", false)) return@launch
            if (!app.wavebits.host.android.WaveBitsRuntime.awaitSignedIn(5_000)) return@launch
            if (!app.wavebits.power.EnergySettings.allowBackgroundPrefetch) return@launch
            val api = com.music.spotui.data.api.Api()
            launch { runCatching { api.getLibraryRoot().collect {} } }
            launch { runCatching { api.getLikedPage().collect {} } }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { com.bumptech.glide.Glide.get(this).trimMemory(level) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { com.bumptech.glide.Glide.get(this).clearMemory() }
    }
}
