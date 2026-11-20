package app.wavebits.innertube

import app.wavebits.innertube.models.Context
import app.wavebits.innertube.models.YouTubeClient
import app.wavebits.innertube.models.YouTubeLocale
import app.wavebits.innertube.models.body.*
import app.wavebits.innertube.utils.parseCookieString
import app.wavebits.innertube.utils.sha1
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.net.Proxy
import java.io.IOException
import kotlinx.coroutines.delay
import java.util.*
import kotlin.io.encoding.Base64
import timber.log.Timber
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Provide access to InnerTube endpoints.
 * For making HTTP requests, not parsing response.
 */
@OptIn(ExperimentalEncodingApi::class)
class InnerTube {
    private var httpClient = createClient()

    var locale = YouTubeLocale(
        gl = Locale.getDefault().country,
        hl = Locale.getDefault().toLanguageTag()
    )
    var visitorData: String? = null
    var dataSyncId: String? = null
    var pageId: String? = null
    var authUser: String? = null
    var clientVersion: String? = null
    var cookie: String? = null
        set(value) {
            if (field != value) {
                resolvedScopeCookie = null
            }
            field = value
            cookieMap = if (value == null) emptyMap() else parseCookieString(value)
        }
    private var cookieMap = emptyMap<String, String>()
    @Volatile
    private var resolvedScopeCookie: String? = null

    var proxy: Proxy? = null
        set(value) {
            field = value
            httpClient.close()
            httpClient = createClient()
        }
    
    var proxyAuth: String? = null

    var useLoginForBrowse: Boolean = false

    val cleanDataSyncId: String?
        get() {
            val pid = pageId?.trim()?.takeIf { it.isNotEmpty() }
            if (pid != null) return pid
            return normalizeDataSyncId(dataSyncId)
        }

    fun adoptSessionScope(
        pageId: String? = null,
        dataSyncId: String? = null,
        authUser: String? = null,
        visitorData: String? = null,
        clientVersion: String? = null,
    ) {
        val cleanPageId = pageId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanSync = normalizeDataSyncId(dataSyncId) ?: cleanPageId
        val cleanAuth = authUser?.trim()?.takeIf { it.isNotEmpty() }
        val cleanVisitor = visitorData?.trim()?.takeIf { it.isNotEmpty() }
        val cleanVer = clientVersion?.trim()?.takeIf { it.isNotEmpty() }

        this.pageId = cleanPageId
        if (cleanSync != null) this.dataSyncId = cleanSync
        if (cleanAuth != null) this.authUser = cleanAuth
        if (cleanVisitor != null) this.visitorData = cleanVisitor
        if (cleanVer != null) this.clientVersion = cleanVer
        if (!cookie.isNullOrBlank() && (!this.visitorData.isNullOrBlank() || !this.dataSyncId.isNullOrBlank())) {
            resolvedScopeCookie = cookie
        }
    }

