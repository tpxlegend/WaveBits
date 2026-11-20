package app.wavebits.innertube.models

data class YouTubeChannel(
    val name: String = "",
    val pageId: String = "",
    val dataSyncId: String? = null,
    val thumbnailUrl: String? = null,
    val selected: Boolean = false,
)

data class AccountInfo(
    val name: String = "",
    val thumbnailUrl: String? = null,
)
