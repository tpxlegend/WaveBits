package app.wavebits.innertube.pages

import app.wavebits.innertube.models.PlaylistItem
import app.wavebits.innertube.models.SongItem

data class PlaylistPage(
    val playlist: PlaylistItem = PlaylistItem(id = "", title = "Playlist", author = null, songCountText = null, thumbnail = null, playEndpoint = null, shuffleEndpoint = null, radioEndpoint = null),
    val songs: List<SongItem> = emptyList(),
    val continuation: String? = null,
)
