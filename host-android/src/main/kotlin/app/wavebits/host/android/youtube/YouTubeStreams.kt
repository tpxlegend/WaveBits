// WaveBits YouTube stream downloader & resolver
package app.wavebits.host.android.youtube

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * The HTTP client NewPipeExtractor asks for.
 */
class NewPipeDownloader(private val http: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder()
            .method(
                request.httpMethod(),
                request.dataToSend()?.toRequestBody(),
            )
            .url(request.url())
            .addHeader("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        val url = request.url()
        if (url.contains("youtube.com")) {
            app.wavebits.innertube.YouTube.visitorData?.takeIf { it.isNotBlank() }?.let { vd ->
                if (request.headers()["X-Goog-Visitor-Id"].isNullOrEmpty()) {
                    builder.header("X-Goog-Visitor-Id", vd)
                }
            }
            app.wavebits.innertube.YouTube.cookie?.takeIf { it.isNotBlank() }?.let { cookie ->
                if (request.headers()["Cookie"].isNullOrEmpty() && request.headers()["cookie"].isNullOrEmpty()) {
                    builder.header("Cookie", cookie)
                }
            }
        }
        http.newCall(builder.build()).execute().use { response ->
            if (response.code == HTTP_TOO_MANY_REQUESTS) {
                throw ReCaptchaException("reCaptcha challenge requested", request.url())
            }
            val body = response.body?.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                body,
                response.request.url.toString(),
            )
        }
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        fun create(): NewPipeDownloader = NewPipeDownloader(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build(),
        )

        private const val READ_TIMEOUT_SECONDS = 30L
    }
}

/**
 * NewPipe's one-time setup and StreamInfo cache, shared by the player and the downloads.
 */
object YouTubeStreams {
    const val WATCH_URL = "https://www.youtube.com/watch?v="
    const val TRACK_PREFIX = "ytmusic:track:"

    private val infos = ConcurrentHashMap<String, Pair<Long, StreamInfo>>()
    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(key: String): Any = locks.computeIfAbsent(key) { Any() }

    /**
     * What YouTube says about a video, kept for a while: the URLs stay good for hours.
     */
    fun info(videoId: String): StreamInfo = synchronized(lockFor(videoId)) {
        val now = SystemClock.elapsedRealtime()
        infos[videoId]?.takeIf { now - it.first < INFO_TTL_MS }?.let { return it.second }
        ensureNewPipe()
        val fresh = StreamInfo.getInfo(ServiceList.YouTube, "$WATCH_URL$videoId")
        infos[videoId] = now to fresh
        if (infos.size > 64) infos.entries.minByOrNull { it.value.first }?.let { infos.remove(it.key) }
        fresh
    }

    /**
     * Resolves the best audio stream URL for [videoId] using Square's exact strategy:
     * 1. Asks anonymously via NewPipeExtractor (`StreamInfo.getInfo(ServiceList.YouTube, WATCH_URL + videoId)`),
     *    preferring M4A (AAC) before Opus at the requested bitrate.
     * 2. If YouTube requires an account/bot check or anonymous extraction fails, falls back to
     *    [SignedInStreams.url] (TVHTML5 / InnerTube multi-client + [PlayerChallenges] `ejs` solver).
     */
    fun resolveAudioUrl(
        context: Context,
        videoId: String,
        lowRate: Boolean = false,
    ): String {
        val anonymousResult = runCatching {
            val info = info(videoId)
            info.audioStreams
                .filter { !it.content.isNullOrEmpty() }
                .sortedWith(
                    compareByDescending<AudioStream> {
                        it.format == MediaFormat.M4A
                    }.thenBy { if (lowRate) it.averageBitrate else -it.averageBitrate },
                )
                .firstOrNull()
                ?.content
                ?: error("no audio track in StreamInfo for $videoId")
        }
        anonymousResult.getOrNull()?.let { return it }

        // Fallback to SignedInStreams (TVHTML5 when signed in, or InnerTube multi-client + ejs challenge solver)
        return SignedInStreams.url(context, videoId, SignedInStreams.Kind.AUDIO, lowRate)
    }

    /**
     * Resolves all candidate audio stream URLs for [videoId] (for playback and OfflineDownloader).
     */
    fun resolveAudioUrls(
        context: Context,
        videoId: String,
        lowRate: Boolean = false,
    ): List<String> {
        val anonymousUrls = runCatching {
            val info = info(videoId)
            info.audioStreams
                .filter { !it.content.isNullOrEmpty() }
                .sortedWith(
                    compareByDescending<AudioStream> {
                        it.format == MediaFormat.M4A
                    }.thenBy { if (lowRate) it.averageBitrate else -it.averageBitrate },
                )
                .mapNotNull { it.content }
        }.getOrDefault(emptyList())

        if (anonymousUrls.isNotEmpty()) return anonymousUrls

        val signedInOrFallback = runCatching {
            SignedInStreams.url(context, videoId, SignedInStreams.Kind.AUDIO, lowRate)
        }.getOrNull()

        return listOfNotNull(signedInOrFallback)
    }

