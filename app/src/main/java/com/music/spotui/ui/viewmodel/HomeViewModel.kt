package com.music.spotui.ui.viewmodel

import com.music.spotui.di.Graph
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class HomeViewModel constructor(private val repository: AppRepository = Graph.repository)  : ViewModel() {

    private val _home : MutableStateFlow<Response<HomeFeedModel>> = MutableStateFlow(Response.Loading())
    val home : StateFlow<Response<HomeFeedModel>> = _home

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore

    /** Selected home chip ("" = All). */
    val facet = androidx.compose.runtime.mutableStateOf("")
    private var savedChips: List<com.music.spotui.data.entity.HomeChipModel> = emptyList()
    private var homeJob: kotlinx.coroutines.Job? = null
    private var loadMoreJob: kotlinx.coroutines.Job? = null

    init {
        fetchHome()
        // Reload immediately when switching streaming platforms or signing in/out of YouTube Music.
        viewModelScope.launch {
            combine(
                app.wavebits.host.android.WaveBitsRuntime.backendMode,
                com.music.spotui.ui.platform.PlatformManager.switchVersion,
                app.wavebits.host.android.WaveBitsRuntime.youtubeAccount.isGuest,
                app.wavebits.host.android.WaveBitsRuntime.youtubeAccount.accountName,
            ) { mode, version, isGuest, accountName ->
                "$mode|$version|$isGuest|$accountName"
            }
                .drop(1)
                .collect {
                    com.music.spotui.data.api.Api.HomeCache.home = null
                    com.music.spotui.data.api.Api.HomeCache.facets.clear()
                    facet.value = ""
                    savedChips = emptyList()
                    _loadingMore.value = false
                    _home.value = Response.Loading()
                    fetchHome()
                }
        }
        // A Home that failed (player still signing in, network back) loads again by itself:
        // when the player is ready again, then a few times with growing pauses.
        viewModelScope.launch {
            app.wavebits.host.android.WaveBitsRuntime.api.ready.collect { ready ->
                if (ready && _home.value is Response.Error) fetchHome()
            }
        }
        viewModelScope.launch {
            var tries = 0
            _home.collect { state ->
                if (state is Response.Success) tries = 0
                if (state is Response.Error && tries < RETRY_DELAYS_MS.size) {
                    kotlinx.coroutines.delay(RETRY_DELAYS_MS[tries++])
                    if (_home.value is Response.Error) fetchHome()
                }
            }
        }
    }

    /** Tries again now (the Home error's button). */
    fun retry() {
        _home.value = Response.Loading()
        fetchHome()
    }

    fun selectFacet(f: String) {
        facet.value = if (facet.value == f) "" else f
        fetchHome()
    }

    fun loadMore() {
        val current = (_home.value as? Response.Success)?.data ?: return
        val cursor = current.continuation ?: return
        if (cursor.isBlank() || _loadingMore.value) return
        _loadingMore.value = true
        val activeFacet = facet.value
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val more = repository.provideHomeFeedContinuation(
                    cursor = cursor,
                    params = activeFacet.takeIf { it.isNotBlank() },
                )
                if (more != null && facet.value == activeFacet) {
                    val latest = (_home.value as? Response.Success)?.data ?: current
                    val combinedSections = (latest.sections + more.sections)
                        .distinctBy { (it.strapline.orEmpty() + "|" + it.title).lowercase().ifBlank { it.hashCode().toString() } }
                    _home.value = Response.Success(
                        latest.copy(
                            sections = combinedSections,
                            continuation = more.continuation?.takeIf { it != cursor && more.sections.isNotEmpty() },
                        )
                    )
                }
            } finally {
                _loadingMore.value = false
            }
        }
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 15_000, 30_000)
    }

    private fun fetchHome() {
        homeJob?.cancel()
        loadMoreJob?.cancel()
        _loadingMore.value = false
        val f = facet.value
        homeJob = viewModelScope.launch(Dispatchers.IO) {
            repository.provideHomeFeed(f).collect { res ->
                if (res is Response.Success && res.data != null) {
                    if (res.data.chips.isNotEmpty()) {
                        savedChips = res.data.chips
                    }
                    val merged = if (res.data.chips.isEmpty() && savedChips.isNotEmpty()) {
                        res.data.copy(chips = savedChips)
                    } else {
                        res.data
                    }
                    _home.value = Response.Success(merged)
                } else {
                    _home.value = res
                }
            }
        }
    }
}
