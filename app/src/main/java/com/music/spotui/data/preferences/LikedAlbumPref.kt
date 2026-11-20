package com.music.spotui.data.preferences

import android.content.Context
import app.wavebits.host.android.BackendMode
import app.wavebits.host.android.WaveBitsRuntime
import com.music.spotui.data.entity.AlbumsModel

private fun likedAlbumsPrefName(): String {
    val mode = WaveBitsRuntime.backendMode.value
    return if (mode == BackendMode.SPOTIFY) "LikedAlbums" else "LikedAlbums_${mode.name}"
}

fun addLikedAlbumId(context: Context, albumId: String) {
    val sharedPreferences = context.getSharedPreferences(likedAlbumsPrefName(), Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.putString(albumId, albumId)
    editor.apply()
}

fun removeLikedAlbumId(context: Context, albumId: String) {
    val sharedPreferences = context.getSharedPreferences(likedAlbumsPrefName(), Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.remove(albumId)
    editor.apply()
}

fun isAlbumLiked(context: Context, albumId: String): Boolean {
    val sharedPreferences = context.getSharedPreferences(likedAlbumsPrefName(), Context.MODE_PRIVATE)
    return sharedPreferences.contains(albumId)
}

fun getLikedAlbumIds(context: Context): Set<Int> {
    val sharedPreferences = context.getSharedPreferences(likedAlbumsPrefName(), Context.MODE_PRIVATE)
    return sharedPreferences.all.keys.mapNotNull { it.toIntOrNull() }.toSet()
}

fun getAlbumsByIds(albumIds: Set<Int>, albums: List<AlbumsModel>): List<AlbumsModel> {
    return albums.filter { album -> album.id in albumIds }
}