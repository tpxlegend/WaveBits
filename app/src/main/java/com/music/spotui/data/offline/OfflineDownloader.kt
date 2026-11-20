package com.music.spotui.data.offline

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.isDownloadsEnabled
import com.music.spotui.di.Graph
import com.music.spotui.video.InterruptibleHttp
import com.music.spotui.video.YouTubeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads songs for offline listening. Spotify only gives the track (title, artist,
 * duration, ISRC); where the audio file is, and that it is the right recording, is the job
 * of a `download-source` extension (docs/extensions/API.md §3), which answers the files it
 * has, best first. Myspoti then:
 *
 * 1. fetches the first file that works (a FLAC, a DASH manifest of FLAC frames joined
 *    into one FLAC, or an M4A / MP3);
 * 2. checks what any file must pass, whoever sent it: real, decodable audio whose length
 *    is the song's (Spotify's duration), so a preview or another cut is refused;
 * 3. keeps it encrypted ([OfflineCrypto]), with its cover, in the Downloaded list.
 *
 * No extension giving download files: downloads say to install one. Nothing that fails the
 * check is kept: the song is marked unavailable instead.
 */
object OfflineDownloader {
    private const val TAG = "Offline"
    private const val WORKERS = 3
    /** A file may be this much shorter than the song (seconds): more is a preview or a cut. */
    private const val SHORTER_MAX = 5
    /** And this much longer (trailing silence, a longer intro): more is another version. */
    private const val LONGER_MAX = 15

    data class Request(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: String,
        val durationMs: Long,
        val coverUrl: String,
        /** The quality chosen for this download ([DownloadQuality.name]); empty = the setting. */
        val quality: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", trackId).put("title", title).put("artist", artist)
            .put("album", album).put("albumId", albumId).put("duration", durationMs).put("cover", coverUrl)
            .put("quality", quality)

        companion object {
            fun of(s: SongsModel) = Request(
                trackId = s.spotifyTrackId.ifBlank { s.url.substringBefore('|').removePrefix("spotify:track:") },
                title = s.title, artist = s.singer, album = s.album,
                albumId = s.albumId, durationMs = s.durationMs.toLong(), coverUrl = s.coverUri,
            )
            fun fromJson(o: JSONObject) = Request(
                o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
                o.optString("albumId"), o.optLong("duration"), o.optString("cover"), o.optString("quality"),
            )
        }
    }

    private class Unavailable(message: String) : Exception(message)

    /** Two AAC encodes at once at most: the CPU stays free for the rest of the app. */
    private val encodeSlots = kotlinx.coroutines.sync.Semaphore(2)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Songs queued, downloading, or whose download did not succeed (for the Downloaded list). */
    val requests: SnapshotStateMap<String, Request> = mutableStateMapOf()
    private val queue = Channel<Request>(Channel.UNLIMITED)
    /** Requests not finished yet, in order (persisted: a restart resumes them). */
    private val pending = LinkedHashMap<String, Request>()
    private val running = ConcurrentHashMap<String, Job>()
    @Volatile private var started = false

