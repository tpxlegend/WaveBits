package app.wavebits.innertube

import app.wavebits.innertube.models.YouTubeClient
import app.wavebits.innertube.models.YouTubeClient.Companion.WEB_REMIX
import app.wavebits.innertube.models.YouTubeLocale
import app.wavebits.innertube.models.getContinuation
import app.wavebits.innertube.models.getItems
import app.wavebits.innertube.models.response.PlayerResponse
import app.wavebits.innertube.models.response.SearchResponse
import app.wavebits.innertube.pages.SearchPage
import app.wavebits.innertube.pages.SearchResult
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import app.wavebits.innertube.models.AccountInfo
import app.wavebits.innertube.models.NavigationEndpoint
import app.wavebits.innertube.models.SongItem
import app.wavebits.innertube.models.WatchEndpoint
import app.wavebits.innertube.models.YouTubeChannel
import app.wavebits.innertube.pages.AlbumPage
import app.wavebits.innertube.pages.ArtistPage
import app.wavebits.innertube.pages.ExplorePage
import app.wavebits.innertube.pages.HomePage
import app.wavebits.innertube.pages.LibraryPage
import app.wavebits.innertube.pages.MoodAndGenreGroup
import app.wavebits.innertube.pages.NextResult
import app.wavebits.innertube.pages.PlaylistPage
import app.wavebits.innertube.pages.SearchSummary
import app.wavebits.innertube.pages.SearchSummarySection
import app.wavebits.innertube.pages.SearchSuggestions
import java.net.Proxy

/**
 * Parse useful data with [InnerTube] sending requests.
 * WaveBits InnerTube engine.
 *
 * Trimmed for WaveBits: only the pieces the app uses survive — track search
 * (to match a Spotify track to a YouTube video), the player endpoint (to
 * resolve the audio stream) and the NewPipe fallback deobfuscation.
 */
object YouTube {
    private val innerTube = InnerTube()

    var locale: YouTubeLocale
        get() = innerTube.locale
        set(value) {
            innerTube.locale = value
        }
    var visitorData: String?
        get() = innerTube.visitorData
        set(value) {
            innerTube.visitorData = value
        }
    var dataSyncId: String?
        get() = innerTube.dataSyncId
        set(value) {
            innerTube.dataSyncId = value
        }
    var cookie: String?
        get() = innerTube.cookie
        set(value) {
            innerTube.cookie = value
        }
    var proxy: Proxy?
        get() = innerTube.proxy
        set(value) {
            innerTube.proxy = value
        }
    var proxyAuth: String?
        get() = innerTube.proxyAuth
        set(value) {
            innerTube.proxyAuth = value
        }
    var useLoginForBrowse: Boolean
        get() = innerTube.useLoginForBrowse
        set(value) {
            innerTube.useLoginForBrowse = value
        }

    var cacheDir: java.io.File? = null
    var pageId: String?
        get() = innerTube.pageId
        set(value) {
            innerTube.pageId = value
        }
    var authUser: String?
        get() = innerTube.authUser
        set(value) {
            innerTube.authUser = value
        }
    var clientVersion: String?
        get() = innerTube.clientVersion
        set(value) {
            innerTube.clientVersion = value
        }

    fun adoptSessionScope(
        pageId: String? = null,
        dataSyncId: String? = null,
        authUser: String? = null,
        visitorData: String? = null,
        clientVersion: String? = null,
    ) = innerTube.adoptSessionScope(
        pageId = pageId,
        dataSyncId = dataSyncId,
        authUser = authUser,
        visitorData = visitorData,
        clientVersion = clientVersion,
    )

    suspend fun ensureSessionScope(force: Boolean = false): Boolean =
        innerTube.ensureSessionScope(force = force)

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun parseJsonObject(raw: String): kotlinx.serialization.json.JsonObject =
        jsonParser.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject

    suspend fun search(query: String, filter: SearchFilter): Result<SearchResult> = runCatching {
        val response = innerTube.search(WEB_REMIX, query, filter.value).body<SearchResponse>()
        val shelves = response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents
            ?.mapNotNull { it.musicShelfRenderer }
            .orEmpty()
        SearchResult(
            items = shelves.flatMap { shelf ->
                shelf.contents?.getItems()?.mapNotNull { SearchPage.toYTItem(it) } ?: emptyList()
            }.distinctBy { it.id },
            continuation = shelves.firstOrNull { it.continuations != null }
                ?.continuations?.getContinuation()
        )
    }

