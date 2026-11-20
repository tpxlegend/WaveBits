package com.music.spotui.data.discord

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.wavebits.host.android.WaveBitsRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/**
 * Discord Music Status / Rich Presence integration inspired by
 * https://github.com/SuperZombi/Discord-Music-Status
 *
 * Connects to Discord Gateway v10 over WebSocket (`wss://gateway.discord.gg/?v=10&encoding=json`)
 * and resolves album artwork via Discord's External Assets proxy so the user's current
 * WaveBits music playback (Spotify or YouTube Music) appears live on their
 * Discord profile with title, artist, album art, live timestamps progress bar, and listen buttons.
 */
object DiscordRpcManager {
    private const val TAG = "DiscordRpcManager"
    private const val PREFS_FILE = "wavebits_discord_rpc_prefs"
    private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
    private const val DISCORD_API_BASE = "https://discord.com/api/v10"

    /**
     * Default Discord Rich Presence Application ID used for external asset proxying and presence.
     * Users can also customize this in Advanced Settings.
     */
    const val DEFAULT_APPLICATION_ID = "1125820264922869850"

    enum class ActivityNameMode(val id: String, val label: String) {
        WAVEBITS("wavebits", "WaveBits"),
        PLATFORM("platform", "Music Platform (Spotify / YT Music)"),
        SONG_TITLE("song_title", "Song Title"),
        ARTIST_NAME("artist_name", "Artist Name"),
        CUSTOM("custom", "Custom Text");

        companion object {
            fun fromId(id: String?): ActivityNameMode =
                entries.firstOrNull { it.id == id } ?: WAVEBITS
        }
    }

    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    data class DiscordUser(
        val id: String,
        val username: String,
        val globalName: String,
        val avatarUrl: String?,
        val discriminator: String = "0",
    )

    data class TrackPresence(
        val id: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val coverUrl: String? = null,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val isPlaying: Boolean = false,
        val platform: String = "Spotify",
        val trackUrl: String? = null,
        val updatedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    )

    data class Config(
        val enabled: Boolean = false,
        val activityNameMode: ActivityNameMode = ActivityNameMode.WAVEBITS,
        val customActivityName: String = "Music",
        val prefixArtistWithBy: Boolean = true,
        val showTimestamps: Boolean = true,
        val showWhenPaused: Boolean = false,
        val showAlbumInLargeText: Boolean = true,
        val showListenButton: Boolean = false,
        val applicationId: String = DEFAULT_APPLICATION_ID,
        val webhookBridgeUrl: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()

    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _statusMessage = MutableStateFlow("Not connected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _user = MutableStateFlow<DiscordUser?>(null)
    val user: StateFlow<DiscordUser?> = _user.asStateFlow()

    private val _currentPresence = MutableStateFlow<TrackPresence?>(null)
    val currentPresence: StateFlow<TrackPresence?> = _currentPresence.asStateFlow()

    private val _hasToken = MutableStateFlow(false)
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

    @Volatile private var token: String = ""
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var lastSequence: Int? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var isGatewayReady = false
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var presenceDebounceJob: Job? = null
    private var lastSentSignature: String = ""
    private var lastSentAtMs: Long = 0L

    // LRU cache mapping original image URLs -> Discord `mp:external/...` asset keys
    private val assetCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 120
        }
    )

