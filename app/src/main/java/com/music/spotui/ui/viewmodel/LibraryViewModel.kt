package com.music.spotui.ui.viewmodel

import com.music.spotui.di.Graph
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.LibraryEdits
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AccountModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.preferences.cacheFollowedArtists
import com.music.spotui.data.preferences.cacheLibraryEntries
import com.music.spotui.data.preferences.getCachedFollowedArtists
import com.music.spotui.data.preferences.getCachedLibraryEntries
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryViewModel constructor(
    private val repository: AppRepository = Graph.repository,
    private val context: android.content.Context = Graph.app,
) : ViewModel() {

    private val _entries: MutableStateFlow<Response<List<LibraryEntry>>> = MutableStateFlow(Response.Loading())
    val entries: StateFlow<Response<List<LibraryEntry>>> = _entries

    private val _account: MutableStateFlow<Response<AccountModel>> = MutableStateFlow(Response.Loading())
    val account: StateFlow<Response<AccountModel>> = _account

    private val _followedArtists: MutableStateFlow<List<com.music.spotui.data.entity.ArtistsModel>> =
        MutableStateFlow(emptyList())
    val followedArtists: StateFlow<List<com.music.spotui.data.entity.ArtistsModel>> = _followedArtists

    /** The list as Spotify gave it; [entries] is this with the changes not yet in Spotify's list on top. */
    @Volatile private var server: List<LibraryEntry>? = null

    private fun show() {
        val list = server ?: return
        _entries.value = Response.Success(LibraryEdits.applyTo(null, list))
    }

    init {
        // Seed UI with the last-seen library immediately so it's visible offline.
        val cached = getCachedLibraryEntries(context)
        if (cached.isNotEmpty()) { server = cached; show() }
        // A delete, rename or new playlist is shown at once, and taken back if Spotify refuses.
        viewModelScope.launch { LibraryEdits.version.drop(1).collect { show() } }
        viewModelScope.launch {
            LibraryEdits.reloads.collect {
                com.music.spotui.data.api.Api.HomeCache.libraryRoot = null
                com.music.spotui.data.api.Api.HomeCache.library = null
                load()
            }
        }
        viewModelScope.launch {
            app.wavebits.host.android.WaveBitsRuntime.backendMode
                .flatMapLatest { app.wavebits.host.android.WaveBitsRuntime.api.library.changes }
                .collect {
                    com.music.spotui.data.api.Api.HomeCache.libraryRoot = null
                    com.music.spotui.data.api.Api.HomeCache.library = null
                    load()
                    loadFollowedArtists()
                }
        }
        val cachedArtists = getCachedFollowedArtists(context)
        if (cachedArtists.isNotEmpty()) _followedArtists.value = cachedArtists

        load()
        loadAccount()
        loadFollowedArtists()

        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                app.wavebits.host.android.WaveBitsRuntime.backendMode,
                com.music.spotui.ui.platform.PlatformManager.switchVersion,
                app.wavebits.host.android.WaveBitsRuntime.youtubeAccount.isGuest,
                app.wavebits.host.android.WaveBitsRuntime.youtubeAccount.accountName,
            ) { mode, version, isGuest, accountName ->
                "$mode|$version|$isGuest|$accountName"
            }
                .drop(1)
                .collect {
                    com.music.spotui.data.api.Api.HomeCache.libraryRoot = null
                    com.music.spotui.data.api.Api.HomeCache.library = null
                    val platformCached = getCachedLibraryEntries(context)
                    server = platformCached.takeIf { it.isNotEmpty() }
                    _entries.value = if (platformCached.isNotEmpty()) Response.Success(platformCached) else Response.Loading()
                    _followedArtists.value = getCachedFollowedArtists(context)
                    load()
                    loadAccount()
                    loadFollowedArtists()
                }
        }
    }

    private val _refreshing = MutableStateFlow(false)
    /** A pull-to-refresh is reading the library again. */
    val refreshing: StateFlow<Boolean> = _refreshing

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            // Both flows end when Spotify has answered (or failed): that is when the spinner goes.
            kotlinx.coroutines.joinAll(load(), loadFollowedArtists())
            _refreshing.value = false
        }
    }

    fun load() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideLibrary().collect { response ->
            // Offline: keep the saved library instead of replacing it with a
            // spinner/error, so downloads stay reachable after a restart (#51).
            if (response !is Response.Success && _entries.value is Response.Success) return@collect
            // Nothing saved yet and no network: offer just the downloads (not cached).
            if (response is Response.Error) {
                _entries.value = Response.Success(emptyList())
                return@collect
            }
            if (response is Response.Success) {
                server = response.data
                show()
                cacheLibraryEntries(context, response.data)
            } else _entries.value = response
        }
    }

    private fun loadFollowedArtists() = viewModelScope.launch(Dispatchers.IO) {
        val artists = repository.provideFollowedArtists()
        _followedArtists.value = artists
        if (artists.isNotEmpty()) cacheFollowedArtists(context, artists)
    }

    private fun loadAccount() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideAccount().collect { _account.value = it }
    }
}
