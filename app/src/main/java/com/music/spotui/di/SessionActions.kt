package com.music.spotui.di

import app.wavebits.host.android.BackendMode
import app.wavebits.host.android.WaveBitsRuntime
import app.wavebits.host.android.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/** Session-wide actions the screens trigger. */
object SessionActions {
    private val _signedOut = MutableStateFlow(false)
    /** True from a logout until the next sign-in: the activity shows the login page, then starts over. */
    val signedOut: StateFlow<Boolean> = _signedOut

    /**
     * Signs out and drops everything tied to the account, so the next account on
     * this phone starts clean: prefs and in-memory caches, the saved screens,
     * the listen history (the Recap), and the Web Player's cookies, Web storage
     * and cache (in the host).
     */
    fun logout(context: android.content.Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("myspoti_prefs", android.content.Context.MODE_PRIVATE)
        val activeMode = WaveBitsRuntime.backendMode.value

        // Nothing of the account keeps playing or showing: local songs, the clip, the notification.
        LocalPlayer.stop()
        com.music.spotui.video.MusicVideoController.clearSession()
        PlaybackService.end()
        runCatching { WaveBitsRuntime.scope.launch { runCatching { WaveBitsRuntime.api.player.pause() } } }

        when (activeMode) {
            BackendMode.YOUTUBE -> {
                prefs.edit()
                    .putBoolean("YouTubeLoggedIn", false)
                    .putBoolean("LoggedIn", false)
                    .remove("YouTubeName")
                    .remove("YouTubeAvatar")
                    .remove("BackendMode")
                    .apply()
                runCatching { WaveBitsRuntime.youtubeAccount.signOut() }
                com.music.spotui.data.api.Api.HomeCache.clearInMemoryOnly()
            }
            BackendMode.SPOTIFY -> {
                prefs.edit()
                    .putBoolean("SpotifyLoggedIn", false)
                    .putBoolean("LoggedIn", false)
                    .remove("SpotifyName")
                    .remove("SpotifyAvatar")
                    .remove("SpotifyUserId")
                    .remove("CurrentAccountName")
                    .remove("BackendMode")
                    .apply()
                com.music.spotui.data.api.Api.HomeCache.clear()
                listOf("PlaybackState", "LibraryCache", "RecentItems", "LikedSongs",
                    "LikedSongsFromSpotify", "LikedAlbums", "FollowedArtists").forEach { name ->
                    app.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit().clear().apply()
                }
                com.music.spotui.data.local.ListenHistory.clear()
                WaveBitsRuntime.host.logout()
            }
            BackendMode.JIOSAAVN -> {
                prefs.edit()
                    .putBoolean("JioSaavnLoggedIn", false)
                    .putBoolean("LoggedIn", false)
                    .remove("JioSaavnName")
                    .remove("JioSaavnAvatar")
                    .remove("BackendMode")
                    .apply()
                com.music.spotui.data.preferences.JioSaavnGuestProfile.reset(app)
                com.music.spotui.data.api.Api.HomeCache.clearInMemoryOnly()
            }
        }

        com.music.spotui.ui.screens.ContextViewModel.clearCache()
        com.music.spotui.data.api.Api.LikedCache.tracks = null
        com.music.spotui.data.api.TopStats.clear()
        com.music.spotui.data.api.ProfileCache.clearSession()
        SongPlayer.clearSession()
        _signedOut.value = true
    }

    /** The new account is signed in. */
    fun signedIn() { _signedOut.value = false }
}
