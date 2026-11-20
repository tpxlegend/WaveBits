package com.music.spotui.data.listentogether

import android.os.Build
import app.wavebits.host.android.BackendMode
import app.wavebits.host.android.Logbook
import app.wavebits.host.android.WaveBitsRuntime
import com.music.spotui.di.SongPlayer
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

data class ListenTogetherPeer(
    val id: String,
    val name: String,
    val isHost: Boolean,
    val synced: Boolean = true,
    val driftMs: Long = 0L,
)

data class ListenTogetherSession(
    val roomId: String,
    val inviteUrl: String,
    val platform: BackendMode,
    val isHost: Boolean,
    val selfId: String,
    val selfName: String,
    val peers: List<ListenTogetherPeer>,
    val guestControlAllowed: Boolean = true,
    val syncStatus: String = "Synced",
    val currentTrackTitle: String? = null,
    val currentTrackArtist: String? = null,
)

/**
 * Listen Together (ported/adapted from https://github.com/jofr/listentogether-app)
 * for real-time peer-to-peer synchronized playback exclusively on YouTube Music and JioSaavn.
 *
 * Uses PeerJS-compatible signaling (`0.peerjs.com`) and real-time playback state packets
 * (`SYNC_STATE`, `JOIN_REQ`, `PEER_LIST`, `RESYNC_REQ`, `LEAVE`) to synchronize track URI,
 * play/pause state, and playback position with drift compensation across connected peers.
 */
object ListenTogetherManager {
    private const val PEERJS_WS_BASE = "wss://0.peerjs.com/peerjs?key=peerjs"
    private const val DRIFT_TOLERANCE_MS = 1_800L
    private const val HEARTBEAT_INTERVAL_MS = 2_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _session = MutableStateFlow<ListenTogetherSession?>(null)
    val session: StateFlow<ListenTogetherSession?> = _session.asStateFlow()

    private var webSocket: WebSocket? = null
    private var syncJob: Job? = null
    private var platformWatchJob: Job? = null
    @Volatile private var applyingRemoteSync = false
    private var lastAppliedTrackUri: String = ""
    private var lastBroadcastHash: Int = 0

    init {
        // Automatically stop Listen Together if the user switches to Spotify (Spotify uses Jam).
        platformWatchJob = scope.launch {
            WaveBitsRuntime.backendMode.collect { mode ->
                if (mode == BackendMode.SPOTIFY && _session.value != null) {
                    leaveSession()
                }
            }
        }
    }

    /** True only when the active platform supports Listen Together (YouTube Music or JioSaavn). */
    fun isSupportedOnCurrentPlatform(): Boolean {
        val mode = WaveBitsRuntime.backendMode.value
        return mode == BackendMode.YOUTUBE || mode == BackendMode.JIOSAAVN
    }

    private fun defaultUserName(): String {
        val mode = WaveBitsRuntime.backendMode.value
        if (mode == BackendMode.JIOSAAVN) {
            val jioName = runCatching {
                com.music.spotui.data.preferences.JioSaavnGuestProfile.guestName
            }.getOrNull()?.takeIf { it.isNotBlank() && !it.equals("JioSaavn Guest", ignoreCase = true) }
            if (jioName != null) return jioName
        }
        val ytName = if (WaveBitsRuntime.isStarted) {
            WaveBitsRuntime.youtubeAccount.accountName.value?.takeIf { it.isNotBlank() }
        } else null
        return ytName ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "WaveBits Listener"
    }

    private fun generateRoomCode(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val part1 = (1..4).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        val part2 = (1..4).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        return "lt-$part1-$part2"
    }