    suspend fun searchContinuation(continuation: String): Result<SearchResult> = runCatching {
        val response = innerTube.search(WEB_REMIX, continuation = continuation).body<SearchResponse>()
        val items = response.continuationContents?.musicShelfContinuation?.contents
            ?.mapNotNull {
                SearchPage.toYTItem(it.musicResponsiveListItemRenderer)
            } ?: emptyList()
        SearchResult(
            items = items,
            continuation = if (items.isEmpty()) null else response.continuationContents?.musicShelfContinuation?.continuations?.getContinuation()
        )
    }

    suspend fun player(videoId: String, playlistId: String? = null, client: YouTubeClient, signatureTimestamp: Int? = null, poToken: String? = null, authenticated: Boolean = false): Result<PlayerResponse> = runCatching {
        innerTube.player(client, videoId, playlistId, signatureTimestamp, poToken, authenticated).body<PlayerResponse>()
    }

    suspend fun visitorData(): Result<String> = runCatching {
        Json.parseToJsonElement(innerTube.getSwJsData().bodyAsText().substring(5))
            .jsonArray[0]
            .jsonArray[2]
            .jsonArray.first {
                (it as? JsonPrimitive)?.contentOrNull?.let { candidate ->
                    VISITOR_DATA_REGEX.containsMatchIn(candidate)
                } ?: false
            }
            .jsonPrimitive.content
    }

    /**
     * Validates that the current cookie/session tuple resolves an active YouTube account.
     * A 200 response alone is not enough: the endpoint can return a signed-out menu.
     */
    suspend fun validateLogin(): Result<Boolean> = runCatching {
        val body = innerTube.accountMenu(WEB_REMIX).bodyAsText()
        body.contains("activeAccountHeaderRenderer") ||
            (body.contains("accountName") && !body.contains("Sign in"))
    }