    private fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            repeat(WORKERS) { scope.launch { for (r in queue) work(r) } }
        }
    }

    /** Re-queues what was waiting when the app last stopped. */
    fun resumePending() {
        val saved = runCatching {
            val a = JSONArray(OfflineStore.pendingFile.readText())
            (0 until a.length()).map { Request.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        if (saved.isNotEmpty()) enqueueAll(saved)
    }

    /**
     * Downloads [songs] in [quality] (asked when the setting is "ask each time"), else in
     * the quality chosen in Settings.
     */
    fun enqueue(
        songs: List<SongsModel>,
        quality: com.music.spotui.data.preferences.DownloadQuality? = null,
        /** Started by the app (a downloaded list keeping in step), not by a tap. */
        auto: Boolean = false,
    ) {
        // Downloads switched off in Settings: say so, instead of a list of failed songs.
        if (!isDownloadsEnabled(Graph.app)) {
            scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(Graph.app, app.wavebits.engine.R.string.downloads_off_toast, android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        // Downloads use their selected platform's server directly (YouTube, JioSaavn, or Spotify native/extension server).
        val isNativeServerPlatform = true
        val wifiOnly = com.music.spotui.data.preferences.isDownloadWifiOnly(Graph.app)
        if (auto) {
            // Automatic downloads wait for Wi-Fi (never the user's mobile data): the next sync takes them.
            if (com.music.spotui.data.DataSaver.metered || !OfflineStore.isOnline(Graph.app)) { OfflineCollections.waitForWifi(); return }
        } else if (com.music.spotui.data.DataSaver.metered) {
            scope.launch(Dispatchers.Main) {
                val msg = if (wifiOnly) app.wavebits.engine.R.string.download_waiting_for_wifi else app.wavebits.engine.R.string.download_on_mobile_data
                android.widget.Toast.makeText(Graph.app, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val q = quality?.let { com.music.spotui.data.preferences.DownloadQuality.saved(it.name) }?.name.orEmpty()
        // Episodes go to PodcastDownloader: their id is no track id, so the download-source lookup
        // would 404 (or worse, match an unrelated recording by title).
        enqueueAll(
            songs.map { Request.of(it).copy(quality = q) }
                .filter { it.trackId.isNotBlank() && !isEpisodeKey(it.trackId) }
        )
    }

    private fun enqueueAll(list: List<Request>) {
        val added = synchronized(pending) {
            list.filter { r ->
                // Last guard before the track download path, whatever the caller (resume, retry, list).
                !isEpisodeKey(r.trackId) &&
                    !OfflineStore.isDownloaded(r.trackId) && pending.putIfAbsent(r.trackId, r) == null
            }.also { if (it.isNotEmpty()) savePending() }
        }
        if (added.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            added.forEach { requests[it.trackId] = it; OfflineStore.status[it.trackId] = OfflineStatus.Queued }
        }
        start()
        added.forEach { queue.trySend(it) }
    }

    /** Stops a queued or running download (and forgets a failed one). */
    fun cancel(trackId: String) {
        synchronized(pending) { if (pending.remove(trackId) != null) savePending() }
        running.remove(trackId)?.cancel()
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            OfflineStore.status.remove(trackId)
            requests.remove(trackId)
        } else {
            scope.launch(Dispatchers.Main) {
                OfflineStore.status.remove(trackId)
                requests.remove(trackId)
            }
        }
    }

    /**
     * Stops every running and queued download and empties the queue. Unlike [removeAll], it
     * deletes nothing: finished songs stay in [OfflineStore.tracks] and in the index, and a
     * cancelled download only drops its own partial file in the temp folder.
     */
    fun stopAll() {
        // The queue survives a restart through pending.json: clearing it here is what makes the
        // stop stick, instead of the downloads resuming at the next launch.
        val ids = synchronized(pending) { pending.keys.toList().also { pending.clear(); savePending() } }
        // Running jobs may not all be in `pending` any more; cancel them by their own handle.
        running.values.forEach { it.cancel() }
        running.clear()
        // Snapshot state: the badges and the list are read from the UI, so write them there.
        scope.launch(Dispatchers.Main) {
            ids.forEach { OfflineStore.status.remove(it); requests.remove(it) }
        }
    }

    /** Tries a failed song again. */
    fun retry(trackId: String) {
        val r = requests[trackId] ?: return
        enqueueAll(listOf(r))
    }

    /** Removes the song: the file if downloaded, else its download. */
    fun remove(trackId: String) {
        cancel(trackId)
        // File deletion on the IO scope, not the (UI) caller's thread.
        scope.launch { OfflineStore.remove(trackId) }
    }

    fun removeAll() {
        val ids = synchronized(pending) { pending.keys.toList().also { pending.clear(); savePending() } }
        ids.forEach { running.remove(it)?.cancel() }
        running.values.forEach { it.cancel() }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            requests.clear()
        } else {
            scope.launch(Dispatchers.Main) { requests.clear() }
        }
        // Deleting a large library can take a while: IO scope, not the caller.
        scope.launch { OfflineCollections.removeAll(); OfflineStore.removeAll() }
    }

    private fun savePending() {
        runCatching {
            val a = JSONArray()
            pending.values.forEach { a.put(it.toJson()) }
            OfflineStore.pendingFile.writeText(a.toString())
        }
    }

    private suspend fun setStatus(id: String, s: OfflineStatus?) = withContext(Dispatchers.Main) {
        if (s == null) OfflineStore.status.remove(id) else OfflineStore.status[id] = s
    }

    private suspend fun work(r: Request) {
        // Cancelled while it waited in the queue.
        if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        // Data saver or Wi-Fi-only setting on metered connection: downloads wait for Wi-Fi.
        while (com.music.spotui.data.DataSaver.active ||
            (com.music.spotui.data.preferences.isDownloadWifiOnly(Graph.app) && com.music.spotui.data.DataSaver.metered)
        ) {
            kotlinx.coroutines.delay(5_000)
            if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        }
        val job = scope.launch {
            val outcome: OfflineStatus? = try {
                setStatus(r.trackId, OfflineStatus.Downloading(-1f))
                val t = download(r)
                // Already on the IO scope: the index rewrite stays off the main thread.
                OfflineStore.add(t)
                withContext(Dispatchers.Main) { requests.remove(r.trackId) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Unavailable) {
                Log.i(TAG, "${r.title}: ${e.message}")
                OfflineStatus.Unavailable
            } catch (t: Throwable) {
                Log.w(TAG, "${r.title} failed", t)
                OfflineStatus.Failed
            }
            synchronized(pending) { pending.remove(r.trackId); savePending() }
            if (outcome != null) setStatus(r.trackId, outcome)
        }
        running[r.trackId] = job
        job.join()
        running.remove(r.trackId, job)
    }

    private fun safeFileKey(trackId: String): String =
        trackId.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private suspend fun download(r: Request): OfflineTrack {
        if (!isDownloadsEnabled(Graph.app)) throw Unavailable("downloads are off")
        val quality = qualityOf(r)
        val tmp = OfflineStore.tmpDir
        val fileKey = safeFileKey(r.trackId)
        val audioTmp = File(tmp, "$fileKey.part")
        try {
            val meta = spotifyMeta(r)
            Log.i(TAG, "${meta.title}: isrc=${meta.isrc ?: "-"}")

            // ── The files the download-source extensions give, tried in their order; each one
            // is checked (real audio, the song's length) before it is taken.
            val file = resolveAndFetch(r, meta, audioTmp, quality)
            currentCoroutineContext().ensureActive()

            val out: File
            if (file.lossy != null) {
                // A small file as the source served it (M4A, MP3, WebM): kept as it is.
                out = File(OfflineStore.audioDir, "$fileKey.${file.lossy}")
                if (out.exists()) out.delete()
                if (!audioTmp.renameTo(out)) throw IOException("could not move the file")
            } else if (quality == com.music.spotui.data.preferences.DownloadQuality.SMALL) {
                // "Smallest": the checked FLAC becomes AAC, encoded on the phone.
                out = File(OfflineStore.audioDir, "$fileKey.m4a")
                val aacTmp = File(OfflineStore.tmpDir, "$fileKey.m4apart")
                try {
                    // Encoding is long: a few at a time, the other downloads go on meanwhile.
                    encodeSlots.withPermit { runInterruptible(Dispatchers.Default) { AacTranscoder.transcode(audioTmp, aacTmp) } }
                    if (out.exists()) out.delete()
                    if (aacTmp.length() < 10_000 || !aacTmp.renameTo(out)) throw IOException("could not encode the small file")
                } finally {
                    aacTmp.delete()
                }
            } else {
                out = File(OfflineStore.audioDir, "$fileKey.flac")
                if (out.exists()) out.delete()
                if (!audioTmp.renameTo(out)) throw IOException("could not move the file")
            }
            // The audio is complete on disk from here on: "stop all" must not interrupt this last
            // part, or the file would stay out of the index and be swept as a stray at startup.
            return withContext(NonCancellable) {
                // Kept encrypted: the file only plays in Myspoti, on this phone.
                runInterruptible(Dispatchers.IO) { OfflineCrypto.encryptInPlace(Graph.app, out) }
                val cover = runCatching {
                    val c = File(OfflineStore.coverDir, "$fileKey.jpg")
                    httpGet(app.wavebits.host.android.Covers.large(meta.coverUrl), c); c.name
                }.getOrDefault("")
                OfflineTrack(
                    trackId = r.trackId, title = meta.title, artist = meta.artist, album = meta.album,
                    albumId = meta.albumId, durationMs = meta.durationMs, coverUrl = meta.coverUrl,
                    file = out.name, cover = cover, source = file.provider, verified = file.isrcMatched,
                    bytes = out.length(), addedAt = System.currentTimeMillis(), providerId = file.extensionId,
                )
            }
        } finally {
            audioTmp.delete()
        }
    }

    private class Fetched(
        val provider: String,
        val isrcMatched: Boolean,
        /** The id of the download-source extension that supplied it. */
        val extensionId: String,
        /** The extension of a lossy file kept as served (m4a, mp3); null for a FLAC. */
        val lossy: String? = null,
    )

    /** This download's quality: the one chosen when it started, else Settings (CD when Settings says "ask"). */
    private fun qualityOf(r: Request): com.music.spotui.data.preferences.DownloadQuality =
        com.music.spotui.data.preferences.DownloadQuality.saved(r.quality)
            ?: com.music.spotui.data.preferences.DownloadQuality.saved(com.music.spotui.data.preferences.getDownloadQuality(Graph.app).name)
            ?: com.music.spotui.data.preferences.DownloadQuality.CD

    /** The quality as a download-source extension is asked for it. */
    internal fun queryQuality(q: com.music.spotui.data.preferences.DownloadQuality): String = when (q) {
        com.music.spotui.data.preferences.DownloadQuality.HI_RES -> "hi-res"
        com.music.spotui.data.preferences.DownloadQuality.SMALL -> "small"
        else -> "cd"
    }

    internal fun orderedJioSaavnUrls(
        primaryUrl: String,
        quality: com.music.spotui.data.preferences.DownloadQuality,
    ): List<String> {
        val variants = buildList {
            if (primaryUrl.contains("_320.mp4") || primaryUrl.contains("_160.mp4") || primaryUrl.contains("_96.mp4")) {
                val url320 = primaryUrl.replace("_160.mp4", "_320.mp4").replace("_96.mp4", "_320.mp4")
                val url160 = primaryUrl.replace("_320.mp4", "_160.mp4").replace("_96.mp4", "_160.mp4")
                val url96 = primaryUrl.replace("_320.mp4", "_96.mp4").replace("_160.mp4", "_96.mp4")
                if (quality == com.music.spotui.data.preferences.DownloadQuality.SMALL) {
                    add(url96)
                    add(url160)
                    add(url320)
                } else {
                    add(url320)
                    add(url160)
                    add(url96)
                }
            }
            add(primaryUrl)
        }
        return variants.distinct()
    }

    internal fun rankYouTubeStreamUrls(
        urls: List<String>,
        quality: com.music.spotui.data.preferences.DownloadQuality,
    ): List<String> {
        fun estimatedKbps(url: String): Int {
            val queryItag = Regex("[?&]itag=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
            return when (queryItag) {
                139 -> 48
                249 -> 50
                250 -> 70
                140 -> 128
                251 -> 160
                141, 774 -> 256
                else -> Regex("[?&]bitrate=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()?.div(1000) ?: 128
            }
        }
        fun isMp4(url: String): Boolean =
            url.contains("mime=audio%2Fmp4", ignoreCase = true) || url.contains("mime=audio/mp4", ignoreCase = true)

        return if (quality == com.music.spotui.data.preferences.DownloadQuality.SMALL) {
            urls.sortedWith(compareBy<String>({ estimatedKbps(it) }, { if (isMp4(it)) 0 else 1 }))
        } else {
            urls.sortedWith(compareByDescending<String> { isMp4(it) }.thenByDescending { estimatedKbps(it) })
        }
    }

    private suspend fun downloadDirectJioSaavn(
        r: Request,
        meta: Meta,
        out: File,
        quality: com.music.spotui.data.preferences.DownloadQuality,
    ): Fetched? {
        val client = app.wavebits.host.android.WaveBitsRuntime.jiosaavn.client
        val primaryUrl = client.resolveStreamUrl(r.trackId, meta.title, meta.artist)
            ?: runCatching {
                val query = "${meta.title} ${meta.artist}".trim()
                val hits = client.searchTracks(query)
                hits.firstOrNull()?.let { client.resolveStreamUrl(it.id, it.title, it.artist) }
            }.getOrNull()
        if (primaryUrl.isNullOrBlank()) return null

        val candidateUrls = orderedJioSaavnUrls(primaryUrl, quality)

        val jioHeaders = mapOf(
            "Referer" to "https://www.jiosaavn.com/",
            "Origin" to "https://www.jiosaavn.com",
        )

        for (streamUrl in candidateUrls) {
            currentCoroutineContext().ensureActive()
            setStatus(r.trackId, OfflineStatus.Downloading(-1f))
            val ok = try {
                (fetchWithProgress(r.trackId, streamUrl, out, jioHeaders) ||
                    runCatching { httpGetWithHeaders(streamUrl, out, jioHeaders); true }.getOrDefault(false)) &&
                    out.length() >= 10_000
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "JioSaavn download failed for ${meta.title} ($streamUrl): ${e.message}")
                false
            }
            if (!ok) continue
            val refused = runCatching { checkAudio(out, meta, checkDuration = false) }.exceptionOrNull()
            if (refused != null) {
                Log.w(TAG, "JioSaavn audio check failed for ${meta.title}: ${refused.message}")
                continue
            }
            Log.i(TAG, "${meta.title}: saved native JioSaavn server download")
            return Fetched("JioSaavn", true, "jiosaavn_native", lossy = "m4a")
        }
        return null
    }

    private suspend fun downloadDirectYouTube(
        r: Request,
        meta: Meta,
        out: File,
        quality: com.music.spotui.data.preferences.DownloadQuality,
    ): Fetched? {
        val cleanId = r.trackId
            .substringBefore('|')
            .removePrefix("spotify:track:")
            .removePrefix("yt_")
            .removePrefix("yt:")
            .trim()
        val ytPlayer = runCatching { app.wavebits.host.android.WaveBitsRuntime.youtube.ytPlayer }.getOrNull()

        data class StreamCandidate(
            val url: String,
            val headers: Map<String, String>,
            val contentLength: Long?,
        )
        val resolvedCandidates = mutableListOf<StreamCandidate>()

        // 1. Primary: YouTube Music InnerTube multi-client stream resolver (z-huang/InnerTune pipeline)
        if (ytPlayer != null) {
            val directUrls = runCatching {
                ytPlayer.resolveDirectStreams(cleanId, meta.title, meta.artist)
            }.getOrDefault(emptyList())
            val sortedUrls = rankYouTubeStreamUrls(directUrls, quality)
            for (u in sortedUrls) {
                resolvedCandidates.add(
                    StreamCandidate(
                        url = u,
                        headers = ytPlayer.getStreamHeaders(u),
                        contentLength = ytPlayer.getStreamContentLength(u),
                    )
                )
            }
        }

        // 2. Fallback: NewPipe extractor if InnerTube returned nothing
        if (resolvedCandidates.isEmpty()) {
            runCatching {
                val directId = cleanId.takeIf { it.length == 11 && !it.contains(":") && !it.startsWith("jio") }
                if (directId != null) {
                    val directUrl = "https://www.youtube.com/watch?v=$directId"
                    val streams = runCatching { YouTubeSource.info(directUrl).audioStreams }.getOrDefault(emptyList())
                    val orderedStreams = if (quality == com.music.spotui.data.preferences.DownloadQuality.SMALL) {
                        streams.sortedBy { it.bitrate }
                    } else {
                        streams.sortedByDescending { it.bitrate }
                    }
                    orderedStreams.forEach { s ->
                        if (s.content.isNotBlank()) {
                            val deobfuscated = runCatching {
                                org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
                                    .getUrlWithThrottlingParameterDeobfuscated(directId, s.content)
                            }.getOrDefault(s.content)
                            val hdrs = ytPlayer?.getStreamHeaders(deobfuscated)
                                ?: mapOf("User-Agent" to app.wavebits.innertube.models.YouTubeClient.USER_AGENT_WEB)
                            val clen = ytPlayer?.getStreamContentLength(deobfuscated)
                            resolvedCandidates.add(StreamCandidate(deobfuscated, hdrs, clen))
                        }
                    }
                }
                if (resolvedCandidates.isEmpty()) {
                    val hit = YouTubeSource.findSong(meta.title, meta.artist, meta.durationMs)
                        ?: YouTubeSource.findAudio(meta.title, meta.artist, meta.durationMs)
                    hit?.let { h ->
                        val vid = h.url.substringAfter("v=").substringBefore("&").takeIf { it.length == 11 } ?: cleanId
                        val streams = YouTubeSource.info(h.url).audioStreams
                        val orderedStreams = if (quality == com.music.spotui.data.preferences.DownloadQuality.SMALL) {
                            streams.sortedBy { it.bitrate }
                        } else {
                            streams.sortedByDescending { it.bitrate }
                        }
                        orderedStreams.forEach { s ->
                            if (s.content.isNotBlank()) {
                                val deobfuscated = runCatching {
                                    org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
                                        .getUrlWithThrottlingParameterDeobfuscated(vid, s.content)
                                }.getOrDefault(s.content)
                                val hdrs = ytPlayer?.getStreamHeaders(deobfuscated)
                                    ?: mapOf("User-Agent" to app.wavebits.innertube.models.YouTubeClient.USER_AGENT_WEB)
                                val clen = ytPlayer?.getStreamContentLength(deobfuscated)
                                resolvedCandidates.add(StreamCandidate(deobfuscated, hdrs, clen))
                            }
                        }
                    }
                }
            }
        }

        if (resolvedCandidates.isEmpty()) return null

        for (candidate in resolvedCandidates) {
            val streamUrl = candidate.url
            val streamHeaders = candidate.headers
            currentCoroutineContext().ensureActive()
            setStatus(r.trackId, OfflineStatus.Downloading(-1f))
            val ok = try {
                val downloadedOk = if (streamUrl.contains("googlevideo.com", ignoreCase = true)) {
                    downloadYouTubeChunkedWithProgress(
                        trackId = r.trackId,
                        url = streamUrl,
                        out = out,
                        headers = streamHeaders,
                        knownTotal = candidate.contentLength ?: -1L,
                    ) || fetchWithProgress(r.trackId, streamUrl, out, streamHeaders)
                } else {
                    fetchWithProgress(r.trackId, streamUrl, out, streamHeaders) ||
                        runCatching { httpGetWithHeaders(streamUrl, out, streamHeaders); true }.getOrDefault(false)
                }
                downloadedOk && out.length() >= 10_000
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "YouTube download attempt failed for ${meta.title}: ${e.message}")
                false
            }
            if (!ok) continue
            val refused = runCatching { checkAudio(out, meta, checkDuration = false) }.exceptionOrNull()
            if (refused != null) {
                Log.w(TAG, "YouTube audio check failed for ${meta.title}: ${refused.message}")
                continue
            }
            val isWebm = isWebmContainer(out) ||
                streamUrl.contains("mime=audio%2Fwebm", ignoreCase = true) ||
                streamUrl.contains("mime=audio/webm", ignoreCase = true)
            val ext = if (isWebm) "webm" else "m4a"
            Log.i(TAG, "${meta.title}: saved native YouTube Music server download ($ext)")
            return Fetched("YouTube Music", true, "youtube_native", lossy = ext)
        }
        return null
    }

    /**
     * Downloads a YouTube `googlevideo.com` audio stream using bounded 512 KB byte-range chunks
     * (matching z-huang/InnerTune's chunked DataSource) with the exact client User-Agent so YouTube's
     * CDN never throttles or resets the download connection.
     */
    private suspend fun downloadYouTubeChunkedWithProgress(
        trackId: String,
        url: String,
        out: File,
        headers: Map<String, String>,
        knownTotal: Long = -1L,
    ): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(out.parentFile ?: OfflineStore.tmpDir, "${out.name}.ytchunk")
        val chunkSize = 512L * 1024L // 512 KB per range request (matches z-huang/InnerTune)
        val userAgent = headers["User-Agent"] ?: app.wavebits.innertube.models.YouTubeClient.USER_AGENT_WEB
        var total = knownTotal.takeIf { it > 0L }
            ?: runCatching { android.net.Uri.parse(url).getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0L } }.getOrNull()
            ?: -1L

        try {
            java.io.RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0L)
                var lastUpdate = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val start = raf.length()
                    if (total > 0L && start >= total) break
                    val end = if (total > 0L) minOf(start + chunkSize - 1L, total - 1L) else start + chunkSize - 1L
                    var done = false
                    var attempt = 0
                    while (true) {
                        attempt++
                        currentCoroutineContext().ensureActive()
                        val chunkStart = raf.length()
                        val reqBuilder = okhttp3.Request.Builder()
                            .url(url)
                            .header("User-Agent", userAgent)
                            .header("Range", "bytes=$chunkStart-$end")
                        headers.forEach { (k, v) ->
                            if (!k.equals("User-Agent", ignoreCase = true) && !k.equals("Range", ignoreCase = true)) {
                                reqBuilder.header(k, v)
                            }
                        }
                        try {
                            val call = YouTubeSource.http.newCall(reqBuilder.build())
                            InterruptibleHttp.read(call) { res ->
                                val code = res.code
                                if (code == 416 && total <= 0L && chunkStart > 0L) {
                                    done = true
                                    return@read
                                }
                                if (code !in 200..299) {
                                    throw IOException("HTTP $code")
                                }
                                val fullBody = code == 200 && chunkStart == 0L && res.header("Content-Range") == null
                                if (fullBody) {
                                    raf.setLength(0L)
                                    val bodyLen = res.body?.contentLength() ?: -1L
                                    if (total <= 0L && bodyLen > 0L) total = bodyLen
                                } else if (total <= 0L) {
                                    total = contentRangeTotal(res.header("Content-Range")) ?: -1L
                                }
                                raf.seek(raf.length())
                                val expected = if (fullBody) -1L else (end - chunkStart + 1L)
                                var got = 0L
                                val body = res.body ?: throw IOException("Empty body")
                                body.byteStream().use { input ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        raf.write(buf, 0, n)
                                        got += n
                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdate >= 250L) {
                                            lastUpdate = now
                                            val curLen = raf.length()
                                            val prog = if (total > 0L) (curLen.toFloat() / total.toFloat()).coerceIn(0f, 1f) else -1f
                                            scope.launch(Dispatchers.Main) {
                                                setStatus(trackId, OfflineStatus.Downloading(prog))
                                            }
                                        }
                                    }
                                }
                                if (fullBody) {
                                    done = true
                                } else if (total <= 0L && got < expected) {
                                    done = true
                                }
                            }
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (attempt >= 3) throw e
                            delay(200L * attempt)
                        }
                    }
                    if (done) break
                    if (total in 1L..raf.length()) break
                }
            }
            if (tmp.length() >= 10_000L) {
                if (out.exists()) out.delete()
                tmp.renameTo(out)
            } else {
                tmp.delete()
                false
            }
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Chunked YouTube download failed for $url: ${e.message}")
            tmp.delete()
            false
        }
    }

    /**
     * Resolves the audio file for [meta]:
     * - In JioSaavn: downloads directly from JioSaavn servers (no extension needed)
     * - In YouTube Music: downloads directly from YouTube Music servers (no extension needed)
     * - In Spotify: tries installed download extensions first, then seamlessly falls back to direct download.
     */
    private suspend fun resolveAndFetch(r: Request, meta: Meta, out: File, quality: com.music.spotui.data.preferences.DownloadQuality): Fetched {
        val currentBackend = app.wavebits.host.android.WaveBitsRuntime.backendMode.value
        val isJio = currentBackend == app.wavebits.host.android.BackendMode.JIOSAAVN || r.trackId.startsWith("jio")
        val isYt = currentBackend == app.wavebits.host.android.BackendMode.YOUTUBE ||
            r.trackId.startsWith("yt_") || r.trackId.startsWith("yt:") || (r.trackId.length == 11 && !r.trackId.contains(":"))

        if (isYt) {
            val fetched = downloadDirectYouTube(r, meta, out, quality)
            if (fetched != null) return fetched
            throw Unavailable("could not download this song from YouTube Music servers")
        }
        if (isJio) {
            val fetched = downloadDirectJioSaavn(r, meta, out, quality)
            if (fetched != null) return fetched
            throw Unavailable("could not download this song from JioSaavn servers")
        }

        // For Spotify or general tracks: try WaveBits Download Manager or installed extensions if present
        val track = com.music.spotui.extensions.Extensions.trackInfo(r.trackId, meta.title, meta.artist, meta.album, meta.durationMs, meta.coverUrl)
            .copy(isrc = meta.isrc)
        val extResult = runCatching { com.music.spotui.extensions.Extensions.downloadFiles(track, queryQuality(quality)) }.getOrNull()
        if (extResult != null) {
            val (extensionId, extension, files) = extResult
            for (file in files) {
                currentCoroutineContext().ensureActive()
                setStatus(r.trackId, OfflineStatus.Downloading(-1f))
                val label = listOfNotNull(file.provider ?: extension, file.bitDepth?.takeIf { file.lossless }?.let { "$it-bit" }).joinToString(" ")
                val ok = try {
                    when {
                        file.format == "dash-flac" -> runInterruptible(Dispatchers.IO) {
                            DashFlacRemux.download("data:application/dash+xml;base64,${file.manifest}", out, file.headers)
                        }
                        else -> fetchWithProgress(r.trackId, file.url!!, out, file.headers)
                    } && out.length() >= 10_000
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${meta.title}: $label ${file.format}: ${e.message}")
                    false
                }
                if (!ok) continue
                val refused = runCatching {
                    if (file.lossless) checkFlacHeader(out, meta, file.bitDepth) else checkAudio(out, meta)
                }.exceptionOrNull()
                if (refused != null) { Log.i(TAG, "${meta.title}: $label: ${refused.message}"); continue }
                if (!file.lossless) {
                    Log.i(TAG, "${meta.title}: saved $label ${file.format}")
                    return Fetched(label, file.isrcMatched, extensionId, lossy = file.format)
                }
                Log.i(TAG, "${meta.title}: saved FLAC from $label")
                return Fetched(label, file.isrcMatched, extensionId)
            }
        }

        // Direct native download fallback when no extension is installed
        downloadDirectYouTube(r, meta, out, quality)?.let { return it }
        downloadDirectJioSaavn(r, meta, out, quality)?.let { return it }

        throw Unavailable("could not download this song")
    }

    /**
     * A STREAMINFO read on the saved file: anything that is not a FLAC file (an encrypted or
     * AAC stream) is refused, a preview or another cut is caught (the header states the real
     * length), and a misplaced 24-bit label shows up in the log.
     */
    private fun checkFlacHeader(file: File, meta: Meta, bitDepth: Int?) {
        val seconds = runCatching {
            file.inputStream().use { s ->
                val head = ByteArray(42); var off = 0
                while (off < head.size) { val n = s.read(head, off, head.size - off); if (n < 0) break; off += n }
                flacDurationSec(head)
            }
        }.getOrNull() ?: throw Unavailable("the source did not send a FLAC file")
        checkLength(seconds.toLong(), meta)
        Log.d(TAG, "${meta.title}: FLAC ${seconds}s header check, labelled ${bitDepth ?: "-"}-bit")
    }

    private fun isWebmContainer(file: File): Boolean = runCatching {
        file.inputStream().use { s ->
            val head = ByteArray(4)
            val n = s.read(head)
            n == 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        }
    }.getOrDefault(false)

    private fun hasValidAudioContainerMagic(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 16L) return@runCatching false
        file.inputStream().use { s ->
            val head = ByteArray(16)
            var off = 0
            while (off < head.size) {
                val n = s.read(head, off, head.size - off)
                if (n < 0) break
                off += n
            }
            if (off < 12) return@runCatching false
            // 1. ISO BMFF / MP4 / M4A / DASH ('ftyp' or 'styp' or 'moof' or 'moov' at offset 4..7)
            val boxType = String(head, 4, 4, Charsets.US_ASCII)
            if (boxType == "ftyp" || boxType == "styp" || boxType == "moov" || boxType == "moof") return@runCatching true
            // 2. WebM / Matroska EBML header (0x1A 0x45 0xDF 0xA3)
            if (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()) return@runCatching true
            // 3. Ogg ("OggS")
            if (String(head, 0, 4, Charsets.US_ASCII) == "OggS") return@runCatching true
            // 4. ID3 ("ID3") or ADTS/MP3 sync word (0xFF 0xFB / 0xFF 0xF3 / 0xFF 0xF2 / 0xFF 0xF1)
            if (String(head, 0, 3, Charsets.US_ASCII) == "ID3") return@runCatching true
            if (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) return@runCatching true
            false
        }
    }.getOrDefault(false)

    /** An M4A / WebM / MP3 as served: the phone's own extractor must find a decodable audio track. */
    private fun checkAudio(file: File, meta: Meta, checkDuration: Boolean = true) {
        val extractor = android.media.MediaExtractor()
        val seconds = try {
            file.inputStream().use { fis ->
                extractor.setDataSource(fis.fd)
            }
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull {
                    val mime = it.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                    mime.startsWith("audio/") || (!checkDuration && (mime.contains("webm") || mime.contains("mp4") || mime.contains("opus")))
                }
                ?: if (!checkDuration && hasValidAudioContainerMagic(file)) {
                    null
                } else {
                    throw Unavailable("the source did not send an audio file")
                }
            if (format != null && format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                format.getLong(android.media.MediaFormat.KEY_DURATION) / 1_000_000
            } else -1L
        } catch (e: Exception) {
            if (!checkDuration && hasValidAudioContainerMagic(file)) {
                -1L
            } else if (e is Unavailable) {
                throw e
            } else {
                throw Unavailable("the file could not be read: ${e.message}")
            }
        } finally {
            runCatching { extractor.release() }
        }
        if (checkDuration && seconds > 0) checkLength(seconds, meta)
    }

    private fun checkLength(seconds: Long, meta: Meta) {
        lengthMismatch(seconds, meta.durationMs)?.let { throw Unavailable("backed out: $it") }
    }

    private data class Meta(
        val title: String, val artist: String, val album: String, val albumId: String,
        val durationMs: Long, val coverUrl: String, val isrc: String?,
    )

    /** The request, completed by platform catalog metadata when needed. */
    private suspend fun spotifyMeta(r: Request): Meta {
        val isYt = app.wavebits.host.android.WaveBitsRuntime.isYouTube.value ||
            r.trackId.startsWith("yt_") || r.trackId.startsWith("yt:") || r.trackId.length == 11
        val isJio = app.wavebits.host.android.WaveBitsRuntime.isJioSaavn.value ||
            r.trackId.startsWith("jio")
        val isNonSpotify = isYt || isJio

        if (isYt) {
            val cleanId = r.trackId.substringBefore('|')
                .removePrefix("spotify:track:")
                .removePrefix("yt_")
                .removePrefix("yt:")
                .trim()
            val ytTrack = runCatching {
                app.wavebits.host.android.WaveBitsRuntime.youtube.ytPlayer.getRegisteredTrack(r.trackId)
                    ?: app.wavebits.host.android.WaveBitsRuntime.youtube.ytPlayer.getRegisteredTrack(cleanId)
            }.getOrNull()
            var title = r.title.ifBlank { ytTrack?.title.orEmpty() }
            var artist = r.artist.ifBlank { ytTrack?.artist.orEmpty() }
            var album = r.album.ifBlank { ytTrack?.album.orEmpty() }
            var cover = r.coverUrl.ifBlank { ytTrack?.coverUrl.orEmpty() }
            var dur = r.durationMs.takeIf { it > 0 && it != 180_000L } ?: ytTrack?.durationMs ?: r.durationMs

            if (title.isBlank() && cleanId.isNotBlank()) {
                val queueItem = runCatching {
                    app.wavebits.innertube.YouTube.queue(listOf(cleanId)).getOrNull()?.firstOrNull()
                        ?: app.wavebits.innertube.YouTube.next(app.wavebits.innertube.models.WatchEndpoint(videoId = cleanId)).getOrNull()?.items?.firstOrNull()
                }.getOrNull()
                if (queueItem != null) {
                    title = queueItem.title
                    if (artist.isBlank()) artist = queueItem.artists.joinToString(", ") { it.name }
                    if (album.isBlank()) album = queueItem.album?.name.orEmpty()
                    if (cover.isBlank()) cover = queueItem.thumbnail
                    if (dur <= 0L || dur == 180_000L) dur = (queueItem.duration ?: 180) * 1000L
                }
            }
            if (cover.isBlank() && cleanId.length == 11) {
                cover = "https://i.ytimg.com/vi/$cleanId/hqdefault.jpg"
            }
            return Meta(
                title = title.ifBlank { cleanId },
                artist = artist.ifBlank { "YouTube Music" },
                album = album,
                albumId = r.albumId,
                durationMs = dur,
                coverUrl = cover,
                isrc = null,
            ).also { if (it.title.isBlank()) throw Unavailable("no track metadata") }
        }

        if (isJio) {
            val jioTrack = runCatching {
                val cleanId = r.trackId.removePrefix("spotify:track:").removePrefix("jio_").removePrefix("jiosaavn:").trim()
                app.wavebits.host.android.WaveBitsRuntime.jiosaavn.client.getSongDetails(cleanId)
            }.getOrNull()
            val title = r.title.ifBlank { jioTrack?.title.orEmpty() }
            val artist = r.artist.ifBlank { jioTrack?.artist.orEmpty() }
            val album = r.album.ifBlank { jioTrack?.album.orEmpty() }
            val albumId = r.albumId.ifBlank { jioTrack?.albumId.orEmpty() }
            val cover = r.coverUrl.ifBlank { jioTrack?.imageUrl.orEmpty() }
            val dur = r.durationMs.takeIf { it > 0 && it != 180_000L }
                ?: jioTrack?.durationSec?.takeIf { it > 0 }?.let { it * 1000L }
                ?: r.durationMs
            return Meta(
                title = title,
                artist = artist.ifBlank { "JioSaavn" },
                album = album,
                albumId = albumId,
                durationMs = dur,
                coverUrl = cover,
                isrc = null,
            ).also { if (it.title.isBlank()) throw Unavailable("no track metadata") }
        }

        val api = app.wavebits.host.android.WaveBitsRuntime.api
        val uri = "spotify:track:${r.trackId}"
        val t = if (!isNonSpotify) runCatching { api.catalog.track(uri) }.onFailure { Log.w(TAG, "${r.title}: Spotify track: $it") }.getOrNull() else null
        val isrc = if (!isNonSpotify) runCatching { api.catalog.isrc(uri) }.onFailure { Log.w(TAG, "${r.title}: ISRC: $it") }.getOrNull() else null
        val cover = r.coverUrl.ifBlank { t?.album?.images?.maxByOrNull { it.width ?: 0 }?.url.orEmpty() }
        return Meta(
            title = r.title.ifBlank { t?.name.orEmpty() },
            artist = r.artist.ifBlank { t?.artists?.joinToString(", ") { it.name }.orEmpty() },
            album = r.album.ifBlank { t?.album?.name.orEmpty() },
            albumId = r.albumId.ifBlank { t?.album?.uri?.substringAfterLast(':').orEmpty() },
            durationMs = r.durationMs.takeIf { it > 0 } ?: t?.durationMs ?: 0L,
            coverUrl = cover,
            isrc = isrc?.let(::normalizeIsrc),
        ).also { if (it.title.isBlank() || it.artist.isBlank()) throw Unavailable("no track metadata") }
    }

    private suspend fun httpGet(url: String, out: File) = httpGetWithHeaders(url, out, emptyMap())

    private suspend fun httpGetWithHeaders(url: String, out: File, headers: Map<String, String>) = runInterruptible {
        val reqBuilder = okhttp3.Request.Builder().url(url)
            .header("User-Agent", headers["User-Agent"] ?: DownloadUa)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        InterruptibleHttp.read(YouTubeSource.http.newCall(reqBuilder.build())) { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val body = res.body ?: throw IOException("empty body")
            out.outputStream().use { body.byteStream().copyTo(it) }
        }
    }

    /**
     * Downloads an audio stream directly using OkHttp streaming with real-time progress updates,
     * avoiding 403/405 HEAD errors on YouTube, JioSaavn, and CDN hosts.
     */
    private suspend fun downloadStreamWithProgress(
        trackId: String,
        url: String,
        out: File,
        headers: Map<String, String> = emptyMap(),
    ): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(out.parentFile ?: OfflineStore.tmpDir, "${out.name}.tmp")
        try {
            val reqBuilder = okhttp3.Request.Builder().url(url)
                .header("User-Agent", headers["User-Agent"] ?: DownloadUa)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            val call = YouTubeSource.http.newCall(reqBuilder.build())
            InterruptibleHttp.read(call) { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                val body = res.body ?: throw IOException("Empty body")
                val total = body.contentLength()
                var downloaded = 0L
                var lastUpdate = 0L
                tmp.outputStream().use { fos ->
                    val buf = ByteArray(128 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            fos.write(buf, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 300L) {
                                lastUpdate = now
                                val prog = if (total > 0) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else -1f
                                scope.launch(Dispatchers.Main) {
                                    setStatus(trackId, OfflineStatus.Downloading(prog))
                                }
                            }
                        }
                        fos.flush()
                    }
                }
                if (tmp.length() >= 10_000) {
                    if (out.exists()) out.delete()
                    tmp.renameTo(out)
                } else false
            }
        } catch (e: CancellationException) {
            if (tmp.exists()) tmp.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Stream download failed for $url: ${e.message}")
            if (tmp.exists()) tmp.delete()
            false
        }
    }

    /**
     * Downloads the file URL into [out] reporting progress: first attempts direct OkHttp
     * streaming, falling back to byte-range chunks if needed.
     */
    private suspend fun fetchWithProgress(trackId: String, url: String, out: File, headers: Map<String, String> = emptyMap()): Boolean {
        // High-speed OkHttp direct streaming first
        if (downloadStreamWithProgress(trackId, url, out, headers)) {
            return true
        }

        // Fallback to byte range chunks
        return runCatching {
            val total = runInterruptible(Dispatchers.IO) { contentLength(url, headers) }
            val watcher = if (total > 0) scope.launch {
                while (isActive) {
                    setStatus(trackId, OfflineStatus.Downloading((out.length().toFloat() / total).coerceIn(0f, 1f)))
                    delay(400)
                }
            } else null
            try {
                runInterruptible(Dispatchers.IO) { rangedDownload(url, out, total, headers) }
            } finally {
                watcher?.cancel()
                setStatus(trackId, OfflineStatus.Downloading(-1f))
            }
        }.getOrDefault(false)
    }

    /**
     * The byte range loop, shared with [PodcastDownloader]: a publisher's MP3 is fetched the
     * same way (follows the 302s to podtrac/radiofrance/simplecast, restarts from zero when a
     * server ignores Range, retries each chunk three times).
     */
    internal fun rangedDownload(url: String, out: File, knownTotal: Long, headers: Map<String, String> = emptyMap()): Boolean {
        val chunk = 4L * 1024 * 1024 // 4 MB: the stream gets its turn between two chunks
        var total = knownTotal
        java.io.RandomAccessFile(out, "rw").use { output ->
            output.setLength(0)
            while (true) {
                // A chunk is [start, end]; a retry resumes where the bytes stopped, never re-appends.
                val end = if (total > 0) minOf(output.length() + chunk - 1, total - 1) else output.length() + chunk - 1
                var done = false
                var attempt = 0
                yieldToPlayer()
                while (true) {
                    attempt++
                    val position = output.length()
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", DownloadUa)
                        headers.forEach { (k, v) -> setRequestProperty(k, v) }
                        setRequestProperty("Range", "bytes=$position-$end")
                    }
                    try {
                        val code = conn.responseCode
                        // Asked past the end of a file whose size was unknown: it is complete.
                        if (code == 416 && total <= 0 && position > 0) { done = true; break }
                        if (code !in 200..299) throw IOException("stream HTTP $code")
                        val full = code != 206 // server ignored Range → the whole body, from byte 0
                        if (full) output.setLength(0)
                        if (total <= 0) total = contentRangeTotal(conn.getHeaderField("Content-Range")) ?: -1L
                        output.seek(output.length())
                        val expected = if (full) -1L else end - position + 1
                        var got = 0L
                        conn.inputStream.use { input ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                got += n
                            }
                        }
                        if (full) done = true
                        // A short chunk with the size still unknown is the end of the stream.
                        else if (total <= 0 && got < expected) done = true
                        break
                    } catch (e: IOException) {
                        if (attempt >= 3) throw e
                        Log.w(TAG, "chunk at ${output.length()} failed (attempt $attempt): ${e.message}")
                    } finally {
                        conn.disconnect()
                    }
                }
                if (done) break
                if (total in 1..output.length()) break
            }
        }
        return true
    }

    /**
     * The song playing here is loading: downloads wait (up to 30 s) so they do not starve
     * it, or the player's stall watchdog would take it for stuck and skip it.
     */
    private fun yieldToPlayer() {
        var waited = 0
        while (waited < 30_000 && playerStarving()) {
            Thread.sleep(500); waited += 500
        }
    }

    private fun playerStarving(): Boolean = runCatching {
        com.music.spotui.di.SongPlayer.isPlaying() && com.music.spotui.di.SongPlayer.isBuffering &&
            com.music.spotui.di.SongPlayer.activeDevice.value == null && !com.music.spotui.di.LocalPlayer.active.value
    }.getOrDefault(false)

    private fun contentLength(url: String, headers: Map<String, String> = emptyMap()): Long =
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", DownloadUa)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(-1L)

    // ── Pure helpers (unit-tested) ──

    /** File hosts expect a browser-ish User-Agent on downloads. */
    internal const val DownloadUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Length from a FLAC STREAMINFO header, or null if [head] is not one. */
    internal fun flacDurationSec(head: ByteArray): Int? {
        if (head.size < 26 || String(head, 0, 4, Charsets.US_ASCII) != "fLaC") return null
        // Bytes 18..25: sample rate (20 bits), channels (3), bits/sample (5), total samples (36).
        var bits = 0L
        for (i in 18..25) bits = (bits shl 8) or (head[i].toLong() and 0xFF)
        val sampleRate = (bits ushr 44).toInt()
        val totalSamples = bits and 0xFFFFFFFFFL
        if (sampleRate <= 0 || totalSamples <= 0) return null
        return (totalSamples / sampleRate).toInt()
    }

    /** Why a file of [seconds] is not the song of [durationMs], or null when it is (or the song's length is unknown). */
    internal fun lengthMismatch(seconds: Long, durationMs: Long): String? {
        val expected = durationMs / 1000
        return when {
            expected <= 0 -> null
            seconds < expected - SHORTER_MAX -> "the file is ${seconds}s, the song ${expected}s (a preview)"
            seconds > expected + LONGER_MAX -> "the file is ${seconds}s, the song ${expected}s (another version)"
            else -> null
        }
    }

    /** "bytes 0-99/1234" → 1234; null when absent or "*". */
    fun contentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** "US-UM7-17-03861" / "usum71703861" → "USUM71703861"; null when not an ISRC. */
    fun normalizeIsrc(raw: String): String? =
        raw.uppercase().filter { it.isLetterOrDigit() }.takeIf { Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}").matches(it) }
}
