package app.wavebits.innertube.pages

import app.wavebits.innertube.models.NavigationEndpoint
import app.wavebits.innertube.models.YTItem

data class HomeSection(
    val title: String = "",
    val label: String? = null,
    val items: List<YTItem> = emptyList(),
) {
    val size: Int get() = items.size
}

data class HomeChipItem(
    val title: String = "",
    val endpoint: NavigationEndpoint? = null,
)

data class HomePage(
    val sections: List<HomeSection> = emptyList(),
    val chips: List<HomeChipItem> = emptyList(),
    val continuation: String? = null,
)
