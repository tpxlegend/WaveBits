// WaveBits YouTube signed-in stream resolver
package app.wavebits.host.android.youtube

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.wavebits.innertube.YouTube
import app.wavebits.innertube.models.YouTubeClient
import app.wavebits.innertube.models.response.PlayerResponse
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager

/**
 * Streams for the songs YouTube will only play to someone signed in, or when
 * anonymous extraction requires TV/InnerTube fallback for WaveBits.
 */
object SignedInStreams {
    /** What to ask for: the sound, the picture alone, or both in one file. */
    enum class Kind { AUDIO, VIDEO, MUXED }

    /** Thrown when a song needs an account and none is signed in. */
    class SignInRequired(videoId: String) : IOException("$videoId plays only when signed in")

    /**
     * Thrown when a song needs an account, one is signed in, and its stream
     * still could not be had: YouTube refused every client, or the address
     * could not be deciphered.
     */
    class Refused(videoId: String, cause: Throwable) :
        IOException("$videoId could not be played signed in: ${cause.message}", cause)

    private val urls = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * Whether [error], from asking without an account, is YouTube asking for
     * one: an age check, a song it serves to accounts alone, or its "confirm
     * you're not a bot", which it puts in front of every song for a network it
     * has stopped trusting.
     */
    fun needsAccount(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is AgeRestrictedContentException ||
                it is SignInConfirmNotBotException ||
                (it is ContentNotAvailableException && it.message.orEmpty().contains("LOGIN_REQUIRED"))
        }

    val available: Boolean
        get() {
            val c = YouTube.cookie.orEmpty()
            return "SAPISID=" in c || "__Secure-3PAPISID=" in c || "__Secure-1PAPISID=" in c
        }

    /**
     * Forgets the addresses kept, after one failed to play: kept, it would be
     * handed out again for the hour, and the song could not be tried afresh.
     */
    fun forget() = urls.clear()

    /** The URL of [kind] for [videoId], as the signed-in account (or multi-client fallback); blocking. */
    fun url(context: Context, videoId: String, kind: Kind, lowRate: Boolean): String {
        val key = "$videoId|$kind|$lowRate|${available}"
        val now = SystemClock.elapsedRealtime()
        urls[key]?.takeIf { now - it.first < URL_TTL_MS }?.let { return it.second }

        val script = runCatching { PlayerChallenges.script(context) }.getOrNull()
        val timestamp = script?.signatureTimestamp
            ?: runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }.getOrNull()
            ?: 20110

        val response = player(videoId, timestamp)
        val issuedAt = SystemClock.elapsedRealtime()
        val formats = response.streamingData
        val chosen = when (kind) {
            Kind.AUDIO -> formats?.adaptiveFormats.orEmpty()
                .filter { it.isAudio && it.isOriginal }
                .ifEmpty { formats?.adaptiveFormats.orEmpty().filter { it.isAudio } }
                // AAC before Opus for consistent WaveBits playback.
                .sortedWith(
                    compareByDescending<PlayerResponse.StreamingData.Format> { it.mimeType.startsWith("audio/mp4") }
                        .thenBy { if (lowRate) it.bitrate else -it.bitrate },
                )
                .firstOrNull()
            Kind.VIDEO -> formats?.adaptiveFormats.orEmpty()
                .filter { !it.isAudio && (it.height ?: 0) in 1..MAX_VIDEO_HEIGHT }
                .sortedWith(
                    compareByDescending<PlayerResponse.StreamingData.Format> { it.height ?: 0 }
                        .thenByDescending { it.mimeType.startsWith("video/mp4") },
                )
                .firstOrNull()
            Kind.MUXED -> formats?.formats.orEmpty().maxByOrNull { it.height ?: 0 }
        } ?: formats?.formats.orEmpty().firstOrNull()
            ?: error("no ${kind.name.lowercase()} stream for $videoId")

