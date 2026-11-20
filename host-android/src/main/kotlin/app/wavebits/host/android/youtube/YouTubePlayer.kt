// WaveBits YouTube Player, GPL-3.0.
package app.wavebits.host.android.youtube

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as ExoPlayerListener
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import app.wavebits.api.AlbumRef
import app.wavebits.api.ArtistRef
import app.wavebits.api.ItemKind
import app.wavebits.api.NowPlaying
import app.wavebits.api.Player
import app.wavebits.api.PlayerRestrictions
import app.wavebits.api.PlayerState
import app.wavebits.api.Queue
import app.wavebits.api.Repeat
import app.wavebits.api.Track
import app.wavebits.api.Uri
import app.wavebits.host.android.WaveBitsRuntime
import app.wavebits.innertube.YouTube
import app.wavebits.innertube.models.SongItem
import app.wavebits.innertube.models.WatchEndpoint
import app.wavebits.innertube.models.YouTubeClient
import app.wavebits.innertube.models.response.PlayerResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class YouTubeTrackData(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String,
    val durationMs: Long,
)

private data class CachedStreamUrl(
    val url: String,
    val expiresAtMs: Long,
    val userAgent: String? = null,
    val contentLength: Long? = null,
)

private object InnerTuneNewPipeHelper {
    @Volatile
    private var initialized = false

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun ensureInitialized() {
        if (initialized && runCatching { NewPipe.getDownloader() }.getOrNull() != null) return
        synchronized(this) {
            if (initialized && runCatching { NewPipe.getDownloader() }.getOrNull() != null) return
            runCatching {
                NewPipe.init(object : Downloader() {
                    override fun execute(request: NewPipeRequest): NewPipeResponse {
                        val method = request.httpMethod()
                        val data = request.dataToSend()
                        val body = when {
                            data != null -> data.toRequestBody(null, 0, data.size)
                            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null, 0, 0)
                            else -> null
                        }
                        val builder = OkHttpRequest.Builder()
                            .url(request.url())
                            .method(method, body)
                            .header("User-Agent", YouTubeClient.USER_AGENT_WEB)
                        for ((k, list) in request.headers()) {
                            builder.removeHeader(k)
                            for (v in list) builder.addHeader(k, v)
                        }
                        httpClient.newCall(builder.build()).execute().use { res ->
                            if (res.code == 429) {
                                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                            }
                            return NewPipeResponse(
                                res.code,
                                res.message,
                                res.headers.toMultimap(),
                                res.body?.string(),
                                res.request.url.toString(),
                            )
                        }
                    }
                })
                initialized = true
            }
        }
    }

    fun getSignatureTimestamp(videoId: String): Int? = runCatching {
        ensureInitialized()
        YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
    }.getOrNull()

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): String? = runCatching {
        ensureInitialized()
        val baseUrl = format.url?.takeIf { it.isNotBlank() } ?: run {
            val cipherStr = format.signatureCipher ?: format.cipher ?: return@runCatching null
            val params = cipherStr.split("&").associate {
                val parts = it.split("=", limit = 2)
                val k = parts.getOrNull(0).orEmpty()
                val v = parts.getOrNull(1)?.let { raw -> URLDecoder.decode(raw, "UTF-8") }.orEmpty()
                k to v
            }
            val urlParam = params["url"]?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val s = params["s"]
            val sig = params["sig"]
            val sp = params["sp"]?.takeIf { it.isNotBlank() } ?: "sig"
            when {
                !s.isNullOrBlank() -> {
                    val deobfuscatedSig = runCatching {
                        YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, s)
                    }.getOrDefault(s)
                    val encodedSig = URLEncoder.encode(deobfuscatedSig, "UTF-8")
                    if (urlParam.contains("?")) "$urlParam&$sp=$encodedSig" else "$urlParam?$sp=$encodedSig"
                }
                !sig.isNullOrBlank() && !urlParam.contains("sig=") && !urlParam.contains("signature=") -> {
                    val encodedSig = URLEncoder.encode(sig, "UTF-8")
                    if (urlParam.contains("?")) "$urlParam&$sp=$encodedSig" else "$urlParam?$sp=$encodedSig"
                }
                else -> urlParam
            }
        }
        runCatching {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, baseUrl)
        }.getOrDefault(baseUrl)
    }.getOrNull()

    fun validateStreamStatus(url: String, userAgent: String): Boolean = runCatching {
        val headReq = OkHttpRequest.Builder()
            .head()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        httpClient.newCall(headReq).execute().use { res ->
            if (res.isSuccessful) return@use true
            if (res.code == 405 || res.code == 501) {
                val rangeReq = OkHttpRequest.Builder()
                    .get()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Range", "bytes=0-0")
                    .build()
                httpClient.newCall(rangeReq).execute().use { r2 ->
                    r2.isSuccessful || r2.code == 206
                }
            } else {
                false
            }
        }
    }.getOrDefault(false)
}

/**
 * WaveBits-powered InnerTube Player & Queue Manager.
 *
 * Implements:
 * - Multi-client stream resolution (`ANDROID_VR_1_43_32`, `ANDROID_VR_1_61_48`, `ANDROID_VR_NO_AUTH`,
 *   `IOS`, `TVHTML5_SIMPLY_EMBEDDED_PLAYER`, `ANDROID_CREATOR`, `IPADOS`, `VISIONOS`, `WEB_REMIX`)
 *   with NewPipe cipher/extractor fallback and stream URL caching.
 * - WaveBits Queue Management (`YouTubeQueue`, `YouTubeAlbumRadio`, `ListQueue`):
 *   - Separate user-queued (`queued`) and context/automix (`nextUp`) queues
 *   - Endless `YouTube.next(WatchEndpoint, continuation)` radio & automix pagination
 *   - Shuffle with original order restoration, `playNext`, `addToQueue`, `removeFromQueue`, `moveInQueue`, `skipTo`.
 */
