package com.music.spotui.data.preferences

import android.content.Context
import app.wavebits.host.android.BackendMode
import app.wavebits.host.android.WaveBitsRuntime

// Local record of artists followed from the app (keyed by artist id),
// scoped by backend mode so each platform keeps its own followed artists.

private fun followedArtistsPrefName(): String {
    val mode = WaveBitsRuntime.backendMode.value
    return if (mode == BackendMode.SPOTIFY) "FollowedArtists" else "FollowedArtists_${mode.name}"
}

fun addFollowedArtist(context: Context, artistId: String, name: String) {
    if (artistId.isBlank()) return
    context.getSharedPreferences(followedArtistsPrefName(), Context.MODE_PRIVATE)
        .edit().putString(artistId, name).apply()
}

fun removeFollowedArtist(context: Context, artistId: String) {
    if (artistId.isBlank()) return
    context.getSharedPreferences(followedArtistsPrefName(), Context.MODE_PRIVATE)
        .edit().remove(artistId).apply()
}

fun isArtistFollowed(context: Context, artistId: String): Boolean =
    artistId.isNotBlank() &&
        context.getSharedPreferences(followedArtistsPrefName(), Context.MODE_PRIVATE).contains(artistId)

