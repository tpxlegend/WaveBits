package com.music.spotui.data.api

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.wavebits.api.best
import app.wavebits.host.android.BackendMode
import app.wavebits.host.android.WaveBitsRuntime
import com.music.spotui.ui.platform.PlatformManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** The signed-in profile (name, avatar, user id), fetched once per session. Compose state. */
object ProfileCache {
    var name by mutableStateOf<String?>(null)
        private set
    var imageUrl by mutableStateOf<String?>(null)
        private set
    /** Spotify user id (owner of the playlists the user can edit) or platform marker. */
    var id by mutableStateOf<String?>(null)
        private set

    private val fetching = AtomicBoolean(false)
    private val generation = java.util.concurrent.atomic.AtomicLong()

    fun clearSession() {
        generation.incrementAndGet()
        fetching.set(false)
        name = null
        imageUrl = null
        id = null
    }

    fun ensure(context: Context) {
        val mode = WaveBitsRuntime.backendMode.value
        if (mode == BackendMode.JIOSAAVN) {
            name = PlatformManager.getSavedAccountName(context, BackendMode.JIOSAAVN) ?: "JioSaavn"
            imageUrl = PlatformManager.getSavedAccountAvatar(context, BackendMode.JIOSAAVN)
            id = "jiosaavn"
            return
        }
        if (mode == BackendMode.YOUTUBE) {
            val isGuest = PlatformManager.isYouTubeGuest(context)
            val ytName = PlatformManager.sanitizeYouTubeName(
                PlatformManager.getSavedAccountName(context, BackendMode.YOUTUBE)
            ) ?: if (isGuest) "Google Guest" else "YouTube Music"
            val ytAvatar = if (isGuest) null else PlatformManager.getSavedAccountAvatar(context, BackendMode.YOUTUBE)
            name = ytName
            imageUrl = ytAvatar
            id = if (isGuest) "youtube_guest" else "youtube"
            if (isGuest) return
            if (!fetching.compareAndSet(false, true)) return
            val requestGeneration = generation.get()
            WaveBitsRuntime.scope.launch {
                try {
                    val me = runCatching { WaveBitsRuntime.api.users.me() }.getOrNull() ?: return@launch
                    if (generation.get() != requestGeneration || WaveBitsRuntime.backendMode.value != BackendMode.YOUTUBE) return@launch
                    val resolvedName = PlatformManager.sanitizeYouTubeName(me.name) ?: ytName
                    val resolvedAvatar = me.images.best(160)?.url?.takeIf { it.isNotBlank() } ?: ytAvatar
                    name = resolvedName
                    imageUrl = resolvedAvatar
                    PlatformManager.saveYouTubeSession(context, resolvedName, resolvedAvatar)
                } finally {
                    fetching.set(false)
                }
            }
            return
        }

        // Spotify mode: immediately seed from saved Spotify profile if available, then refresh from API
        if (name == null) {
            PlatformManager.getSavedAccountName(context, BackendMode.SPOTIFY)?.let { savedName ->
                name = savedName
                imageUrl = PlatformManager.getSavedAccountAvatar(context, BackendMode.SPOTIFY)
                id = PlatformManager.getSavedSpotifyUserId(context)
            }
        }
        if (!fetching.compareAndSet(false, true)) return
        val requestGeneration = generation.get()
        WaveBitsRuntime.scope.launch {
            try {
                WaveBitsRuntime.api.ready.first { it }
                val me = runCatching { WaveBitsRuntime.api.users.me() }.getOrNull() ?: return@launch
                if (generation.get() != requestGeneration || WaveBitsRuntime.backendMode.value != BackendMode.SPOTIFY) return@launch
                val resolvedName = me.name
                val resolvedAvatar = me.images.best(160)?.url
                val resolvedId = me.username.takeIf { it.isNotBlank() }
                name = resolvedName
                imageUrl = resolvedAvatar
                id = resolvedId
                PlatformManager.saveSpotifySession(context, resolvedName, resolvedAvatar, resolvedId)
            } finally {
                fetching.set(false)
            }
        }
    }
}