    /**
     * Fetches the `https://music.youtube.com/` shell with the user's cookies and `SAPISIDHASH`
     * to extract the session-bound `DELEGATED_SESSION_ID`, `DATASYNC_ID`, `SESSION_INDEX`,
     * `VISITOR_DATA`, and `INNERTUBE_CLIENT_VERSION`, matching BitChord's `fetchSessionScope`.
     */
    suspend fun ensureSessionScope(force: Boolean = false): Boolean {
        val rawCookie = cookie?.takeIf { it.isNotBlank() } ?: return false
        val hasAuth = cookieMap.containsKey("SAPISID") ||
            cookieMap.containsKey("__Secure-3PAPISID") ||
            cookieMap.containsKey("__Secure-1PAPISID")
        if (!hasAuth) return false
        if (!force && resolvedScopeCookie == rawCookie && !visitorData.isNullOrBlank() && !cleanDataSyncId.isNullOrBlank()) {
            return true
        }
        return runCatching {
            val origin = YouTubeClient.ORIGIN_YOUTUBE_MUSIC
            val currentTime = System.currentTimeMillis() / 1000
            val authParts = buildList {
                cookieMap["SAPISID"]?.let { add("SAPISIDHASH ${currentTime}_${sha1("$currentTime $it $origin")}") }
                cookieMap["__Secure-3PAPISID"]?.let { add("SAPISID3PHASH ${currentTime}_${sha1("$currentTime $it $origin")}") }
                cookieMap["__Secure-1PAPISID"]?.let { add("SAPISID1PHASH ${currentTime}_${sha1("$currentTime $it $origin")}") }
            }
            val html = httpClient.get("${YouTubeClient.ORIGIN_YOUTUBE_MUSIC}/") {
                headers {
                    append(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    append(HttpHeaders.UserAgent, YouTubeClient.USER_AGENT_WEB)
                    append("Origin", origin)
                    append("Referer", "$origin/")
                    append("Cookie", rawCookie)
                    if (authParts.isNotEmpty()) {
                        append("Authorization", authParts.joinToString(" "))
                    }
                    append("X-Goog-AuthUser", authUser?.trim()?.takeIf { it.isNotEmpty() } ?: "0")
                }
            }.bodyAsText()

            val loggedIn = LOGGED_IN_REGEX.find(html)?.groupValues?.getOrNull(1)?.equals("true", ignoreCase = true) == true
            val pageIdFromHtml = DELEGATED_SESSION_REGEX.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            val rawSyncFromHtml = DATASYNC_ID_REGEX.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            val normSync = normalizeDataSyncId(rawSyncFromHtml)
            val resolvedSync = normSync ?: pageIdFromHtml
            val sessionIdx = SESSION_INDEX_REGEX.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            val visitor = VISITOR_DATA_REGEX_HTML.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            val cver = CLIENT_VERSION_REGEX.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

            if (loggedIn || resolvedSync != null || visitor != null) {
                pageId = pageIdFromHtml
                if (resolvedSync != null) dataSyncId = resolvedSync
                if (sessionIdx != null) authUser = sessionIdx
                if (visitor != null) visitorData = visitor
                if (cver != null) clientVersion = cver
                resolvedScopeCookie = rawCookie
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    companion object {
        private val DELEGATED_SESSION_REGEX = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]+)"""")
        private val DATASYNC_ID_REGEX = Regex(""""DATASYNC_ID"\s*:\s*"([^"]+)"""")
        private val SESSION_INDEX_REGEX = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)"?""")
        private val VISITOR_DATA_REGEX_HTML = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
        private val CLIENT_VERSION_REGEX = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")
        private val LOGGED_IN_REGEX = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")

