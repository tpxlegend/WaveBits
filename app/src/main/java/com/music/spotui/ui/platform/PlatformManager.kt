package com.music.spotui.ui.platform

import android.content.Context
import app.wavebits.host.android.BackendMode
import app.wavebits.host.android.WaveBitsRuntime
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.ProfileCache
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SessionActions
import com.music.spotui.di.SongPlayer
import com.music.spotui.extensions.Extensions
import com.music.spotui.ui.screens.ContextViewModel
import com.music.spotui.video.MusicVideoController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates multi-platform credential persistence and instant switching between
 * Spotify and YouTube Music without losing saved sessions on other platforms.
 */
object PlatformManager {
    private const val PREFS_NAME = "myspoti_prefs"

    private val _loginRequest = MutableSharedFlow<BackendMode>(extraBufferCapacity = 1)
    val loginRequest: SharedFlow<BackendMode> = _loginRequest

    private val _switchVersion = MutableStateFlow(0L)
    val switchVersion: StateFlow<Long> = _switchVersion

    private val _switchingToPlatform = MutableStateFlow<BackendMode?>(null)
    val switchingToPlatform: StateFlow<BackendMode?> = _switchingToPlatform

    fun clearSwitchingAnimation() {
        _switchingToPlatform.value = null
    }