    fun init(context: Context) {
        val ctx = context.applicationContext ?: context
        appContext = ctx
        val storage = openSecurePrefs(ctx)
        prefs = storage

        token = storage.getString("discord_token", "").orEmpty().trim()
        _hasToken.value = token.isNotBlank()

        val persistedAppId = storage.getString("application_id", DEFAULT_APPLICATION_ID).orEmpty().trim().let { id ->
            if (Regex("^\\d{17,22}$").matches(id)) id else DEFAULT_APPLICATION_ID
        }
        val persistedWebhook = storage.getString("webhook_bridge_url", "").orEmpty().trim()
            .takeIf { isAllowedWebhookBridgeUrl(it) }.orEmpty()
        val loadedConfig = Config(
            enabled = storage.getBoolean("enabled", false),
            activityNameMode = ActivityNameMode.WAVEBITS,
            customActivityName = "WaveBits",
            prefixArtistWithBy = storage.getBoolean("prefix_artist_by", true),
            showTimestamps = storage.getBoolean("show_timestamps", true),
            showWhenPaused = storage.getBoolean("show_when_paused_v2", false),
            showAlbumInLargeText = storage.getBoolean("show_album_large_text", true),
            showListenButton = false,
            applicationId = persistedAppId,
            webhookBridgeUrl = persistedWebhook,
        )
        storage.edit().putBoolean("show_listen_button", false).apply()
        _config.value = loadedConfig

        // Restore cached Discord user profile for instant UI display
        val cachedUserId = storage.getString("user_id", null)
        val cachedUsername = storage.getString("user_username", null)
        if (!cachedUserId.isNullOrBlank() && !cachedUsername.isNullOrBlank()) {
            _user.value = DiscordUser(
                id = cachedUserId,
                username = cachedUsername,
                globalName = storage.getString("user_global_name", cachedUsername).orEmpty().ifBlank { cachedUsername },
                avatarUrl = storage.getString("user_avatar_url", null)?.takeIf { it.isNotBlank() },
            )
        }

        if (loadedConfig.enabled && (token.isNotBlank() || loadedConfig.webhookBridgeUrl.isNotBlank())) {
            connect()
        }
    }

    private fun openSecurePrefs(context: Context): SharedPreferences {
        return runCatching {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    fun updateConfig(transform: (Config) -> Config) {
        val previous = _config.value
        val candidate = transform(previous)
        val safeAppId = candidate.applicationId.trim().let { id ->
            if (Regex("^\\d{17,22}$").matches(id)) id else DEFAULT_APPLICATION_ID
        }
        val safeWebhook = candidate.webhookBridgeUrl.trim().takeIf { isAllowedWebhookBridgeUrl(it) }.orEmpty()
        val updated = candidate.copy(
            activityNameMode = ActivityNameMode.WAVEBITS,
            customActivityName = "WaveBits",
            showListenButton = false,
            applicationId = safeAppId,
            webhookBridgeUrl = safeWebhook,
        )
        _config.value = updated
        prefs?.edit()?.apply {
            putBoolean("enabled", updated.enabled)
            putString("activity_name_mode", ActivityNameMode.WAVEBITS.id)
            putString("custom_activity_name", "WaveBits")
            putBoolean("prefix_artist_by", updated.prefixArtistWithBy)
            putBoolean("show_timestamps", updated.showTimestamps)
            putBoolean("show_when_paused", updated.showWhenPaused)
            putBoolean("show_when_paused_v2", updated.showWhenPaused)
            putBoolean("show_album_large_text", updated.showAlbumInLargeText)
            putBoolean("show_listen_button", false)
            putString("application_id", updated.applicationId.ifBlank { DEFAULT_APPLICATION_ID })
            putString("webhook_bridge_url", updated.webhookBridgeUrl)
            apply()
        }

        if (!updated.enabled) {
            clearPresenceAndDisconnect()
        } else if (updated.enabled && !previous.enabled) {
            connect()
        } else if (updated.enabled) {
            // Re-push current presence with updated formatting
            lastSentSignature = ""
            pushPresenceNow()
        }
    }

    fun setToken(rawToken: String) {
        val cleaned = rawToken
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        token = cleaned
        _hasToken.value = cleaned.isNotBlank()
        prefs?.edit()?.putString("discord_token", cleaned)?.apply()
        if (cleaned.isNotBlank()) {
            val wasEnabled = _config.value.enabled
            if (!wasEnabled) {
                // updateConfig(enabled = true) will automatically call connect()
                updateConfig { it.copy(enabled = true) }
            } else {
                connect()
            }
        } else {
            disconnect()
        }
    }

    fun logout() {
        clearPresenceAndDisconnect()
        token = ""
        _hasToken.value = false
        _user.value = null
        _status.value = ConnectionStatus.DISCONNECTED
        _statusMessage.value = "Disconnected"
        prefs?.edit()?.apply {
            remove("discord_token")
            remove("user_id")
            remove("user_username")
            remove("user_global_name")
            remove("user_avatar_url")
            putBoolean("enabled", false)
            apply()
        }
        _config.value = _config.value.copy(enabled = false)
    }

    fun connect() {
        val cfg = _config.value
        if (token.isBlank() && cfg.webhookBridgeUrl.isBlank()) {
            _status.value = ConnectionStatus.DISCONNECTED
            _statusMessage.value = "Sign in with Discord to start"
            return
        }
        if (token.isBlank() && cfg.webhookBridgeUrl.isNotBlank()) {
            _status.value = ConnectionStatus.CONNECTED
            _statusMessage.value = "DMS Bridge Active"
            pushPresenceNow()
            return
        }

        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        runCatching { webSocket?.close(1000, "Reconnecting") }
        webSocket = null
        isGatewayReady = false

        _status.value = ConnectionStatus.CONNECTING
        _statusMessage.value = "Connecting to Discord Gateway..."

        scope.launch {
            fetchDiscordUserProfile()
        }

        val request = Request.Builder()
            .url(GATEWAY_URL)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Discord Gateway WebSocket opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleGatewayMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Discord Gateway closing: $code / $reason")
                isGatewayReady = false
                heartbeatJob?.cancel()
                if (code == 4004) {
                    // Authentication failed
                    _status.value = ConnectionStatus.ERROR
                    _statusMessage.value = "Invalid or expired Discord token (4004)"
                } else if (_config.value.enabled && token.isNotBlank()) {
                    scheduleReconnect()
                } else {
                    _status.value = ConnectionStatus.DISCONNECTED
                    _statusMessage.value = "Disconnected"
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Discord Gateway failure: ${t.message}")
                isGatewayReady = false
                heartbeatJob?.cancel()
                if (_config.value.enabled && token.isNotBlank()) {
                    _status.value = ConnectionStatus.ERROR
                    _statusMessage.value = "Connection lost, retrying..."
                    scheduleReconnect()
                } else {
                    _status.value = ConnectionStatus.DISCONNECTED
                    _statusMessage.value = "Disconnected"
                }
            }
        })
    }

