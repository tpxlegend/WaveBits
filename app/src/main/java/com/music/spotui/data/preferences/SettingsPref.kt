package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// Trimmed from Spotui: stream quality, YouTube and web-playback settings are gone (the Web
// Player engine streams Spotify's own audio). Crossfade is ours: a fade, not an overlap.

private const val PREF = "settings_prefs"
private const val KEY_LIBRARY_GRID = "library_grid_view"
private const val KEY_LYRIC_TRANSLATE_LANG = "lyric_translate_lang"
private const val KEY_LYRICS_EXTRA = "lyrics_extra_sources"
private const val KEY_CROSSFADE = "crossfade_seconds"

private var settingsStateTick by androidx.compose.runtime.mutableStateOf(0)

/** Extra lyrics sources (NetEase, Genius) when Spotify and LRCLIB have none. Off by default. */
fun isLyricsExtraSources(c: Context): Boolean {
    settingsStateTick
    return prefs(c).getBoolean(KEY_LYRICS_EXTRA, false)
}
fun setLyricsExtraSources(c: Context, v: Boolean) {
    prefs(c).edit().putBoolean(KEY_LYRICS_EXTRA, v).apply()
    settingsStateTick++
}

/** Lyrics timed word by word first (karaoke); off: line by line, Spotify's first. */
fun isLyricsWordSync(c: Context): Boolean {
    settingsStateTick
    return prefs(c).getBoolean("lyrics_word_sync", true)
}
fun setLyricsWordSync(c: Context, v: Boolean) {
    prefs(c).edit().putBoolean("lyrics_word_sync", v).apply()
    settingsStateTick++
}

fun isDownloadWifiOnly(c: Context): Boolean {
    settingsStateTick
    return prefs(c).getBoolean("download_wifi_only", false)
}
fun setDownloadWifiOnly(c: Context, v: Boolean) {
    prefs(c).edit().putBoolean("download_wifi_only", v).apply()
    settingsStateTick++
}

private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

/** The bottom bar: fading into the list (the default), or solid black. Read by the bar as state. */
object SolidNavBar {
    private const val KEY = "solid_nav_bar"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, false))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/** "When your music ends, keep playing similar songs" (on by default). Read by the player. */
object AutoplayPref {
    private const val KEY = "autoplay_similar"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
        val runtime = app.wavebits.host.android.WaveBitsRuntime
        if (runtime.isStarted) {
            runCatching { runtime.youtube.ytPlayer.autoplayEnabled = value }
            runCatching { runtime.jiosaavn.jioPlayer.autoplayEnabled = value }
        }
    }
}

/**
 * The automatic update check at launch (on by default: the app behaved that way before this
 * option existed). Off means no check when the app starts — a check asked for in Settings still
 * works, it reads the same release list.
 */
object AutoUpdateCheckPref {
    private const val KEY = "auto_update_check"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/** Screen rotation: portrait (the default), automatic (follows the system), or landscape. */
object OrientationPref {
    private const val KEY = "screen_orientation"
    const val PORTRAIT = "portrait"
    const val AUTO = "auto"
    const val LANDSCAPE = "landscape"
    var mode by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getString(KEY, PORTRAIT) ?: PORTRAIT)
        private set

    fun set(c: Context, value: String) {
        mode = value
        prefs(c).edit().putString(KEY, value).apply()
    }

    /** The [android.content.pm.ActivityInfo] value for the chosen mode. */
    fun requested(): Int = when (mode) {
        AUTO -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}

/** Library layout: false = rows (default), true = Spotify-style 3-column grid. */
fun isLibraryGridView(c: Context): Boolean = prefs(c).getBoolean(KEY_LIBRARY_GRID, false)
fun setLibraryGridView(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_LIBRARY_GRID, v).apply()

/**
 * Crossfade length in seconds, 0 = off (the default: nothing changes unless asked for).
 *
 * It is a fade, not an overlap: the page plays one media element, so the next song cannot
 * start before this one ends. The song fades out over its last N seconds, the next one fades
 * in. The value is Compose state, so the settings row redraws when it changes.
 */
object CrossfadePref {
    const val MAX = 12
    var seconds by androidx.compose.runtime.mutableStateOf(
        prefs(com.music.spotui.di.Graph.app).getInt(KEY_CROSSFADE, 0).coerceIn(0, MAX),
    )
        private set

