package com.music.spotui.data.preferences

import android.content.Context
import app.wavebits.host.android.WaveBitsRuntime
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry
import org.json.JSONArray
import org.json.JSONObject

private const val PREF = "LibraryCache"

private fun entriesKey(): String {
    val mode = WaveBitsRuntime.backendMode.value.name
    return if (mode == "SPOTIFY") "entries" else "entries_$mode"
}

private fun artistsKey(): String {
    val mode = WaveBitsRuntime.backendMode.value.name
    return if (mode == "SPOTIFY") "followedArtists" else "followedArtists_$mode"
}

fun cacheLibraryEntries(context: Context, entries: List<LibraryEntry>) {
    val json = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject().apply {
                put("spotifyId", e.spotifyId)
                put("name", e.name)
                put("subtitle", e.subtitle)
                put("coverUri", e.coverUri)
                put("isPlaylist", e.isPlaylist)
                put("artists", e.artists)
                put("folderUri", e.folderUri)
                put("childCount", e.childCount)
                put("uri", e.uri)
                put("pinned", e.pinned)
                put("addedAt", e.addedAt)
            })
        }
    }.toString()
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putString(entriesKey(), json).apply()
}

fun getCachedLibraryEntries(context: Context): List<LibraryEntry> = runCatching {
    val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(entriesKey(), null) ?: return emptyList()
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        arr.getJSONObject(i).let { o ->
            val spotifyId = o.getString("spotifyId")
            val isPlaylist = o.getBoolean("isPlaylist")
            val folderUri = o.optString("folderUri", "")
            val rawUri = o.optString("uri", "")
            val resolvedUri = when {
                rawUri.isNotBlank() -> rawUri
                folderUri.isNotBlank() -> folderUri
                spotifyId == com.music.spotui.data.api.Api.HomeCache.LIKED_SONGS_ID -> ""
                isPlaylist -> "spotify:playlist:$spotifyId"
                else -> "spotify:album:$spotifyId"
            }
            LibraryEntry(
                spotifyId = spotifyId,
                name = o.getString("name"),
                subtitle = o.getString("subtitle"),
                coverUri = o.getString("coverUri"),
                isPlaylist = isPlaylist,
                artists = o.optString("artists", ""),
                folderUri = folderUri,
                childCount = o.optInt("childCount", 0),
                uri = resolvedUri,
                pinned = o.optBoolean("pinned", false),
                addedAt = o.optString("addedAt", ""),
            )
        }
    }
}.getOrDefault(emptyList())

fun cacheFollowedArtists(context: Context, artists: List<ArtistsModel>) {
    val json = JSONArray().apply {
        artists.forEach { a ->
            put(JSONObject().apply {
                put("name", a.name)
                put("coverUri", a.coverUri)
                put("id", a.id)
            })
        }
    }.toString()
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putString(artistsKey(), json).apply()
}

fun getCachedFollowedArtists(context: Context): List<ArtistsModel> = runCatching {
    val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(artistsKey(), null) ?: return emptyList()
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        arr.getJSONObject(i).let { o ->
            ArtistsModel(
                name = o.getString("name"),
                coverUri = o.getString("coverUri"),
                id = o.optString("id", ""),
            )
        }
    }
}.getOrDefault(emptyList())

fun clearYouTubeLibraryCache(context: Context) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit()
        .remove("entries_YOUTUBE")
        .remove("followedArtists_YOUTUBE")
        .commit()
}
