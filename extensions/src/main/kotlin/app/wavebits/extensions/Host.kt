package app.wavebits.extensions

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * What the app gives the sandbox. Every call arrives here already checked against the rights the
 * user granted (see [ExtensionRuntime]): an implementation only does the work.
 */
interface Host {
    /** A request to one of the extension's hosts, without cookies or any account. */
    fun http(extensionId: String, request: HttpRequest): HttpResponse = BasicHttp.send(request, userAgent(extensionId))

    fun log(extensionId: String, message: String)

    /** The extension's own small store (key → JSON text). */
    fun storage(extensionId: String): Storage

    /** The user's value for one of the extension's settings, as JSON text, or null when unset. */
    fun setting(extensionId: String, key: String): String?

    /** The song playing now, as the hooks receive a track (JSON), or null. */
    fun nowPlaying(): String? = null

    /**
     * play, pause, next, previous, seek (argument: milliseconds), asked by [extensionId]. False when
     * it could not be done, or the app's [PlaybackArbiter] dropped it.
     */
    fun control(extensionId: String, action: String, argument: Long?): Boolean = false

    fun notify(extensionId: String, text: String) = log(extensionId, "notify: $text")

    /** The extension's blocks on [surface] (home, search, menu, player, trackinfo) changed: ask it again when shown. */
    fun refresh(extensionId: String, surface: String) {}

    /** A message from the extension to its own web page, if it is open (JSON text). */
    fun postToPage(extensionId: String, json: String) {}

    /**
     * Runs one `myspoti.spotify` call as the signed-in account and answers its result as JSON.
     * The method is known and the right granted already; [SpotifyDispatcher] does the work over a
     * [app.wavebits.api.WaveBitsApi]. Suspends: the script's thread waits, within its time limit.
     */
    suspend fun spotify(extensionId: String, call: SpotifyCall): String = throw UnsupportedOperationException("this app has no Spotify account for extensions")

    /** Whether the raw GraphQL operation [name] changes something. Unknown to the host: it does. */
    fun spotifyMutation(name: String): Boolean = true

    /**
     * Asks the user to allow a destructive change an extension is about to make ([text] says what,
     * in plain words). False refuses it, which is also what a host without a screen answers.
     */
    suspend fun confirm(extensionId: String, text: String): Boolean = false

    /** The writes the user allowed [extensionId] for good on `raw.request` ([RawGrant]). */
    fun rawGrants(extensionId: String): List<RawGrant> = emptyList()

    /**
     * Like [confirm], for a `raw.request` write, with a third answer: always allow [offer] to this
     * extension. On [Consent.ALWAYS] the host keeps [offer] and lists it in the extension's rights,
     * where the user revokes it. A host that cannot keep grants asks a plain [confirm].
     */
    suspend fun confirmWrite(extensionId: String, text: String, offer: RawGrant): Consent =
        if (confirm(extensionId, text)) Consent.ONCE else Consent.REFUSE

    /**
     * Opens a sign-in page for [extensionId] ([Action.WEBVIEW_AUTH], [AuthRequest] checked) and
     * returns at once with its id; the outcome comes later as the `auth` event ([AuthResult]).
     * The page runs in a web view of its own: its cookies are never the app's, never Spotify's,
     * never given to the extension. Throws when it cannot be opened now.
     */
    fun openAuth(extensionId: String, request: AuthRequest): String = throw UnsupportedOperationException("this app has no sign-in pages for extensions")

    /**
     * Asks for the `online` event once the device has a network ([Action.NETWORK]): at once when
     * it has one, else when it comes back, also after the app was restarted. Asking again before
     * it fires changes nothing.
     */
    fun whenOnline(extensionId: String) {}

    fun userAgent(extensionId: String) = "Myspoti-Extension/$API_VERSION ($extensionId)"
}

interface Storage {
    fun get(key: String): String?
    fun set(key: String, json: String)
    fun remove(key: String)
    /** Characters stored, to keep the store small. */
    fun size(): Int
}

/** A store in memory, for tests and the desktop runner. */
class MemoryStorage : Storage {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, json: String) { values[key] = json }
    override fun remove(key: String) { values.remove(key) }
    override fun size() = values.entries.sumOf { it.key.length + it.value.length }
    fun snapshot(): Map<String, String> = values.toMap()
}

/**
 * `myspoti.auth.open({ url, callback, hidden? })`: where the sign-in starts, and the address it ends
 * on. [hidden]: the page first runs out of sight (a check that passes by itself needs no screen) and
 * is shown only if it has not reached the callback after [Companion.HIDDEN_MS].
 */
