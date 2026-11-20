package app.wavebits.innertube.pages

import app.wavebits.innertube.models.AlbumItem
import app.wavebits.innertube.models.NavigationEndpoint

data class MoodAndGenreItem(
    val title: String = "",
    val stripeColor: Long = 0xFFFFFFFF,
    val endpoint: NavigationEndpoint = NavigationEndpoint(),
)

data class MoodAndGenreGroup(
    val title: String = "",
    val items: List<MoodAndGenreItem> = emptyList(),
)

data class ExplorePage(
    val newReleaseAlbums: List<AlbumItem> = emptyList(),
    val moodAndGenres: List<MoodAndGenreGroup> = emptyList(),
)
