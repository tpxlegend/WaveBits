package app.wavebits.host.android.jiosaavn

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer as Media3ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.wavebits.api.NowPlaying
import app.wavebits.api.PlayerRestrictions
import app.wavebits.api.PlayerState
import app.wavebits.api.Queue
import app.wavebits.api.Repeat
import app.wavebits.api.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ExoPlayer-backed media player for JioSaavn powered by [JioSaavnQueueManager]
 * and [JioSaavnApiClient] (https://github.com/sumitkolhe/jiosaavn-api).
 */
class JioSaavnPlayer(
    private val context: Context,
    val client: JioSaavnApiClient,
    private val scope: CoroutineScope,
) {
    data class JioTrackData(
        val id: String,
        val uri: String,
        val title: String,
        val artist: String,
        val album: String,
        val coverUrl: String,
        val durationMs: Long,
    )

    private val tag = "JioSaavnPlayer"
    private var exoPlayer: Media3ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state

    val queueManager = JioSaavnQueueManager(
        client = client,
        scope = scope,
        onSnapshotChanged = ::onQueueSnapshotChanged,
    )

    val queue: StateFlow<Queue?> = queueManager.queue

    @Volatile private var audioQuality: String = "auto"
    @Volatile var crossfadeSeconds: Int = 0
        set(value) {
            val v = value.coerceIn(0, 12)
            if (field == v) return
            field = v
            if (v == 0) {
                mainHandler.post { cancelCrossfade(restorePrimaryVolume = true) }
            }
        }

    private var incomingPlayer: Media3ExoPlayer? = null
    private var crossfadePrepareJob: Job? = null
    @Volatile private var crossfadePreparingForId: String? = null
    @Volatile private var crossfadePreparedEntry: JioSaavnQueueManager.QueueEntry? = null
    @Volatile private var crossfadeActive: Boolean = false
    @Volatile private var crossfadeStartedPosMs: Long = 0L
    @Volatile private var crossfadeWindowMs: Long = 0L
    @Volatile private var cachedPeekId: String? = null
    @Volatile private var cachedPeekEntry: JioSaavnQueueManager.QueueEntry? = null

    var autoplayEnabled: Boolean
        get() = queueManager.autoplayEnabled
        set(value) {
            queueManager.autoplayEnabled = value
        }

    fun setAudioQuality(level: String) {
        audioQuality = level
    }

    private fun applyStreamQuality(url: String): String {
        val targetSuffix = when (audioQuality) {
            "low" -> "_96.mp4"
            "normal" -> "_160.mp4"
            else -> "_320.mp4"
        }
        return url.replace("_96.mp4", targetSuffix)
            .replace("_160.mp4", targetSuffix)
            .replace("_320.mp4", targetSuffix)
    }

    private var tickerJob: Job? = null
    private var playbackJob: Job? = null
    private val errorRetryCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    init {
        mainHandler.post { initExoPlayer() }
    }

    private fun onQueueSnapshotChanged(snapshot: JioSaavnQueueManager.QueueSnapshot) {
        val cur = _state.value
        val nowItem = snapshot.current?.toNowPlaying()
        val prevItem = snapshot.previous?.toNowPlaying()
        val nextItem = snapshot.next?.toNowPlaying()

        if (cur != null) {
            _state.value = cur.copy(
                item = nowItem?.let { np ->
                    val liveDur = cur.item?.durationMs?.takeIf { it > 0 && cur.item?.uri == np.uri } ?: np.durationMs
                    np.copy(durationMs = liveDur)
                } ?: cur.item,
                previous = prevItem,
                next = nextItem,
                contextUri = snapshot.contextUri,
                contextName = snapshot.contextName,
                shuffle = snapshot.shuffle,
                repeat = snapshot.repeat,
            )
        } else if (nowItem != null) {
            val hasMedia = exoPlayer?.currentMediaItem != null
            _state.value = PlayerState(
                item = nowItem,
                previous = prevItem,
                next = nextItem,
                contextUri = snapshot.contextUri,
                contextName = snapshot.contextName,
                isPaused = !hasMedia,
                isBuffering = hasMedia,
                positionMs = 0L,
                timestampMs = System.currentTimeMillis(),
                speed = 1.0,
                shuffle = snapshot.shuffle,
                repeat = snapshot.repeat,
                restrictions = PlayerRestrictions(),
            )
        }
    }

    fun restoreSession(
        current: JioTrackData,
        upcoming: List<JioTrackData>,
        contextUri: String?,
        contextName: String?,
        positionMs: Long,
    ) {
        queueManager.restoreSession(current, upcoming, contextUri, contextName)
        if (_state.value == null) {
            val nowEntry = JioSaavnQueueManager.QueueEntry(uid = "restored_now", track = current, isUserQueued = false)
            val nextEntry = upcoming.firstOrNull()?.let {
                JioSaavnQueueManager.QueueEntry(uid = "restored_next", track = it, isUserQueued = false)
            }
            _state.value = PlayerState(
                item = nowEntry.toNowPlaying(),
                previous = null,
                next = nextEntry?.toNowPlaying(),
                contextUri = contextUri?.ifBlank { null },
                contextName = contextName?.ifBlank { current.title },
                isPaused = true,
                isBuffering = false,
                positionMs = positionMs.coerceAtLeast(0L),
                timestampMs = System.currentTimeMillis(),
                speed = 1.0,
                shuffle = queueManager.isShuffle,
                repeat = queueManager.repeatMode,
                restrictions = PlayerRestrictions(),
            )
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun musicAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildExoPlayerInstance(handleAudioFocus: Boolean): Media3ExoPlayer {
        val httpSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.jiosaavn.com/",
                    "Origin" to "https://www.jiosaavn.com",
                ),
            )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 800,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .setBackBuffer(30_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(httpSourceFactory)

        return Media3ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(musicAudioAttributes(), handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    private fun attachPrimaryListener(player: Media3ExoPlayer) {
        player.addListener(object : ExoPlayer.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== exoPlayer) return
                when (playbackState) {
                    ExoPlayer.STATE_READY -> {
                        if (crossfadeSeconds <= 0 && !crossfadeActive) {
                            player.volume = 1f
                        }
                        updatePlaybackState(isBuffering = false)
                        startTicker()
                    }
                    ExoPlayer.STATE_BUFFERING -> {
                        updatePlaybackState(isBuffering = true)
                    }
                    ExoPlayer.STATE_ENDED -> {
                        stopTicker()
                        if (crossfadeActive && incomingPlayer != null) {
                            completeCrossfadeHandoff()
                        } else {
                            scope.launch { onTrackEnded() }
                        }
                    }
                    ExoPlayer.STATE_IDLE -> {
                        stopTicker()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== exoPlayer) return
                updatePlaybackState(isBuffering = false)
                if (isPlaying) startTicker() else stopTicker()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== exoPlayer) return
                Log.w(tag, "Playback error: ${error.message}")
                val entry = queueManager.getCurrentEntry()
                val resumePos = player.currentPosition.coerceAtLeast(0L)
                if (entry != null) {
                    val attempts = errorRetryCount.getOrDefault(entry.track.id, 0)
                    if (attempts < 2) {
                        errorRetryCount[entry.track.id] = attempts + 1
                        scope.launch {
                            delay(350L * (attempts + 1))
                            playEntry(entry, resumePos, forceFallbackQuality = attempts >= 1)
                        }
                        return
                    }
                }
                updatePlaybackState(isBuffering = false)
                scope.launch {
                    delay(600)
                    next()
                }
            }
        })
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initExoPlayer() {
        if (exoPlayer != null) return
        val player = buildExoPlayerInstance(handleAudioFocus = true)
        attachPrimaryListener(player)
        exoPlayer = player
    }

    private fun cancelCrossfade(restorePrimaryVolume: Boolean) {
        crossfadePrepareJob?.cancel()
        crossfadePrepareJob = null
        crossfadePreparingForId = null
        crossfadePreparedEntry = null
        crossfadeActive = false
        crossfadeStartedPosMs = 0L
        crossfadeWindowMs = 0L
        runCatching {
            incomingPlayer?.stop()
            incomingPlayer?.release()
        }
        incomingPlayer = null
        if (restorePrimaryVolume) {
            exoPlayer?.volume = 1f
        }
    }

    private fun maybePrepareCrossfade() {
        val currentId = queueManager.getCurrentEntry()?.track?.id ?: return
        if (crossfadePrepareJob?.isActive == true || (incomingPlayer != null && crossfadePreparedEntry != null)) return
        crossfadePrepareJob = scope.launch {
            val nextEntry = queueManager.peekNextEntry() ?: return@launch
            cachedPeekId = nextEntry.track.id
            cachedPeekEntry = nextEntry
            if (crossfadePreparingForId == nextEntry.track.id && incomingPlayer != null) return@launch
            crossfadePreparingForId = nextEntry.track.id
            val streamUrl = withContext(Dispatchers.IO) {
                client.resolveStreamUrl(nextEntry.track.id, nextEntry.track.title, nextEntry.track.artist)
            } ?: return@launch
            val effectiveUrl = applyStreamQuality(streamUrl)
            withContext(Dispatchers.Main) {
                if (!isActive || crossfadeSeconds <= 0 || queueManager.getCurrentEntry()?.track?.id != currentId) return@withContext
                runCatching {
                    incomingPlayer?.stop()
                    incomingPlayer?.release()
                    val secondary = buildExoPlayerInstance(handleAudioFocus = false)
                    val mediaItem = MediaItem.Builder()
                        .setUri(Uri.parse(effectiveUrl))
                        .setMediaId(nextEntry.track.uri)
                        .build()
                    secondary.volume = 0f
                    secondary.setMediaItem(mediaItem)
                    secondary.playWhenReady = false
                    secondary.prepare()
                    incomingPlayer = secondary
                    crossfadePreparedEntry = nextEntry
                }.onFailure { e ->
                    Log.w(tag, "Crossfade prepare failed for ${nextEntry.track.title}: ${e.message}")
                    crossfadePreparingForId = null
                }
            }
        }
    }

    private fun completeCrossfadeHandoff() {
        val nextEntry = crossfadePreparedEntry ?: return
        val promoted = incomingPlayer ?: return
        val outgoing = exoPlayer

        incomingPlayer = null
        crossfadePrepareJob = null
        crossfadePreparingForId = null
        crossfadePreparedEntry = null
        crossfadeActive = false
        crossfadeStartedPosMs = 0L
        crossfadeWindowMs = 0L

        attachPrimaryListener(promoted)
        promoted.setAudioAttributes(musicAudioAttributes(), true)
        promoted.volume = 1f
        if (!promoted.isPlaying) {
            promoted.playWhenReady = true
            promoted.play()
        }
        exoPlayer = promoted

        runCatching {
            outgoing?.stop()
            outgoing?.release()
        }

        scope.launch {
            val advanced = queueManager.advanceToNext(fromTrackEnd = true)
            if (advanced != null && advanced.track.id != nextEntry.track.id) {
                playEntry(advanced, 0L)
                return@launch
            }
            val dur = withContext(Dispatchers.Main) { promoted.duration.takeIf { it > 0 } } ?: nextEntry.track.durationMs
            val cur = _state.value
            _state.value = PlayerState(
                item = nextEntry.toNowPlaying().copy(durationMs = dur),
                previous = cur?.previous,
                next = cur?.next,
                contextUri = queueManager.contextUri,
                contextName = queueManager.contextName,
                isPaused = false,
                isBuffering = promoted.playbackState == ExoPlayer.STATE_BUFFERING,
                positionMs = withContext(Dispatchers.Main) { promoted.currentPosition.coerceAtLeast(0L) },
                timestampMs = System.currentTimeMillis(),
                speed = 1.0,
                shuffle = queueManager.isShuffle,
                repeat = queueManager.repeatMode,
                restrictions = PlayerRestrictions(),
            )
            queueManager.replenishSuggestionsIfNeeded()
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val delayMs = if (crossfadeSeconds > 0 && exoPlayer?.isPlaying == true) 50L else 250L
                delay(delayMs)
                updatePositionTick()
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun updatePositionTick() {
        val player = exoPlayer ?: return
        val cur = _state.value ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.takeIf { it > 0 } ?: cur.item?.durationMs ?: 0L
        val cfMs = crossfadeSeconds.coerceIn(0, 12) * 1000L
        if (cfMs > 0 && dur > 8_000L && player.isPlaying) {
            val effectiveCfMs = minOf(cfMs, (dur * 2L / 5L).coerceAtLeast(2_000L))
            val remaining = (dur - pos).coerceAtLeast(0L)
            val hasNext = cur.next != null || queueManager.repeatMode == Repeat.CONTEXT
            if (hasNext && remaining in 1..(effectiveCfMs + 10_000L)) {
                maybePrepareCrossfade()
            }
            if (!crossfadeActive && hasNext && remaining in 1..effectiveCfMs) {
                val sec = incomingPlayer
                if (sec != null && crossfadePreparedEntry != null && sec.playbackState != ExoPlayer.STATE_IDLE) {
                    crossfadeActive = true
                    crossfadeStartedPosMs = pos
                    crossfadeWindowMs = remaining.coerceAtLeast(300L)
                    sec.volume = 0f
                    sec.playWhenReady = true
                    sec.play()
                }
            }
            if (crossfadeActive && incomingPlayer != null) {
                val window = crossfadeWindowMs.coerceAtLeast(300L)
                val elapsedFade = (pos - crossfadeStartedPosMs).coerceAtLeast(0L)
                val t = (elapsedFade.toFloat() / window.toFloat()).coerceIn(0f, 1f)
                val outGain = kotlin.math.cos(t * (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)
                val inGain = kotlin.math.sin(t * (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)
                player.volume = outGain
                incomingPlayer?.volume = inGain
                if (t >= 1f || remaining <= 60L) {
                    completeCrossfadeHandoff()
                    return
                }
            } else if (hasNext && remaining in 1..effectiveCfMs) {
                val t = (1f - remaining.toFloat() / effectiveCfMs.toFloat()).coerceIn(0f, 1f)
                player.volume = kotlin.math.cos(t * (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)
            } else {
                player.volume = 1f
            }
        } else {
            if (crossfadeActive || incomingPlayer != null) {
                cancelCrossfade(restorePrimaryVolume = false)
            }
            player.volume = 1f
        }
        _state.value = cur.copy(
            positionMs = pos,
            timestampMs = System.currentTimeMillis(),
            isPaused = !player.isPlaying,
            isBuffering = player.playbackState == ExoPlayer.STATE_BUFFERING,
            item = cur.item?.copy(durationMs = dur),
        )
    }

    private fun updatePlaybackState(isBuffering: Boolean) {
        val player = exoPlayer ?: return
        val cur = _state.value ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.takeIf { it > 0 } ?: cur.item?.durationMs ?: 0L
        _state.value = cur.copy(
            positionMs = pos,
            timestampMs = System.currentTimeMillis(),
            isPaused = !player.isPlaying,
            isBuffering = isBuffering,
            item = cur.item?.copy(durationMs = dur),
        )
    }

    // ── Track & Context Registration (Delegated to JioSaavnQueueManager) ──

    fun registerQueryHint(id: String, hint: String) = queueManager.registerQueryHint(id, hint)

    fun getRegisteredContext(uri: String): List<JioTrackData>? = queueManager.getRegisteredContext(uri)

    fun getRegisteredTrack(id: String): JioTrackData? = queueManager.getRegisteredTrack(id)

    fun cacheTrack(t: Track) = queueManager.cacheTrack(t)

    fun registerTracks(
        items: List<Triple<String, String, String>>,
        meta: List<Triple<String, String, out Number>>,
    ) = queueManager.registerTracks(items, meta)

    fun registerContextTracks(
        contextUri: String,
        items: List<Triple<String, String, String>>,
        meta: List<Triple<String, String, out Number>>,
    ) = queueManager.registerContextTracks(contextUri, items, meta)

    // ── Queue & Playback Operations (Delegated to JioSaavnQueueManager) ──

    fun onQueueWatched() = queueManager.onQueueWatched()

    fun setAutoplay(enabled: Boolean) {
        queueManager.autoplayEnabled = enabled
        if (enabled) queueManager.replenishSuggestionsIfNeeded()
    }

    suspend fun addToQueue(uris: List<String>) {
        val startEntry = queueManager.addToQueue(uris)
        if (startEntry != null) {
            playbackJob?.cancel()
            playbackJob = scope.launch { playEntry(startEntry, 0L) }
        }
    }

    suspend fun playNext(uris: List<String>) {
        val startEntry = queueManager.playNext(uris)
        if (startEntry != null) {
            playbackJob?.cancel()
            playbackJob = scope.launch { playEntry(startEntry, 0L) }
        }
    }

    suspend fun removeFromQueue(items: List<NowPlaying>) {
        queueManager.removeFromQueue(items)
    }

    suspend fun clearQueue() {
        queueManager.clearUserQueue()
    }

    fun skipTo(item: NowPlaying) {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            val entry = queueManager.skipTo(item) ?: return@launch
            playEntry(entry, 0L)
        }
    }

    suspend fun moveInQueue(item: NowPlaying, target: NowPlaying, after: Boolean) {
        queueManager.moveInQueue(item, target, after)
    }

    suspend fun moveIntoQueue(item: NowPlaying, target: NowPlaying?, after: Boolean) {
        queueManager.moveIntoUserQueue(item, target, after)
    }

    fun play(
        contextUri: String,
        trackUri: String? = null,
        index: Int? = null,
        positionMs: Long? = null,
        order: List<String>? = null,
    ) {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            val entry = queueManager.startPlayback(
                requestedContextUri = contextUri,
                trackUri = trackUri,
                index = index,
                order = order,
            ) ?: return@launch
            playEntry(entry, positionMs ?: 0L)
        }
    }

    private suspend fun playEntry(
        entry: JioSaavnQueueManager.QueueEntry,
        startPosMs: Long,
        forceFallbackQuality: Boolean = false,
    ) {
        val track = entry.track
        if (!forceFallbackQuality && startPosMs == 0L) {
            errorRetryCount.remove(track.id)
        }
        val curState = _state.value
        _state.value = PlayerState(
            item = entry.toNowPlaying(),
            previous = curState?.previous,
            next = curState?.next,
            contextUri = queueManager.contextUri,
            contextName = queueManager.contextName,
            isPaused = false,
            isBuffering = true,
            positionMs = startPosMs,
            timestampMs = System.currentTimeMillis(),
            speed = 1.0,
            shuffle = queueManager.isShuffle,
            repeat = queueManager.repeatMode,
            restrictions = PlayerRestrictions(),
        )

        // Resolve full metadata if current track was a placeholder
        val effectiveTrack = if (track.title == "WaveBits Song" || track.artist == "WaveBits" || track.coverUrl.isBlank()) {
            val full = queueManager.resolveTrack(track.uri)
            if (full.title != track.title || full.coverUrl != track.coverUrl) {
                queueManager.updateCurrentTrackMetadata(full)
            }
            full
        } else {
            track
        }

        val streamUrl = withContext(Dispatchers.IO) {
            client.resolveStreamUrl(effectiveTrack.id, effectiveTrack.title, effectiveTrack.artist)
        }

        if (streamUrl.isNullOrBlank()) {
            Log.w(tag, "Failed to resolve stream for ${effectiveTrack.title}")
            _state.value = _state.value?.copy(isBuffering = false, isPaused = true)
            return
        }

        withContext(Dispatchers.Main) {
            try {
                cancelCrossfade(restorePrimaryVolume = true)
                initExoPlayer()
                val player = exoPlayer ?: return@withContext
                player.volume = 1f
                val effectiveUrl = if (forceFallbackQuality) {
                    streamUrl.replace("_320.mp4", "_160.mp4")
                } else {
                    applyStreamQuality(streamUrl)
                }
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(effectiveUrl))
                    .setMediaId(effectiveTrack.uri)
                    .build()

                player.setMediaItem(mediaItem)
                player.prepare()
                if (startPosMs > 0) player.seekTo(startPosMs)
                player.play()
                startTicker()
                queueManager.replenishSuggestionsIfNeeded()
            } catch (e: Exception) {
                Log.e(tag, "Failed to play MediaItem: ${e.message}")
            }
        }
    }

    private suspend fun onTrackEnded() {
        val nextEntry = queueManager.advanceToNext(fromTrackEnd = true)
        if (nextEntry != null) {
            playEntry(nextEntry, 0L)
        } else {
            stopAndReset()
        }
    }

    fun next() {
        mainHandler.post { cancelCrossfade(restorePrimaryVolume = true) }
        playbackJob?.cancel()
        playbackJob = scope.launch {
            val nextEntry = queueManager.advanceToNext(fromTrackEnd = false)
            if (nextEntry != null) {
                playEntry(nextEntry, 0L)
            } else {
                stopAndReset()
            }
        }
    }

    fun previous() {
        mainHandler.post { cancelCrossfade(restorePrimaryVolume = true) }
        playbackJob?.cancel()
        playbackJob = scope.launch {
            val pos = withContext(Dispatchers.Main) { exoPlayer?.currentPosition ?: 0L }
            if (pos > 3500L) {
                seekTo(0L)
                return@launch
            }
            val prevEntry = queueManager.skipToPrevious()
            if (prevEntry != null) {
                playEntry(prevEntry, 0L)
            } else {
                seekTo(0L)
            }
        }
    }

    fun pause() {
        mainHandler.post {
            cancelCrossfade(restorePrimaryVolume = true)
            exoPlayer?.pause()
            _state.value = _state.value?.copy(isPaused = true)
            stopTicker()
        }
    }

    fun resume() {
        mainHandler.post {
            val player = exoPlayer
            if (player != null && player.currentMediaItem != null) {
                player.play()
                _state.value = _state.value?.copy(isPaused = false)
                startTicker()
            } else {
                val entry = queueManager.getCurrentEntry() ?: return@post
                val startPos = _state.value?.positionMs ?: 0L
                playbackJob?.cancel()
                playbackJob = scope.launch {
                    playEntry(entry, startPos)
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        mainHandler.post {
            cancelCrossfade(restorePrimaryVolume = true)
            if (exoPlayer?.currentMediaItem != null) {
                exoPlayer?.seekTo(positionMs)
            }
            _state.value = _state.value?.copy(positionMs = positionMs, timestampMs = System.currentTimeMillis())
        }
    }

    suspend fun setShuffle(shuffle: Boolean) {
        queueManager.setShuffle(shuffle)
    }

    suspend fun setRepeatMode(mode: Int) {
        val r = when (mode) {
            1 -> Repeat.CONTEXT
            2 -> Repeat.TRACK
            else -> Repeat.OFF
        }
        setRepeatMode(r)
    }

    suspend fun setRepeatMode(mode: Repeat) {
        queueManager.setRepeatMode(mode)
    }

    fun stopAndReset() {
        playbackJob?.cancel()
        queueManager.reset()
        _state.value = null
        mainHandler.post {
            stopTicker()
            cancelCrossfade(restorePrimaryVolume = false)
            exoPlayer?.stop()
            _state.value = null
        }
    }
}