        /**
         * BitChord `normalizeDataSyncId`:
         * `ytcfg.DATASYNC_ID` is formatted as `<delegatedOrPrimary>||<userId>` or `||<id>`.
         * Prefers the non-empty token after `||`, falling back to the token before `||`.
         */
        fun normalizeDataSyncId(raw: String?): String? {
            val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
            if (!value.contains("||")) return value
            return value.substringAfter("||").trim().takeIf { it.isNotBlank() }
                ?: value.substringBefore("||").trim().takeIf { it.isNotBlank() }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createClient() = HttpClient(OkHttp) {
        expectSuccess = true

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
            })
        }

        install(ContentEncoding) {
            gzip(0.9F)
            deflate(0.8F)
        }

        // Enhanced network configuration for better performance
        engine {
            config {
                // Connection pool settings for better connection reuse
                connectionPool(
                    okhttp3.ConnectionPool(
                        10, // maxIdleConnections
                        5, // keepAliveDuration
                        java.util.concurrent.TimeUnit.MINUTES
                    )
                )
                
                // Timeout configurations
                connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                
                // Enable HTTP/2 for better performance
                protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                
                // Retry on connection failure
                retryOnConnectionFailure(true)
                
                // Cache configuration for better performance
                cache(
                    okhttp3.Cache(
                        directory = java.io.File(System.getProperty("java.io.tmpdir"), "http_cache"),
                        maxSize = 50L * 1024L * 1024L // 50 MB
                    )
                )
                
                // Apply proxy configuration
                this@InnerTube.proxy?.let { proxyConfig ->
                    proxy(proxyConfig)
                }
                
                // Apply proxy authentication
                this@InnerTube.proxyAuth?.let { auth ->
                    proxyAuthenticator { _, response ->
                        response.request.newBuilder()
                            .header("Proxy-Authorization", auth)
                            .build()
                    }
                }
            }
        }

        // Request timeout configuration
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 30000
            socketTimeoutMillis = 60000
        }

        defaultRequest {
            url(YouTubeClient.API_URL_YOUTUBE_MUSIC)
            header("Accept", "application/json")
            // Use the user's locale instead of hardcoding en-US so region-specific
            // catalogs and language-matched recommendations are returned.
            header("Accept-Language", "${locale.hl},${locale.gl};q=0.9,en;q=0.8")
            header("Cache-Control", "no-cache")
        }
    }

    private fun HttpRequestBuilder.ytClient(
        client: YouTubeClient,
        setLogin: Boolean = false,
        includePageId: Boolean = true,
    ) {
        contentType(ContentType.Application.Json)
        headers {
            append("X-Goog-Api-Format-Version", "1")
            append("X-YouTube-Client-Name", client.clientId /* Not a typo. The Client-Name header does contain the client id. */)
            val resolvedClientVersion = if (client.clientName == "WEB_REMIX" && !clientVersion.isNullOrBlank()) {
                clientVersion!!
            } else {
                client.clientVersion
            }
            append("X-YouTube-Client-Version", resolvedClientVersion)
            client.origin?.let {
                append("Origin", it)
                append("X-Origin", it)
                append("Referer", "$it/")
            }
            visitorData?.let { append("X-Goog-Visitor-Id", it) }
            if (client.loginSupported) {
                cookie?.takeIf { it.isNotBlank() }?.let { rawCookie ->
                    val origin = client.origin ?: YouTubeClient.ORIGIN_YOUTUBE_MUSIC
                    val currentTime = System.currentTimeMillis() / 1000
                    val authParts = buildList {
                        cookieMap["SAPISID"]?.let { add("SAPISIDHASH ${currentTime}_${sha1("$currentTime $it $origin")}") }
                        cookieMap["__Secure-3PAPISID"]?.let { add("SAPISID3PHASH ${currentTime}_${sha1("$currentTime $it $origin")}") }
                        cookieMap["__Secure-1PAPISID"]?.let { add("SAPISID1PHASH ${currentTime}_${sha1("$currentTime $it $origin")}") }
                    }
                    if (setLogin && authParts.isNotEmpty()) {
                        append("cookie", rawCookie)
                        append("X-Goog-AuthUser", authUser?.trim()?.takeIf { it.isNotEmpty() } ?: "0")
                        if (includePageId) {
                            pageId?.takeIf { it.isNotBlank() }?.let { append("X-Goog-PageId", it) }
                        }
                        append("Authorization", authParts.joinToString(" "))
                    } else if (authParts.isEmpty()) {
                        // Real YouTube Guest session cookies (VISITOR_INFO1_live, YSC, PREF, SOCS, CONSENT)
                        append("cookie", rawCookie)
                    }
                }
            }
        }
        userAgent(client.userAgent)
        parameter("prettyPrint", false)
    }

    /**
     * Simple retry wrapper for transient IO errors (socket aborts, timeouts).
     * Retries the given block up to [maxAttempts] times with exponential backoff.
     * Cancellation is respected since [delay] will throw if the coroutine is cancelled.
     */
    private suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelay: Long = 500L,
        factor: Double = 2.0,
        block: suspend () -> T,
    ): T {
        var currentDelay = initialDelay
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                attempt++
                if (attempt >= maxAttempts) throw e
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong()
            }
        }
    }

    private fun isPrivateLibraryBrowseId(browseId: String?): Boolean {
        if (browseId == null) return false
        return browseId == "FEmusic_library_landing" ||
            browseId == "FEmusic_liked_playlists" ||
            browseId == "FEmusic_liked_albums" ||
            browseId == "FEmusic_liked_videos" ||
            browseId == "FEmusic_library_corpus_track_artists" ||
            browseId == "FEmusic_library_corpus_artists" ||
            browseId == "FEmusic_history" ||
            browseId == "FEmusic_mixed_for_you" ||
            browseId == "VLLM" ||
            browseId == "VLSE"
    }

    suspend fun browse(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ) = withRetry {
        val wantLogin = setLogin || useLoginForBrowse
        try {
            httpClient.post("browse") {
                ytClient(client, setLogin = wantLogin)
                setBody(
                    BrowseBody(
                        context = client.toContext(
                            locale,
                            visitorData,
                            if (wantLogin) cleanDataSyncId else null,
                        ),
                        browseId = if (continuation == null) browseId else null,
                        params = if (continuation == null) params else null,
                    )
                )
                if (continuation != null) {
                    parameter("continuation", continuation)
                    parameter("ctoken", continuation)
                    parameter("type", "next")
                }
            }
        } catch (e: ResponseException) {
            // If a delegated pageId/dataSyncId caused 400/401/403, retry without onBehalfOfUser/pageId first
            if (wantLogin && (!pageId.isNullOrBlank() || cleanDataSyncId != null)) {
                try {
                    return@withRetry httpClient.post("browse") {
                        ytClient(client, setLogin = true, includePageId = false)
                        setBody(
                            BrowseBody(
                                context = client.toContext(locale, visitorData, null),
                                browseId = if (continuation == null) browseId else null,
                                params = if (continuation == null) params else null,
                            )
                        )
                        if (continuation != null) {
                            parameter("continuation", continuation)
                            parameter("ctoken", continuation)
                            parameter("type", "next")
                        }
                    }
                } catch (_: Exception) {
                }
            }
            // If authenticated browse fails on a non-private endpoint (e.g. FEmusic_home), fall back to unauthenticated browse
            if (wantLogin && !isPrivateLibraryBrowseId(browseId)) {
                httpClient.post("browse") {
                    ytClient(client, setLogin = false)
                    setBody(
                        BrowseBody(
                            context = client.toContext(locale, visitorData, null),
                            browseId = if (continuation == null) browseId else null,
                            params = if (continuation == null) params else null,
                        )
                    )
                    if (continuation != null) {
                        parameter("continuation", continuation)
                        parameter("ctoken", continuation)
                        parameter("type", "next")
                    }
                }
            } else {
                throw e
            }
        }
    }

    suspend fun next(
        client: YouTubeClient,
        videoId: String? = null,
        playlistId: String? = null,
        playlistSetVideoId: String? = null,
        index: Int? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ) = withRetry {
        val wantLogin = setLogin || useLoginForBrowse
        try {
            httpClient.post("next") {
                ytClient(client, setLogin = wantLogin)
                setBody(
                    NextBody(
                        context = client.toContext(
                            locale,
                            visitorData,
                            if (wantLogin) cleanDataSyncId else null,
                        ),
                        videoId = videoId,
                        playlistId = playlistId,
                        playlistSetVideoId = playlistSetVideoId,
                        index = index,
                        params = params,
                        continuation = continuation,
                    )
                )
                if (continuation != null) {
                    parameter("continuation", continuation)
                    parameter("ctoken", continuation)
                }
            }
        } catch (e: ResponseException) {
            if (wantLogin) {
                httpClient.post("next") {
                    ytClient(client, setLogin = false)
                    setBody(
                        NextBody(
                            context = client.toContext(locale, visitorData, null),
                            videoId = videoId,
                            playlistId = playlistId,
                            playlistSetVideoId = playlistSetVideoId,
                            index = index,
                            params = params,
                            continuation = continuation,
                        )
                    )
                    if (continuation != null) {
                        parameter("continuation", continuation)
                        parameter("ctoken", continuation)
                    }
                }
            } else {
                throw e
            }
        }
    }

    suspend fun getSearchSuggestions(
        client: YouTubeClient,
        input: String,
    ) = withRetry {
        httpClient.post("music/get_search_suggestions") {
            ytClient(client, setLogin = useLoginForBrowse)
            setBody(
                GetSearchSuggestionsBody(
                    context = client.toContext(locale, visitorData, null),
                    input = input,
                )
            )
        }
    }

    suspend fun search(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
    ) = withRetry {
        httpClient.post("search") {
            ytClient(client, setLogin = useLoginForBrowse)
            setBody(
                SearchBody(
                    context = client.toContext(
                        locale,
                        visitorData,
                        null,
                    ),
                    query = query,
                    params = params
                )
            )
            parameter("continuation", continuation)
            parameter("ctoken", continuation)
        }
    }

    suspend fun player(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String? = null,
        authenticated: Boolean = false,
    ) = withRetry {
        httpClient.post(client.playerApiUrl) {
            // WaveBits's critical distinction: native clients are anonymous.
            // Cookies belong only on a browser-shaped authenticated fallback.
            ytClient(client, setLogin = authenticated)
            setBody(
                PlayerBody(
                    // WaveBits authenticates with Cookie + SAPISIDHASH only. A
                    // delegated onBehalfOfUser id can bind this request to the
                    // wrong channel and is intentionally omitted.
                    context = client.toContext(locale, visitorData, null).let {
                        if (client.isEmbedded) {
                            it.copy(
                                thirdParty = Context.ThirdParty(
                                    embedUrl = "https://www.youtube.com/watch?v=${videoId}"
                                )
                            )
                        } else it
                    },
                    videoId = videoId,
                    playlistId = playlistId,
                    playbackContext = if (client.useSignatureTimestamp && signatureTimestamp != null) {
                        PlayerBody.PlaybackContext(
                            PlayerBody.PlaybackContext.ContentPlaybackContext(
                                signatureTimestamp
                            )
                        )
                    } else null,
                    serviceIntegrityDimensions = if (client.useWebPoTokens && poToken != null) {
                        PlayerBody.ServiceIntegrityDimensions(poToken)
                    } else null,
                )
            )
        }
    }

    suspend fun getSwJsData() = withRetry { httpClient.get("https://music.youtube.com/sw.js_data") }

    /** Authenticated endpoint used to reject incomplete/stale cookie captures. */
    suspend fun accountMenu(client: YouTubeClient) = withRetry {
        httpClient.post("account/account_menu") {
            ytClient(client, setLogin = true)
            setBody(AccountMenuBody(client.toContext(locale, visitorData, null)))
        }
    }

    suspend fun likeVideo(client: YouTubeClient, videoId: String, like: Boolean) = withRetry {
        httpClient.post(if (like) "like/like" else "like/removelike") {
            ytClient(client, setLogin = true)
            setBody(
                LikeTargetBody(
                    context = client.toContext(locale, visitorData, cleanDataSyncId),
                    target = LikeTargetBody.Target(videoId = videoId),
                )
            )
        }
    }

    suspend fun likePlaylist(client: YouTubeClient, playlistId: String, like: Boolean) = withRetry {
        httpClient.post(if (like) "like/like" else "like/removelike") {
            ytClient(client, setLogin = true)
            setBody(
                LikeTargetBody(
                    context = client.toContext(locale, visitorData, cleanDataSyncId),
                    target = LikeTargetBody.Target(playlistId = playlistId.removePrefix("VL")),
                )
            )
        }
    }

    suspend fun createPlaylist(client: YouTubeClient, title: String, description: String? = null) = withRetry {
        httpClient.post("playlist/create") {
            ytClient(client, setLogin = true)
            setBody(
                CreatePlaylistBody(
                    context = client.toContext(locale, visitorData, cleanDataSyncId),
                    title = title,
                    description = description.orEmpty(),
                )
            )
        }
    }

    suspend fun renamePlaylist(client: YouTubeClient, playlistId: String, name: String) = withRetry {
        httpClient.post("browse/edit_playlist") {
            ytClient(client, setLogin = true)
            setBody(
                EditPlaylistBody(
                    context = client.toContext(locale, visitorData, cleanDataSyncId),
                    playlistId = playlistId.removePrefix("VL"),
                    actions = listOf(
                        EditPlaylistBody.Action(
                            action = "ACTION_SET_PLAYLIST_NAME",
                            playlistName = name,
                        )
                    ),
                )
            )
        }
    }

    suspend fun addToPlaylist(client: YouTubeClient, playlistId: String, videoId: String) = withRetry {
        httpClient.post("browse/edit_playlist") {
            ytClient(client, setLogin = true)
            setBody(
                EditPlaylistBody(
                    context = client.toContext(locale, visitorData, cleanDataSyncId),
                    playlistId = playlistId.removePrefix("VL"),
                    actions = listOf(
                        EditPlaylistBody.Action(
                            action = "ACTION_ADD_VIDEO",
                            addedVideoId = videoId,
                        )
                    ),
                )
            )
        }
    }

    suspend fun removeFromPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
        setVideoId: String? = null,
    ) = withRetry {
        httpClient.post("browse/edit_playlist") {
            ytClient(client, setLogin = true)
            setBody(
                EditPlaylistBody(
                    context = client.toContext(locale, visitorData, cleanDataSyncId),
                    playlistId = playlistId.removePrefix("VL"),
                    actions = listOf(
                        EditPlaylistBody.Action(
                            action = "ACTION_REMOVE_VIDEO",
                            removedVideoId = videoId,
                            setVideoId = setVideoId?.takeIf { it.isNotBlank() },
                        )
                    ),
                )
            )
        }
    }

    suspend fun deletePlaylist(client: YouTubeClient, playlistId: String) = withRetry {
        httpClient.post("playlist/delete") {
            ytClient(client, setLogin = true)
            setBody(
                DeletePlaylistBody(
                    context = client.toContext(locale, visitorData, cleanDataSyncId),
                    playlistId = playlistId.removePrefix("VL"),
                )
            )
        }
    }

    suspend fun subscribeChannel(client: YouTubeClient, channelId: String, subscribe: Boolean) = withRetry {
        httpClient.post(if (subscribe) "subscription/subscribe" else "subscription/unsubscribe") {
            ytClient(client, setLogin = true)
            setBody(
                SubscribeBody(
                    context = client.toContext(locale, visitorData, cleanDataSyncId),
                    channelIds = listOf(channelId.removePrefix("MPLA")),
                )
            )
        }
    }

    
    private suspend fun returnYouTubeDislike(videoId: String) = withRetry {
        httpClient.get("https://returnyoutubedislikeapi.com/Votes?videoId=$videoId") {
            contentType(ContentType.Application.Json)
        }
    }


}
