package com.music.spotui.data.local

import android.content.Context
import android.util.Log
import app.wavebits.host.android.BackendMode
import app.wavebits.host.android.WaveBitsRuntime
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores and manages the 15 most recently played songs on device,
 * isolated per active backend platform (Spotify, YouTube Music, JioSaavn).
 */
object RecentPlayedStore {
    private const val TAG = "RecentPlayedStore"
    const val MAX_RECENT = 15

    private var filesDir: File? = null
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val byBackend = ConcurrentHashMap<BackendMode, List<SongsModel>>()

    private val _recentSongs = MutableStateFlow<List<SongsModel>>(emptyList())
    val recentSongs: StateFlow<List<SongsModel>> = _recentSongs.asStateFlow()

    private fun fileFor(mode: BackendMode): File? =
        filesDir?.let { File(it, "recent_played_songs_${mode.name}.json") }

    private fun sourceFor(mode: BackendMode): ListenHistory.Source = when (mode) {
        BackendMode.SPOTIFY -> ListenHistory.Source.SPOTIFY
        BackendMode.YOUTUBE -> ListenHistory.Source.YOUTUBE
        BackendMode.JIOSAAVN -> ListenHistory.Source.JIOSAAVN
    }

    fun inferModeForSong(song: SongsModel, fallback: BackendMode = WaveBitsRuntime.backendMode.value): BackendMode {
        val rawId = song.spotifyTrackId.ifBlank { song.url }
        val clean = rawId.substringBefore('|').substringAfterLast(':')
        val cover = song.coverUri.lowercase()
        return when {
            clean.startsWith("jio_") || rawId.contains(":jio_") || rawId.startsWith("jiosaavn:") || cover.contains("saavncdn.com") || cover.contains("jiosaavn.com") -> BackendMode.JIOSAAVN
            clean.startsWith("yt_") || rawId.contains(":yt_") || rawId.startsWith("youtube:") || cover.contains("ytimg.com") || cover.contains("ggpht.com") || cover.contains("googleusercontent.com") -> BackendMode.YOUTUBE
            clean.isNotBlank() && !clean.startsWith("yt_") && !clean.startsWith("jio_") && cover.contains("scdn.co") -> BackendMode.SPOTIFY
            else -> fallback
        }
    }

    fun belongsToMode(song: SongsModel, mode: BackendMode): Boolean =
        inferModeForSong(song, mode) == mode

    fun init(context: Context) {
        filesDir = context.filesDir
        synchronized(lock) {
            BackendMode.values().forEach { mode ->
                val loaded = loadFromFile(mode).filter { belongsToMode(it, mode) }
                if (loaded.isNotEmpty()) {
                    byBackend[mode] = loaded
                } else {
                    val seeded = seedFromListenHistory(mode).filter { belongsToMode(it, mode) }
                    byBackend[mode] = seeded
                    if (seeded.isNotEmpty()) {
                        saveToFile(mode, seeded)
                    }
                }
            }
            val activeMode = WaveBitsRuntime.backendMode.value
            _recentSongs.value = byBackend[activeMode].orEmpty()
        }
        scope.launch {
            WaveBitsRuntime.backendMode.collect { mode ->
                synchronized(lock) {
                    val current = byBackend[mode]
                        ?: loadFromFile(mode).filter { belongsToMode(it, mode) }.also { byBackend[mode] = it }
                    _recentSongs.value = current
                }
            }
        }
    }

    /**
     * Records a played song at the top of its platform's 15 recent songs list.
     */
    fun record(song: SongsModel, mode: BackendMode = inferModeForSong(song, WaveBitsRuntime.backendMode.value)) {
        if (song.title.isBlank()) return
        val targetMode = inferModeForSong(song, mode)
        synchronized(lock) {
            val current = (byBackend[targetMode] ?: loadFromFile(targetMode))
                .filter { belongsToMode(it, targetMode) }
                .toMutableList()
            current.removeAll {
                it.id == song.id ||
                    (it.spotifyTrackId.isNotBlank() && it.spotifyTrackId == song.spotifyTrackId) ||
                    (it.title.equals(song.title, ignoreCase = true) &&
                        it.singer.equals(song.singer, ignoreCase = true))
            }
            current.add(0, song)
            val trimmed = current.take(MAX_RECENT)
            byBackend[targetMode] = trimmed
            if (WaveBitsRuntime.backendMode.value == targetMode) {
                _recentSongs.value = trimmed
            }
            saveToFile(targetMode, trimmed)
        }
    }