class YouTubePlayer(
    private val context: Context,
    val account: YouTubeAccount,
    private val scope: CoroutineScope,
) : Player {
    private val tag = "WaveBitsYTPlayer"

    private val _state = MutableStateFlow<PlayerState?>(null)
    override val state: StateFlow<PlayerState?> = _state

    private val _queue = MutableStateFlow<Queue?>(null)
    override val queue: StateFlow<Queue?> = _queue

    private var exoPlayer: ExoPlayer? = null
    private var tickerJob: Job? = null
    private var playJob: Job? = null
    private var prefetchJob: Job? = null
    private var currentTrack: YouTubeTrackData? = null
    private var currentContextUri: String? = null
    private var currentContextName: String = "YouTube Music"

    // Main context/radio queue
    private var currentQueueList = mutableListOf<YouTubeTrackData>()
    private var unshuffledQueueList: List<YouTubeTrackData>? = null
    private var currentQueueIndex = 0

    // Explicit user-added queue (Play Next / Add to Queue), played before advancing nextUp
    private val userQueuedList = mutableListOf<YouTubeTrackData>()

    // WaveBits YouTubeQueue / YouTubeAlbumRadio continuation state
    private var activeWatchEndpoint: WatchEndpoint? = null
    private var continuationToken: String? = null
    private val isLoadingContinuation = AtomicBoolean(false)

    private var isShuffle = false
    private var repeatMode = Repeat.OFF
    private var currentVolume = 1.0
    @Volatile private var audioQuality: String = "auto"
    @Volatile var crossfadeSeconds: Int = 0
        set(value) {
            val v = value.coerceIn(0, 12)
            if (field == v) return
            field = v
            if (v == 0) {
                scope.launch(Dispatchers.Main) {
                    cancelCrossfade(restorePrimaryVolume = true)
                }
            }
        }
    @Volatile var autoplayEnabled: Boolean = true

    private var incomingPlayer: ExoPlayer? = null
    private var crossfadePrepareJob: Job? = null
    @Volatile private var crossfadePreparingForId: String? = null
    @Volatile private var crossfadePreparedTrack: YouTubeTrackData? = null
    @Volatile private var crossfadeActive: Boolean = false
    @Volatile private var crossfadeStartedPosMs: Long = 0L
    @Volatile private var crossfadeWindowMs: Long = 0L

    fun setAudioQuality(level: String) {
        if (audioQuality != level) {
            audioQuality = level
            streamCache.clear()
        }
    }

    var fallbackStreamResolver: (suspend (videoId: String, title: String, artist: String) -> String?)? = null

    private val registeredTracksMap = ConcurrentHashMap<String, YouTubeTrackData>()
    private val registeredContexts = ConcurrentHashMap<String, List<YouTubeTrackData>>()
    private val streamCache = ConcurrentHashMap<String, CachedStreamUrl>()
    private val inFlightResolutions = ConcurrentHashMap<String, Deferred<List<String>>>()
    private val streamUserAgents = ConcurrentHashMap<String, String>()
    private val streamContentLengths = ConcurrentHashMap<String, Long>()
    private val queryHints = ConcurrentHashMap<String, String>()
    private val playbackRetryCount = ConcurrentHashMap<String, Int>()

    init {
        startTicker()
        // Pre-warm ExoPlayer codecs on Main and visitorData + NewPipe on IO so first song tap has zero cold-start overhead
        scope.launch(Dispatchers.Main) {
            runCatching { getOrCreatePlayer() }
        }
        scope.launch(Dispatchers.IO) {
            runCatching { YouTubeStreams.ensureNewPipe() }
            if (YouTube.visitorData.isNullOrBlank()) {
                runCatching {
                    YouTube.visitorData().getOrNull()?.let { YouTube.visitorData = it }
                }
            }
        }
    }

    /**
     * Resolves HTTP headers required by YouTube's googlevideo CDN based on client signature.
     */
    fun getStreamHeaders(url: String): Map<String, String> = streamHeaders(url)

    fun getStreamContentLength(url: String): Long? =
        streamContentLengths[url] ?: runCatching {
            android.net.Uri.parse(url).getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0L }
        }.getOrNull()

    private fun streamHeaders(url: String): Map<String, String> {
        streamUserAgents[url]?.takeIf { it.isNotBlank() }?.let { ua ->
            return mapOf("User-Agent" to ua)
        }
        val loc = Localization.DEFAULT
        return when {
            url.contains("c=ANDROID_VR") ->
                mapOf("User-Agent" to YouTubeClient.ANDROID_VR_1_43_32.userAgent)
            url.contains("c=ANDROID_TESTSUITE") ->
                mapOf("User-Agent" to YouTubeClient.ANDROID_TESTSUITE.userAgent)
            YoutubeParsingHelper.isAndroidStreamingUrl(url) ->
                mapOf("User-Agent" to YoutubeParsingHelper.getAndroidUserAgent(loc))
            YoutubeParsingHelper.isIosStreamingUrl(url) ->
                mapOf("User-Agent" to YoutubeParsingHelper.getIosUserAgent(loc))
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
                mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(loc))
            url.contains("c=TVHTML5") ->
                mapOf("User-Agent" to YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER.userAgent)
            url.contains("googlevideo.com") ->
                mapOf("User-Agent" to YouTubeClient.USER_AGENT_WEB)
            else -> emptyMap()
        }
    }

    fun registerQueryHint(trackId: String, queryHint: String) {
        val clean = trackId.substringAfterLast(':').removePrefix("yt_").substringBefore('|').trim()
        if (clean.isNotBlank() && queryHint.isNotBlank()) {
            queryHints[clean] = queryHint
            queryHints[trackId] = queryHint
            queryHints[trackId.substringBefore('|')] = queryHint
            queryHints["spotify:track:$clean"] = queryHint
            queryHints["spotify:track:yt_$clean"] = queryHint
        }
    }

    fun cacheTrack(t: Track) {
        val cleanId = t.uri.substringAfterLast(':').removePrefix("yt_").substringBefore('|').trim()
        if (cleanId.isBlank()) return
        val cover = t.album?.images?.firstOrNull()?.url.orEmpty()
        val artist = t.artists.joinToString(", ") { it.name }.ifBlank { "YouTube Music" }
        val reg = YouTubeTrackData(
            id = cleanId,
            uri = if (t.uri.startsWith("spotify:track:")) t.uri.substringBefore('|') else "spotify:track:yt_$cleanId",
            title = t.name,
            artist = artist,
            album = t.album?.name.orEmpty(),
            coverUrl = cover,
            durationMs = t.durationMs.takeIf { it > 0 } ?: 180_000L,
        )
        registeredTracksMap[cleanId] = reg
        registeredTracksMap[reg.uri] = reg
        registeredTracksMap["spotify:track:$cleanId"] = reg
        registeredTracksMap["spotify:track:yt_$cleanId"] = reg
        if (t.name.isNotBlank() && t.name != "YouTube Track") {
            val hint = "$artist - ${t.name}"
            queryHints[cleanId] = hint
            queryHints[reg.uri] = hint
            queryHints["spotify:track:$cleanId"] = hint
        }
    }

    fun getRegisteredTrack(idOrUri: String): YouTubeTrackData? {
        val clean = idOrUri.substringAfterLast(':').removePrefix("yt_").substringBefore('|').trim()
        val keyWithYt = "spotify:track:yt_$clean"
        val keyWithoutYt = "spotify:track:$clean"
        return registeredTracksMap[clean]
            ?: registeredTracksMap[idOrUri]
            ?: registeredTracksMap[idOrUri.substringBefore('|')]
            ?: registeredTracksMap[keyWithYt]
            ?: registeredTracksMap[keyWithoutYt]
    }

    fun getRegisteredContext(ctx: String): List<YouTubeTrackData>? = registeredContexts[ctx]

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun musicAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildExoPlayerInstance(handleAudioFocus: Boolean): ExoPlayer {
        val defaultHttp = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val dataSources = androidx.media3.datasource.ResolvingDataSource.Factory(
            ChunkedDataSource.Factory(
                androidx.media3.datasource.DefaultDataSource.Factory(
                    context,
                    defaultHttp,
                ),
            ),
            YouTubeStreamResolver(
                context = context.applicationContext,
                wantsLowRate = { audioQuality.equals("low", ignoreCase = true) },
            ),
        )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .setBackBuffer(30_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(musicAudioAttributes(), handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    private fun attachPrimaryListener(player: ExoPlayer) {
        player.addListener(object : ExoPlayerListener.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== exoPlayer) return
                when (playbackState) {
                    ExoPlayerListener.STATE_READY -> {
                        val track = currentTrack ?: return
                        if (crossfadeSeconds <= 0 && !crossfadeActive) {
                            player.volume = currentVolume.toFloat().coerceIn(0f, 1f)
                        }
                        val updatedDuration = player.duration.takeIf { it > 0 } ?: track.durationMs
                        val currentItem = _state.value?.item ?: nowPlayingOf(track)
                        _state.value = _state.value?.copy(
                            item = currentItem.copy(durationMs = updatedDuration),
                            isBuffering = false,
                            positionMs = player.currentPosition,
                            timestampMs = System.currentTimeMillis(),
                        )
                    }
                    ExoPlayerListener.STATE_BUFFERING -> {
                        _state.value = _state.value?.copy(isBuffering = true)
                    }
                    ExoPlayerListener.STATE_ENDED -> {
                        if (crossfadeActive && incomingPlayer != null) {
                            completeCrossfadeHandoff()
                        } else {
                            scope.launch { onTrackCompleted() }
                        }
                    }
                    ExoPlayerListener.STATE_IDLE -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== exoPlayer) return
                _state.value = _state.value?.copy(
                    isPaused = !isPlaying,
                    positionMs = player.currentPosition,
                    timestampMs = System.currentTimeMillis(),
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== exoPlayer) return
                Log.w(tag, "ExoPlayer error ${error.errorCodeName}: ${error.message}")
                SignedInStreams.forget()
                val track = currentTrack
                val resumePos = player.currentPosition.coerceAtLeast(0L)
                if (track != null) {
                    YouTubeStreams.forget(track.id)
                    streamCache.remove(track.id)
                    val attempts = playbackRetryCount.getOrDefault(track.id, 0)
                    if (attempts < 3) {
                        playbackRetryCount[track.id] = attempts + 1
                        scope.launch {
                            delay(1_000L * (attempts + 1))
                            val retryUrl = withContext(Dispatchers.IO) {
                                resolveDirectStreams(track.id, track.title, track.artist).firstOrNull()
                                    ?: fallbackStreamResolver?.invoke(track.id, track.title, track.artist)
                            }
                            if (!retryUrl.isNullOrBlank()) {
                                playWithUrl(retryUrl, track, resumePos)
                                return@launch
                            }
                            next()
                        }
                        return
                    }
                }
                scope.launch { next() }
            }
        })
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun getOrCreatePlayer(): ExoPlayer {
        if (tickerJob?.isActive != true) startTicker()
        val existing = exoPlayer
        if (existing != null) return existing
        val player = buildExoPlayerInstance(handleAudioFocus = true)
        attachPrimaryListener(player)
        exoPlayer = player
        return player
    }

    fun registerTracks(items: List<Triple<String, String, String>>, meta: List<Triple<String, String, out Number>>) {
        for (i in items.indices) {
            val (rawId, title, artist) = items[i]
            val cover = meta.getOrNull(i)?.first.orEmpty()
            val album = meta.getOrNull(i)?.second.orEmpty()
            val duration = meta.getOrNull(i)?.third?.toLong() ?: 180_000L
            val cleanId = rawId.substringAfterLast(':').removePrefix("yt_").substringBefore('|').trim()
            if (cleanId.isBlank()) continue
            val reg = YouTubeTrackData(
                id = cleanId,
                uri = if (rawId.startsWith("spotify:track:")) rawId.substringBefore('|') else "spotify:track:yt_$cleanId",
                title = title,
                artist = artist,
                album = album,
                coverUrl = cover,
                durationMs = duration,
            )
            registeredTracksMap[cleanId] = reg
            registeredTracksMap[reg.uri] = reg
            registeredTracksMap[rawId.substringBefore('|')] = reg
            registeredTracksMap["spotify:track:$cleanId"] = reg
            registeredTracksMap["spotify:track:yt_$cleanId"] = reg
            if (title.isNotBlank() && title != "YouTube Track") {
                val hint = if (artist.isNotBlank() && artist != "YouTube Music") "$title - $artist" else title
                queryHints[cleanId] = hint
                queryHints[reg.uri] = hint
                queryHints[rawId.substringBefore('|')] = hint
                queryHints["spotify:track:$cleanId"] = hint
                queryHints["spotify:track:yt_$cleanId"] = hint
            }
        }
    }

    fun registerContextTracks(ctx: String, items: List<Triple<String, String, String>>, meta: List<Triple<String, String, out Number>>) {
        registerTracks(items, meta)
        val list = items.mapIndexedNotNull { i, it ->
            val cleanId = it.first.substringAfterLast(':').removePrefix("yt_").substringBefore('|')
            if (cleanId.isBlank()) return@mapIndexedNotNull null
            registeredTracksMap[cleanId] ?: YouTubeTrackData(
                id = cleanId,
                uri = "spotify:track:yt_$cleanId",
                title = it.second,
                artist = it.third,
                album = meta.getOrNull(i)?.second.orEmpty(),
                coverUrl = meta.getOrNull(i)?.first.orEmpty(),
                durationMs = meta.getOrNull(i)?.third?.toLong() ?: 180_000L,
            )
        }
        if (list.isNotEmpty()) {
            registeredContexts[ctx] = list
        }
    }

    private fun registerSongItems(songs: List<SongItem>): List<YouTubeTrackData> {
        if (songs.isEmpty()) return emptyList()
        val items = songs.map { Triple(it.id, it.title, it.artists.joinToString(", ") { a -> a.name }) }
        val meta = songs.map { Triple(it.thumbnail, it.album?.name.orEmpty(), (it.duration ?: 180) * 1000L) }
        registerTracks(items, meta)
        return songs.mapNotNull { registeredTracksMap[it.id] }
    }

    fun stopAndReset() {
        tickerJob?.cancel()
        playJob?.cancel()
        prefetchJob?.cancel()
        cancelCrossfade(restorePrimaryVolume = false)
        runCatching {
            exoPlayer?.stop()
            exoPlayer?.release()
        }
        exoPlayer = null
        currentTrack = null
        userQueuedList.clear()
        continuationToken = null
        activeWatchEndpoint = null
        _state.value = null
        _queue.value = null
    }

    private fun cancelCrossfade(restorePrimaryVolume: Boolean) {
        crossfadePrepareJob?.cancel()
        crossfadePrepareJob = null
        crossfadePreparingForId = null
        crossfadePreparedTrack = null
        crossfadeActive = false
        crossfadeStartedPosMs = 0L
        crossfadeWindowMs = 0L
        runCatching {
            incomingPlayer?.stop()
            incomingPlayer?.release()
        }
        incomingPlayer = null
        if (restorePrimaryVolume) {
            exoPlayer?.volume = currentVolume.toFloat().coerceIn(0f, 1f)
        }
    }

    private fun peekNextTrackForCrossfade(): YouTubeTrackData? {
        if (repeatMode == Repeat.TRACK) return null
        return userQueuedList.firstOrNull()
            ?: currentQueueList.getOrNull(currentQueueIndex + 1)
            ?: if (repeatMode == Repeat.CONTEXT && currentQueueList.isNotEmpty()) currentQueueList.firstOrNull() else null
    }

    private fun maybePrepareCrossfade(nextTrack: YouTubeTrackData) {
        if (crossfadePreparingForId == nextTrack.id && (incomingPlayer != null || crossfadePrepareJob?.isActive == true)) return
        crossfadePrepareJob?.cancel()
        runCatching {
            incomingPlayer?.stop()
            incomingPlayer?.release()
        }
        incomingPlayer = null
        crossfadePreparedTrack = null
        crossfadePreparingForId = nextTrack.id
        crossfadePrepareJob = scope.launch {
            val streamUrl = withContext(Dispatchers.IO) { resolveStreamUrl(nextTrack) } ?: return@launch
            withContext(Dispatchers.Main) {
                if (!isActive || crossfadeSeconds <= 0 || peekNextTrackForCrossfade()?.id != nextTrack.id) return@withContext
                runCatching {
                    val secondary = buildExoPlayerInstance(handleAudioFocus = false)
                    val mediaSource = buildMediaSource(streamUrl)
                    secondary.volume = 0f
                    secondary.setMediaSource(mediaSource, 0L)
                    secondary.playWhenReady = false
                    secondary.prepare()
                    incomingPlayer = secondary
                    crossfadePreparedTrack = nextTrack
                }.onFailure { e ->
                    Log.w(tag, "Failed to prepare crossfade player for ${nextTrack.title}", e)
                    crossfadePreparingForId = null
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun completeCrossfadeHandoff() {
        val nextTrack = crossfadePreparedTrack ?: return
        val promoted = incomingPlayer ?: return
        val outgoing = exoPlayer

        // Advance queue to match nextTrack
        if (userQueuedList.isNotEmpty() && userQueuedList.first().id == nextTrack.id) {
            val queued = userQueuedList.removeAt(0)
            val insertAt = (currentQueueIndex + 1).coerceAtMost(currentQueueList.size)
            currentQueueList.add(insertAt, queued)
            currentQueueIndex = insertAt
        } else if (currentQueueIndex + 1 < currentQueueList.size && currentQueueList[currentQueueIndex + 1].id == nextTrack.id) {
            currentQueueIndex++
        } else if (repeatMode == Repeat.CONTEXT && currentQueueList.isNotEmpty() && currentQueueList.first().id == nextTrack.id) {
            currentQueueIndex = 0
        }

        incomingPlayer = null
        crossfadePrepareJob = null
        crossfadePreparingForId = null
        crossfadePreparedTrack = null
        crossfadeActive = false
        crossfadeStartedPosMs = 0L
        crossfadeWindowMs = 0L

        attachPrimaryListener(promoted)
        promoted.setAudioAttributes(musicAudioAttributes(), true)
        promoted.volume = currentVolume.toFloat().coerceIn(0f, 1f)
        if (!promoted.isPlaying) {
            promoted.playWhenReady = true
            promoted.play()
        }
        exoPlayer = promoted
        currentTrack = nextTrack

        runCatching {
            outgoing?.stop()
            outgoing?.release()
        }

        val updatedDuration = promoted.duration.takeIf { it > 0 } ?: nextTrack.durationMs
        val prevTrack = currentQueueList.getOrNull(currentQueueIndex - 1)?.let(::nowPlayingOf)
        val upcomingTrack = (userQueuedList.firstOrNull() ?: currentQueueList.getOrNull(currentQueueIndex + 1))?.let(::nowPlayingOf)
        _state.value = PlayerState(
            item = nowPlayingOf(nextTrack).copy(durationMs = updatedDuration),
            previous = prevTrack,
            next = upcomingTrack,
            contextUri = currentContextUri,
            contextName = currentContextName,
            isPaused = false,
            isBuffering = promoted.playbackState == ExoPlayerListener.STATE_BUFFERING,
            positionMs = promoted.currentPosition.coerceAtLeast(0L),
            timestampMs = System.currentTimeMillis(),
            speed = 1.0,
            shuffle = isShuffle,
            repeat = repeatMode,
            restrictions = PlayerRestrictions(),
        )
        updateQueueState()
        prefetchUpcomingStreams()
        maybeFetchContinuation()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val delayMs = if (crossfadeSeconds > 0 && exoPlayer?.isPlaying == true) 50L else 250L
                delay(delayMs)
                val p = exoPlayer ?: continue
                if (p.isPlaying) {
                    val pos = p.currentPosition.coerceAtLeast(0L)
                    val dur = p.duration.takeIf { it > 0 } ?: currentTrack?.durationMs ?: 0L
                    val cfMs = crossfadeSeconds.coerceIn(0, 12) * 1000L
                    val maxVol = currentVolume.toFloat().coerceIn(0f, 1f)
                    if (cfMs > 0 && dur > 8_000L) {
                        val effectiveCfMs = minOf(cfMs, (dur * 2L / 5L).coerceAtLeast(2_000L))
                        val remaining = (dur - pos).coerceAtLeast(0L)
                        val nextTrack = peekNextTrackForCrossfade()
                        if (nextTrack != null && remaining in 1..(effectiveCfMs + 10_000L)) {
                            maybePrepareCrossfade(nextTrack)
                        }
                        if (!crossfadeActive && nextTrack != null && remaining in 1..effectiveCfMs) {
                            val sec = incomingPlayer
                            if (sec != null && crossfadePreparedTrack?.id == nextTrack.id &&
                                sec.playbackState != ExoPlayerListener.STATE_IDLE
                            ) {
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
                            p.volume = (maxVol * outGain).coerceIn(0f, 1f)
                            incomingPlayer?.volume = (maxVol * inGain).coerceIn(0f, 1f)
                            if (t >= 1f || remaining <= 60L) {
                                completeCrossfadeHandoff()
                                continue
                            }
                        } else if (nextTrack != null && remaining in 1..effectiveCfMs) {
                            val t = (1f - remaining.toFloat() / effectiveCfMs.toFloat()).coerceIn(0f, 1f)
                            val outGain = kotlin.math.cos(t * (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)
                            p.volume = (maxVol * outGain).coerceIn(0f, 1f)
                        } else {
                            p.volume = maxVol
                        }
                    } else {
                        if (crossfadeActive || incomingPlayer != null) {
                            cancelCrossfade(restorePrimaryVolume = false)
                        }
                        p.volume = maxVol
                    }
                    _state.value = _state.value?.copy(
                        positionMs = pos,
                        timestampMs = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    private suspend fun loadContextOnDemand(contextUri: String): List<YouTubeTrackData> = withContext(Dispatchers.IO) {
        val cleanId = contextUri.substringAfterLast(':').removePrefix("yt_").substringBefore('|')
        val songs = when {
            contextUri.startsWith("spotify:playlist:") -> {
                if (cleanId.startsWith("RDAMPL") || cleanId.startsWith("RDAMVM")) {
                    val videoId = cleanId.removePrefix("RDAMPL").removePrefix("RDAMVM")
                    val ep = WatchEndpoint(videoId = videoId, playlistId = cleanId)
                    val nextRes = YouTube.next(ep).getOrNull()
                    activeWatchEndpoint = nextRes?.endpoint ?: ep
                    continuationToken = nextRes?.continuation
                    nextRes?.items.orEmpty()
                } else {
                    val plPage = YouTube.playlist(cleanId).getOrNull()
                    activeWatchEndpoint = WatchEndpoint(playlistId = cleanId)
                    continuationToken = null
                    plPage?.songs.orEmpty()
                }
            }
            contextUri.startsWith("spotify:album:") -> {
                val albumPage = YouTube.album(cleanId).getOrNull()
                val playlistId = albumPage?.album?.playlistId
                if (!playlistId.isNullOrBlank()) {
                    activeWatchEndpoint = WatchEndpoint(playlistId = playlistId)
                }
                albumPage?.songs.orEmpty()
            }
            contextUri.startsWith("spotify:artist:") -> {
                val page = YouTube.artist(cleanId).getOrNull()
                page?.sections?.firstOrNull { s -> s.items.any { it is SongItem } }
                    ?.items?.filterIsInstance<SongItem>().orEmpty()
            }
            contextUri == "spotify:collection:tracks" -> {
                YouTube.playlist("LM").getOrNull()?.songs.orEmpty()
            }
            else -> emptyList()
        }
        if (songs.isNotEmpty()) {
            val items = songs.map { Triple(it.id, it.title, it.artists.joinToString(", ") { a -> a.name }) }
            val meta = songs.map { Triple(it.thumbnail, it.album?.name.orEmpty(), (it.duration ?: 180) * 1000L) }
            registerContextTracks(contextUri, items, meta)
        }
        registeredContexts[contextUri].orEmpty()
    }

    fun onQueueWatched() {
        updateQueueState()
        val remaining = currentQueueList.size - 1 - currentQueueIndex
        if (remaining < 5 && currentTrack != null && (autoplayEnabled || !continuationToken.isNullOrBlank())) {
            maybeFetchContinuation(force = true)
        }
    }

    fun setAutoplay(enabled: Boolean) {
        autoplayEnabled = enabled
        if (enabled) {
            maybeFetchContinuation(force = true)
        } else {
            updateQueueState()
        }
    }

    suspend fun moveIntoQueue(item: NowPlaying, target: NowPlaying?, after: Boolean) {
        val fromNextIdx = currentQueueList.indexOfFirst {
            (it.uri == item.uri || it.id == item.uid) && currentQueueList.indexOf(it) > currentQueueIndex
        }
        val movedTrack = if (fromNextIdx > currentQueueIndex) {
            currentQueueList.removeAt(fromNextIdx)
        } else {
            val fromUserIdx = userQueuedList.indexOfFirst { it.uri == item.uri || it.id == item.uid }
            if (fromUserIdx >= 0) userQueuedList.removeAt(fromUserIdx) else trackOfUri(item.uri)
        }

        if (target == null) {
            userQueuedList.add(0, movedTrack)
        } else {
            val targetIdx = userQueuedList.indexOfFirst { it.uri == target.uri || it.id == target.uid }
            if (targetIdx >= 0) {
                val insertAt = if (after) (targetIdx + 1).coerceAtMost(userQueuedList.size) else targetIdx
                userQueuedList.add(insertAt, movedTrack)
            } else {
                userQueuedList.add(movedTrack)
            }
        }
        updateQueueState()
    }

    override suspend fun play(
        contextUri: Uri,
        trackUri: Uri?,
        index: Int?,
        positionMs: Long?,
        order: List<Uri>?,
    ) {
        currentContextUri = contextUri
        currentContextName = "YouTube Music"
        continuationToken = null
        activeWatchEndpoint = null
        isLoadingContinuation.set(false)

        val effectiveTrackUri = trackUri ?: contextUri.takeIf { it.startsWith("spotify:track:") }
        var tracks = registeredContexts[contextUri].orEmpty()

        // CRITICAL FAST-PATH: Only block on loadContextOnDemand when we don't know which track to play yet
        // (e.g., user tapped "Play" on an uncached album/playlist card without a specific trackUri).
        // When effectiveTrackUri is known, start playing the requested track IMMEDIATELY at 0ms and load
        // the surrounding context/radio tracks asynchronously in the background!
        if (tracks.isEmpty() && effectiveTrackUri == null && !contextUri.startsWith("spotify:track:")) {
            tracks = loadContextOnDemand(contextUri)
        }
        currentQueueList = tracks.toMutableList()
        unshuffledQueueList = null

        if (order != null && order.isNotEmpty() && currentQueueList.isNotEmpty()) {
            val orderMap = order.mapIndexed { idx, u -> u.substringBefore('|') to idx }.toMap()
            currentQueueList.sortBy { orderMap[it.uri] ?: Int.MAX_VALUE }
        }

        if (currentQueueList.isNotEmpty()) {
            val cleanEffectiveUri = effectiveTrackUri?.substringBefore('|')
            val targetUri = cleanEffectiveUri ?: currentQueueList.getOrNull(index ?: 0)?.uri
            val cleanTargetId = targetUri?.substringAfterLast(':')?.removePrefix("yt_")?.substringBefore('|')
            val trackIdx = if (targetUri != null) {
                currentQueueList.indexOfFirst { it.uri == targetUri || it.id == cleanTargetId }
            } else index ?: 0

            val safeIdx = if (trackIdx >= 0) {
                trackIdx
            } else if (effectiveTrackUri != null) {
                val requested = trackOfUri(effectiveTrackUri)
                currentQueueList.add(0, requested)
                0
            } else {
                (index ?: 0).coerceIn(0, (currentQueueList.size - 1).coerceAtLeast(0))
            }
            if (isShuffle && currentQueueList.size > 1) {
                unshuffledQueueList = currentQueueList.toList()
                val chosen = currentQueueList[safeIdx]
                val rest = currentQueueList.filterIndexed { i, _ -> i != safeIdx }.shuffled()
                currentQueueList = (listOf(chosen) + rest).toMutableList()
                currentQueueIndex = 0
                playTrackAtIndex(0, positionMs ?: 0L, triggerContinuation = true)
            } else {
                currentQueueIndex = safeIdx
                playTrackAtIndex(safeIdx, positionMs ?: 0L, triggerContinuation = true)
            }
        } else if (effectiveTrackUri != null) {
            val single = trackOfUri(effectiveTrackUri)
            currentQueueList = mutableListOf(single)
            currentQueueIndex = 0
            // Start resolving stream URL & playing immediately without waiting for context/radio API!
            playTrackAtIndex(0, positionMs ?: 0L, triggerContinuation = false)

            // Asynchronously populate context/radio queue in the background
            scope.launch(Dispatchers.IO) {
                val contextLoaded = if (!contextUri.startsWith("spotify:track:")) {
                    runCatching { loadContextOnDemand(contextUri) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                val registered = if (contextLoaded.size > 1) {
                    contextLoaded
                } else if (autoplayEnabled) {
                    fetchRadioOrSearchFallbackTracks(single)
                } else {
                    emptyList()
                }

                if (registered.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (currentTrack?.id == single.id) {
                            val firstMatch = registered.firstOrNull { it.id == single.id }
                            if (firstMatch != null && (single.title == "YouTube Track" || single.coverUrl.isBlank())) {
                                currentTrack = firstMatch
                                if (currentQueueIndex in currentQueueList.indices) {
                                    currentQueueList[currentQueueIndex] = firstMatch
                                }
                            }
                            val idxInContext = registered.indexOfFirst { it.id == single.id }
                            if (idxInContext >= 0 && !isShuffle && contextLoaded.size > 1 && currentQueueList.size <= 1) {
                                currentQueueList = registered.toMutableList()
                                currentQueueIndex = idxInContext
                            } else {
                                val existingIds = currentQueueList.map { it.id }.toHashSet()
                                val radioTracks = registered.filter { it.id !in existingIds }
                                val finalRadio = if (isShuffle) radioTracks.shuffled() else radioTracks
                                currentQueueList.addAll(finalRadio)
                            }
                            updateQueueState()
                            prefetchUpcomingStreams()
                        }
                    }
                }
                // If contextLoaded had tracks but single was near the end of the context, top up radio if autoplay is on
                if (currentQueueList.size - 1 - currentQueueIndex < 4 && autoplayEnabled) {
                    maybeFetchContinuation(force = true)
                }
            }
        }
    }

    private suspend fun fetchRadioOrSearchFallbackTracks(seedTrack: YouTubeTrackData): List<YouTubeTrackData> {
        val videoIdForWatch = if (isValidYouTubeVideoId(seedTrack.id)) {
            seedTrack.id
        } else {
            resolveYouTubeVideoId(seedTrack) ?: seedTrack.id
        }
        val ep = WatchEndpoint(videoId = videoIdForWatch, playlistId = "RDAMVM$videoIdForWatch")
        val nextResult = runCatching {
            YouTube.next(ep).getOrNull()?.takeIf { it.items.size > 1 }
                ?: YouTube.next(WatchEndpoint(videoId = videoIdForWatch)).getOrNull()?.takeIf { it.items.size > 1 }
                ?: YouTube.next(WatchEndpoint(playlistId = "RDAMVM$videoIdForWatch")).getOrNull()
        }.getOrNull()

        val nextSongs = nextResult?.items.orEmpty()
        if (nextResult != null) {
            activeWatchEndpoint = nextResult.endpoint ?: ep
            continuationToken = nextResult.continuation
        }
        val fromNext = registerSongItems(nextSongs)
        if (fromNext.count { it.id != seedTrack.id } >= 2) {
            return fromNext
        }

        // Fallback: if YouTube.next returned only the current track or empty, search related songs by artist/title
        val cleanTitle = seedTrack.title.takeIf { it.isNotBlank() && it != "YouTube Track" }.orEmpty()
        val cleanArtist = seedTrack.artist.takeIf { it.isNotBlank() && it != "YouTube Music" }.orEmpty()
        val searchQuery = when {
            cleanArtist.isNotBlank() && cleanTitle.isNotBlank() -> "$cleanArtist $cleanTitle songs"
            cleanArtist.isNotBlank() -> "$cleanArtist top songs"
            cleanTitle.isNotBlank() -> "$cleanTitle songs"
            else -> "Top Hits Music"
        }
        val searchedSongs = runCatching {
            YouTube.search(searchQuery, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                ?.items?.filterIsInstance<SongItem>().orEmpty()
        }.getOrDefault(emptyList())
        val fromSearch = registerSongItems(searchedSongs)
        return (fromNext + fromSearch).distinctBy { it.id }
    }

    fun cancelBackgroundPrefetch() {
        prefetchJob?.cancel()
    }

    /**
     * Pre-resolves stream URLs for the next upcoming tracks in the background so
     * skipping or auto-advancing hits `streamCache` with 0ms latency.
     */
    private fun prefetchUpcomingStreams() {
        prefetchJob?.cancel()
        if (!YouTubeStreams.allowBackgroundPrefetch) return
        val upcoming = buildList {
            addAll(userQueuedList.take(3))
            if (size < 3 && currentQueueList.isNotEmpty()) {
                val nextIdx = currentQueueIndex + 1
                for (i in 0 until 3) {
                    if (nextIdx + i < currentQueueList.size) add(currentQueueList[nextIdx + i])
                }
            }
        }.distinctBy { it.id }

        if (upcoming.isEmpty()) return

        prefetchJob = scope.launch(Dispatchers.IO) {
            coroutineScope {
                for (track in upcoming) {
                    if (!isActive) break
                    val now = System.currentTimeMillis()
                    val cached = streamCache[track.id]
                    if (cached != null && cached.expiresAtMs > now + 60_000L) continue
                    async { runCatching { resolveStreamUrl(track) } }
                }
            }
        }
    }

    /**
     * WaveBits Queue Continuation (`YouTubeQueue.getNextPage()` & `YouTubeAlbumRadio`):
     * Automatically loads more tracks when approaching the end of the queue.
     */
    private fun maybeFetchContinuation(force: Boolean = false) {
        if (!autoplayEnabled && !force) return
        val remaining = currentQueueList.size - 1 - currentQueueIndex
        if (remaining > 4) return
        if (!isLoadingContinuation.compareAndSet(false, true)) return

        scope.launch(Dispatchers.IO) {
            try {
                val token = continuationToken
                val seedTrack = currentQueueList.lastOrNull() ?: currentTrack ?: return@launch
                val videoId = if (isValidYouTubeVideoId(seedTrack.id)) {
                    seedTrack.id
                } else {
                    resolveYouTubeVideoId(seedTrack) ?: seedTrack.id
                }
                val ep = activeWatchEndpoint ?: WatchEndpoint(videoId = videoId, playlistId = "RDAMVM$videoId")

                val nextResult = runCatching {
                    if (!token.isNullOrBlank()) {
                        YouTube.next(ep, continuation = token).getOrNull()
                    } else {
                        YouTube.next(WatchEndpoint(videoId = videoId, playlistId = "RDAMVM$videoId")).getOrNull()
                            ?.takeIf { it.items.size > 1 }
                            ?: YouTube.next(WatchEndpoint(videoId = videoId)).getOrNull()
                    }
                }.getOrNull()

                if (nextResult != null) {
                    continuationToken = nextResult.continuation
                    activeWatchEndpoint = nextResult.endpoint ?: ep
                }

                val existingIds = currentQueueList.map { it.id }.toHashSet()
                var newTracks = registerSongItems(nextResult?.items.orEmpty()).filter { it.id !in existingIds }

                if (newTracks.isEmpty()) {
                    newTracks = fetchRadioOrSearchFallbackTracks(seedTrack).filter { it.id !in existingIds }
                }

                if (newTracks.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val currentIds = currentQueueList.map { it.id }.toHashSet()
                        val filtered = newTracks.filter { it.id !in currentIds }
                        if (filtered.isNotEmpty()) {
                            currentQueueList.addAll(filtered)
                            updateQueueState()
                            prefetchUpcomingStreams()
                        }
                    }
                }
            } finally {
                isLoadingContinuation.set(false)
            }
        }
    }

    private fun splitQueryHint(hint: String): Pair<String, String> {
        val clean = hint.trim()
        if (clean.isBlank()) return "" to ""
        if (" - " in clean) {
            val title = clean.substringBefore(" - ").trim()
            val artist = clean.substringAfter(" - ").trim()
            return title to artist
        }
        return clean to ""
    }

    private fun trackOfUri(uri: String): YouTubeTrackData {
        val cleanUri = uri.substringBefore('|')
        val cleanId = cleanUri.substringAfterLast(':').removePrefix("yt_").substringBefore('|')
        val queryHint = uri.substringAfter('|', "").trim().ifBlank { queryHints[cleanId].orEmpty() }
        val (parsedTitle, parsedArtist) = splitQueryHint(queryHint)
        val hintTitle = parsedTitle.takeIf { it.isNotBlank() }
        val hintArtist = parsedArtist.takeIf { it.isNotBlank() }
        return registeredTracksMap[cleanId] ?: registeredTracksMap[cleanUri] ?: YouTubeTrackData(
            id = cleanId,
            uri = if (cleanUri.startsWith("spotify:track:")) cleanUri else "spotify:track:yt_$cleanId",
            title = hintTitle ?: "YouTube Track",
            artist = hintArtist ?: "YouTube Music",
            album = "",
            coverUrl = "https://i.ytimg.com/vi/$cleanId/hqdefault.jpg",
            durationMs = 180_000L,
        )
    }

    private suspend fun playTrackAtIndex(idx: Int, startPositionMs: Long, triggerContinuation: Boolean = true) {
        val track = currentQueueList.getOrNull(idx) ?: return
        currentTrack = track
        currentQueueIndex = idx
        playTrackInternal(track, startPositionMs)
        if (triggerContinuation) {
            maybeFetchContinuation()
        }
    }

    private suspend fun playTrackInternal(track: YouTubeTrackData, startPositionMs: Long) {
        playJob?.cancel()
        currentTrack = track
        val prevTrack = currentQueueList.getOrNull(currentQueueIndex - 1)?.let(::nowPlayingOf)
        val nextTrack = (userQueuedList.firstOrNull() ?: currentQueueList.getOrNull(currentQueueIndex + 1))?.let(::nowPlayingOf)

        val effectiveContextName = currentContextName
        _state.value = PlayerState(
            item = nowPlayingOf(track),
            previous = prevTrack,
            next = nextTrack,
            contextUri = currentContextUri,
            contextName = effectiveContextName,
            isPaused = false,
            isBuffering = true,
            positionMs = startPositionMs,
            timestampMs = System.currentTimeMillis(),
            speed = 1.0,
            shuffle = isShuffle,
            repeat = repeatMode,
            restrictions = PlayerRestrictions(),
        )
        updateQueueState()

        playJob = scope.launch {
            var workingTrack = track
            val isStationPlaceholder = workingTrack.title == "YouTube Track" ||
                workingTrack.title.isBlank() ||
                workingTrack.title in STATION_CARD_TITLES ||
                workingTrack.artist.startsWith("Mood for You") ||
                workingTrack.artist.startsWith("Personal Station") ||
                workingTrack.artist.startsWith("Top Pick")

            if (isStationPlaceholder && isValidYouTubeVideoId(workingTrack.id)) {
                val enriched = withContext(Dispatchers.IO) {
                    runCatching {
                        YouTube.next(WatchEndpoint(videoId = workingTrack.id)).getOrNull()?.items?.firstOrNull()
                    }.getOrNull()
                }
                if (enriched != null) {
                    val artistStr = enriched.artists.joinToString(", ") { it.name }.ifBlank { "YouTube Music" }
                    workingTrack = workingTrack.copy(
                        title = enriched.title.ifBlank { workingTrack.title },
                        artist = artistStr,
                        album = enriched.album?.name ?: workingTrack.album,
                        coverUrl = enriched.thumbnail.ifBlank { workingTrack.coverUrl },
                        durationMs = ((enriched.duration ?: 180) * 1000L).takeIf { it > 0L } ?: workingTrack.durationMs,
                    )
                    registerTracks(
                        listOf(Triple(workingTrack.id, workingTrack.title, workingTrack.artist)),
                        listOf(Triple(workingTrack.coverUrl, workingTrack.album, workingTrack.durationMs)),
                    )
                    if (currentTrack?.id == track.id) {
                        currentTrack = workingTrack
                        if (currentQueueIndex in currentQueueList.indices && currentQueueList[currentQueueIndex].id == track.id) {
                            currentQueueList[currentQueueIndex] = workingTrack
                        }
                        updateQueueState()
                    }
                }
            }

            val streamUrl = withContext(Dispatchers.IO) {
                resolveStreamUrl(workingTrack)
            }

            // Guard against race if user tapped another song while stream was resolving
            if (currentTrack?.id != workingTrack.id) return@launch

            if (streamUrl.isNullOrBlank()) {
                val sourceDesc = "YouTube"
                Log.e(tag, "Failed to resolve $sourceDesc audio stream for ${workingTrack.id} (${workingTrack.title})")
                if (userQueuedList.isNotEmpty() || currentQueueIndex + 1 < currentQueueList.size) {
                    next()
                } else {
                    _state.value = _state.value?.copy(isPaused = true, isBuffering = false)
                }
                return@launch
            }

            playWithUrl(streamUrl, workingTrack, startPositionMs)
            prefetchUpcomingStreams()
        }
    }

    private val STATION_CARD_TITLES = setOf(
        "Morning Energy",
        "Afternoon Focus",
        "Evening Chill",
        "Late Night Vibes",
        "Discovery Station",
    )

    private fun isValidYouTubeVideoId(id: String): Boolean {
        val clean = id.substringAfterLast(':').removePrefix("yt_").substringBefore('|')
        return clean.length == 11 && clean.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    private suspend fun resolveYouTubeVideoId(
        track: YouTubeTrackData,
        excludeVideoId: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val registered = getRegisteredTrack(track.id) ?: getRegisteredTrack(track.uri)
        val cleanId = track.id.substringAfterLast(':').removePrefix("yt_").substringBefore('|').trim()
        val hint = queryHints[cleanId] ?: queryHints[track.id] ?: queryHints[track.uri] ?: queryHints["spotify:track:$cleanId"]
        val title = (registered?.title ?: track.title).takeIf { it.isNotBlank() && it != "YouTube Track" }
        val artist = (registered?.artist ?: track.artist).takeIf { it.isNotBlank() && it != "YouTube Music" }
        val primaryQuery = when {
            !hint.isNullOrBlank() -> hint
            title != null && artist != null -> "$title $artist"
            title != null -> title
            else -> null
        } ?: return@withContext null

        val cleanedTitle = title
            ?.replace(Regex("""\([^)]*\)|\[[^\]]*]"""), " ")
            ?.replace(Regex("""\b7"\b|\b12"\b"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }

        val queries = buildList {
            add(primaryQuery)
            if (cleanedTitle != null) {
                add(if (artist != null) "$cleanedTitle $artist" else cleanedTitle)
            }
            if (title != null && artist != null && primaryQuery != "$title $artist") {
                add("$title $artist")
            }
        }.distinct()

        for (query in queries) {
            val res = runCatching {
                YouTube.search(query = query, filter = YouTube.SearchFilter.FILTER_SONG).getOrNull()
            }.getOrNull()
            val songId = res?.items?.filterIsInstance<SongItem>()
                ?.firstOrNull { it.id.isNotBlank() && it.id != excludeVideoId }?.id
                ?: res?.items?.firstOrNull { it.id.isNotBlank() && it.id != excludeVideoId }?.id
            if (!songId.isNullOrBlank()) {
                return@withContext songId
            }

            val videoRes = runCatching {
                YouTube.search(query = query, filter = YouTube.SearchFilter.FILTER_VIDEO).getOrNull()
            }.getOrNull()
            val videoId = videoRes?.items
                ?.firstOrNull { it.id.isNotBlank() && it.id != excludeVideoId }?.id
            if (!videoId.isNullOrBlank()) {
                return@withContext videoId
            }
        }

        val summaryRes = runCatching {
            YouTube.searchSummary(query = primaryQuery).getOrNull()
        }.getOrNull()
        val summarySongId = summaryRes?.summaries
            ?.flatMap { it.items }
            ?.firstOrNull { it.id.isNotBlank() && it.id != excludeVideoId }?.id
        if (!summarySongId.isNullOrBlank()) {
            return@withContext summarySongId
        }

        null
    }

    private fun extractUrlFromFormat(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
    ): String? = InnerTuneNewPipeHelper.getStreamUrl(format, videoId)

    private fun extractStreamsFromPlayerResponse(
        effectiveVideoId: String,
        client: YouTubeClient,
        resp: PlayerResponse,
        now: Long,
        validateTopStream: Boolean = true,
    ): List<String> {
        if (resp.playabilityStatus.status != "OK") return emptyList()
        val expiresInSec = resp.streamingData?.expiresInSeconds?.toLong() ?: 3600L
        val expiresAt = now + expiresInSec * 1000L
        val urls = mutableListOf<String>()

        val q = audioQuality
        val rawAdaptive = resp.streamingData?.adaptiveFormats.orEmpty().filter { it.isAudio }
        val preferredAdaptive = rawAdaptive.filter { it.isOriginal }.ifEmpty { rawAdaptive }
        val adaptiveAudio = preferredAdaptive
            .mapNotNull { format ->
                val resolvedUrl = extractUrlFromFormat(format, effectiveVideoId) ?: return@mapNotNull null
                format to resolvedUrl
            }
            .sortedWith(
                compareByDescending<Pair<PlayerResponse.StreamingData.Format, String>> { (format, _) ->
                    format.mimeType.contains("opus", ignoreCase = true) || format.mimeType.contains("mp4a", ignoreCase = true)
                }.let { cmp ->
                    when (q) {
                        "low" -> cmp.thenBy { (format, _) -> format.bitrate }
                        "normal" -> cmp.thenBy { (format, _) -> kotlin.math.abs(format.bitrate - 96_000) }
                        else -> cmp.thenByDescending { (format, _) -> format.bitrate }
                    }
                }
            )

        for ((format, url) in adaptiveAudio) {
            if (urls.isEmpty() && validateTopStream) {
                if (!InnerTuneNewPipeHelper.validateStreamStatus(url, client.userAgent)) {
                    Log.w(tag, "Stream validation failed (403/unplayable) for client ${client.clientName} on $effectiveVideoId")
                    return emptyList()
                }
            }
            streamUserAgents[url] = client.userAgent
            format.contentLength?.takeIf { it > 0L }?.let { streamContentLengths[url] = it }
            if (urls.isEmpty()) {
                streamCache[effectiveVideoId] = CachedStreamUrl(
                    url = url,
                    expiresAtMs = expiresAt,
                    userAgent = client.userAgent,
                    contentLength = format.contentLength,
                )
            }
            urls.add(url)
        }

        if (urls.isEmpty()) {
            val genericFormats = resp.streamingData?.formats.orEmpty()
                .mapNotNull { format ->
                    val u = extractUrlFromFormat(format, effectiveVideoId) ?: return@mapNotNull null
                    format to u
                }
            for ((format, url) in genericFormats) {
                if (urls.isEmpty() && validateTopStream) {
                    if (!InnerTuneNewPipeHelper.validateStreamStatus(url, client.userAgent)) {
                        return emptyList()
                    }
                }
                streamUserAgents[url] = client.userAgent
                format.contentLength?.takeIf { it > 0L }?.let { streamContentLengths[url] = it }
                if (urls.isEmpty()) {
                    streamCache[effectiveVideoId] = CachedStreamUrl(
                        url = url,
                        expiresAtMs = expiresAt,
                        userAgent = client.userAgent,
                        contentLength = format.contentLength,
                    )
                }
                urls.add(url)
            }
        }
        return urls
    }

    /**
     * Resolves direct stream URLs for a known YouTube [videoIdOrId] (or searches if not an 11-char ID)
     * using z-huang/InnerTune's multi-client playback pipeline (`YTPlayerUtils` + `NewPipeUtils`),
     * deobfuscating signatureCipher & throttling `n` parameter, validating HTTP 200/206 status, and
     * recording the required User-Agent for each stream URL so ExoPlayer and OfflineDownloader never hit HTTP 403.
     */
    suspend fun resolveDirectStreams(
        videoIdOrId: String,
        title: String,
        artist: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val cleanId = videoIdOrId.substringAfterLast(':').removePrefix("yt_").substringBefore('|').trim()
        val effectiveVideoId = if (isValidYouTubeVideoId(cleanId)) {
            cleanId
        } else {
            resolveYouTubeVideoId(
                YouTubeTrackData(
                    id = cleanId,
                    uri = "spotify:track:yt_$cleanId",
                    title = title,
                    artist = artist,
                    album = "",
                    coverUrl = "",
                    durationMs = 180_000L,
                ),
            ) ?: return@withContext emptyList()
        }

        val now = System.currentTimeMillis()
        streamCache[effectiveVideoId]?.let { cached ->
            if (cached.expiresAtMs > now + 30_000L) {
                cached.userAgent?.let { streamUserAgents[cached.url] = it }
                cached.contentLength?.let { streamContentLengths[cached.url] = it }
                return@withContext listOf(cached.url)
            } else {
                streamCache.remove(effectiveVideoId)
            }
        }

        // Deduplicate concurrent stream resolutions for the same videoId (e.g. background prefetch + user tap)
        inFlightResolutions[effectiveVideoId]?.let { existing ->
            if (existing.isActive) {
                val result = runCatching { existing.await() }.getOrDefault(emptyList())
                if (result.isNotEmpty()) return@withContext result
            }
        }

        val deferred = CompletableDeferred<List<String>>()
        inFlightResolutions[effectiveVideoId] = deferred

        try {
            YouTubeStreams.ensureNewPipe()
            if (YouTube.visitorData.isNullOrBlank()) {
                kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                    runCatching {
                        YouTube.visitorData().getOrNull()?.takeIf { it.isNotBlank() }?.let {
                            YouTube.visitorData = it
                        }
                    }
                }
            }

            val wantsLow = audioQuality.equals("low", ignoreCase = true)

            // Phase 1: Fast hedged race between direct InnerTube native clients with HTTP 200/206 stream validation.
            val fastUrls = supervisorScope {
                val primary = async {
                    val resp = runCatching {
                        YouTube.player(effectiveVideoId, client = YouTubeClient.ANDROID_VR_1_43_32).getOrNull()
                    }.getOrNull() ?: return@async emptyList()
                    extractStreamsFromPlayerResponse(effectiveVideoId, YouTubeClient.ANDROID_VR_1_43_32, resp, now, validateTopStream = true)
                }
                val backupIos = async {
                    delay(35)
                    val resp = runCatching {
                        YouTube.player(effectiveVideoId, client = YouTubeClient.IOS).getOrNull()
                    }.getOrNull() ?: return@async emptyList()
                    extractStreamsFromPlayerResponse(effectiveVideoId, YouTubeClient.IOS, resp, now, validateTopStream = true)
                }
                val backupVr161 = async {
                    delay(70)
                    val resp = runCatching {
                        YouTube.player(effectiveVideoId, client = YouTubeClient.ANDROID_VR_1_61_48).getOrNull()
                    }.getOrNull() ?: return@async emptyList()
                    extractStreamsFromPlayerResponse(effectiveVideoId, YouTubeClient.ANDROID_VR_1_61_48, resp, now, validateTopStream = true)
                }
                val backupVisionOs = async {
                    delay(110)
                    val resp = runCatching {
                        YouTube.player(effectiveVideoId, client = YouTubeClient.VISIONOS).getOrNull()
                    }.getOrNull() ?: return@async emptyList()
                    extractStreamsFromPlayerResponse(effectiveVideoId, YouTubeClient.VISIONOS, resp, now, validateTopStream = true)
                }
                val backupTestSuite = async {
                    delay(150)
                    val resp = runCatching {
                        YouTube.player(effectiveVideoId, client = YouTubeClient.ANDROID_TESTSUITE).getOrNull()
                    }.getOrNull() ?: return@async emptyList()
                    extractStreamsFromPlayerResponse(effectiveVideoId, YouTubeClient.ANDROID_TESTSUITE, resp, now, validateTopStream = true)
                }
                val candidates = mutableListOf(primary, backupIos, backupVr161, backupVisionOs, backupTestSuite)
                var winner: List<String> = emptyList()
                while (candidates.isNotEmpty() && winner.isEmpty()) {
                    val (completed, urls) = select<Pair<Deferred<List<String>>, List<String>>> {
                        for (c in candidates) {
                            c.onAwait { res -> c to res }
                        }
                    }
                    candidates.remove(completed)
                    if (urls.isNotEmpty()) {
                        winner = urls
                        candidates.forEach { it.cancel() }
                    }
                }
                winner
            }

            if (fastUrls.isNotEmpty()) {
                val distinct = fastUrls.distinct()
                deferred.complete(distinct)
                return@withContext distinct
            }

            // Phase 2: Full WaveBits InnerTube multi-client fallback (TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            // ANDROID_CREATOR, IPADOS, ANDROID_NO_SDK, MOBILE, TVHTML5, WEB_REMIX with signatureTimestamp & deobfuscation).
            val sigTimestamp by lazy { InnerTuneNewPipeHelper.getSignatureTimestamp(effectiveVideoId) }
            val fallbackClients = listOf(
                YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
                YouTubeClient.ANDROID_CREATOR,
                YouTubeClient.IPADOS,
                YouTubeClient.ANDROID_NO_SDK,
                YouTubeClient.MOBILE,
                YouTubeClient.TVHTML5,
                YouTubeClient.WEB_REMIX,
            )
            for (client in fallbackClients) {
                val ts = if (client.useSignatureTimestamp) sigTimestamp else null
                val resp = runCatching {
                    YouTube.player(
                        videoId = effectiveVideoId,
                        playlistId = activeWatchEndpoint?.playlistId,
                        client = client,
                        signatureTimestamp = ts,
                    ).getOrNull()
                }.getOrNull() ?: continue
                val urls = extractStreamsFromPlayerResponse(
                    effectiveVideoId = effectiveVideoId,
                    client = client,
                    resp = resp,
                    now = now,
                    validateTopStream = true,
                )
                if (urls.isNotEmpty()) {
                    val distinct = urls.distinct()
                    deferred.complete(distinct)
                    return@withContext distinct
                }
            }

            // Phase 3: WaveBits fallback stream resolution (`YouTubeStreams.resolveAudioUrls`:
            // NewPipeExtractor `StreamInfo.getInfo` + `SignedInStreams.url` with TVHTML5 + `PlayerChallenges` ejs AST solver).
            val fallbackStreamUrls = runCatching {
                YouTubeStreams.resolveAudioUrls(context, effectiveVideoId, lowRate = wantsLow)
            }.getOrDefault(emptyList())

            if (fallbackStreamUrls.isNotEmpty()) {
                val validFallbackUrls = fallbackStreamUrls.distinct().filter { candidateUrl ->
                    val inferredUa = streamUserAgents[candidateUrl]?.takeIf { it.isNotBlank() }
                        ?: streamHeaders(candidateUrl)["User-Agent"]?.takeIf { it.isNotBlank() }
                        ?: NewPipeDownloader.USER_AGENT
                    streamUserAgents[candidateUrl] = inferredUa
                    InnerTuneNewPipeHelper.validateStreamStatus(candidateUrl, inferredUa)
                }
                if (validFallbackUrls.isNotEmpty()) {
                    val topUrl = validFallbackUrls.first()
                    streamCache[effectiveVideoId] = CachedStreamUrl(
                        url = topUrl,
                        expiresAtMs = now + 3_600_000L,
                        userAgent = streamUserAgents[topUrl],
                        contentLength = getStreamContentLength(topUrl),
                    )
                    deferred.complete(validFallbackUrls)
                    return@withContext validFallbackUrls
                }
            }

            // Phase 4: Fallback stream resolver if available (NewPipe + cross-catalog high-bitrate audio match)
            val resolvedUrls = mutableListOf<String>()
            val fallbackUrl = runCatching { fallbackStreamResolver?.invoke(effectiveVideoId, title, artist) }.getOrNull()
            if (!fallbackUrl.isNullOrBlank()) {
                val deobfuscatedFallback = if (fallbackUrl.contains("googlevideo.com")) {
                    runCatching {
                        YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(effectiveVideoId, fallbackUrl)
                    }.getOrDefault(fallbackUrl)
                } else {
                    fallbackUrl
                }
                val fallbackUa = streamHeaders(deobfuscatedFallback)["User-Agent"]
                if (fallbackUa != null) {
                    streamUserAgents[deobfuscatedFallback] = fallbackUa
                }
                streamCache[effectiveVideoId] = CachedStreamUrl(deobfuscatedFallback, now + 1800_000L, fallbackUa)
                resolvedUrls.add(deobfuscatedFallback)
            }

            val finalResult = resolvedUrls.distinct()
            deferred.complete(finalResult)
            finalResult
        } catch (t: Throwable) {
            deferred.complete(emptyList())
            throw t
        } finally {
            inFlightResolutions.remove(effectiveVideoId, deferred)
        }
    }

    /**
     * Resolves streamable audio URL for [track].
     */
    private suspend fun resolveStreamUrl(track: YouTubeTrackData): String? {
        val registered = getRegisteredTrack(track.id) ?: getRegisteredTrack(track.uri)
        var title = (registered?.title ?: track.title).takeIf { it != "YouTube Track" && it !in STATION_CARD_TITLES } ?: ""
        var artist = (registered?.artist ?: track.artist).takeIf {
            it != "YouTube Music" &&
                !it.startsWith("Mood for You") &&
                !it.startsWith("Personal Station") &&
                !it.startsWith("Top Pick")
        } ?: ""

        val cleanId = track.id.substringAfterLast(':').removePrefix("yt_").substringBefore('|').trim()
        val isExplicitYtId = track.id.startsWith("yt_") || (!track.id.startsWith("spotify:") && isValidYouTubeVideoId(cleanId))

        var effectiveVideoId: String? = null
        if (isExplicitYtId && isValidYouTubeVideoId(cleanId)) {
            effectiveVideoId = cleanId
        }

        if (effectiveVideoId != null && title.isBlank()) {
            val nextSong = runCatching {
                YouTube.next(WatchEndpoint(videoId = effectiveVideoId)).getOrNull()?.items?.firstOrNull()
            }.getOrNull()
            if (nextSong != null) {
                title = nextSong.title
                artist = nextSong.artists.joinToString(", ") { it.name }
            }
        }

        if (effectiveVideoId == null) {
            effectiveVideoId = resolveYouTubeVideoId(track.copy(title = title, artist = artist))
        }

        val now = System.currentTimeMillis()
        if (effectiveVideoId != null) {
            streamCache[effectiveVideoId]?.let { cached ->
                if (cached.expiresAtMs > now + 30_000L) {
                    cached.userAgent?.let { streamUserAgents[cached.url] = it }
                    return cached.url
                } else {
                    streamCache.remove(effectiveVideoId)
                }
            }

            // 1. Resolve exact YouTube videoId stream first
            val directStreams = runCatching { resolveDirectStreams(effectiveVideoId, title, artist) }.getOrDefault(emptyList())
            if (directStreams.isNotEmpty()) {
                return directStreams.first()
            }
        }

        // 2. Fallback to search-based resolution if direct videoId failed or wasn't direct
        val searchedVideoId = resolveYouTubeVideoId(
            track = track.copy(title = title, artist = artist),
            excludeVideoId = effectiveVideoId,
        )
        if (searchedVideoId != null && searchedVideoId != effectiveVideoId) {
            val searchedStreams = runCatching { resolveDirectStreams(searchedVideoId, title, artist) }.getOrDefault(emptyList())
            if (searchedStreams.isNotEmpty()) {
                return searchedStreams.first()
            }
        }

        // 3. Fallback stream resolver if available
        val fallbackUrl = runCatching { fallbackStreamResolver?.invoke(effectiveVideoId ?: cleanId, title, artist) }.getOrNull()
        if (!fallbackUrl.isNullOrBlank()) {
            if (effectiveVideoId != null) {
                streamCache[effectiveVideoId] = CachedStreamUrl(fallbackUrl, now + 1800_000L, null)
            }
            return fallbackUrl
        }

        return null
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildMediaSource(streamUrl: String): androidx.media3.exoplayer.source.MediaSource {
        val isGoogleVideo = streamUrl.contains("googlevideo.com", ignoreCase = true)
        val headers = streamHeaders(streamUrl)
        val explicitUa = streamUserAgents[streamUrl]?.takeIf { it.isNotBlank() }
            ?: headers["User-Agent"]?.takeIf { it.isNotBlank() }
            ?: if (isGoogleVideo) YouTubeClient.ANDROID_VR_1_43_32.userAgent else NewPipeDownloader.USER_AGENT

        val requestProps = LinkedHashMap<String, String>()
        requestProps.putAll(headers)
        if (!isGoogleVideo && (streamUrl.contains("saavncdn.com") || streamUrl.contains("jiosaavn.com"))) {
            requestProps["Referer"] = "https://www.jiosaavn.com/"
            requestProps["Origin"] = "https://www.jiosaavn.com"
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(explicitUa)
            .setDefaultRequestProperties(requestProps)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val mediaItem = MediaItem.fromUri(android.net.Uri.parse(streamUrl))
        val isHls = streamUrl.contains(".m3u8", ignoreCase = true) ||
            streamUrl.contains("/manifest/hls", ignoreCase = true)

        return if (isGoogleVideo && !isHls) {
            val chunkedFactory = ChunkedDataSource.Factory(
                androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
            )
            ProgressiveMediaSource.Factory(chunkedFactory)
                .createMediaSource(mediaItem)
        } else {
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
            ).createMediaSource(mediaItem)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun playWithUrl(streamUrl: String, track: YouTubeTrackData, startPositionMs: Long) =
        withContext(Dispatchers.Main) {
            try {
                cancelCrossfade(restorePrimaryVolume = true)
                val player = getOrCreatePlayer()
                player.volume = currentVolume.toFloat().coerceIn(0f, 1f)
                val mediaSource = buildMediaSource(streamUrl)
                player.setMediaSource(mediaSource, startPositionMs.coerceAtLeast(0L))
                player.playWhenReady = true
                player.prepare()
                if (tickerJob?.isActive != true) startTicker()
            } catch (e: Exception) {
                Log.e(tag, "ExoPlayer playback failed for ${track.title}", e)
                if (userQueuedList.isNotEmpty() || currentQueueIndex + 1 < currentQueueList.size) {
                    scope.launch { next() }
                } else {
                    _state.value = _state.value?.copy(isPaused = true, isBuffering = false)
                }
            }
        }

    private suspend fun onTrackCompleted() {
        when (repeatMode) {
            Repeat.TRACK -> {
                seek(0L)
                resume()
            }
            Repeat.CONTEXT -> {
                if (userQueuedList.isNotEmpty() || currentQueueIndex + 1 < currentQueueList.size) {
                    next()
                } else if (currentQueueList.isNotEmpty()) {
                    currentQueueIndex = 0
                    playTrackAtIndex(0, 0L)
                }
            }
            Repeat.OFF -> {
                if (userQueuedList.isNotEmpty() || currentQueueIndex + 1 < currentQueueList.size) {
                    next()
                } else {
                    _state.value = _state.value?.copy(isPaused = true, positionMs = 0L)
                }
            }
        }
    }

    override suspend fun pause() = withContext(Dispatchers.Main) {
        cancelCrossfade(restorePrimaryVolume = true)
        exoPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                val pos = it.currentPosition
                _state.value = _state.value?.copy(isPaused = true, positionMs = pos, timestampMs = System.currentTimeMillis())
            }
        }
        Unit
    }

    fun restoreSession(
        items: List<Triple<String, String, String>>,
        meta: List<Triple<String, String, Int>>,
        contextUri: String?,
        contextName: String?,
        positionMs: Long,
    ) {
        if (items.isEmpty()) return
        registerTracks(items, meta)
        if (_state.value != null) return
        val mapped = items.mapIndexedNotNull { i, (id, title, artist) ->
            val cleanId = id.substringAfterLast(':').removePrefix("yt_").removePrefix("jio_").substringBefore('|').trim()
            if (cleanId.isBlank()) return@mapIndexedNotNull null
            val (cover, album, dur) = meta.getOrNull(i) ?: Triple("", "", 180_000)
            YouTubeTrackData(
                id = cleanId,
                uri = if (id.startsWith("spotify:track:")) id else "spotify:track:yt_$cleanId",
                title = title,
                artist = artist,
                album = album,
                coverUrl = cover,
                durationMs = dur.toLong().takeIf { it > 0L } ?: 180_000L,
            )
        }
        val first = mapped.firstOrNull() ?: return
        currentQueueList.clear()
        currentQueueList.addAll(mapped)
        currentQueueIndex = 0
        currentTrack = first
        currentContextUri = contextUri?.ifBlank { first.uri } ?: first.uri
        currentContextName = contextName?.takeIf { it.isNotBlank() } ?: first.title
        _state.value = PlayerState(
            item = nowPlayingOf(first),
            previous = null,
            next = mapped.getOrNull(1)?.let(::nowPlayingOf),
            contextUri = currentContextUri,
            contextName = currentContextName,
            isPaused = true,
            isBuffering = false,
            positionMs = positionMs.coerceAtLeast(0L),
            timestampMs = System.currentTimeMillis(),
            speed = 1.0,
            shuffle = isShuffle,
            repeat = repeatMode,
            restrictions = PlayerRestrictions(),
        )
        updateQueueState()
    }

    override suspend fun resume() = withContext(Dispatchers.Main) {
        val player = exoPlayer
        if (player != null && player.currentMediaItem != null) {
            player.play()
            val pos = player.currentPosition
            _state.value = _state.value?.copy(isPaused = false, isBuffering = false, positionMs = pos, timestampMs = System.currentTimeMillis())
        } else {
            val track = currentTrack ?: return@withContext
            val startPos = _state.value?.positionMs ?: 0L
            scope.launch {
                playTrackInternal(track, startPos)
            }
        }
        Unit
    }

    override suspend fun seek(positionMs: Long) = withContext(Dispatchers.Main) {
        cancelCrossfade(restorePrimaryVolume = true)
        val player = exoPlayer
        if (player != null && player.currentMediaItem != null) {
            player.seekTo(positionMs)
        }
        _state.value = _state.value?.copy(positionMs = positionMs, timestampMs = System.currentTimeMillis())
        Unit
    }

    override suspend fun next() {
        withContext(Dispatchers.Main) { cancelCrossfade(restorePrimaryVolume = true) }
        if (userQueuedList.isNotEmpty()) {
            val nextQueued = userQueuedList.removeAt(0)
            // Also insert into currentQueueList right after currentQueueIndex so history/previous works naturally
            val insertAt = (currentQueueIndex + 1).coerceAtMost(currentQueueList.size)
            currentQueueList.add(insertAt, nextQueued)
            currentQueueIndex = insertAt
            playTrackInternal(nextQueued, 0L)
            return
        }
        if (currentQueueIndex + 1 < currentQueueList.size) {
            playTrackAtIndex(currentQueueIndex + 1, 0L)
        } else if (repeatMode == Repeat.CONTEXT && currentQueueList.isNotEmpty()) {
            playTrackAtIndex(0, 0L)
        }
    }

    override suspend fun previous() {
        val ep = withContext(Dispatchers.Main) { exoPlayer?.currentPosition ?: 0L }
        if (ep > 3000) {
            seek(0L)
        } else if (currentQueueIndex > 0) {
            playTrackAtIndex(currentQueueIndex - 1, 0L)
        } else {
            seek(0L)
        }
    }

    override suspend fun setShuffle(on: Boolean) {
        if (isShuffle == on) {
            _state.value = _state.value?.copy(shuffle = on)
            return
        }
        isShuffle = on
        if (currentQueueList.size > 1) {
            val cur = currentTrack
            if (on) {
                unshuffledQueueList = currentQueueList.toList()
                val rest = currentQueueList.filterIndexed { idx, _ -> idx != currentQueueIndex }.shuffled()
                currentQueueList = (listOfNotNull(cur) + rest).toMutableList()
                currentQueueIndex = 0
            } else {
                val orig = unshuffledQueueList
                unshuffledQueueList = null
                if (orig != null && cur != null) {
                    val appended = currentQueueList.filter { t -> orig.none { it.uri == t.uri } }
                    currentQueueList = (orig + appended).toMutableList()
                    currentQueueIndex = currentQueueList.indexOfFirst { it.uri == cur.uri }.coerceAtLeast(0)
                }
            }
            updateQueueState()
        }
        _state.value = _state.value?.copy(shuffle = on)
    }

    override suspend fun setRepeat(mode: Repeat) {
        repeatMode = mode
        _state.value = _state.value?.copy(repeat = mode)
    }

    override suspend fun addToQueue(uris: List<Uri>) {
        val newTracks = uris.map(::trackOfUri)
        userQueuedList.addAll(newTracks)
        updateQueueState()
    }

    override suspend fun removeFromQueue(items: List<NowPlaying>) {
        val uris = items.map { it.uri }.toSet()
        val ids = items.map { it.uid }.toSet()
        userQueuedList.removeAll { it.uri in uris || it.id in ids }
        // Only remove from upcoming tracks in currentQueueList to avoid breaking currentQueueIndex
        for (i in currentQueueList.size - 1 downTo currentQueueIndex + 1) {
            val t = currentQueueList[i]
            if (t.uri in uris || t.id in ids) {
                currentQueueList.removeAt(i)
            }
        }
        updateQueueState()
    }

    override suspend fun clearQueue() {
        userQueuedList.clear()
        currentTrack?.let { currentQueueList = mutableListOf(it) } ?: currentQueueList.clear()
        currentQueueIndex = 0
        updateQueueState()
    }

    override suspend fun skipTo(item: NowPlaying) {
        val userIdx = userQueuedList.indexOfFirst { it.uri == item.uri || it.id == item.uid }
        if (userIdx >= 0) {
            val chosen = userQueuedList.removeAt(userIdx)
            val insertAt = (currentQueueIndex + 1).coerceAtMost(currentQueueList.size)
            currentQueueList.add(insertAt, chosen)
            currentQueueIndex = insertAt
            playTrackInternal(chosen, 0L)
            return
        }
        val idx = currentQueueList.indexOfFirst { it.uri == item.uri || it.id == item.uid }
        if (idx >= 0) playTrackAtIndex(idx, 0L)
    }

    override suspend fun playNext(uris: List<Uri>) {
        val newTracks = uris.map(::trackOfUri)
        userQueuedList.addAll(0, newTracks)
        updateQueueState()
    }

    override suspend fun moveInQueue(item: NowPlaying, target: NowPlaying, after: Boolean) {
        val userFrom = userQueuedList.indexOfFirst { it.uri == item.uri || it.id == item.uid }
        val userTo = userQueuedList.indexOfFirst { it.uri == target.uri || it.id == target.uid }
        if (userFrom >= 0 && userTo >= 0 && userFrom != userTo) {
            val moved = userQueuedList.removeAt(userFrom)
            val insertAt = if (after) (userTo + 1).coerceAtMost(userQueuedList.size) else userTo
            userQueuedList.add(insertAt, moved)
            updateQueueState()
            return
        }

        val from = currentQueueList.indexOfFirst { it.uri == item.uri || it.id == item.uid }
        val to = currentQueueList.indexOfFirst { it.uri == target.uri || it.id == target.uid }
        if (from >= 0 && to >= 0 && from != to) {
            val moved = currentQueueList.removeAt(from)
            val insertAt = if (after) (to + 1).coerceAtMost(currentQueueList.size) else to
            currentQueueList.add(insertAt, moved)
            currentQueue?.let {
                currentQueueIndex = currentQueueList.indexOfFirst { t -> t.uri == it.uri }.coerceAtLeast(0)
            }
            updateQueueState()
        }
    }

    private val currentQueue: YouTubeTrackData? get() = currentTrack

    override suspend fun volume(): Double = currentVolume

    override suspend fun setVolume(level: Double) = withContext(Dispatchers.Main) {
        currentVolume = level.coerceIn(0.0, 1.0)
        exoPlayer?.volume = currentVolume.toFloat()
    }

    override suspend fun setSpeed(speed: Double) = withContext(Dispatchers.Main) {
        exoPlayer?.setPlaybackSpeed(speed.toFloat().coerceIn(0.5f, 3.5f))
        _state.value = _state.value?.copy(speed = speed)
        Unit
    }

    private fun updateQueueState() {
        val now = currentTrack?.let(::nowPlayingOf)
        val userQueued = userQueuedList.map(::nowPlayingOf)
        val nextUp = currentQueueList.drop(currentQueueIndex + 1).map(::nowPlayingOf)
        val prevTrack = currentQueueList.getOrNull(currentQueueIndex - 1)?.let(::nowPlayingOf)
        val nextTrack = userQueued.firstOrNull() ?: nextUp.firstOrNull()
        _state.value = _state.value?.copy(
            item = now ?: _state.value?.item,
            previous = prevTrack,
            next = nextTrack,
        )
        _queue.value = Queue(
            current = now,
            nextUp = nextUp,
            queued = userQueued,
        )
    }

    private fun nowPlayingOf(t: YouTubeTrackData): NowPlaying = NowPlaying(
        kind = ItemKind.TRACK,
        uri = t.uri,
        uid = t.id,
        name = t.title,
        artists = listOf(ArtistRef(uri = "spotify:artist:yt_${t.id}", name = t.artist)),
        album = AlbumRef(uri = "spotify:album:yt_${t.id}", name = t.album),
        imageUrl = t.coverUrl.ifBlank { "https://i.ytimg.com/vi/${t.id}/hqdefault.jpg" },
        durationMs = t.durationMs,
    )
}

/**
 * Wraps [DefaultHttpDataSource.Factory] to stream `googlevideo.com` URLs using bounded
 * `range=<start>-<end>` URL query chunks (2 MB per chunk).
 *
 * Without bounded range chunks, YouTube's CDN throttles open-ended `Range: bytes=0-` HTTP
 * requests to ~30 KB/s after the initial burst or drops long-lived idle TCP sockets when
 * the app is in the background, causing audio buffer underruns, silence, or playback stops.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ChunkedGoogleVideoDataSourceFactory(
    private val upstreamFactory: DefaultHttpDataSource.Factory,
    private val knownContentLength: Long? = null,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        ChunkedGoogleVideoDataSource(upstreamFactory.createDataSource(), knownContentLength)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ChunkedGoogleVideoDataSource(
    private val upstream: DefaultHttpDataSource,
    private val knownContentLength: Long? = null,
) : DataSource {
    private var baseSpec: DataSpec? = null
    private var originalUri: android.net.Uri? = null
    private var isChunked = false
    private var currentPosition = 0L
    private var endPosition = 0L
    private var totalBytesRemaining = 0L
    private var chunkBytesRemaining = 0L
    private var chunkOpened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        originalUri = dataSpec.uri
        val host = dataSpec.uri.host.orEmpty().lowercase()
        val clen = knownContentLength?.takeIf { it > 0L }
            ?: dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0L }

        if (host.contains("googlevideo.com") && clen != null) {
            val opened = runCatching {
                isChunked = true
                currentPosition = dataSpec.position.coerceAtLeast(0L)
                endPosition = if (dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length > 0L) {
                    minOf(clen, currentPosition + dataSpec.length)
                } else {
                    clen
                }
                totalBytesRemaining = (endPosition - currentPosition).coerceAtLeast(0L)
                chunkBytesRemaining = 0L
                chunkOpened = false
                if (totalBytesRemaining == 0L) return 0L
                openNextChunk()
                totalBytesRemaining
            }.getOrNull()
            if (opened != null) return opened
        }

        isChunked = false
        chunkOpened = true
        return upstream.open(dataSpec)
    }

    private fun openNextChunk() {
        if (chunkOpened) {
            runCatching { upstream.close() }
            chunkOpened = false
        }
        val spec = baseSpec ?: return
        val orig = originalUri ?: return
        val chunkStart = currentPosition
        val chunkEnd = (minOf(endPosition, chunkStart + CHUNK_SIZE_BYTES) - 1L).coerceAtLeast(chunkStart)
        val expectedLen = (chunkEnd - chunkStart + 1L).coerceAtLeast(0L)

        // Keep the exact signed googlevideo URL untouched to avoid signature/sparams 403 Forbidden.
        // DefaultHttpDataSource natively issues HTTP `Range: bytes=start-end` request header.
        val chunkSpec = spec.buildUpon()
            .setUri(orig)
            .setPosition(chunkStart)
            .setLength(expectedLen)
            .build()

        val openedLen = upstream.open(chunkSpec)
        chunkOpened = true
        chunkBytesRemaining = if (openedLen != C.LENGTH_UNSET.toLong() && openedLen > 0L) {
            minOf(openedLen, expectedLen)
        } else {
            expectedLen
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isChunked) {
            return upstream.read(buffer, offset, length)
        }
        if (length == 0) return 0
        if (totalBytesRemaining <= 0L || currentPosition >= endPosition) {
            return C.RESULT_END_OF_INPUT
        }

        var retried = false
        while (true) {
            try {
                if (!chunkOpened || chunkBytesRemaining <= 0L) {
                    if (currentPosition >= endPosition || totalBytesRemaining <= 0L) {
                        return C.RESULT_END_OF_INPUT
                    }
                    openNextChunk()
                }

                val toRead = minOf(length.toLong(), chunkBytesRemaining, totalBytesRemaining).toInt()
                if (toRead <= 0) return C.RESULT_END_OF_INPUT

                val bytesRead = upstream.read(buffer, offset, toRead)
                if (bytesRead == C.RESULT_END_OF_INPUT) {
                    runCatching { upstream.close() }
                    chunkOpened = false
                    chunkBytesRemaining = 0L
                    if (currentPosition >= endPosition || totalBytesRemaining <= 0L || retried) {
                        return C.RESULT_END_OF_INPUT
                    }
                    retried = true
                    continue
                }

                currentPosition += bytesRead
                chunkBytesRemaining = (chunkBytesRemaining - bytesRead).coerceAtLeast(0L)
                totalBytesRemaining = (totalBytesRemaining - bytesRead).coerceAtLeast(0L)
                return bytesRead
            } catch (e: java.io.IOException) {
                runCatching { upstream.close() }
                chunkOpened = false
                chunkBytesRemaining = 0L
                if (retried) throw e
                retried = true
                runCatching { Thread.sleep(150L) }
            }
        }
    }

    override fun getUri(): android.net.Uri? = originalUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        baseSpec = null
        originalUri = null
        isChunked = false
        totalBytesRemaining = 0L
        chunkBytesRemaining = 0L
        if (chunkOpened) {
            chunkOpened = false
            runCatching { upstream.close() }
        } else {
            runCatching { upstream.close() }
        }
    }

    companion object {
        private const val CHUNK_SIZE_BYTES = 512L * 1024L // 512 KB per range request (matches z-huang/InnerTune)
    }
}

