package app.wavebits.innertube.models.body

import app.wavebits.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class BrowseBody(
    val context: Context,
    val browseId: String? = null,
    val params: String? = null,
)

@Serializable
data class NextBody(
    val context: Context,
    val videoId: String? = null,
    val playlistId: String? = null,
    val playlistSetVideoId: String? = null,
    val index: Int? = null,
    val params: String? = null,
    val continuation: String? = null,
)

@Serializable
data class GetSearchSuggestionsBody(
    val context: Context,
    val input: String,
)

@Serializable
data class LikeTargetBody(
    val context: Context,
    val target: Target,
) {
    @Serializable
    data class Target(
        val videoId: String? = null,
        val playlistId: String? = null,
    )
}

@Serializable
data class CreatePlaylistBody(
    val context: Context,
    val title: String,
    val description: String = "",
    val privacyStatus: String = "PRIVATE",
)

@Serializable
data class EditPlaylistBody(
    val context: Context,
    val playlistId: String,
    val actions: List<Action>,
) {
    @Serializable
    data class Action(
        val action: String,
        val addedVideoId: String? = null,
        val removedVideoId: String? = null,
        val setVideoId: String? = null,
        val playlistName: String? = null,
    )
}

@Serializable
data class DeletePlaylistBody(
    val context: Context,
    val playlistId: String,
)

@Serializable
data class SubscribeBody(
    val context: Context,
    val channelIds: List<String>,
)
