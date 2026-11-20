package com.music.spotui.ui.viewmodel

import com.music.spotui.di.Graph
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ArtistViewModel constructor(private val repository: AppRepository = Graph.repository, private val currentSongState: CurrentSongState = Graph.currentSongState) : ViewModel() {

    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState
    val currentSongId: State<Int> get() = currentSongState.songId


    private val _songs : MutableStateFlow<Response<List<SongsModel>>> = MutableStateFlow(Response.Loading())
    val songs : StateFlow<Response<List<SongsModel>>> = _songs

    private val _artists : MutableStateFlow<Response<List<ArtistsModel>>> = MutableStateFlow(Response.Loading())
    val artists : StateFlow<Response<List<ArtistsModel>>> = _artists

    private val _overview : MutableStateFlow<Response<ArtistOverviewModel>> = MutableStateFlow(Response.Loading())
    val overview : StateFlow<Response<ArtistOverviewModel>> = _overview

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId :Int, songIndex : Int = 0, album : String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    val likeState = currentSongState.likeState

    fun updateLikeState(likeState : Boolean){
        currentSongState.updateLikeState(likeState)
    }

    private var loadedArtist: String? = null

    // Ids of artists the user follows on Spotify, so the Follow button reflects
    // the real account state (not just follows made from this app).
    private val _followedArtistIds: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    val followedArtistIds: StateFlow<Set<String>> = _followedArtistIds

    // No look-ups at creation: the page loads what it shows (follow state comes from
    // the library check in ArtistScreen).


    companion object {
        private val overviewCache = java.util.concurrent.ConcurrentHashMap<String, Response.Success<ArtistOverviewModel>>()
        private val songsCache = java.util.concurrent.ConcurrentHashMap<String, Response.Success<List<SongsModel>>>()
    }

    // Loads this artist's top tracks (GraphQL, not rate-limited). Called by the
    // screen with the artist name it was opened for.
    fun loadArtistSongs(artistName: String) {
        if (loadedArtist == artistName) return
        loadedArtist = artistName
        songsCache[artistName]?.let { cached ->
            _songs.value = cached
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.provideArtistSongs(artistName).collect { songs ->
                val typed = songs as Response<List<SongsModel>>
                if (typed is Response.Success) {
                    songsCache[artistName] = typed
                }
                _songs.value = typed
            }
        }
    }

    private var loadedOverview: String? = null

    // Loads the full Spotify-style artist page (header, monthly listeners, bio,
    // popular tracks, discography, related artists) in one GQL round-trip.
    // Passing the exact Spotify artist id avoids the fuzzy name search.
    fun loadArtistOverview(artistName: String, artistId: String = "") {
        val key = artistId.ifBlank { artistName }
        if (loadedOverview == key && _overview.value is Response.Success) return
        loadedOverview = key
        val cached = overviewCache[key] ?: artistName.takeIf { it.isNotBlank() }?.let { overviewCache[it] }
        if (cached != null) {
            _overview.value = cached
            _songs.value = Response.Success(cached.data.topTracks.map { it.song })
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.provideArtistOverview(artistName, artistId).collect { overview ->
                if (overview is Response.Loading && _overview.value is Response.Success) return@collect
                _overview.value = overview
                // A failed page may be loaded again (Try again, or opening it anew).
                if (overview is Response.Error) loadedOverview = null
                // Keep the legacy songs flow in sync (popular tracks) so any
                // other consumer and the play/queue helpers still work.
                if (overview is Response.Success) {
                    overviewCache[key] = overview
                    if (artistName.isNotBlank()) overviewCache[artistName] = overview
                    if (overview.data.id.isNotBlank()) overviewCache[overview.data.id] = overview
                    if (overview.data.name.isNotBlank()) overviewCache[overview.data.name] = overview
                    val topSongs = Response.Success(overview.data.topTracks.map { it.song })
                    _songs.value = topSongs
                    if (artistName.isNotBlank()) songsCache[artistName] = topSongs
                }
            }
        }
    }

    private fun fetchArtists() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideArtists().collect{ artists ->
            _artists.value = artists as Response<List<ArtistsModel>>
        }
    }
}