    @JvmInline
    value class SearchFilter(val value: String) {
        companion object {
            val FILTER_SONG = SearchFilter("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            val FILTER_VIDEO = SearchFilter("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ALBUM = SearchFilter("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ARTIST = SearchFilter("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_FEATURED_PLAYLIST = SearchFilter("EgeKAQQoADgBagwQDhAKEAMQBRAJEA4%3D")
        }
    }

    suspend fun accounts(): Result<List<YouTubeChannel>> = runCatching {
        val raw = innerTube.accountMenu(WEB_REMIX).bodyAsText()
        app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseAccounts(parseJsonObject(raw))
    }

    suspend fun accountInfo(): Result<AccountInfo> = runCatching {
        val raw = innerTube.accountMenu(WEB_REMIX).bodyAsText()
        app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseAccountInfo(parseJsonObject(raw))
            ?: AccountInfo()
    }

    suspend fun queue(videoIds: List<String>): Result<List<SongItem>> = runCatching {
        if (videoIds.isEmpty()) return@runCatching emptyList()
        val nextRes = next(WatchEndpoint(videoId = videoIds.first())).getOrNull()
        nextRes?.items.orEmpty()
    }

    suspend fun next(endpoint: WatchEndpoint? = null, continuation: String? = null): Result<NextResult> = runCatching {
        val raw = innerTube.next(
            client = WEB_REMIX,
            videoId = endpoint?.videoId,
            playlistId = endpoint?.playlistId,
            playlistSetVideoId = endpoint?.playlistSetVideoId,
            index = endpoint?.index,
            params = endpoint?.params,
            continuation = continuation,
            setLogin = useLoginForBrowse,
        ).bodyAsText()
        val parsed = app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseNext(parseJsonObject(raw), endpoint)
        if (continuation == null && parsed.items.size <= 1 && !endpoint?.videoId.isNullOrBlank()) {
            val vid = endpoint!!.videoId!!
            val radioPlaylistId = parsed.endpoint?.playlistId?.takeIf { it.isNotBlank() && it != endpoint.playlistId }
                ?: if (endpoint.playlistId == null) "RDAMVM$vid" else null
            if (!radioPlaylistId.isNullOrBlank()) {
                val radioRaw = runCatching {
                    innerTube.next(
                        client = WEB_REMIX,
                        videoId = vid,
                        playlistId = radioPlaylistId,
                        setLogin = useLoginForBrowse,
                    ).bodyAsText()
                }.getOrNull()
                if (radioRaw != null) {
                    val radioParsed = app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseNext(
                        parseJsonObject(radioRaw),
                        WatchEndpoint(videoId = vid, playlistId = radioPlaylistId),
                    )
                    if (radioParsed.items.size > parsed.items.size) {
                        return@runCatching radioParsed.copy(
                            lyricsEndpoint = radioParsed.lyricsEndpoint ?: parsed.lyricsEndpoint,
                        )
                    }
                }
            }
        }
        parsed
    }

    suspend fun playlist(playlistId: String): Result<PlaylistPage> = runCatching {
        val cleanId = if (playlistId == "FEmusic_liked_videos") "LM" else playlistId.removePrefix("VL")
        val browseId = when {
            playlistId == "FEmusic_liked_videos" -> "FEmusic_liked_videos"
            playlistId.startsWith("VL") -> playlistId
            else -> "VL$cleanId"
        }
        val raw = runCatching {
            innerTube.browse(
                client = WEB_REMIX,
                browseId = browseId,
                setLogin = useLoginForBrowse || cleanId == "LM" || cleanId == "SE",
            ).bodyAsText()
        }.getOrNull()
        var parsed = raw?.let {
            app.wavebits.innertube.pages.WaveBitsInnertubeParser.parsePlaylist(parseJsonObject(it), cleanId)
        }
        if ((parsed == null || parsed.songs.isEmpty()) && cleanId == "LM" && browseId != "FEmusic_liked_videos") {
            val altRaw = runCatching {
                innerTube.browse(
                    client = WEB_REMIX,
                    browseId = "FEmusic_liked_videos",
                    setLogin = true,
                ).bodyAsText()
            }.getOrNull()
            if (altRaw != null) {
                parsed = app.wavebits.innertube.pages.WaveBitsInnertubeParser.parsePlaylist(parseJsonObject(altRaw), "LM")
            }
        }
        if (parsed != null && parsed.songs.isNotEmpty()) {
            return@runCatching parsed
        }
        // Fallback for radio/auto-mix playlists (RDCLAK..., RDTMAK..., RDEM..., RDAMVM...) via next endpoint
        val nextRes = next(WatchEndpoint(playlistId = cleanId)).getOrNull()
        val songs = nextRes?.items.orEmpty()
        PlaylistPage(
            playlist = parsed?.playlist?.takeIf { it.title != "Playlist" } ?: app.wavebits.innertube.models.PlaylistItem(
                id = cleanId,
                title = parsed?.playlist?.title?.takeIf { it.isNotBlank() && it != "Playlist" } ?: "YouTube Music Mix",
                author = app.wavebits.innertube.models.Artist("YouTube Music", null),
                songCountText = "${songs.size} songs",
                thumbnail = parsed?.playlist?.thumbnail ?: songs.firstOrNull()?.thumbnail,
                playEndpoint = WatchEndpoint(playlistId = cleanId),
                shuffleEndpoint = null,
                radioEndpoint = null,
            ),
            songs = songs,
            continuation = nextRes?.continuation,
        )
    }

    suspend fun playlistContinuation(continuation: String, cleanId: String = ""): Result<PlaylistPage> = runCatching {
        val raw = innerTube.browse(
            client = WEB_REMIX,
            continuation = continuation,
            setLogin = true,
        ).bodyAsText()
        app.wavebits.innertube.pages.WaveBitsInnertubeParser.parsePlaylist(
            parseJsonObject(raw),
            cleanId.ifBlank { "LM" },
        )
    }

    suspend fun album(browseId: String): Result<AlbumPage> = runCatching {
        val raw = innerTube.browse(
            client = WEB_REMIX,
            browseId = browseId,
            setLogin = useLoginForBrowse,
        ).bodyAsText()
        app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseAlbum(parseJsonObject(raw), browseId)
    }

    suspend fun artist(browseId: String): Result<ArtistPage> = runCatching {
        val raw = innerTube.browse(
            client = WEB_REMIX,
            browseId = browseId,
            setLogin = useLoginForBrowse,
        ).bodyAsText()
        app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseArtist(parseJsonObject(raw), browseId)
    }

    suspend fun searchSummary(query: String): Result<SearchSummary> = runCatching {
        val res = search(query, SearchFilter.FILTER_SONG).getOrNull()
        SearchSummary(listOf(SearchSummarySection(items = res?.items.orEmpty())))
    }

    suspend fun searchSuggestions(query: String): Result<SearchSuggestions> = runCatching {
        val raw = innerTube.getSearchSuggestions(WEB_REMIX, query).bodyAsText()
        app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseSearchSuggestions(parseJsonObject(raw))
    }

    suspend fun home(continuation: String? = null, params: String? = null): Result<HomePage> = runCatching {
        val raw = innerTube.browse(
            client = WEB_REMIX,
            browseId = if (continuation == null) "FEmusic_home" else null,
            params = if (continuation == null) params else null,
            continuation = continuation,
            setLogin = useLoginForBrowse,
        ).bodyAsText()
        val json = parseJsonObject(raw)
        var page = if (continuation != null) {
            app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseHomeContinuation(json)
        } else {
            app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseHome(json)
        }
        // If initial FEmusic_home returned a reload/next continuation token with 0 sections, follow it immediately
        if (continuation == null && page.sections.isEmpty() && !page.continuation.isNullOrBlank()) {
            val contRaw = runCatching {
                innerTube.browse(
                    client = WEB_REMIX,
                    continuation = page.continuation,
                    setLogin = useLoginForBrowse,
                ).bodyAsText()
            }.getOrNull()
            if (contRaw != null) {
                val contPage = app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseHomeContinuation(parseJsonObject(contRaw))
                if (contPage.sections.isNotEmpty()) {
                    page = page.copy(
                        sections = contPage.sections,
                        continuation = contPage.continuation,
                    )
                }
            }
        }
        // If authenticated FEmusic_home still returned empty sections, fallback to unauthenticated FEmusic_home so home is never blank
        if (continuation == null && page.sections.isEmpty() && useLoginForBrowse) {
            val anonRaw = runCatching {
                innerTube.browse(
                    client = WEB_REMIX,
                    browseId = "FEmusic_home",
                    params = params,
                    setLogin = false,
                ).bodyAsText()
            }.getOrNull()
            if (anonRaw != null) {
                val anonPage = app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseHome(parseJsonObject(anonRaw))
                if (anonPage.sections.isNotEmpty()) {
                    page = anonPage
                }
            }
        }
        page
    }

    /**
     * WaveBits repository helper:
     * Browse arbitrary shelf endpoints (`FEmusic_new_releases`, `FEmusic_explore`,
     * `FEmusic_mixed_for_you`, `FEmusic_moods_and_genres_category`).
     */
    suspend fun browseShelves(browseId: String, params: String? = null): Result<HomePage> = runCatching {
        val raw = innerTube.browse(
            client = WEB_REMIX,
            browseId = browseId,
            params = params,
            setLogin = useLoginForBrowse,
        ).bodyAsText()
        app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseHome(parseJsonObject(raw))
    }

    /**
     * WaveBits history helper:
     * Fetch recently played songs from `FEmusic_history`.
     */
    suspend fun history(): Result<List<SongItem>> = runCatching {
        if (!useLoginForBrowse || cookie.isNullOrBlank()) return@runCatching emptyList()
        val raw = innerTube.browse(
            client = WEB_REMIX,
            browseId = "FEmusic_history",
            setLogin = true,
        ).bodyAsText()
        app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseHistorySongs(parseJsonObject(raw))
    }

    suspend fun explore(): Result<ExplorePage> = runCatching {
        val raw = innerTube.browse(
            client = WEB_REMIX,
            browseId = "FEmusic_explore",
            setLogin = useLoginForBrowse,
        ).bodyAsText()
        app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseExplore(parseJsonObject(raw))
    }

    suspend fun moodAndGenres(): Result<List<MoodAndGenreGroup>> = runCatching {
        val raw = innerTube.browse(
            client = WEB_REMIX,
            browseId = "FEmusic_moods_and_genres",
            setLogin = useLoginForBrowse,
        ).bodyAsText()
        app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseMoodAndGenres(parseJsonObject(raw))
    }

    suspend fun library(
        browseId: String = "FEmusic_library_landing",
        continuation: String? = null,
        maxContinuationPages: Int = 10,
    ): Result<LibraryPage> = runCatching {
        val raw = innerTube.browse(
            client = WEB_REMIX,
            browseId = if (continuation == null) browseId else null,
            continuation = continuation,
            setLogin = true,
        ).bodyAsText()
        val firstPage = app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseLibrary(parseJsonObject(raw))
        if (continuation != null) {
            return@runCatching firstPage
        }
        val combined = firstPage.items.toMutableList()
        val seenKeys = combined.mapTo(HashSet()) { "${it::class.simpleName}:${it.id}" }
        val seenTokens = HashSet<String>()
        var nextToken = firstPage.continuation
        var pagesFetched = 0
        while (!nextToken.isNullOrBlank() && seenTokens.add(nextToken) && pagesFetched < maxContinuationPages) {
            pagesFetched++
            val nextPage = runCatching {
                val contRaw = innerTube.browse(
                    client = WEB_REMIX,
                    continuation = nextToken,
                    setLogin = true,
                ).bodyAsText()
                app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseLibrary(parseJsonObject(contRaw))
            }.getOrNull() ?: break
            var addedAny = false
            for (item in nextPage.items) {
                if (seenKeys.add("${item::class.simpleName}:${item.id}")) {
                    combined.add(item)
                    addedAny = true
                }
            }
            nextToken = if (addedAny) nextPage.continuation else null
        }
        LibraryPage(
            items = combined,
            continuation = nextToken,
        )
    }

    suspend fun createPlaylist(title: String, description: String? = null): String? {
        if (!useLoginForBrowse || cookie.isNullOrBlank()) return null
        return runCatching {
            val raw = innerTube.createPlaylist(WEB_REMIX, title, description).bodyAsText()
            (parseJsonObject(raw)["playlistId"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        if (!useLoginForBrowse || cookie.isNullOrBlank()) return
        runCatching { innerTube.renamePlaylist(WEB_REMIX, playlistId, name) }
    }

    suspend fun deletePlaylist(playlistId: String) {
        if (!useLoginForBrowse || cookie.isNullOrBlank()) return
        runCatching { innerTube.deletePlaylist(WEB_REMIX, playlistId) }
    }

    suspend fun likePlaylist(playlistId: String, like: Boolean) {
        if (!useLoginForBrowse || cookie.isNullOrBlank()) return
        runCatching { innerTube.likePlaylist(WEB_REMIX, playlistId, like) }
    }

    suspend fun likeVideo(videoId: String, like: Boolean) {
        if (!useLoginForBrowse || cookie.isNullOrBlank()) return
        runCatching { innerTube.likeVideo(WEB_REMIX, videoId, like) }
    }

    suspend fun subscribeChannel(channelId: String, subscribe: Boolean) {
        if (!useLoginForBrowse || cookie.isNullOrBlank()) return
        runCatching { innerTube.subscribeChannel(WEB_REMIX, channelId, subscribe) }
    }

    suspend fun addToPlaylist(playlistId: String, videoId: String) {
        if (!useLoginForBrowse || cookie.isNullOrBlank()) return
        runCatching { innerTube.addToPlaylist(WEB_REMIX, playlistId, videoId) }
    }

    suspend fun removeFromPlaylist(playlistId: String, videoId: String, setVideoId: String? = null) {
        if (!useLoginForBrowse || cookie.isNullOrBlank()) return
        runCatching { innerTube.removeFromPlaylist(WEB_REMIX, playlistId, videoId, setVideoId) }
    }
    suspend fun transcript(videoId: String): Result<String?> = Result.success(null)
    suspend fun lyrics(endpoint: NavigationEndpoint): Result<String?> = runCatching {
        val bId = endpoint.browseEndpoint?.browseId ?: return@runCatching null
        val raw = innerTube.browse(
            client = WEB_REMIX,
            browseId = bId,
            params = endpoint.browseEndpoint.params,
        ).bodyAsText()
        app.wavebits.innertube.pages.WaveBitsInnertubeParser.parseLyrics(parseJsonObject(raw))
    }

    private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")

    fun getNewPipeStreamUrls(videoId: String): List<Pair<Int, String>> {
        return NewPipeExtractor.newPipePlayer(videoId)
    }

    suspend fun newPipePlayer(
        videoId: String,
        tempRes: PlayerResponse,
    ): PlayerResponse? {
        if (tempRes.playabilityStatus.status != "OK") {
            return null
        }

        val streamsList = getNewPipeStreamUrls(videoId)
        if (streamsList.isEmpty()) return null

        val decodedSigResponse = tempRes.copy(
            streamingData = tempRes.streamingData?.copy(
                formats = tempRes.streamingData.formats?.map { format ->
                    format.copy(
                        url = streamsList.find { it.first == format.itag }?.second ?: format.url,
                    )
                },
                adaptiveFormats = tempRes.streamingData.adaptiveFormats.map { adaptiveFormat ->
                    adaptiveFormat.copy(
                        url = streamsList.find { it.first == adaptiveFormat.itag }?.second ?: adaptiveFormat.url,
                    )
                },
            ),
        )

        val urlList = (
            decodedSigResponse.streamingData?.adaptiveFormats?.mapNotNull { it.url }?.toMutableList() ?: mutableListOf()
        ).apply {
            decodedSigResponse.streamingData?.formats?.mapNotNull { it.url }?.let { addAll(it) }
        }

        return if (urlList.isNotEmpty()) {
            decodedSigResponse
        } else {
            null
        }
    }
}