@Serializable
data class AuthRequest(val url: String, val callback: String, val hidden: Boolean = false) {
    /** This request, or why it is refused. */
    fun checked(): AuthRequest {
        val start = runCatching { URI(url) }.getOrNull() ?: throw IllegalArgumentException("auth.open: not a URL: $url")
        require(start.scheme == "https" && !start.host.isNullOrEmpty()) { "auth.open: the url must be https" }
        require(!RawRequest.isSpotifyHost(start.host.lowercase())) { "auth.open: not on a Spotify address" }
        val end = runCatching { URI(callback) }.getOrNull() ?: throw IllegalArgumentException("auth.open: not a URL: $callback")
        val scheme = end.scheme?.lowercase() ?: throw IllegalArgumentException("auth.open: the callback needs a scheme")
        require(scheme !in UNSAFE_SCHEMES) { "auth.open: a $scheme: callback is not allowed" }
        if (scheme == "https") require(!end.host.isNullOrEmpty() && !RawRequest.isSpotifyHost(end.host.lowercase())) { "auth.open: the callback must name a host that is not Spotify's" }
        else require(scheme.matches(Regex("[a-z][a-z0-9+.-]{1,40}"))) { "auth.open: the callback is https or a custom scheme (myextension://done)" }
        require(url.length <= 8_192 && callback.length <= 1_024) { "auth.open: address too long" }
        return this
    }

    /** Whether the page reached the callback: [address] starts with it (its query and fragment are the answer). */
    fun reached(address: String): Boolean = address.startsWith(callback, ignoreCase = false) &&
        address.length.let { it == callback.length || address[callback.length] in "?#/&" || callback.last() in "?#/&=" }

    companion object {
        /** How long a hidden sign-in runs out of sight before it is shown. */
        const val HIDDEN_MS = 8_000L
        /** A sign-in that has not reached its callback by then ends with the error "timeout". */
        const val TIMEOUT_MS = 180_000L
        private val UNSAFE_SCHEMES = setOf("http", "javascript", "data", "file", "content", "intent", "about", "blob", "filesystem", "ws", "wss")
    }
}

/** The `auth` event: `{ id, url }` when the callback was reached (with its query), `{ id, error }` otherwise. */
@Serializable
data class AuthResult(val id: String, val url: String? = null, val error: String? = null)

@Serializable
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    /** Milliseconds; capped by the sandbox. */
    val timeout: Int = 15_000,
)

@Serializable
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String)

/** Plain HTTP for extensions: no cookies, no cache, a size cap, redirects only to https. */
object BasicHttp {
    const val MAX_BODY = 5 * 1024 * 1024
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
    /** Set by the transport itself: an extension cannot pretend to be another client this way. */
    private val FORBIDDEN_HEADERS = setOf("host", "content-length", "connection", "cookie", "transfer-encoding")

    fun send(request: HttpRequest, userAgent: String): HttpResponse {
        val method = request.method.uppercase()
        require(method in METHODS) { "method $method is not allowed" }
        val uri = URI(request.url)
        require(uri.scheme?.lowercase() == "https") { "http: only https requests are allowed" }
        val host = uri.host?.lowercase()?.trimEnd('.') ?: throw IllegalArgumentException("http: missing host")
        require(!ExtensionRuntime.isPrivate(host)) { "http: local and private addresses are not allowed" }
        val resolved = runCatching { java.net.InetAddress.getAllByName(host) }.getOrNull().orEmpty()
        require(resolved.isNotEmpty() && resolved.none { ExtensionRuntime.isPrivateAddress(it) }) {
            "http: local and private addresses are not allowed"
        }
        val c = uri.toURL().openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.connectTimeout = request.timeout.coerceIn(1_000, 20_000)
        c.readTimeout = request.timeout.coerceIn(1_000, 20_000)
        c.requestMethod = method
        c.setRequestProperty("User-Agent", userAgent)
        request.headers.forEach { (k, v) ->
            val cleanKey = k.replace("\r", "").replace("\n", "")
            val cleanVal = v.replace("\r", "").replace("\n", "")
            if (cleanKey.lowercase() !in FORBIDDEN_HEADERS) c.setRequestProperty(cleanKey, cleanVal)
        }
        if (request.body != null && method != "GET" && method != "HEAD") {
            c.doOutput = true
            c.outputStream.use { it.write(request.body.toByteArray()) }
        }
        val status = c.responseCode
        val stream = if (status >= 400) c.errorStream else runCatching { c.inputStream }.getOrNull()
        val out = ByteArrayOutputStream()
        stream?.use { s ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BODY) throw IllegalStateException("response larger than ${MAX_BODY / 1024 / 1024} MB")
            }
        }
        val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(", ") }
        c.disconnect()
        return HttpResponse(status, headers, out.toString(Charsets.UTF_8))
    }
}
