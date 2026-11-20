package app.wavebits.innertube.pages

import app.wavebits.innertube.models.NavigationEndpoint
import app.wavebits.innertube.models.SongItem
import app.wavebits.innertube.models.WatchEndpoint
import app.wavebits.innertube.models.YTItem

data class SearchSummarySection(
    val title: String? = null,
    val items: List<YTItem> = emptyList(),
)

data class SearchSummary(
    val summaries: List<SearchSummarySection> = emptyList(),
)

data class SearchSuggestions(
    val queries: List<String> = emptyList(),
)

data class NextResult(
    val endpoint: WatchEndpoint? = null,
    val continuation: String? = null,
    val items: List<SongItem> = emptyList(),
    val lyricsEndpoint: NavigationEndpoint? = null,
)
