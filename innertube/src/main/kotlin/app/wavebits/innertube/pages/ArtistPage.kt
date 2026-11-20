package app.wavebits.innertube.pages

import app.wavebits.innertube.models.ArtistItem
import app.wavebits.innertube.models.YTItem

data class ArtistSection(
    val title: String? = null,
    val items: List<YTItem> = emptyList(),
)

data class ArtistPage(
    val artist: ArtistItem = ArtistItem(id = "", title = "Artist", thumbnail = null, shuffleEndpoint = null, radioEndpoint = null),
    val description: String? = null,
    val sections: List<ArtistSection> = emptyList(),
)