    @Volatile
    var allowBackgroundPrefetch: Boolean = true

    private val prefetcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WaveBitsYTPrefetch").apply { isDaemon = true }
    }

    /** Asks ahead for what [videoId] will need, so the next item starts at once. */
    fun prefetch(videoId: String) {
        if (!allowBackgroundPrefetch || videoId.isBlank()) return
        prefetcher.execute {
            if (!allowBackgroundPrefetch) return@execute
            runCatching { info(videoId) }
        }
    }

    /** Drops what is kept about [videoId], so its addresses are asked for again. */
    fun forget(videoId: String) {
        infos.remove(videoId)
    }

    private const val INFO_TTL_MS = 60 * 60 * 1000L

    @Volatile
    private var initialised = false

    /** Idempotent: several tracks resolve on the same thread pool. */
    @Synchronized
    fun ensureNewPipe() {
        if (initialised) return
        NewPipe.init(NewPipeDownloader.create(), Localization.DEFAULT)
        initialised = true
    }
}

/**
 * Turns a `ytmusic:track:<videoId>` URI into the URL the audio really lives at
 * on ExoPlayer's loading thread.
 */
@UnstableApi
class YouTubeStreamResolver(
    private val context: Context,
    private val wantsLowRate: () -> Boolean = { false },
) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri.toString()
        if (!uri.startsWith(YouTubeStreams.TRACK_PREFIX)) {
            return dataSpec
        }
        val videoId = uri.removePrefix(YouTubeStreams.TRACK_PREFIX).substringBefore('#').trim()
        val resolvedUrl = YouTubeStreams.resolveAudioUrl(context, videoId, wantsLowRate())
        return dataSpec.withUri(Uri.parse(resolvedUrl))
    }
}

/**
 * Reads a YouTube media stream in half-megabyte pieces.
 *
 * `googlevideo.com` throttles an open-ended `Range: bytes=0-` request to the
 * bitrate of the track after the first burst, and drops a connection that sits
 * idle once the buffer is full; bounded range requests are served at line speed
 * and each piece is its own short-lived request.
 */
@UnstableApi
class ChunkedDataSource(private val upstream: DataSource) : DataSource {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource() = ChunkedDataSource(upstream.createDataSource())
    }

    private var spec: DataSpec? = null
    private var chunked = false

    /** Where the next piece starts, from the start of the file. */
    private var position = 0L

    /** Bytes still wanted in all, or unset for "to the end". */
    private var remaining = C.LENGTH_UNSET.toLong()
    private var pieceLeft = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        chunked = dataSpec.uri.scheme == "https" && dataSpec.uri.host.orEmpty().endsWith("googlevideo.com")
        if (!chunked) {
            opened = true
            return upstream.open(dataSpec)
        }
        position = dataSpec.position
        remaining = dataSpec.length
        val first = openPiece()
        // The whole of what is left, where the server said how big the file is.
        val contentRange = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.firstOrNull()
        val total = contentRange?.substringAfter('/')?.trim()?.toLongOrNull()
            ?: dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0L }
        return when {
            remaining != C.LENGTH_UNSET.toLong() -> remaining
            total != null -> (total - dataSpec.position).coerceAtLeast(0L)
            else -> if (first < CHUNK_BYTES) first else C.LENGTH_UNSET.toLong()
        }
    }

    private fun openPiece(): Long {
        val wanted = if (remaining == C.LENGTH_UNSET.toLong()) CHUNK_BYTES else minOf(CHUNK_BYTES, remaining)
        val piece = spec!!.buildUpon().setPosition(position).setLength(wanted).build()
        val length = upstream.open(piece)
        opened = true
        pieceLeft = if (length == C.LENGTH_UNSET.toLong()) wanted else length
        return pieceLeft
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!chunked) return upstream.read(buffer, offset, length)
        while (true) {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            if (!opened) {
                // The next piece. Asked past the end, the server says 416.
                val next = try {
                    openPiece()
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    if (e.responseCode == 416) return C.RESULT_END_OF_INPUT
                    throw e
                }
                if (next == 0L) return C.RESULT_END_OF_INPUT
            }
            val read = upstream.read(buffer, offset, minOf(length.toLong(), pieceLeft).toInt())
            if (read == C.RESULT_END_OF_INPUT) {
                // Shorter than asked for: the file ends inside this piece.
                if (pieceLeft > 0) return C.RESULT_END_OF_INPUT
                upstream.close()
                opened = false
                continue
            }
            position += read
            pieceLeft -= read
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
            if (pieceLeft == 0L) {
                upstream.close()
                opened = false
            }
            return read
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (opened) upstream.close()
        opened = false
        spec = null
    }

    private companion object {
        /** Half a megabyte: a few seconds of 1080p, a minute of sound. */
        const val CHUNK_BYTES = 512L * 1024
    }
}
