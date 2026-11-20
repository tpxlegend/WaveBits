package app.wavebits.innertube.pages

import app.wavebits.innertube.models.YTItem

data class LibraryPage(
    val items: List<YTItem> = emptyList(),
    val continuation: String? = null,
)