        val url = try {
            urlOf(context, videoId, script, chosen)
        } catch (failure: Exception) {
            PlayerChallenges.reset()
            throw failure
        }
        awaitReady(videoId, url, issuedAt)
        urls[key] = now to url
        if (urls.size > 64) urls.entries.minByOrNull { it.value.first }?.let { urls.remove(it.key) }
        Log.i(
            TAG,
            "$videoId played through $workingName (${kind.name.lowercase()}, itag ${chosen.itag})",
        )
        return url
    }

    /**
     * The first client that will play it. When signed in, the TV app's comes first,
     * asked as YouTube's own TV app asks: of www.youtube.com, as a named Samsung TV.
     * Its streams need no proof-of-origin token, which the website's clients do.
     * Also supports anonymous multi-client fallback (VISIONOS, ANDROID_VR, TVHTML5_SIMPLY_EMBEDDED_PLAYER, IOS)
     * when nobody is signed in.
     */
    private fun player(videoId: String, timestamp: Int): PlayerResponse {
        if (YouTube.visitorData.isNullOrBlank()) {
            runBlocking {
                runCatching {
                    YouTube.visitorData().getOrNull()?.takeIf { it.isNotBlank() }?.let {
                        YouTube.visitorData = it
                    }
                }
            }
        }
        val refusals = mutableListOf<String>()
        if (available) {
            val tv = runCatching { tvPlayer(videoId, timestamp) }
                .onFailure { refusals += "TV: ${it.message}" }
                .getOrNull()
            val tvStatus = tv?.playabilityStatus
            if (tv != null && tvStatus?.status == "OK" && tv.streamingData != null) {
                workingName = "TVHTML5 (Samsung)"
                return tv
            }
            if (tv != null) refusals += "TV: ${tvStatus?.status} ${tvStatus?.reason.orEmpty()}"
        }

        val clientChain = if (available) CLIENTS else ANONYMOUS_FALLBACK_CLIENTS
        for (client in clientChain) {
            val response = runBlocking {
                YouTube.player(
                    videoId = videoId,
                    client = client,
                    signatureTimestamp = timestamp.takeIf { client.useSignatureTimestamp },
                )
            }.getOrNull()
            val status = response?.playabilityStatus
            if (response != null && status?.status == "OK" && response.streamingData != null) {
                workingName = client.clientName
                if (refusals.isNotEmpty()) Log.i(TAG, "$videoId refused first: ${refusals.joinToString(" / ")}")
                return response
            }
            refusals += "${client.clientName}: ${status?.status} ${status?.reason.orEmpty()}"
        }
        Log.w(TAG, "$videoId refused: ${refusals.joinToString(" / ")}")
        if (!available) throw SignInRequired(videoId)
        error("signed in, $videoId is unplayable: ${refusals.joinToString(" / ")}")
    }

    /**
     * Waits for [url] to open. An address the TV client has just been given
     * answers 403 for its first three or four seconds, then plays; handed to
     * the player at once, it failed the song. Asked for its first two bytes
     * until it answers otherwise, for as long as [READY_WAIT_MS] after it was
     * issued; one that still refuses then is refused for good.
     */
    private fun awaitReady(videoId: String, url: String, issuedAt: Long) {
        while (true) {
            val code = runCatching {
                val request = Request.Builder().url(url).header("Range", "bytes=0-1").build()
                http.newCall(request).execute().use { it.code }
            }.getOrElse { return }
            if (code != 403) return
            val waited = SystemClock.elapsedRealtime() - issuedAt
            if (waited > 2_500L) error("$videoId still refused after $waited ms")
            Thread.sleep(350L)
        }
    }

    /** The player answer of YouTube's TV app, signed in; see [player]. */
    private fun tvPlayer(videoId: String, timestamp: Int): PlayerResponse {
        val locale = YouTube.locale
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "TVHTML5")
                        .put("clientVersion", TV_VERSION)
                        .put("userAgent", TV_USER_AGENT)
                        .put("deviceMake", "Samsung")
                        .put("deviceModel", "UKS9800")
                        .put("osName", "Tizen")
                        .put("osVersion", "2.4.0")
                        .put("hl", locale.hl)
                        .put("gl", locale.gl)
                        .apply { YouTube.visitorData?.let { put("visitorData", it) } },
                ),
            )
            .put("videoId", videoId)
            .put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject().put("signatureTimestamp", timestamp).put("html5Preference", "HTML5_PREF_WANTS"),
                ),
            )
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val cookie = YouTube.cookie ?: throw SignInRequired(videoId)
        val request = Request.Builder()
            .url("$WWW/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", TV_USER_AGENT)
            .header("Origin", WWW)
            .header("X-Origin", WWW)
            .header("X-Youtube-Client-Name", "7")
            .header("X-Youtube-Client-Version", TV_VERSION)
            .header("Cookie", cookie)
            .header("X-Goog-AuthUser", "0")
            .apply {
                authorization(cookie)?.let { header("Authorization", it) }
                YouTube.visitorData?.let { header("X-Goog-Visitor-Id", it) }
                YouTube.pageId?.takeIf { it.isNotBlank() }?.let { header("X-Goog-PageId", it) }
            }
            .build()
        val text = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string() ?: error("empty answer")
        }
        return json.decodeFromString(PlayerResponse.serializer(), text)
    }

    /**
     * The hashes that prove the cookie's holder made the request, as the
     * website sends them: one for each of the account's three API cookies
     * the cookie carries, each over the time, the cookie and the origin.
     */
    private fun authorization(cookie: String): String? {
        val cookies = cookie.split(';').associate {
            it.trim().substringBefore('=') to it.trim().substringAfter('=', "")
        }
        val now = System.currentTimeMillis() / 1000
        fun hash(scheme: String, value: String?) = value?.takeIf { it.isNotEmpty() }?.let {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest("$now $it $WWW".toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
            "$scheme ${now}_$digest"
        }
        return listOfNotNull(
            hash("SAPISIDHASH", cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"]),
            hash("SAPISID1PHASH", cookies["__Secure-1PAPISID"]),
            hash("SAPISID3PHASH", cookies["__Secure-3PAPISID"]),
        ).takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    @Volatile
    private var workingName: String? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val CLIENTS = listOf(
        YouTubeClient.TVHTML5,
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        YouTubeClient.ANDROID_VR_1_43_32,
        YouTubeClient.ANDROID_VR_1_61_48,
        YouTubeClient.IOS,
        YouTubeClient.MOBILE,
        YouTubeClient.WEB_CREATOR,
        YouTubeClient.WEB_REMIX,
        YouTubeClient.WEB_ACCOUNTS,
    )

    private val ANONYMOUS_FALLBACK_CLIENTS = listOf(
        YouTubeClient.ANDROID_VR_1_43_32,
        YouTubeClient.ANDROID_VR_1_61_48,
        YouTubeClient.ANDROID_VR_1_65_10,
        YouTubeClient.VISIONOS,
        YouTubeClient.IOS,
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        YouTubeClient.ANDROID_CREATOR,
        YouTubeClient.IPADOS,
        YouTubeClient.ANDROID_NO_SDK,
        YouTubeClient.TVHTML5,
        YouTubeClient.WEB_REMIX,
    )

    private const val WWW = "https://www.youtube.com"
    private const val TV_VERSION = "5.20260707"
    private const val TV_USER_AGENT =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"

    /**
     * A format's address with its signature and its throttling parameter
     * undone, both by YouTube's own player script; see [PlayerChallenges].
     * Falls back to [YoutubeJavaScriptPlayerManager] if the device WebView lacks
     * [androidx.javascriptengine.JavaScriptSandbox].
     */
    private fun urlOf(
        context: Context,
        videoId: String,
        script: PlayerChallenges.Script?,
        format: PlayerResponse.StreamingData.Format,
    ): String {
        var url: String
        var signature: String? = null
        var signatureParameter = "signature"
        val cipher = format.signatureCipher ?: format.cipher
        val direct = format.url
        if (direct != null) {
            url = direct
        } else {
            cipher ?: error("no address for itag ${format.itag}")
            val params = cipher.split('&').associate { pair ->
                pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
            url = params["url"] ?: error("no url in the cipher")
            signature = params["s"]
            params["sp"]?.let { signatureParameter = it }
        }
        val throttle = android.net.Uri.parse(url).getQueryParameter("n")
        if (signature == null && throttle == null) return url

        if (script != null) {
            val solvedPair = runCatching {
                PlayerChallenges.solve(
                    context,
                    script,
                    listOfNotNull(throttle),
                    listOfNotNull(signature),
                )
            }.getOrNull()
            if (solvedPair != null) {
                val (nAnswers, sigAnswers) = solvedPair
                if (signature != null) {
                    val solved = sigAnswers[signature] ?: error("signature unsolved")
                    url += "&$signatureParameter=${URLEncoder.encode(solved, "UTF-8")}"
                }
                if (throttle != null) {
                    val solved = nAnswers[throttle] ?: error("throttling parameter unsolved")
                    url = url.replace(THROTTLE, "$1n=${URLEncoder.encode(solved, "UTF-8")}")
                }
                return url
            }
        }

        // Fallback to NewPipe's JS manager if JavaScriptSandbox is unsupported on this device
        if (signature != null) {
            val deobfSig = YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, signature)
            url += "&$signatureParameter=${URLEncoder.encode(deobfSig, "UTF-8")}"
        }
        return runCatching {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        }.getOrDefault(url)
    }

    private val THROTTLE = Regex("""([?&])n=[^&]*""")
    private const val URL_TTL_MS = 60 * 60 * 1000L
    private const val MAX_VIDEO_HEIGHT = 1080
    private const val READY_WAIT_MS = 10_000L
    private const val READY_POLL_MS = 500L
    private const val TAG = "WaveBitsYTSignedIn"
}