    fun getRecentSongs(limit: Int = MAX_RECENT, mode: BackendMode = WaveBitsRuntime.backendMode.value): List<SongsModel> {
        val inMem = byBackend[mode]?.filter { belongsToMode(it, mode) }
        if (!inMem.isNullOrEmpty()) {
            if (WaveBitsRuntime.backendMode.value == mode && _recentSongs.value != inMem) {
                _recentSongs.value = inMem
            }
            return inMem.take(limit)
        }
        return synchronized(lock) {
            val loaded = loadFromFile(mode).filter { belongsToMode(it, mode) }
            byBackend[mode] = loaded
            if (WaveBitsRuntime.backendMode.value == mode) {
                _recentSongs.value = loaded
            }
            loaded.take(limit)
        }
    }

    fun getRecentHomeItems(limit: Int = MAX_RECENT): List<HomeItem.Playlist> {
        val mode = WaveBitsRuntime.backendMode.value
        return getRecentSongs(limit, mode).map { song ->
            val rawId = song.spotifyTrackId.ifBlank { song.url }.ifBlank { song.id.toString() }
            val finalId = when (mode) {
                BackendMode.JIOSAAVN -> rawId
                BackendMode.YOUTUBE -> when {
                    rawId.startsWith("yt_RDAMVM") -> rawId
                    rawId.startsWith("yt_") -> "yt_RDAMVM" + rawId.removePrefix("yt_")
                    else -> "yt_RDAMVM$rawId"
                }
                BackendMode.SPOTIFY -> when {
                    rawId.startsWith("song_") -> rawId
                    else -> "song_${rawId.removePrefix("spotify:track:")}"
                }
            }
            val defaultSub = when (mode) {
                BackendMode.JIOSAAVN -> "JioSaavn"
                BackendMode.YOUTUBE -> "YouTube Music"
                BackendMode.SPOTIFY -> "Spotify"
            }
            HomeItem.Playlist(
                name = song.title,
                imageUrl = song.coverUri,
                subtitle = song.singer.ifBlank { defaultSub },
                id = finalId,
            )
        }
    }

    private fun seedFromListenHistory(mode: BackendMode): List<SongsModel> {
        return runCatching {
            val targetSource = sourceFor(mode)
            val history = ListenHistory.all().filter { it.source == targetSource }.reversed()
            if (history.isEmpty()) return emptyList()

            val out = mutableListOf<SongsModel>()
            history.forEach { item ->
                if (item.title.isNotBlank() && out.none { it.title.equals(item.title, ignoreCase = true) }) {
                    val rawId = item.key
                        .removePrefix("spotify:track:")
                        .removePrefix("jio_")
                        .removePrefix("yt_")
                    val numericId = rawId.filter { it.isDigit() }.toIntOrNull() ?: item.title.hashCode()
                    val trackId = when (mode) {
                        BackendMode.JIOSAAVN -> if (item.key.startsWith("jio_")) item.key else "jio_$rawId"
                        BackendMode.YOUTUBE -> if (item.key.startsWith("yt_")) item.key else "yt_$rawId"
                        BackendMode.SPOTIFY -> rawId
                    }
                    out.add(
                        SongsModel(
                            id = numericId,
                            title = item.title,
                            album = item.album,
                            singer = item.artist,
                            coverUri = item.cover,
                            url = trackId,
                            spotifyTrackId = trackId,
                        )
                    )
                }
            }
            out.take(MAX_RECENT)
        }.getOrDefault(emptyList())
    }

    private fun loadFromFile(mode: BackendMode): List<SongsModel> {
        val f = fileFor(mode)?.takeIf { it.exists() } ?: return emptyList()
        return runCatching {
            val text = f.readText()
            if (text.isBlank()) return emptyList()
            val arr = JSONArray(text)
            val list = mutableListOf<SongsModel>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    SongsModel(
                        id = o.optInt("id", i),
                        title = o.getString("title"),
                        album = o.optString("album", ""),
                        singer = o.optString("singer", ""),
                        coverUri = o.optString("coverUri", ""),
                        url = o.optString("url", ""),
                        spotifyTrackId = o.optString("spotifyTrackId", ""),
                        durationMs = o.optInt("durationMs", 0),
                    )
                )
            }
            list.take(MAX_RECENT)
        }.getOrElse {
            Log.w(TAG, "Failed to load recent songs from file for $mode", it)
            emptyList()
        }
    }

    private fun saveToFile(mode: BackendMode, list: List<SongsModel>) {
        val f = fileFor(mode) ?: return
        runCatching {
            val arr = JSONArray()
            list.take(MAX_RECENT).forEach { s ->
                val o = JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("album", s.album)
                    .put("singer", s.singer)
                    .put("coverUri", s.coverUri)
                    .put("url", s.url)
                    .put("spotifyTrackId", s.spotifyTrackId)
                    .put("durationMs", s.durationMs)
                arr.put(o)
            }
            f.writeText(arr.toString())
        }.onFailure {
            Log.w(TAG, "Failed to save recent songs for $mode", it)
        }
    }
}