    private fun scheduleReconnect(delayMs: Long = 6_000L) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (_config.value.enabled && token.isNotBlank() && !isGatewayReady) {
                connect()
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        isGatewayReady = false
        runCatching { webSocket?.close(1000, "User disconnected") }
        webSocket = null
        _status.value = ConnectionStatus.DISCONNECTED
        _statusMessage.value = "Paused / Disconnected"
    }

    private fun clearPresenceAndDisconnect() {
        if (isGatewayReady && webSocket != null) {
            val emptyPresence = JSONObject().apply {
                put("op", 3)
                put("d", JSONObject().apply {
                    put("since", JSONObject.NULL)
                    put("activities", JSONArray())
                    put("status", "online")
                    put("afk", false)
                })
            }
            runCatching { webSocket?.send(emptyPresence.toString()) }
        }
        disconnect()
    }

    private fun handleGatewayMessage(ws: WebSocket, raw: String) {
        runCatching {
            val json = JSONObject(raw)
            val op = json.optInt("op", -1)
            if (!json.isNull("s")) {
                lastSequence = json.optInt("s")
            }
            when (op) {
                10 -> { // HELLO
                    val d = json.optJSONObject("d")
                    val intervalMs = d?.optLong("heartbeat_interval", 41250L) ?: 41250L
                    startHeartbeat(ws, intervalMs)
                    sendIdentify(ws)
                }
                0 -> { // DISPATCH
                    val eventType = json.optString("t")
                    val d = json.optJSONObject("d")
                    if (eventType == "READY" && d != null) {
                        sessionId = d.optString("session_id")
                        d.optJSONObject("user")?.let { parseAndSaveUser(it) }
                        isGatewayReady = true
                        _status.value = ConnectionStatus.CONNECTED
                        val username = _user.value?.let { "@${it.username}" } ?: "Discord"
                        _statusMessage.value = "Connected ($username)"
                        lastSentSignature = ""
                        pushPresenceNow()
                    }
                }
                1 -> { // Heartbeat requested immediately
                    sendHeartbeatPacket(ws)
                }
                7 -> { // Reconnect requested by Discord
                    scheduleReconnect(1_500L)
                }
                9 -> { // Invalid Session
                    sessionId = null
                    scheduleReconnect(3_000L)
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed handling gateway message: ${e.message}")
        }
    }

    private fun startHeartbeat(ws: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay(1_500L)
            while (isActive) {
                sendHeartbeatPacket(ws)
                delay(intervalMs.coerceAtLeast(15_000L))
            }
        }
    }

    private fun sendHeartbeatPacket(ws: WebSocket) {
        val payload = JSONObject().apply {
            put("op", 1)
            put("d", lastSequence ?: JSONObject.NULL)
        }
        runCatching { ws.send(payload.toString()) }
    }

    private fun sendIdentify(ws: WebSocket) {
        val currentToken = token
        if (currentToken.isBlank()) return
        val identify = JSONObject().apply {
            put("op", 2)
            put("d", JSONObject().apply {
                put("token", currentToken)
                put("capabilities", 65)
                put("properties", JSONObject().apply {
                    put("os", "Android")
                    put("browser", "Discord Android")
                    put("device", "WaveBits")
                })
                put("compress", false)
            })
        }
        runCatching { ws.send(identify.toString()) }
    }

    private suspend fun fetchDiscordUserProfile() = withContext(Dispatchers.IO) {
        val currentToken = token
        if (currentToken.isBlank()) return@withContext
        runCatching {
            val req = Request.Builder()
                .url("$DISCORD_API_BASE/users/@me")
                .header("Authorization", currentToken)
                .header("User-Agent", "Discord-Android/205214")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        parseAndSaveUser(JSONObject(body))
                    }
                } else if (resp.code == 401) {
                    _status.value = ConnectionStatus.ERROR
                    _statusMessage.value = "Unauthorized token (401) - please sign in again"
                }
            }
        }
    }

    private fun parseAndSaveUser(userObj: JSONObject) {
        val id = userObj.optString("id")
        val username = userObj.optString("username")
        if (id.isBlank() || username.isBlank()) return
        val globalName = userObj.optString("global_name").takeIf { it.isNotBlank() && it != "null" } ?: username
        val avatarHash = userObj.optString("avatar").takeIf { it.isNotBlank() && it != "null" }
        val avatarUrl = if (avatarHash != null) {
            "https://cdn.discordapp.com/avatars/$id/$avatarHash.png?size=256"
        } else {
            "https://cdn.discordapp.com/embed/avatars/0.png"
        }
        val discordUser = DiscordUser(
            id = id,
            username = username,
            globalName = globalName,
            avatarUrl = avatarUrl,
        )
        _user.value = discordUser
        prefs?.edit()?.apply {
            putString("user_id", id)
            putString("user_username", username)
            putString("user_global_name", globalName)
            putString("user_avatar_url", avatarUrl)
            apply()
        }
    }

    /**
     * Called automatically by [com.music.spotui.di.SongPlayer] whenever track metadata,
     * playback state, or seek position changes.
     */
    fun onPlaybackUpdate(
        id: String,
        title: String,
        artist: String,
        album: String,
        coverUrl: String?,
        durationMs: Long,
        positionMs: Long,
        playing: Boolean,
    ) {
        if (title.isBlank() || id == "spotify:ad") return

        val platformName = when {
            WaveBitsRuntime.isStarted && WaveBitsRuntime.isYouTube.value -> "YouTube Music"
            else -> "Spotify"
        }

        val trackUrl = buildTrackUrl(id, title, artist, platformName)

        val presence = TrackPresence(
            id = id,
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            coverUrl = coverUrl?.takeIf { it.isNotBlank() },
            durationMs = durationMs.coerceAtLeast(0L),
            positionMs = positionMs.coerceAtLeast(0L),
            isPlaying = playing,
            platform = platformName,
            trackUrl = trackUrl,
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        _currentPresence.value = presence

        if (!_config.value.enabled) return

        // Debounce rapid updates while still responding quickly to song changes and play/pause
        presenceDebounceJob?.cancel()
        presenceDebounceJob = scope.launch {
            delay(850L)
            sendPresenceInternal(presence, force = false)
        }
    }

    fun onSeek(newPositionMs: Long) {
        val current = _currentPresence.value ?: return
        val updated = current.copy(
            positionMs = newPositionMs.coerceAtLeast(0L),
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        _currentPresence.value = updated
        if (!_config.value.enabled) return
        presenceDebounceJob?.cancel()
        presenceDebounceJob = scope.launch {
            delay(600L)
            sendPresenceInternal(updated, force = true)
        }
    }

    fun onSessionCleared() {
        _currentPresence.value = null
        lastSentSignature = ""
        if (isGatewayReady && webSocket != null) {
            val emptyPresence = JSONObject().apply {
                put("op", 3)
                put("d", JSONObject().apply {
                    put("since", JSONObject.NULL)
                    put("activities", JSONArray())
                    put("status", "online")
                    put("afk", false)
                })
            }
            runCatching { webSocket?.send(emptyPresence.toString()) }
        }
    }

    fun pushPresenceNow() {
        var p = _currentPresence.value
        if (p == null) {
            val state = runCatching { com.music.spotui.di.Graph.currentSongState }.getOrNull()
            val title = state?.title?.value.orEmpty().trim()
            if (title.isNotBlank()) {
                val platformName = when {
                    WaveBitsRuntime.isStarted && WaveBitsRuntime.isYouTube.value -> "YouTube Music"
                    else -> "Spotify"
                }
                val id = state?.songId?.value?.toString().orEmpty()
                val artist = state?.singer?.value.orEmpty().trim()
                p = TrackPresence(
                    id = id,
                    title = title,
                    artist = artist,
                    album = state?.album?.value.orEmpty().trim(),
                    coverUrl = state?.coverUri?.value?.takeIf { it.isNotBlank() },
                    durationMs = runCatching { com.music.spotui.di.SongPlayer.getDuration() }.getOrDefault(0L).coerceAtLeast(0L),
                    positionMs = runCatching { com.music.spotui.di.SongPlayer.getCurrentPosition() }.getOrDefault(0L).coerceAtLeast(0L),
                    isPlaying = state?.playingState?.value ?: false,
                    platform = platformName,
                    trackUrl = buildTrackUrl(id, title, artist, platformName),
                    updatedAtElapsedMs = SystemClock.elapsedRealtime(),
                )
                _currentPresence.value = p
            }
        }
        val targetPresence = p ?: return
        scope.launch {
            sendPresenceInternal(targetPresence, force = true)
        }
    }

    private fun buildTrackUrl(id: String, title: String, artist: String, platform: String): String {
        return "https://wavebits.vercel.app"
    }

    private suspend fun sendPresenceInternal(presence: TrackPresence, force: Boolean) = withContext(Dispatchers.IO) {
        val cfg = _config.value
        if (!cfg.enabled) return@withContext

        // Send to optional SuperZombi/Discord-Music-Status companion webhook if configured
        if (cfg.webhookBridgeUrl.isNotBlank()) {
            sendWebhookBridgePayload(cfg.webhookBridgeUrl, presence)
        }

        if (token.isBlank()) return@withContext
        if (!isGatewayReady || webSocket == null) {
            if (_status.value != ConnectionStatus.CONNECTING) {
                connect()
            }
            return@withContext
        }

        // If paused and user disabled "Show status when paused", clear activity
        if (!presence.isPlaying && !cfg.showWhenPaused) {
            val clearSignature = "PAUSED_CLEARED"
            if (!force && lastSentSignature == clearSignature) return@withContext
            lastSentSignature = clearSignature
            val payload = JSONObject().apply {
                put("op", 3)
                put("d", JSONObject().apply {
                    put("since", JSONObject.NULL)
                    put("activities", JSONArray())
                    put("status", "online")
                    put("afk", false)
                })
            }
            runCatching { webSocket?.send(payload.toString()) }
            return@withContext
        }

        val elapsedSinceSnapshot = (SystemClock.elapsedRealtime() - presence.updatedAtElapsedMs).coerceAtLeast(0L)
        val livePositionMs = if (presence.isPlaying) {
            (presence.positionMs + elapsedSinceSnapshot).let { pos ->
                if (presence.durationMs > 0) pos.coerceAtMost(presence.durationMs) else pos
            }
        } else {
            presence.positionMs
        }

        // Avoid spamming identical gateway updates within a few seconds unless forced (e.g. seek)
        val posBucket = livePositionMs / 5000L
        val signature = "${presence.id}|${presence.title}|${presence.artist}|${presence.isPlaying}|${cfg.activityNameMode}|${cfg.showTimestamps}|${cfg.showListenButton}|${cfg.prefixArtistWithBy}"
        val now = SystemClock.elapsedRealtime()
        if (!force && signature == lastSentSignature && (now - lastSentAtMs) < 4_000L && posBucket == 0L) {
            return@withContext
        }
        lastSentSignature = signature
        lastSentAtMs = now

        val appId = cfg.applicationId.ifBlank { DEFAULT_APPLICATION_ID }
        val activityName = "WaveBits"

        val detailsText = presence.title.ifBlank { "Unknown Track" }.take(128)
        val rawArtist = presence.artist.ifBlank { "Unknown Artist" }
        val stateText = (if (cfg.prefixArtistWithBy) "by $rawArtist" else rawArtist).let {
            if (!presence.isPlaying) "$it (Paused)" else it
        }.take(128)

        val largeText = when {
            cfg.showAlbumInLargeText && presence.album.isNotBlank() -> presence.album
            else -> "Listening on ${presence.platform} • WaveBits"
        }.take(128)

        val resolvedAsset = presence.coverUrl?.let { resolveDiscordExternalAsset(appId, it) }

        val activityObj = JSONObject().apply {
            put("name", activityName)
            // Discord client UI renders external Rich Presence action buttons exclusively on `type: 0` (Playing).
            // Activity type 2 (Listening) suppresses custom external action buttons (reserved by Discord for Spotify sync).
            put("type", if (cfg.showListenButton) 0 else 2)
            put("application_id", appId)
            put("details", detailsText)
            put("state", stateText)

            if (cfg.showTimestamps && presence.isPlaying && presence.durationMs > 0L) {
                val nowEpochMs = System.currentTimeMillis()
                val startMs = nowEpochMs - livePositionMs
                val endMs = startMs + presence.durationMs
                put("timestamps", JSONObject().apply {
                    put("start", startMs)
                    put("end", endMs)
                })
            }

            val assetsObj = JSONObject()
            if (!resolvedAsset.isNullOrBlank()) {
                assetsObj.put("large_image", resolvedAsset)
            }
            if (largeText.length >= 2) {
                assetsObj.put("large_text", largeText)
            }
            assetsObj.put(
                "small_text",
                if (presence.isPlaying) "Playing on ${presence.platform} • WaveBits" else "Paused • WaveBits"
            )
            put("assets", assetsObj)

            if (cfg.showListenButton) {
                put("buttons", JSONArray().apply {
                    put("Visit WaveBits")
                })
                put("metadata", JSONObject().apply {
                    put("button_urls", JSONArray().apply {
                        put("https://wavebits.vercel.app")
                    })
                })
            }
        }

        val gatewayPayload = JSONObject().apply {
            put("op", 3)
            put("d", JSONObject().apply {
                put("since", JSONObject.NULL)
                put("activities", JSONArray().put(activityObj))
                put("status", "online")
                put("afk", false)
            })
        }

        val sent = runCatching { webSocket?.send(gatewayPayload.toString()) ?: false }.getOrDefault(false)
        if (!sent) {
            isGatewayReady = false
            scheduleReconnect(2_000L)
        }
    }

    private fun cacheAsset(key: String, value: String) {
        // assetCache is a synchronized LRU LinkedHashMap capped at 120 entries
        assetCache[key] = value
    }

    private fun isAllowedWebhookBridgeUrl(rawUrl: String): Boolean {
        if (rawUrl.isBlank() || rawUrl.length > 1024) return false
        val parsed = runCatching { Request.Builder().url(rawUrl).build().url }.getOrNull() ?: return false
        if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        val host = parsed.host.lowercase().trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        val v4 = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }
        if (v4 != null) {
            val (a, b) = v4
            if (a == 10 || a == 127 || a == 0 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)) {
                return false
            }
        }
        return !host.startsWith("[") && !host.contains(':')
    }

    /**
     * Resolves an arbitrary HTTPS cover URL (Spotify CDN, YouTube thumbnail, etc.) into a
     * Discord `mp:external/...` asset key using Discord's External Assets endpoint, with a
     * deterministic fallback if the endpoint rate-limits.
     */
    private fun resolveDiscordExternalAsset(appId: String, imageUrl: String): String? {
        if (!imageUrl.startsWith("https://") || imageUrl.length > 1024) return null
        val safeAppId = if (Regex("^\\d{17,22}$").matches(appId)) appId else DEFAULT_APPLICATION_ID
        assetCache[imageUrl]?.let { return it }

        if (token.isNotBlank()) {
            runCatching {
                val bodyJson = JSONObject().apply {
                    put("urls", JSONArray().put(imageUrl))
                }
                val req = Request.Builder()
                    .url("https://discord.com/api/v9/applications/$safeAppId/external-assets")
                    .header("Authorization", token)
                    .header("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        if (arr.length() > 0) {
                            val externalPath = arr.getJSONObject(0).optString("external_asset_path")
                            if (externalPath.isNotBlank()) {
                                val formatted = "mp:$externalPath"
                                cacheAsset(imageUrl, formatted)
                                return formatted
                            }
                        }
                    }
                }
            }
        }

        // Spotify image CDN native Discord shorthand (`spotify:<hash>`) when cover is from i.scdn.co/image/
        if (imageUrl.contains("i.scdn.co/image/")) {
            val hash = imageUrl.substringAfterLast('/').substringBefore('?').trim()
            if (hash.isNotBlank() && hash.all { it.isLetterOrDigit() }) {
                val spotifyAsset = "spotify:$hash"
                cacheAsset(imageUrl, spotifyAsset)
                return spotifyAsset
            }
        }

        val withoutScheme = imageUrl.replaceFirst("https://", "https/")
        val fallback = "mp:external/$withoutScheme"
        cacheAsset(imageUrl, fallback)
        return fallback
    }

    private fun sendWebhookBridgePayload(url: String, presence: TrackPresence) {
        if (!isAllowedWebhookBridgeUrl(url)) return
        runCatching {
            val targetHost = java.net.URI(url).host ?: return
            val addrs = java.net.InetAddress.getAllByName(targetHost)
            if (addrs.isEmpty() || addrs.any { app.wavebits.extensions.ExtensionRuntime.isPrivateAddress(it) }) return
            val payload = JSONObject().apply {
                put("app", "WaveBits")
                put("source", "Discord-Music-Status")
                put("title", presence.title.take(128))
                put("artist", presence.artist.take(128))
                put("album", presence.album.take(128))
                put("coverUrl", presence.coverUrl?.takeIf { it.startsWith("https://") } ?: "")
                put("durationMs", presence.durationMs)
                put("positionMs", presence.positionMs)
                put("isPlaying", presence.isPlaying)
                put("platform", presence.platform.take(32))
                put("trackUrl", presence.trackUrl ?: "")
                put("timestamp", System.currentTimeMillis())
            }
            val req = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().close()
        }
    }
}