    fun isSpotifyLoggedIn(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("SpotifyLoggedIn", false)) return true
        // Migrate legacy "LoggedIn" state if the user was previously logged into Spotify
        val legacyLoggedIn = prefs.getBoolean("LoggedIn", false)
        val savedBackend = prefs.getString("BackendMode", "SPOTIFY")
        val wasGuest = prefs.getBoolean("GuestMode", false)
        if (legacyLoggedIn && savedBackend == "SPOTIFY" && !wasGuest) {
            prefs.edit().putBoolean("SpotifyLoggedIn", true).apply()
            return true
        }
        if (WaveBitsRuntime.isStarted && !WaveBitsRuntime.host.loginRequired.value && WaveBitsRuntime.spotify.signedInHint() == true) {
            prefs.edit().putBoolean("SpotifyLoggedIn", true).apply()
            return true
        }
        return false
    }

    fun isYouTubeLoggedIn(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val runtimeSignedIn = WaveBitsRuntime.isStarted && WaveBitsRuntime.youtubeAccount.isSignedIn
        if (runtimeSignedIn) {
            if (!prefs.getBoolean("YouTubeLoggedIn", false)) {
                prefs.edit().putBoolean("YouTubeLoggedIn", true).apply()
            }
            return true
        }
        return prefs.getBoolean("YouTubeLoggedIn", false)
    }

    fun isJioSaavnLoggedIn(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("JioSaavnLoggedIn", false)
    }

    fun isPlatformLoggedIn(context: Context, mode: BackendMode): Boolean {
        return when (mode) {
            BackendMode.SPOTIFY -> isSpotifyLoggedIn(context)
            BackendMode.YOUTUBE -> isYouTubeLoggedIn(context)
            BackendMode.JIOSAAVN -> isJioSaavnLoggedIn(context)
        }
    }

    fun sanitizeYouTubeName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.trim()
    }

    fun getSavedAccountName(context: Context, mode: BackendMode): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when (mode) {
            BackendMode.SPOTIFY -> prefs.getString("SpotifyName", null)?.takeIf { it.isNotBlank() }
            BackendMode.YOUTUBE -> {
                val live = if (WaveBitsRuntime.isStarted) WaveBitsRuntime.youtubeAccount.accountName.value else null
                val raw = live?.takeIf { it.isNotBlank() } ?: prefs.getString("YouTubeName", null)?.takeIf { it.isNotBlank() }
                val clean = sanitizeYouTubeName(raw)
                if (raw != null && clean != raw) {
                    prefs.edit().putString("YouTubeName", clean).apply()
                }
                clean
            }
            BackendMode.JIOSAAVN -> {
                com.music.spotui.data.preferences.JioSaavnGuestProfile.load(context)
                com.music.spotui.data.preferences.JioSaavnGuestProfile.guestName
            }
        }
    }

    fun getSavedAccountAvatar(context: Context, mode: BackendMode): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when (mode) {
            BackendMode.SPOTIFY -> prefs.getString("SpotifyAvatar", null)?.takeIf { it.isNotBlank() }
            BackendMode.YOUTUBE -> {
                val live = if (WaveBitsRuntime.isStarted) WaveBitsRuntime.youtubeAccount.accountAvatar.value else null
                live?.takeIf { it.isNotBlank() } ?: prefs.getString("YouTubeAvatar", null)?.takeIf { it.isNotBlank() }
            }
            BackendMode.JIOSAAVN -> {
                com.music.spotui.data.preferences.JioSaavnGuestProfile.load(context)
                com.music.spotui.data.preferences.JioSaavnGuestProfile.avatarId
            }
        }
    }

    fun getSavedSpotifyUserId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("SpotifyUserId", null)?.takeIf { it.isNotBlank() }
    }

    fun getSavedBackendMode(context: Context): BackendMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString("BackendMode", "SPOTIFY")
        return runCatching { BackendMode.valueOf(name!!) }.getOrDefault(BackendMode.SPOTIFY)
    }

    fun saveSpotifySession(
        context: Context,
        name: String? = null,
        avatar: String? = null,
        userId: String? = null,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putBoolean("SpotifyLoggedIn", true)
            .putBoolean("LoggedIn", true)
            .putString("BackendMode", BackendMode.SPOTIFY.name)
        if (!name.isNullOrBlank()) editor.putString("SpotifyName", name)
        if (!avatar.isNullOrBlank()) editor.putString("SpotifyAvatar", avatar)
        if (!userId.isNullOrBlank()) editor.putString("SpotifyUserId", userId)
        editor.apply()
    }

    fun isYouTubeGuest(context: Context): Boolean {
        if (WaveBitsRuntime.isStarted && WaveBitsRuntime.youtubeAccount.isSignedIn) {
            return WaveBitsRuntime.youtubeAccount.isGuest.value
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("YouTubeGuest", false)
    }

    fun saveYouTubeSession(
        context: Context,
        name: String? = null,
        avatar: String? = null,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isGuest = if (WaveBitsRuntime.isStarted && WaveBitsRuntime.youtubeAccount.isSignedIn) {
            WaveBitsRuntime.youtubeAccount.isGuest.value
        } else {
            prefs.getBoolean("YouTubeGuest", false)
        }
        val resolvedName = sanitizeYouTubeName(name)
            ?: sanitizeYouTubeName(if (WaveBitsRuntime.isStarted) WaveBitsRuntime.youtubeAccount.accountName.value else null)
            ?: if (isGuest) "Google Guest" else "YouTube Music"
        val resolvedAvatar = if (isGuest) null else {
            avatar?.takeIf { it.isNotBlank() }
                ?: (if (WaveBitsRuntime.isStarted) WaveBitsRuntime.youtubeAccount.accountAvatar.value else null)
        }
        val editor = prefs.edit()
            .putBoolean("YouTubeLoggedIn", true)
            .putBoolean("YouTubeGuest", isGuest)
            .putBoolean("LoggedIn", true)
            .putString("BackendMode", BackendMode.YOUTUBE.name)
            .putString("YouTubeName", resolvedName)
        if (!resolvedAvatar.isNullOrBlank()) {
            editor.putString("YouTubeAvatar", resolvedAvatar)
        } else {
            editor.remove("YouTubeAvatar")
        }
        editor.commit()
    }

    fun saveYouTubeGuestSession(
        context: Context,
        name: String = "Google Guest",
        guestCookie: String? = null,
        visitorData: String? = null,
    ) {
        val cleanName = sanitizeYouTubeName(name)?.takeIf { it.isNotBlank() } ?: "Google Guest"
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("YouTubeLoggedIn", true)
            .putBoolean("YouTubeGuest", true)
            .putBoolean("LoggedIn", true)
            .putString("BackendMode", BackendMode.YOUTUBE.name)
            .putString("YouTubeName", cleanName)
            .remove("YouTubeAvatar")
            .commit()
        if (WaveBitsRuntime.isStarted) {
            WaveBitsRuntime.youtubeAccount.saveGuest(
                name = cleanName,
                guestCookie = guestCookie,
                visitorData = visitorData,
            )
            WaveBitsRuntime.scope.launch {
                runCatching {
                    WaveBitsRuntime.youtubeAccount.establishRealYouTubeGuestSession(
                        name = cleanName,
                        webViewCookie = guestCookie,
                        webViewVisitorData = visitorData,
                    )
                }
            }
        }
    }

    fun saveJioSaavnSession(
        context: Context,
        name: String? = null,
        avatar: String? = null,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val resolvedName = name?.takeIf { it.isNotBlank() } ?: "Jiosaavn Guest"
        val resolvedAvatar = avatar?.takeIf { it.isNotBlank() } ?: "beard_man"
        prefs.edit()
            .putBoolean("JioSaavnLoggedIn", true)
            .putBoolean("LoggedIn", true)
            .putString("BackendMode", BackendMode.JIOSAAVN.name)
            .putString("JioSaavnName", resolvedName)
            .putString("JioSaavnAvatar", resolvedAvatar)
            .apply()
    }

    /**
     * Switches to [target] immediately if saved login data exists,
     * or requests interactive login for [target] while keeping the current session intact until login finishes.
     */
    fun requestPlatformLogin(target: BackendMode) {
        _loginRequest.tryEmit(target)
    }

    fun switchToPlatform(context: Context, target: BackendMode): Boolean {
        if (target == BackendMode.YOUTUBE && !isPlatformLoggedIn(context, BackendMode.YOUTUBE)) {
            saveYouTubeGuestSession(context, "Google Guest")
            activatePlatform(context, BackendMode.YOUTUBE)
            return true
        }
        if (target != BackendMode.JIOSAAVN && !isPlatformLoggedIn(context, target)) {
            _loginRequest.tryEmit(target)
            return false
        }
        activatePlatform(context, target)
        return true
    }

    /**
     * Activates [target], clears transient in-memory UI caches from the previous platform,
     * stops active playback, and restores [target]'s profile and feed.
     */
    fun activatePlatform(context: Context, target: BackendMode) {
        val appContext = context.applicationContext ?: context
        _switchingToPlatform.value = target
        runCatching { SongPlayer.flushListen() }
        runCatching { LocalPlayer.stop() }
        runCatching { MusicVideoController.clearSession() }
        runCatching { SongPlayer.clearInMemorySessionForPlatformSwitch() }
        runCatching { ContextViewModel.clearCache() }
        runCatching { Api.HomeCache.clearInMemoryOnly() }
        runCatching { ProfileCache.clearSession() }

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("BackendMode", target.name).putBoolean("LoggedIn", true).apply()

        when (target) {
            BackendMode.SPOTIFY -> {
                saveSpotifySession(appContext)
                WaveBitsRuntime.setSpotifyMode(appContext)
            }
            BackendMode.YOUTUBE -> {
                if (isYouTubeGuest(appContext)) {
                    saveYouTubeGuestSession(
                        appContext,
                        name = getSavedAccountName(appContext, BackendMode.YOUTUBE) ?: "Google Guest",
                    )
                } else {
                    saveYouTubeSession(appContext)
                }
                WaveBitsRuntime.setYouTubeMode(appContext)
            }
            BackendMode.JIOSAAVN -> {
                saveJioSaavnSession(
                    appContext,
                    name = getSavedAccountName(appContext, BackendMode.JIOSAAVN),
                    avatar = getSavedAccountAvatar(appContext, BackendMode.JIOSAAVN),
                )
                WaveBitsRuntime.setJioSaavnMode(appContext)
            }
        }

        runCatching { Api.HomeCache.clearInMemoryOnly() }
        runCatching {
            com.music.spotui.data.preferences.applyCrossfade(com.music.spotui.data.preferences.CrossfadePref.seconds)
            com.music.spotui.data.preferences.applyStreamingQuality(appContext)
        }
        runCatching { SongPlayer.restoreLastTrackForCurrentMode() }
        ProfileCache.ensure(appContext)
        SessionActions.signedIn()
        _switchVersion.value = _switchVersion.value + 1L
    }
}