    fun extractRoomId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        Regex("""(?:room=|#room=|/listentogether/|#)(lt-[a-z0-9]{4}-[a-z0-9]{4}|[A-Za-z0-9_-]{4,32})""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.getOrNull(1)?.let { return it.lowercase() }
        if (trimmed.matches(Regex("""^(?:lt-)?[a-zA-Z0-9_-]{4,32}$"""))) {
            return trimmed.lowercase()
        }
        return null
    }

    fun startSession(customName: String? = null): ListenTogetherSession {
        val mode = WaveBitsRuntime.backendMode.value
        require(mode == BackendMode.YOUTUBE || mode == BackendMode.JIOSAAVN) {
            "Listen Together is only available on YouTube Music and JioSaavn"
        }
        leaveSession()

        val roomId = generateRoomCode()
        val selfName = sanitizePeerText(customName, maxLen = 40, fallback = defaultUserName())
        val hostPeerId = "wb-${roomId}-host"
        val currentTrack = SongPlayer.engineQueue.value.current
        val currentItem = if (WaveBitsRuntime.isStarted) WaveBitsRuntime.api.player.state.value?.item else null
        val trackTitle = currentTrack?.title?.takeIf { it.isNotBlank() } ?: currentItem?.name?.takeIf { it.isNotBlank() }
        val trackArtist = currentTrack?.singer?.takeIf { it.isNotBlank() }
            ?: currentItem?.artists?.joinToString { it.name }?.takeIf { it.isNotBlank() }
        val initialSession = ListenTogetherSession(
            roomId = roomId,
            inviteUrl = "https://listentogether.app/?room=$roomId",
            platform = mode,
            isHost = true,
            selfId = hostPeerId,
            selfName = selfName,
            peers = listOf(
                ListenTogetherPeer(
                    id = hostPeerId,
                    name = "$selfName (Host)",
                    isHost = true,
                    synced = true,
                    driftMs = 0L,
                )
            ),
            guestControlAllowed = true,
            syncStatus = "Hosting • Waiting for peers",
            currentTrackTitle = trackTitle,
            currentTrackArtist = trackArtist,
        )
        _session.value = initialSession
        connectSignaling(peerId = hostPeerId, targetHostPeerId = null)
        startSyncLoop()
        Logbook.log("ListenTogether: started room $roomId on $mode")
        return initialSession
    }

    fun joinSession(rawCodeOrLink: String, customName: String? = null): Result<ListenTogetherSession> {
        val mode = WaveBitsRuntime.backendMode.value
        if (mode != BackendMode.YOUTUBE && mode != BackendMode.JIOSAAVN) {
            return Result.failure(IllegalStateException("Listen Together is only supported on YouTube Music and JioSaavn"))
        }
        val roomId = extractRoomId(rawCodeOrLink)
            ?: return Result.failure(IllegalArgumentException("Invalid Listen Together room code or link"))

        leaveSession()

        val selfName = sanitizePeerText(customName, maxLen = 40, fallback = defaultUserName())
        val suffix = (1000..9999).random()
        val selfPeerId = "wb-${roomId}-peer-$suffix"
        val hostPeerId = "wb-${roomId}-host"
        val currentTrack = SongPlayer.engineQueue.value.current
        val currentItem = if (WaveBitsRuntime.isStarted) WaveBitsRuntime.api.player.state.value?.item else null
        val trackTitle = currentTrack?.title?.takeIf { it.isNotBlank() } ?: currentItem?.name?.takeIf { it.isNotBlank() }
        val trackArtist = currentTrack?.singer?.takeIf { it.isNotBlank() }
            ?: currentItem?.artists?.joinToString { it.name }?.takeIf { it.isNotBlank() }
        val joinedSession = ListenTogetherSession(
            roomId = roomId,
            inviteUrl = "https://listentogether.app/?room=$roomId",
            platform = mode,
            isHost = false,
            selfId = selfPeerId,
            selfName = selfName,
            peers = listOf(
                ListenTogetherPeer(
                    id = hostPeerId,
                    name = "Room Host",
                    isHost = true,
                    synced = true,
                    driftMs = 0L,
                ),
                ListenTogetherPeer(
                    id = selfPeerId,
                    name = "$selfName (You)",
                    isHost = false,
                    synced = true,
                    driftMs = 0L,
                ),
            ),
            guestControlAllowed = true,
            syncStatus = "Connected • Syncing playback",
            currentTrackTitle = trackTitle,
            currentTrackArtist = trackArtist,
        )
        _session.value = joinedSession
        connectSignaling(peerId = selfPeerId, targetHostPeerId = hostPeerId)
        startSyncLoop()
        Logbook.log("ListenTogether: joined room $roomId on $mode")
        return Result.success(joinedSession)
    }

    fun setGuestControl(allowed: Boolean) {
        val current = _session.value ?: return
        if (!current.isHost) return
        _session.value = current.copy(guestControlAllowed = allowed)
        broadcastState(force = true)
    }

    fun syncNow() {
        val current = _session.value ?: return
        if (current.isHost) {
            broadcastState(force = true)
            _session.value = current.copy(syncStatus = "Live • Broadcasted sync pulse")
        } else {
            sendSignalingPayload(
                dst = "wb-${current.roomId}-host",
                payload = JSONObject().apply {
                    put("cmd", "RESYNC_REQ")
                    put("senderId", current.selfId)
                    put("senderName", current.selfName)
                }
            )
            _session.value = current.copy(syncStatus = "Synced with Host")
        }
    }

    fun leaveSession() {
        val current = _session.value
        if (current != null) {
            runCatching {
                val targets = if (current.isHost) {
                    current.peers.filter { !it.isHost }.map { it.id }
                } else {
                    listOf("wb-${current.roomId}-host")
                }
                targets.forEach { dst ->
                    sendSignalingPayload(
                        dst = dst,
                        payload = JSONObject().apply {
                            put("cmd", "LEAVE")
                            put("senderId", current.selfId)
                        }
                    )
                }
            }
        }
        syncJob?.cancel()
        syncJob = null
        runCatching { webSocket?.close(1000, "Left session") }
        webSocket = null
        _session.value = null
        lastAppliedTrackUri = ""
        lastBroadcastHash = 0
    }

    private fun connectSignaling(peerId: String, targetHostPeerId: String?) {
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        val wsUrl = "$PEERJS_WS_BASE&id=$peerId&token=$token"
        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", "WaveBits-ListenTogether/1.0")
            .build()

        ioScope.launch {
            runCatching {
                webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        scope.launch {
                            val s = _session.value ?: return@launch
                            if (!s.isHost && targetHostPeerId != null) {
                                sendSignalingPayload(
                                    dst = targetHostPeerId,
                                    payload = JSONObject().apply {
                                        put("cmd", "JOIN_REQ")
                                        put("senderId", s.selfId)
                                        put("senderName", s.selfName)
                                    }
                                )
                                _session.value = s.copy(syncStatus = "Live • Connected to room ${s.roomId}")
                            } else if (s.isHost) {
                                _session.value = s.copy(syncStatus = "Live • Room ready (${s.peers.size} listening)")
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleSignalingMessage(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        scope.launch {
                            val s = _session.value ?: return@launch
                            _session.value = s.copy(
                                syncStatus = if (s.isHost) "Hosting • Reconnecting P2P..." else "Connected • Reconnecting P2P..."
                            )
                            delay(3_000L)
                            val active = _session.value ?: return@launch
                            if (active.selfId == peerId) {
                                connectSignaling(peerId = peerId, targetHostPeerId = targetHostPeerId)
                            }
                        }
                    }
                })
            }
        }
    }

    private fun sendSignalingPayload(dst: String, payload: JSONObject) {
        val ws = webSocket ?: return
        val envelope = JSONObject().apply {
            put("type", "CANDIDATE")
            put("dst", dst)
            put("payload", payload)
        }
        ioScope.launch {
            runCatching { ws.send(envelope.toString()) }
        }
    }

    private val signalingTimestamps = ArrayDeque<Long>()

    @Synchronized
    private fun allowSignalingMessage(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        while (signalingTimestamps.isNotEmpty() && now - signalingTimestamps.first() > 10_000L) {
            signalingTimestamps.removeFirst()
        }
        if (signalingTimestamps.size >= 40) return false
        signalingTimestamps.addLast(now)
        return true
    }

    private fun sanitizePeerText(raw: String?, maxLen: Int = 80, fallback: String = ""): String {
        val cleaned = raw.orEmpty()
            .replace(Regex("<[^>]*>"), "")
            .filter { ch -> ch.code >= 0x20 && ch != '<' && ch != '>' }
            .trim()
            .take(maxLen)
        return cleaned.ifBlank { fallback }
    }

    private fun sanitizePeerId(raw: String?): String {
        val candidate = raw.orEmpty().trim()
        return if (Regex("^[A-Za-z0-9_-]{3,64}$").matches(candidate)) candidate else ""
    }

    private fun handleSignalingMessage(rawText: String) {
        if (rawText.length > 16_384 || !allowSignalingMessage()) return
        val msg = runCatching { JSONObject(rawText) }.getOrNull() ?: return
        val msgType = msg.optString("type")
        if (msgType == "OPEN") {
            Logbook.log("ListenTogether: Registered with PeerJS server")
            scope.launch {
                val s = _session.value ?: return@launch
                if (!s.isHost) {
                    val hostId = "wb-${s.roomId}-host"
                    sendSignalingPayload(
                        dst = hostId,
                        payload = JSONObject().apply {
                            put("cmd", "JOIN_REQ")
                            put("senderId", s.selfId)
                            put("senderName", s.selfName)
                        }
                    )
                    _session.value = s.copy(syncStatus = "Live • Connected to room ${s.roomId}")
                }
            }
            return
        }

        val rawPayload = msg.opt("payload") ?: msg
        val payload = when (rawPayload) {
            is JSONObject -> rawPayload
            is String -> runCatching { JSONObject(rawPayload) }.getOrNull()
            else -> null
        } ?: return

        val cmd = payload.optString("cmd")
        // Prefer the PeerJS server envelope's authenticated `src` peer ID over self-reported payload senderId
        val envelopeSrc = sanitizePeerId(msg.optString("src"))
        val payloadSender = sanitizePeerId(payload.optString("senderId"))
        val senderId = envelopeSrc.ifBlank { payloadSender }
        if (envelopeSrc.isNotBlank() && payloadSender.isNotBlank() && envelopeSrc != payloadSender) {
            return
        }
        val senderName = sanitizePeerText(payload.optString("senderName"), maxLen = 40, fallback = "Listener")

        scope.launch {
            val current = _session.value ?: return@launch
            val expectedHostId = "wb-${current.roomId}-host"
            when (cmd) {
                "JOIN_REQ" -> {
                    if (current.isHost && senderId.isNotBlank() && senderId != current.selfId) {
                        val existingFiltered = current.peers.filter { it.id != senderId }
                        if (existingFiltered.size >= 16) return@launch
                        val updatedPeers = existingFiltered +
                            ListenTogetherPeer(id = senderId, name = senderName, isHost = false, synced = true)
                        _session.value = current.copy(
                            peers = updatedPeers,
                            syncStatus = "Live • ${updatedPeers.size} peers listening together",
                        )
                        broadcastPeersList()
                        broadcastState(force = true)
                    }
                }
                "RESYNC_REQ" -> {
                    if (current.isHost && current.peers.any { it.id == senderId }) {
                        broadcastState(force = true)
                    }
                }
                "LEAVE" -> {
                    if (senderId.isNotBlank()) {
                        val remaining = current.peers.filter { it.id != senderId }
                        _session.value = current.copy(
                            peers = remaining,
                            syncStatus = "Live • ${remaining.size} listening",
                        )
                        if (current.isHost) broadcastPeersList()
                    }
                }
                "PEER_LIST" -> {
                    // Only the room host is authorized to broadcast the canonical peer list
                    if (current.isHost || senderId != expectedHostId) return@launch
                    val arr = payload.optJSONArray("peers") ?: return@launch
                    val parsed = mutableListOf<ListenTogetherPeer>()
                    val maxPeers = minOf(arr.length(), 16)
                    for (i in 0 until maxPeers) {
                        val o = arr.optJSONObject(i) ?: continue
                        val pid = sanitizePeerId(o.optString("id"))
                        if (pid.isBlank()) continue
                        parsed.add(
                            ListenTogetherPeer(
                                id = pid,
                                name = sanitizePeerText(o.optString("name"), maxLen = 40, fallback = "Listener"),
                                isHost = pid == expectedHostId && o.optBoolean("isHost", false),
                                synced = true,
                            )
                        )
                    }
                    if (parsed.isNotEmpty()) {
                        _session.value = current.copy(
                            peers = parsed,
                            syncStatus = "Live • ${parsed.size} peers synced",
                        )
                    }
                }
                "SYNC_STATE" -> {
                    val isAuthorizedSender = if (current.isHost) {
                        current.guestControlAllowed && current.peers.any { it.id == senderId && !it.isHost }
                    } else {
                        senderId == expectedHostId || (current.guestControlAllowed && current.peers.any { it.id == senderId })
                    }
                    if (isAuthorizedSender) {
                        applyRemotePlaybackState(payload, fromHost = senderId == expectedHostId)
                    }
                }
            }
        }
    }

    private fun broadcastPeersList() {
        val current = _session.value ?: return
        if (!current.isHost) return
        val arr = JSONArray()
        current.peers.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("isHost", p.isHost)
                }
            )
        }
        val payload = JSONObject().apply {
            put("cmd", "PEER_LIST")
            put("senderId", current.selfId)
            put("peers", arr)
        }
        current.peers.filter { !it.isHost && it.id != current.selfId }.forEach { peer ->
            sendSignalingPayload(peer.id, payload)
        }
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = scope.launch {
            var tick = 0
            while (isActive) {
                val s = _session.value ?: break
                val currentTrack = SongPlayer.engineQueue.value.current
                val currentItem = if (WaveBitsRuntime.isStarted) WaveBitsRuntime.api.player.state.value?.item else null
                val title = currentTrack?.title?.takeIf { it.isNotBlank() } ?: currentItem?.name.orEmpty()
                val artist = currentTrack?.singer?.takeIf { it.isNotBlank() }
                    ?: currentItem?.artists?.joinToString { it.name }.orEmpty()
                if (title.isNotBlank() && (title != s.currentTrackTitle || artist != s.currentTrackArtist)) {
                    _session.value = s.copy(
                        currentTrackTitle = title,
                        currentTrackArtist = artist,
                    )
                }
                // Keep PeerJS signaling socket alive with protocol HEARTBEAT
                if (tick % 5 == 0) {
                    ioScope.launch {
                        runCatching { webSocket?.send("""{"type":"HEARTBEAT"}""") }
                    }
                }
                val isPlaying = SongPlayer.isPlaying()
                if ((s.isHost || s.guestControlAllowed) && !applyingRemoteSync && isPlaying) {
                    broadcastState(force = false)
                }
                tick++
                // Low power consumption optimization: 3s delay when playing, 6s when paused
                delay(if (isPlaying) 3_000L else 6_000L)
            }
        }
    }

    private fun broadcastState(force: Boolean) {
        val current = _session.value ?: return
        val np = SongPlayer.nowPlaying.value
        val trackUri = np.uri
        if (trackUri.isBlank()) return
        val currentTrack = SongPlayer.engineQueue.value.current
        val currentItem = if (WaveBitsRuntime.isStarted) WaveBitsRuntime.api.player.state.value?.item else null
        val title = currentTrack?.title?.takeIf { it.isNotBlank() } ?: currentItem?.name.orEmpty()
        val artist = currentTrack?.singer?.takeIf { it.isNotBlank() }
            ?: currentItem?.artists?.joinToString { it.name }.orEmpty()
        val album = currentTrack?.album?.takeIf { it.isNotBlank() } ?: currentItem?.album?.name.orEmpty()
        val coverUrl = currentTrack?.coverUri?.takeIf { it.isNotBlank() }
            ?: currentItem?.album?.images?.firstOrNull()?.url.orEmpty()
        val durationMs = SongPlayer.getDuration().takeIf { it > 0L }
            ?: currentTrack?.durationMs?.toLong()?.takeIf { it > 0L }
            ?: currentItem?.durationMs ?: 180_000L
        val isPlaying = SongPlayer.isPlaying()
        val posMs = SongPlayer.getCurrentPosition()
        val stateSignature = (trackUri.hashCode() * 31) + (if (isPlaying) 1 else 0)
        if (!force && stateSignature == lastBroadcastHash && !isPlaying) return
        lastBroadcastHash = stateSignature

        val payload = JSONObject().apply {
            put("cmd", "SYNC_STATE")
            put("senderId", current.selfId)
            put("senderName", current.selfName)
            put("trackUri", trackUri)
            put("contextUri", np.contextUri)
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("coverUrl", coverUrl)
            put("durationMs", durationMs)
            put("isPlaying", isPlaying)
            put("positionMs", posMs)
            put("sentAtMs", System.currentTimeMillis())
            put("guestControlAllowed", current.guestControlAllowed)
        }

        val recipients = if (current.isHost) {
            current.peers.filter { !it.isHost && it.id != current.selfId }.map { it.id }
        } else {
            listOf("wb-${current.roomId}-host")
        }
        recipients.forEach { dst ->
            sendSignalingPayload(dst, payload)
        }
    }

    private fun applyRemotePlaybackState(payload: JSONObject, fromHost: Boolean = false) {
        val current = _session.value ?: return
        val remoteTrackUri = sanitizePeerText(payload.optString("trackUri"), maxLen = 256)
        if (remoteTrackUri.isBlank()) return
        val remoteTitle = sanitizePeerText(payload.optString("title"), maxLen = 120)
        val remoteArtist = sanitizePeerText(payload.optString("artist"), maxLen = 120)
        val remoteAlbum = sanitizePeerText(payload.optString("album"), maxLen = 120)
        val rawCoverUrl = payload.optString("coverUrl").trim()
        val remoteCoverUrl = if (rawCoverUrl.startsWith("https://") && rawCoverUrl.length <= 512) rawCoverUrl else ""
        val remoteDurationMs = payload.optLong("durationMs", 180_000L).coerceIn(1_000L, 36_000_000L)
        val remoteIsPlaying = payload.optBoolean("isPlaying", false)
        val remotePosMs = payload.optLong("positionMs", 0L).coerceIn(0L, remoteDurationMs)
        val sentAtMs = payload.optLong("sentAtMs", System.currentTimeMillis())
        val guestAllowed = if (fromHost && !current.isHost) {
            payload.optBoolean("guestControlAllowed", current.guestControlAllowed)
        } else {
            current.guestControlAllowed
        }

        val transitMs = (System.currentTimeMillis() - sentAtMs).coerceIn(0L, 4_000L)
        val targetPosMs = remotePosMs + if (remoteIsPlaying) transitMs else 0L

        applyingRemoteSync = true
        try {
            val localNowPlaying = SongPlayer.nowPlaying.value
            if (localNowPlaying.uri != remoteTrackUri && lastAppliedTrackUri != remoteTrackUri) {
                lastAppliedTrackUri = remoteTrackUri
                val rawTrackId = remoteTrackUri.substringBefore('|').removePrefix("spotify:track:")
                    .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                    .take(64)
                if (rawTrackId.isBlank()) return
                val remoteSong = com.music.spotui.data.entity.SongsModel(
                    id = rawTrackId.hashCode() and 0x7fffffff,
                    title = remoteTitle,
                    singer = remoteArtist,
                    album = remoteAlbum,
                    coverUri = remoteCoverUrl,
                    url = SongPlayer.buildSpotifyPlayQuery(rawTrackId, remoteTitle, remoteArtist),
                    durationMs = remoteDurationMs.toInt(),
                    spotifyTrackId = rawTrackId,
                )
                SongPlayer.playSong(
                    song = remoteSong.url,
                    songs = listOf(remoteSong),
                    positionMs = targetPosMs,
                )
                if (!remoteIsPlaying) {
                    scope.launch {
                        delay(400L)
                        SongPlayer.pause()
                    }
                }
            } else {
                val localPlaying = SongPlayer.isPlaying()
                if (remoteIsPlaying && !localPlaying) {
                    SongPlayer.play()
                } else if (!remoteIsPlaying && localPlaying) {
                    SongPlayer.pause()
                }
                val drift = abs(SongPlayer.getCurrentPosition() - targetPosMs)
                if (drift > DRIFT_TOLERANCE_MS) {
                    SongPlayer.seekTo(targetPosMs)
                }
            }
            _session.value = current.copy(
                guestControlAllowed = guestAllowed,
                currentTrackTitle = remoteTitle.ifBlank { current.currentTrackTitle },
                currentTrackArtist = remoteArtist.ifBlank { current.currentTrackArtist },
                syncStatus = "Live • Synced (${transitMs}ms latency)",
            )
        } finally {
            scope.launch {
                delay(600L)
                applyingRemoteSync = false
            }
        }
    }
}
