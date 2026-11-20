package app.wavebits.innertube.pages

import app.wavebits.innertube.models.AlbumItem
import app.wavebits.innertube.models.SongItem

data class AlbumPage(
    val album: AlbumItem = AlbumItem(browseId = "", playlistId = "", title = "Album", artists = null, thumbnail = ""),
    val songs: List<SongItem> = emptyList(),
)
