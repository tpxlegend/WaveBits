package com.music.spotui.data.preferences

/*
 * Sound quality: what the Web Player streams, and how downloads are saved.
 */

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

private const val PREF = "settings_prefs"
private const val KEY_STREAMING = "streaming_quality"
private const val KEY_CHOSEN = "quality_chosen"
private const val KEY_STREAMING_CELL = "streaming_quality_cellular"
private const val KEY_CANVAS_WIFI = "canvas_wifi_only"

/**
 * The Web Player's own streaming levels (its "Streaming quality" setting): 24, 96 and
 * 160 kbit/s. Only the levels a free account gets; "very_high" (320) is Premium's.
 */
enum class StreamingQuality(val level: String) {
    AUTO("auto"), LOW("low"), NORMAL("normal"), HIGH("high");

    companion object {
        fun of(level: String?): StreamingQuality = entries.firstOrNull { it.level == level } ?: AUTO
    }
}

/**
 * How a downloaded song is saved: not at all, small (AAC re-encoded from the checked FLAC),
 * FLAC 16-bit (CD), or FLAC 24-bit when it exists.
 */
enum class DownloadQuality {
    OFF, SMALL, CD, HI_RES,
    /** Asked when a download starts (once for a whole playlist or album). */
    ASK;

    companion object {
        /** A quality a file can be saved in ([ASK] and [OFF] are not); null when unknown. */
        fun saved(name: String?): DownloadQuality? = entries.firstOrNull { it.name == name }?.takeIf { it != ASK && it != OFF }
    }
}

/** Reactive counter incremented whenever streaming or download quality preferences change. */
private var qualityStateTick by mutableStateOf(0)

/**
 * The level for Wi-Fi, or for mobile data ([cellular]), as the official app's two settings.
 * Mobile data follows the Wi-Fi level until it is set on its own.
 */
fun getStreamingQuality(c: Context, cellular: Boolean = false): StreamingQuality {
    qualityStateTick
    val wifi = prefs(c).getString(KEY_STREAMING, null)
    return StreamingQuality.of(if (cellular) prefs(c).getString(KEY_STREAMING_CELL, null) ?: wifi else wifi)
}

/** Saves the level for that network and hands the one for the current network to the player. */
fun setStreamingQuality(c: Context, q: StreamingQuality, cellular: Boolean = false) {
    prefs(c).edit().putString(if (cellular) KEY_STREAMING_CELL else KEY_STREAMING, q.level).apply()
    qualityStateTick++
    applyStreamingQuality(c)
}

/** Gives the current network's level to the active players (at start and when the network changes); harmless before it is up. */
fun applyStreamingQuality(c: Context) {
    val runtime = app.wavebits.host.android.WaveBitsRuntime
    if (!runtime.isStarted) return
    val q = getStreamingQuality(c, cellular = com.music.spotui.data.DataSaver.metered)
    runCatching { runtime.youtube.ytPlayer.setAudioQuality(q.level) }
    runCatching { runtime.jiosaavn.jioPlayer.setAudioQuality(q.level) }
    runtime.scope.launch { runCatching { runtime.spotify.player.setAudioQuality(q.level) } }
}

/** Canvas (the looping videos) only on Wi-Fi: they cost data on every song. */
fun isCanvasWifiOnly(c: Context): Boolean {
    qualityStateTick
    return prefs(c).getBoolean(KEY_CANVAS_WIFI, false)
}
fun setCanvasWifiOnly(c: Context, on: Boolean) {
    prefs(c).edit().putBoolean(KEY_CANVAS_WIFI, on).apply()
    qualityStateTick++
}

fun getDownloadQuality(c: Context): DownloadQuality {
    qualityStateTick
    return when {
        !isDownloadsEnabled(c) -> DownloadQuality.OFF
        isAskDownloadQuality(c) -> DownloadQuality.ASK
        isSmallDownloads(c) -> DownloadQuality.SMALL
        isHiRes(c) -> DownloadQuality.HI_RES
        else -> DownloadQuality.CD
    }
}

fun setDownloadQuality(c: Context, q: DownloadQuality) {
    setDownloadsEnabled(c, q != DownloadQuality.OFF)
    setHiRes(c, q == DownloadQuality.HI_RES)
    setSmallDownloads(c, q == DownloadQuality.SMALL)
    setAskDownloadQuality(c, q == DownloadQuality.ASK)
    qualityStateTick++
}

/** The quality step of the onboarding was seen (bypassed by default). */
fun isQualityChosen(c: Context): Boolean = true
fun setQualityChosen(c: Context) = prefs(c).edit().putBoolean(KEY_CHOSEN, true).apply()

private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
