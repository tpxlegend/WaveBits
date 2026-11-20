package com.music.spotui.di

import android.app.Application
import app.wavebits.host.android.BackendMode
import app.wavebits.host.android.WaveBitsRuntime
import app.wavebits.innertube.YouTube
import app.wavebits.innertube.models.WatchEndpoint
import com.music.spotui.data.api.Api
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.launch

/** Manual replacement for Spotui's Hilt graph: app-wide singletons. */
object Graph {
    lateinit var app: Application
        private set

    fun init(application: Application) {
        app = application
        // The data saver first: on a metered network with it on, nothing may go out, not even the page.
        com.music.spotui.data.DataSaver.init(application)
        // The Spotify session: hidden Web Player + API (operation hashes refresh themselves).
        WaveBitsRuntime.start(application, loadPage = !com.music.spotui.data.DataSaver.active)
        // Restore active platform mode saved in preferences
        val savedMode = com.music.spotui.ui.platform.PlatformManager.getSavedBackendMode(application)
        when (savedMode) {
            BackendMode.YOUTUBE -> WaveBitsRuntime.setYouTubeMode(application)
            BackendMode.JIOSAAVN -> WaveBitsRuntime.setJioSaavnMode(application)
            BackendMode.SPOTIFY -> { /* default mode */ }
        }
        // Fallback resolver for YouTube Music mode via NewPipeExtractor and CDN fallback when InnerTube clients require ciphering or IP verification
        WaveBitsRuntime.youtube.ytPlayer.fallbackStreamResolver = { videoId, title, artist ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val directUrl = "https://www.youtube.com/watch?v=$videoId"
                    val directStream = runCatching {
                        com.music.spotui.video.YouTubeSource.info(directUrl)
                            .audioStreams.maxByOrNull { it.bitrate }?.content
                    }.getOrNull()
                    if (!directStream.isNullOrBlank()) return@runCatching directStream

                    var resolvedTitle = title
                    var resolvedArtist = artist
                    if (resolvedTitle.isBlank() || resolvedTitle.equals("YouTube Track", ignoreCase = true)) {
                        runCatching {
                            val nextItem = YouTube.next(
                                WatchEndpoint(videoId = videoId)
                            ).getOrNull()?.items?.firstOrNull()
                            if (nextItem != null) {
                                if (nextItem.title.isNotBlank()) resolvedTitle = nextItem.title
                                val nextArtist = nextItem.artists.joinToString(", ") { it.name }
                                if (nextArtist.isNotBlank()) resolvedArtist = nextArtist
                            }
                        }
                    }

                    if (resolvedTitle.isNotBlank()) {
                        val hit = runCatching {
                            com.music.spotui.video.YouTubeSource.findSong(resolvedTitle, resolvedArtist, 0L)
                                ?: com.music.spotui.video.YouTubeSource.findAudio(resolvedTitle, resolvedArtist, 0L)
                        }.getOrNull()
                        if (hit != null) {
                            val hitStream = runCatching {
                                com.music.spotui.video.YouTubeSource.info(hit.url)
                                    .audioStreams.maxByOrNull { it.bitrate }?.content
                            }.getOrNull()
                            if (!hitStream.isNullOrBlank()) return@runCatching hitStream
                        }
                        val cdnFallback = runCatching {
                            WaveBitsRuntime.jiosaavn.client.resolveStreamUrl("", resolvedTitle, resolvedArtist)
                        }.getOrNull()
                        if (!cdnFallback.isNullOrBlank()) return@runCatching cdnFallback
                    }
                    null
                }.getOrNull()
            }
        }
        // Lightweight in-memory / preference initializations needed before UI renders
        com.music.spotui.data.api.SupportReminder.init(application)
        com.music.spotui.data.preferences.LookPref.init(application)
        com.music.spotui.data.offline.OfflineStore.init(application)
        com.music.spotui.data.offline.OfflineCollections.init(application)
        com.music.spotui.data.local.ListenHistory.init(application)
        com.music.spotui.data.local.RecentPlayedStore.init(application)
        com.music.spotui.data.local.LocalPlaylists.init(application)
        com.music.spotui.data.local.LibraryOrder.init(application)
        runCatching { com.music.spotui.data.DataSaverGlide.install(application) }

        // Offload background disk/network work so Application.onCreate never lags the main thread
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.music.spotui.data.preferences.applyStreamingQuality(application) }
            runCatching { com.music.spotui.data.preferences.applyCrossfade(com.music.spotui.data.preferences.getCrossfadeSeconds(application)) }
            runCatching { SongPlayer.startLibraryOutbox(application) }
            runCatching { com.music.spotui.data.offline.OfflineCollections.refreshAll(application) }
            runCatching { com.music.spotui.data.offline.OfflineDownloader.resumePending() }
            runCatching { com.music.spotui.data.offline.PodcastDownloader.resumePending() }
            runCatching { com.music.spotui.extensions.Extensions.init(application) }
            runCatching { com.music.spotui.data.discord.DiscordRpcManager.init(application) }
        }
    }

    val api: Api by lazy { Api(app) }
    val repository: AppRepository by lazy { AppRepository(api) }
    val currentSongState: CurrentSongState by lazy { CurrentSongState() }
}