    /** Saves it and hands it to the player: the change applies to the song playing now. */
    fun set(c: Context, value: Int) {
        val v = value.coerceIn(0, MAX)
        seconds = v
        prefs(c).edit().putInt(KEY_CROSSFADE, v).apply()
        applyCrossfade(v)
    }
}

/**
 * "Music videos": the video button in the player, the lookup behind it and a clip that starts
 * by itself on the next song. On by default; off, the player is audio (and Canvas) only.
 */
object MusicVideoPref {
    private const val KEY = "music_video_enabled"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
        if (!value) com.music.spotui.video.MusicVideoController.setOn(false)
    }
}

fun getCrossfadeSeconds(c: Context): Int =
    prefs(c).getInt(KEY_CROSSFADE, 0).coerceIn(0, CrossfadePref.MAX)

/** Gives the saved length to the player (at start, and at every change); 0 stops a fade at once. */
fun applyCrossfade(seconds: Int) {
    val runtime = app.wavebits.host.android.WaveBitsRuntime
    if (!runtime.isStarted) return
    runtime.spotify.player.crossfadeSeconds = seconds
    runCatching { runtime.youtube.ytPlayer.crossfadeSeconds = seconds }
    runCatching { runtime.jiosaavn.jioPlayer.crossfadeSeconds = seconds }
    runCatching { runtime.youtube.ytPlayer.autoplayEnabled = AutoplayPref.on }
    runCatching { runtime.jiosaavn.jioPlayer.autoplayEnabled = AutoplayPref.on }
}

fun getLyricTranslateLang(c: Context): String =
    prefs(c).getString(KEY_LYRIC_TRANSLATE_LANG, "").orEmpty()

fun setLyricTranslateLang(c: Context, code: String) =
    prefs(c).edit().putString(KEY_LYRIC_TRANSLATE_LANG, code.trim()).apply()

/**
 * Liquid Glass in the Immersive look (Settings > Display, under Look): the floating bars refract
 * the page through a lens instead of only frosting it. Off by default; it only takes effect on
 * Android 12+ and when the phone does not ask for less blur or motion (see
 * [com.music.spotui.ui.immersive.shell.rememberLiquidGlassActive]).
 */
object LiquidGlassPref {
    private const val KEY = "immersive_liquid_glass"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/**
 * Restarts/resets whichever playback engine is active (Spotify WebView, YouTube Music, or JioSaavn),
 * stops local/video sessions, clears transient home cache, and re-applies crossfade & quality prefs.
 */
internal fun restartActivePlayer(c: Context) {
    runCatching { com.music.spotui.di.LocalPlayer.stop() }
    runCatching { com.music.spotui.video.MusicVideoController.clearSession() }
    runCatching { com.music.spotui.data.api.Api.HomeCache.clearInMemoryOnly() }
    val runtime = app.wavebits.host.android.WaveBitsRuntime
    if (runtime.isStarted) {
        when (runtime.backendMode.value) {
            app.wavebits.host.android.BackendMode.YOUTUBE -> runCatching { runtime.youtube.ytPlayer.stopAndReset() }
            app.wavebits.host.android.BackendMode.JIOSAAVN -> runCatching { runtime.jiosaavn.jioPlayer.stopAndReset() }
            app.wavebits.host.android.BackendMode.SPOTIFY -> runCatching { runtime.host.reload() }
        }
        runCatching { applyCrossfade(CrossfadePref.seconds) }
        runCatching { applyStreamingQuality(c) }
    }
}

