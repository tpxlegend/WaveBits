// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
package app.wavebits.host.android

import android.content.Context
import app.wavebits.api.*
import app.wavebits.host.android.jiosaavn.JioSaavnWaveBitsApi
import app.wavebits.host.android.youtube.YouTubeAccount
import app.wavebits.host.android.youtube.YouTubeWaveBitsApi
import app.wavebits.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import app.wavebits.api.Device
import app.wavebits.api.LibraryFilter
import app.wavebits.api.LibraryItem
import app.wavebits.api.LibrarySort
import app.wavebits.api.Page
import app.wavebits.api.SavedTrack

enum class BackendMode {
    SPOTIFY,
    YOUTUBE,
    JIOSAAVN,
}

/**
 * The app's runtime session:
 * - Standard Mode: one WebView host and one [SpotifyApi] for signed-in Spotify accounts.
 * - YouTube Mode ([isYouTube] == true): operates with YouTube Music via [YouTubeWaveBitsApi] and [YouTubeAccount].
 * - Jiosavan Mode ([isJioSaavn] == true): operates with JioSaavn via [JioSaavnWaveBitsApi].
 */
object WaveBitsRuntime {
    lateinit var host: WebPlayerHost
        private set
    lateinit var spotify: SpotifyApi
        private set
    lateinit var youtubeAccount: YouTubeAccount
        private set
    lateinit var youtube: YouTubeWaveBitsApi
        private set
    lateinit var jiosaavn: JioSaavnWaveBitsApi
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _backendMode = MutableStateFlow(BackendMode.SPOTIFY)
    val backendMode: StateFlow<BackendMode> = _backendMode

    private val _isYouTube = MutableStateFlow(false)
    val isYouTube: StateFlow<Boolean> = _isYouTube

    private val _isJioSaavn = MutableStateFlow(false)
    val isJioSaavn: StateFlow<Boolean> = _isJioSaavn

    val api: WaveBitsApi by lazy { RoutingWaveBitsApi() }

    val isStarted: Boolean get() = ::host.isInitialized && ::spotify.isInitialized
    private val _fallbackLoginRequired = MutableStateFlow(false)
    val loginRequired: StateFlow<Boolean> get() = if (::host.isInitialized) host.loginRequired else _fallbackLoginRequired
    private val _runtimeVersion = MutableStateFlow(0L)
    val runtimeVersion: StateFlow<Long> = _runtimeVersion

    fun setJioSaavnMode(context: Context) {
        if (::spotify.isInitialized) {
            scope.launch { runCatching { spotify.player.pause() } }
        }
        if (::youtube.isInitialized) {
            youtube.ytPlayer.stopAndReset()
        }
        if (::host.isInitialized) {
            host.unload()
        }
        val prefs = context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("BackendMode", "JIOSAAVN")
            .putBoolean("JioSaavnLoggedIn", true)
            .putBoolean("LoggedIn", true)
            .apply()
        _backendMode.value = BackendMode.JIOSAAVN
        _isYouTube.value = false
        _isJioSaavn.value = true
        _runtimeVersion.value++
    }

    fun setYouTubeMode(context: Context) {
        if (::spotify.isInitialized) {
            scope.launch { runCatching { spotify.player.pause() } }
        }
        if (::jiosaavn.isInitialized) {
            jiosaavn.jioPlayer.stopAndReset()
        }
        if (::host.isInitialized) {
            host.unload()
        }
        val prefs = context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("BackendMode", "YOUTUBE")
            .putBoolean("YouTubeLoggedIn", true)
            .putBoolean("LoggedIn", true)
            .apply()
        if (::youtubeAccount.isInitialized) {
            youtubeAccount.reapply()
        }
        app.wavebits.innertube.YouTube.useLoginForBrowse = if (::youtubeAccount.isInitialized) {
            youtubeAccount.isAuthenticatedUser
        } else {
            false
        }
        _backendMode.value = BackendMode.YOUTUBE
        _isYouTube.value = true
        _isJioSaavn.value = false
        _runtimeVersion.value++
        if (::youtubeAccount.isInitialized) {
            scope.launch(Dispatchers.IO) { youtubeAccount.revalidate() }
        }
    }

    fun setSpotifyMode(context: Context, loadPlayer: Boolean = true) {
        if (::youtube.isInitialized) {
            youtube.ytPlayer.stopAndReset()
        }
        if (::jiosaavn.isInitialized) {
            jiosaavn.jioPlayer.stopAndReset()
        }
        val prefs = context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)
        val hasSpotifyLogin = prefs.getBoolean("SpotifyLoggedIn", false) || prefs.getBoolean("LoggedIn", false)
        prefs.edit()
            .putString("BackendMode", "SPOTIFY")
            .putBoolean("LoggedIn", hasSpotifyLogin)
            .apply()
        _backendMode.value = BackendMode.SPOTIFY
        _isYouTube.value = false
        _isJioSaavn.value = false
        _runtimeVersion.value++
        if (loadPlayer && ::host.isInitialized) {
            host.load()
        }
    }

    /** [loadPage] false (data saver on a metered network): nothing goes out until [resume]. */
    fun start(context: Context, loadPage: Boolean = true) {
        if (isStarted) return
        val prefs = context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)

        // Initialize standalone network backends first so they are immediately accessible
        app.wavebits.innertube.YouTube.cacheDir = java.io.File(context.cacheDir, "innertube")
        youtubeAccount = YouTubeAccount(context.applicationContext)
        youtube = YouTubeWaveBitsApi(context.applicationContext, youtubeAccount, scope)
        jiosaavn = JioSaavnWaveBitsApi(context.applicationContext, scope = scope)

        val okHttp = okhttp3.OkHttpClient()
        val updater = app.wavebits.spotify.gql.GqlOpsUpdater(okHttp, java.io.File(context.filesDir, "spotify/gql-ops.json"))
        runCatching {
            host = WebPlayerHost(context)
            AudioModeWatch.install(context.applicationContext)
            spotify = SpotifyApi(host, scope, language = Locale.getDefault().language, ops = updater.load(), deviceId = clientDeviceId(context), userAgent = host.userAgent)
        }.onFailure { e ->
            Logbook.log("WebPlayerHost initialization failed: ${e.message}")
        }
        _runtimeVersion.value++

        val savedBackend = prefs.getString("BackendMode", null)
        when {
            savedBackend == "JIOSAAVN" -> {
                _backendMode.value = BackendMode.JIOSAAVN
                _isYouTube.value = false
                _isJioSaavn.value = true
            }
            savedBackend == "YOUTUBE" && (youtubeAccount.isSignedIn || prefs.getBoolean("YouTubeLoggedIn", false)) -> {
                _backendMode.value = BackendMode.YOUTUBE
                _isYouTube.value = true
                _isJioSaavn.value = false
                app.wavebits.innertube.YouTube.useLoginForBrowse = youtubeAccount.isAuthenticatedUser
                scope.launch(Dispatchers.IO) { youtubeAccount.revalidate() }
            }
            savedBackend == null && youtubeAccount.isSignedIn && !prefs.getBoolean("SpotifyLoggedIn", false) && !prefs.getBoolean("LoggedIn", false) -> {
                _backendMode.value = BackendMode.YOUTUBE
                _isYouTube.value = true
                _isJioSaavn.value = false
                app.wavebits.innertube.YouTube.useLoginForBrowse = youtubeAccount.isAuthenticatedUser
                scope.launch(Dispatchers.IO) { youtubeAccount.revalidate() }
            }
            else -> {
                _backendMode.value = BackendMode.SPOTIFY
                _isYouTube.value = false
                _isJioSaavn.value = false
            }
        }
        _runtimeVersion.value++

        // Pre-warm InnerTube visitorData for YouTube mode
        scope.launch(Dispatchers.IO) {
            if (app.wavebits.innertube.YouTube.visitorData.isNullOrBlank()) {
                runCatching {
                    app.wavebits.innertube.YouTube.visitorData().getOrNull()?.let {
                        app.wavebits.innertube.YouTube.visitorData = it
                    }
                }
            }
        }

        if (::spotify.isInitialized && ::host.isInitialized) {
            spotify.bridge.logger = { line -> Logbook.log(line) }
            host.onPageLoaded = { scope.launch { if (_backendMode.value == BackendMode.SPOTIFY) spotify.onPageLoaded() } }
            host.onPageStarting = { if (_backendMode.value == BackendMode.SPOTIFY) spotify.onPageStarting() }
            spotify.player.reloadHost = { if (_backendMode.value == BackendMode.SPOTIFY) host.reload() }
            spotify.player.recoveryHeld = { _backendMode.value != BackendMode.SPOTIFY || host.licenseLimited() || host.loginRequired.value }
            spotify.player.playbackHeld = { _backendMode.value != BackendMode.SPOTIFY || host.licenseLimited() }
            scope.launch {
                host.licenseLimitedUntil.collect { until ->
                    if (_backendMode.value == BackendMode.SPOTIFY && until > android.os.SystemClock.elapsedRealtime()) runCatching { spotify.player.pause() }
                }
            }
        }
        scope.launch {
            var last = ""
            var planLogged = false
            var notReadySince = 0L
            var notReadyWait = 60_000L
            while (true) {
                kotlinx.coroutines.delay(10_000)
                if (_backendMode.value == BackendMode.JIOSAAVN) {
                    notReadySince = 0L
                    continue
                }
                if (_backendMode.value == BackendMode.YOUTUBE) {
                    notReadySince = 0L
                    if (!::youtube.isInitialized || !::youtubeAccount.isInitialized) continue
                    val s = youtube.player.state.value
                    val line = "status ready=true session=youtube_music user=${youtubeAccount.accountName.value} track=${s?.item?.name} paused=${s?.isPaused} buffering=${s?.isBuffering}"
                    if (line != last) {
                        last = line
                        Logbook.log(line)
                    }
                    continue
                }
                if (!::spotify.isInitialized || !::host.isInitialized) continue
                val clock = android.os.SystemClock.elapsedRealtime()
                val waiting = !spotify.ready.value && !host.loginRequired.value && !host.licenseLimited() &&
                    host.url.value.startsWith(WebPlayerHost.PLAYER_URL)
                if (!waiting) { notReadySince = 0L; if (spotify.ready.value) notReadyWait = 60_000L }
                else if (notReadySince == 0L) notReadySince = clock
                else if (clock - notReadySince >= notReadyWait) {
                    Logbook.log("player not ready for ${(clock - notReadySince) / 1000} s: loading it again")
                    notReadySince = 0L
                    notReadyWait = (notReadyWait * 2).coerceAtMost(600_000L)
                    host.reload()
                }
                if (!planLogged && spotify.ready.value && runCatching { !host.loginRequired.value && spotify.isSignedIn() }.getOrDefault(false)) {
                    planLogged = true
                    runCatching {
                        val a = spotify.raw.gql("accountAttributes", kotlinx.serialization.json.buildJsonObject { })
                            .let { (it as kotlinx.serialization.json.JsonObject)["data"] as kotlinx.serialization.json.JsonObject }
                            .let { (it["me"] as kotlinx.serialization.json.JsonObject)["account"] as kotlinx.serialization.json.JsonObject }
                        val attrs = a["attributes"] as? kotlinx.serialization.json.JsonObject
                        fun v(o: kotlinx.serialization.json.JsonObject?, k: String) = (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
                        Logbook.log("account: product=${v(a, "product")} country=${v(a, "country")} catalogue=${v(attrs, "catalogue")} onDemand=${v(attrs, "onDemand")} ads=${v(attrs, "ads")}")
                        Logbook.fact("account", "product=${v(a, "product")} country=${v(a, "country")} catalogue=${v(attrs, "catalogue")} onDemand=${v(attrs, "onDemand")} ads=${v(attrs, "ads")}")
                    }.onFailure { planLogged = false }
                }
                val s = spotify.player.state.value
                val session = when {
                    host.loginRequired.value -> "login-page"
                    else -> when (spotify.signedInHint()) { true -> "signed-in"; false -> "anonymous"; null -> "unknown" }
                }
                val line = "status ready=${spotify.ready.value} session=$session " +
                    "track=${s?.item?.name} paused=${s?.isPaused} buffering=${s?.isBuffering} url=${host.url.value.take(60)}"
                if (line != last) {
                    last = line
                    Logbook.log(line)
                }
            }
        }
        val isSpotifyLoggedIn = prefs.getBoolean("SpotifyLoggedIn", false) || prefs.getBoolean("LoggedIn", false)
        if (!loadPage) return
        if (_backendMode.value != BackendMode.SPOTIFY || !isSpotifyLoggedIn) {
            return
        }
        if (::host.isInitialized && ::spotify.isInitialized) {
            host.load()
            scope.launch {
                runCatching { updater.refresh(spotify.opsInUse) }.getOrNull()?.let(spotify::updateOps)
            }
        }
    }

    /** True when signed in with the active backend. */
    suspend fun isSignedIn(): Boolean = when (_backendMode.value) {
        BackendMode.JIOSAAVN -> true
        BackendMode.YOUTUBE -> ::youtubeAccount.isInitialized && youtubeAccount.isSignedIn
        BackendMode.SPOTIFY -> ::host.isInitialized && ::spotify.isInitialized && (!host.loginRequired.value && spotify.isSignedIn())
    }

    suspend fun awaitSignedIn(timeoutMs: Long): Boolean {
        if (_backendMode.value != BackendMode.SPOTIFY) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { isSignedIn() }.getOrDefault(false)) return true
            kotlinx.coroutines.delay(150)
        }
        return runCatching { isSignedIn() }.getOrDefault(false)
    }

    fun suspendPlayer() {
        if (::host.isInitialized) host.unload()
    }
    fun resume() {
        if (!::host.isInitialized) return
        val prefs = host.view.context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)
        if (_backendMode.value == BackendMode.SPOTIFY && prefs.getBoolean("LoggedIn", false)) host.load()
    }

    private fun clientDeviceId(context: Context): String {
        val file = java.io.File(context.filesDir, "spotify/device-id")
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        runCatching { file.parentFile?.mkdirs(); file.writeText(id) }
        return id
    }

    private object NoOpWaveBitsApi : WaveBitsApi {
        override val ready: StateFlow<Boolean> = MutableStateFlow(false)
        override val player: Player = object : Player {
            override val state: StateFlow<PlayerState?> = MutableStateFlow(null)
            override val queue: StateFlow<Queue?> = MutableStateFlow(null)
            override suspend fun play(contextUri: Uri, trackUri: Uri?, index: Int?, positionMs: Long?, order: List<Uri>?) {}
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun seek(positionMs: Long) {}
            override suspend fun next() {}
            override suspend fun previous() {}
            override suspend fun setShuffle(on: Boolean) {}
            override suspend fun setRepeat(mode: Repeat) {}
            override suspend fun addToQueue(uris: List<Uri>) {}
            override suspend fun removeFromQueue(items: List<NowPlaying>) {}
            override suspend fun clearQueue() {}
            override suspend fun skipTo(item: NowPlaying) {}
            override suspend fun playNext(uris: List<Uri>) {}
            override suspend fun moveInQueue(item: NowPlaying, target: NowPlaying, after: Boolean) {}
            override suspend fun volume(): Double = 1.0
            override suspend fun setVolume(level: Double) {}
            override suspend fun setSpeed(speed: Double) {}
        }
        override val search: Search = object : Search {
            override suspend fun all(query: String, limit: Int): SearchResults = SearchResults(
                Page(emptyList(), 0, 0, null),
                Page(emptyList(), 0, 0, null),
                Page(emptyList(), 0, 0, null),
                Page(emptyList(), 0, 0, null),
            )
            override suspend fun tracks(query: String, offset: Int, limit: Int): Page<Track> = Page(emptyList(), offset, 0, null)
            override suspend fun artists(query: String, offset: Int, limit: Int): Page<Artist> = Page(emptyList(), offset, 0, null)
            override suspend fun albums(query: String, offset: Int, limit: Int): Page<Album> = Page(emptyList(), offset, 0, null)
            override suspend fun playlists(query: String, offset: Int, limit: Int): Page<Playlist> = Page(emptyList(), offset, 0, null)
            override suspend fun shows(query: String, offset: Int, limit: Int): Page<MediaCard> = Page(emptyList(), offset, 0, null)
            override suspend fun episodes(query: String, offset: Int, limit: Int): Page<Episode> = Page(emptyList(), offset, 0, null)
            override suspend fun audiobooks(query: String, offset: Int, limit: Int): Page<MediaCard> = Page(emptyList(), offset, 0, null)
            override suspend fun users(query: String, offset: Int, limit: Int): Page<MediaCard> = Page(emptyList(), offset, 0, null)
            override suspend fun genres(query: String, offset: Int, limit: Int): Page<MediaCard> = Page(emptyList(), offset, 0, null)
            override suspend fun suggestions(query: String, limit: Int): List<String> = emptyList()
            override suspend fun recent(): List<MediaCard> = emptyList()
            override suspend fun clearRecent() {}
        }
        override val catalog: Catalog = object : Catalog {
            override suspend fun track(uri: Uri): Track = Track(uri, "", 0L, emptyList(), null)
            override suspend fun album(uri: Uri): Album = Album(uri, "", emptyList(), emptyList(), "ALBUM", null, 0)
            override suspend fun albumTracks(uri: Uri, offset: Int, limit: Int): Page<Track> = Page(emptyList(), offset, 0, null)
            override suspend fun artist(uri: Uri): Artist = Artist(uri, "", emptyList())
            override suspend fun artistTopTracks(uri: Uri): List<Track> = emptyList()
            override suspend fun artistPage(uri: Uri): ArtistPage = ArtistPage(Artist(uri, "", emptyList()), null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            override suspend fun isrc(trackUri: Uri): String? = null
            override suspend fun discography(uri: Uri, offset: Int, limit: Int): Page<Album> = Page(emptyList(), offset, 0, null)
            override suspend fun relatedArtists(uri: Uri): List<Artist> = emptyList()
            override suspend fun appearsOn(uri: Uri): List<Album> = emptyList()
            override suspend fun similarAlbums(trackUri: Uri, limit: Int): List<Album> = emptyList()
        }
        override val library: Library = object : Library {
            override val changes: Flow<Unit> = emptyFlow()
            override suspend fun contains(uris: List<Uri>): List<Boolean> = uris.map { false }
            override suspend fun add(uris: List<Uri>) {}
            override suspend fun remove(uris: List<Uri>) {}
            override suspend fun items(filter: LibraryFilter, sort: LibrarySort, folderUri: Uri?, offset: Int, limit: Int): Page<LibraryItem> = Page(emptyList(), offset, 0, null)
            override suspend fun likedTracks(offset: Int, limit: Int): Page<SavedTrack> = Page(emptyList(), offset, 0, null)
            override suspend fun pin(uri: Uri) {}
            override suspend fun unpin(uri: Uri) {}
            override suspend fun createFolder(name: String, parentFolderUri: Uri?): Uri = ""
            override suspend fun renameFolder(folderUri: Uri, name: String) {}
            override suspend fun deleteFolder(folderUri: Uri) {}
            override suspend fun moveToFolder(uri: Uri, folderUri: Uri?) {}
        }
        override val playlists: Playlists = object : Playlists {
            override suspend fun playlist(uri: Uri): Playlist = Playlist(uri, "", null, null, emptyList(), 0)
            override suspend fun tracks(uri: Uri, offset: Int, limit: Int): Page<Track> = Page(emptyList(), offset, 0, null)
            override suspend fun sortedTracks(uri: Uri, sort: PlaylistSort, descending: Boolean): List<Track> = emptyList()
            override suspend fun mine(): List<Playlist> = emptyList()
            override suspend fun editable(): List<Playlist> = emptyList()
            override suspend fun create(name: String, folderUri: Uri?): Uri = ""
            override suspend fun rename(uri: Uri, name: String) {}
            override suspend fun setDescription(uri: Uri, description: String) {}
            override suspend fun access(uri: Uri): PlaylistAccess = PlaylistAccess.PUBLIC
            override suspend fun setAccess(uri: Uri, access: PlaylistAccess) {}
            override suspend fun published(uri: Uri): Boolean = false
            override suspend fun setPublished(uri: Uri, published: Boolean) {}
            override suspend fun setCover(uri: Uri, jpeg: ByteArray?) {}
            override suspend fun delete(uri: Uri) {}
            override suspend fun follow(uri: Uri) {}
            override suspend fun unfollow(uri: Uri) {}
            override suspend fun join(uri: Uri, invitationToken: String): PlaylistAccess = PlaylistAccess.PUBLIC
            override suspend fun collaboratorInvite(uri: Uri): String = ""
            override suspend fun members(uri: Uri): List<PlaylistMember> = emptyList()
            override suspend fun leave(uri: Uri) {}
            override suspend fun setMemberCollaborator(uri: Uri, username: String, collaborator: Boolean) {}
            override suspend fun removeMember(uri: Uri, username: String) {}
            override suspend fun stopCollaboration(uri: Uri) {}
            override suspend fun addedBy(uri: Uri): List<RowAdder> = emptyList()
            override suspend fun addTracks(uri: Uri, trackUris: List<Uri>) {}
            override suspend fun removeTracks(uri: Uri, uids: List<String>) {}
            override suspend fun moveTracks(uri: Uri, uids: List<String>, beforeUid: String?) {}
            override suspend fun recommended(uri: Uri, limit: Int, skip: List<Uri>): List<Track> = emptyList()
        }
        override val home: Home = object : Home {
            override suspend fun feed(filter: HomeFilter, itemsPerShelf: Int): HomeFeed = HomeFeed("", emptyList(), emptyList())
            override suspend fun categories(): List<Category> = emptyList()
            override suspend fun category(uri: Uri): BrowsePage = BrowsePage(uri, "", emptyList())
            override suspend fun whatsNew(offset: Int, limit: Int): Page<Album> = Page(emptyList(), offset, 0, null)
        }
        override val podcasts: Podcasts = object : Podcasts {
            override suspend fun show(uri: Uri): Show = Show(uri, "", "", null, emptyList(), false, null, emptyList())
            override suspend fun audioUrl(uri: Uri): EpisodeAudio = EpisodeAudio(null, "NONE", "NONE")
            override suspend fun episodes(uri: Uri, offset: Int, limit: Int): Page<Episode> = Page(emptyList(), offset, 0, null)
            override suspend fun episode(uri: Uri): Episode = Episode(uri, "", null, 0L, null, emptyList(), null, 0L, false, false, false, null)
            override suspend fun audiobook(uri: Uri): Audiobook = Audiobook(uri, "", emptyList(), emptyList(), null, emptyList(), 0)
            override suspend fun chapters(uri: Uri, offset: Int, limit: Int): Page<Episode> = Page(emptyList(), offset, 0, null)
            override suspend fun savedEpisodes(offset: Int, limit: Int): Page<Episode> = Page(emptyList(), offset, 0, null)
            override suspend fun setEpisodeSaved(uri: Uri, saved: Boolean) {}
        }
        override val users: Users = object : Users {
            override suspend fun me(): Account = Account("spotify:user:guest", "guest", "WaveBits Listener", emptyList(), true, "US")
            override suspend fun profile(userUri: Uri): UserProfile = UserProfile(userUri, "WaveBits Listener", emptyList(), 0, 0, emptyList())
            override suspend fun followers(userUri: Uri): List<UserRef> = emptyList()
            override suspend fun following(userUri: Uri): List<UserRef> = emptyList()
            override suspend fun top(range: TimeRange, limit: Int): TopContent = TopContent(emptyList(), emptyList())
            override suspend fun follow(userUris: List<Uri>) {}
            override suspend fun unfollow(userUris: List<Uri>) {}
            override suspend fun isFollowing(userUris: List<Uri>): List<Boolean> = userUris.map { false }
            override suspend fun isBlocked(userUri: Uri): Boolean = false
            override suspend fun blockUser(userUri: Uri) {}
            override suspend fun unblockUser(userUri: Uri) {}
            override suspend fun friendActivity(): List<FriendActivity> = emptyList()
            override suspend fun history(): List<HistoryEntry> = emptyList()
            override suspend fun setPrivateSession(on: Boolean) {}
        }
        override val extras: TrackExtras = object : TrackExtras {
            override suspend fun lyrics(trackUri: Uri): Lyrics? = null
            override suspend fun canvas(trackUri: Uri): Canvas? = null
            override suspend fun credits(trackUri: Uri): Credits = Credits(emptyList())
            override suspend fun radio(seedUri: Uri): Uri? = null
            override suspend fun setExcludedFromTaste(trackUri: Uri, excluded: Boolean) {}
            override suspend fun isExcludedFromTaste(trackUris: List<Uri>): List<Boolean> = trackUris.map { false }
            override suspend fun setArtistBanned(artistUri: Uri, banned: Boolean) {}
            override suspend fun isArtistBanned(artistUris: List<Uri>): List<Boolean> = artistUris.map { false }
        }
        override val devices: Devices = object : Devices {
            override val devices: StateFlow<List<Device>> = MutableStateFlow(emptyList())
            override suspend fun refresh() {}
            override suspend fun transfer(deviceId: String, play: Boolean) {}
            override suspend fun pullHere() {}
        }
        override val jam: Jam = object : Jam {
            override suspend fun availability(): JamAvailability = JamAvailability.RESTRICTED
            override val session: StateFlow<JamSession?> = MutableStateFlow(null)
            override suspend fun refresh() {}
            override suspend fun start(): JamSession = throw UnsupportedOperationException("Jam not available")
            override suspend fun join(joinToken: String): JamSession = throw UnsupportedOperationException("Jam not available")
            override suspend fun leave() {}
            override suspend fun end() {}
            override suspend fun inviteLink(): String? = null
            override suspend fun removeMember(userUri: Uri) {}
            override suspend fun setQueueOnly(on: Boolean) {}
            override suspend fun setParticipantVolumeControl(on: Boolean) {}
        }
        override val blends: Blends = object : Blends {
            override suspend fun invite(): BlendInvite = BlendInvite("", "")
            override suspend fun invitation(linkOrId: String): BlendInvitation = BlendInvitation(BlendInvitationState.UNKNOWN, null, null, null, emptyList(), null)
            override suspend fun join(linkOrId: String): Uri = ""
            override suspend fun group(playlist: Uri): BlendGroup = BlendGroup(null, null, null, emptyList())
            override suspend fun story(playlist: Uri): BlendStory? = null
            override suspend fun rename(playlist: Uri, name: String) {}
            override suspend fun leave(playlist: Uri) {}
        }
        override val recents: Recents = object : Recents {
            override suspend fun recentEntries(offset: Int, limit: Int): Page<RecentEntry> = Page(emptyList(), offset, 0, null)
        }
    }

    /**
     * Routes [WaveBitsApi] domains to the active backend:
     * - Jiosavan Mode ([BackendMode.JIOSAAVN]) -> [jiosaavn]
     * - YouTube Music Mode ([BackendMode.YOUTUBE]) -> [youtube]
     * - Spotify Mode ([BackendMode.SPOTIFY]) -> [spotify]
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private class RoutingWaveBitsApi : WaveBitsApi {
        private val activeCatalog: WaveBitsApi get() = when (_backendMode.value) {
            BackendMode.JIOSAAVN -> if (::jiosaavn.isInitialized) jiosaavn else NoOpWaveBitsApi
            BackendMode.YOUTUBE -> if (::youtube.isInitialized) youtube else NoOpWaveBitsApi
            BackendMode.SPOTIFY -> if (::spotify.isInitialized) spotify else NoOpWaveBitsApi
        }

        private val activePlayerApi: WaveBitsApi get() = when (_backendMode.value) {
            BackendMode.JIOSAAVN -> if (::jiosaavn.isInitialized) jiosaavn else NoOpWaveBitsApi
            BackendMode.YOUTUBE -> if (::youtube.isInitialized) youtube else NoOpWaveBitsApi
            BackendMode.SPOTIFY -> if (::spotify.isInitialized) spotify else NoOpWaveBitsApi
        }

        override val ready: StateFlow<Boolean> = combine(_backendMode, _runtimeVersion) { mode, _ -> mode }
            .flatMapLatest { mode ->
                when (mode) {
                    BackendMode.JIOSAAVN -> if (::jiosaavn.isInitialized) jiosaavn.ready else flowOf(true)
                    BackendMode.YOUTUBE -> if (::youtube.isInitialized) youtube.ready else flowOf(true)
                    BackendMode.SPOTIFY -> if (::spotify.isInitialized) spotify.ready else flowOf(false)
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, _backendMode.value != BackendMode.SPOTIFY)

        override val player: Player = object : Player {
            override val state: StateFlow<PlayerState?> = combine(_backendMode, _runtimeVersion) { mode, _ -> mode }
                .flatMapLatest { mode ->
                    when (mode) {
                        BackendMode.JIOSAAVN -> if (::jiosaavn.isInitialized) jiosaavn.player.state else flowOf(null)
                        BackendMode.YOUTUBE -> if (::youtube.isInitialized) youtube.player.state else flowOf(null)
                        BackendMode.SPOTIFY -> if (::spotify.isInitialized) spotify.player.state else flowOf(null)
                    }
                }
                .stateIn(scope, SharingStarted.Eagerly, null)

            override val queue: StateFlow<Queue?> = combine(_backendMode, _runtimeVersion) { mode, _ -> mode }
                .flatMapLatest { mode ->
                    when (mode) {
                        BackendMode.JIOSAAVN -> if (::jiosaavn.isInitialized) jiosaavn.player.queue else flowOf(null)
                        BackendMode.YOUTUBE -> if (::youtube.isInitialized) youtube.player.queue else flowOf(null)
                        BackendMode.SPOTIFY -> if (::spotify.isInitialized) spotify.player.queue else flowOf(null)
                    }
                }
                .stateIn(scope, SharingStarted.Eagerly, null)

            override suspend fun play(contextUri: Uri, trackUri: Uri?, index: Int?, positionMs: Long?, order: List<Uri>?) =
                activePlayerApi.player.play(contextUri, trackUri, index, positionMs, order)
            override suspend fun pause() = activePlayerApi.player.pause()
            override suspend fun resume() = activePlayerApi.player.resume()
            override suspend fun seek(positionMs: Long) = activePlayerApi.player.seek(positionMs)
            override suspend fun next() = activePlayerApi.player.next()
            override suspend fun previous() = activePlayerApi.player.previous()
            override suspend fun setShuffle(on: Boolean) = activePlayerApi.player.setShuffle(on)
            override suspend fun setRepeat(mode: Repeat) = activePlayerApi.player.setRepeat(mode)
            override suspend fun addToQueue(uris: List<Uri>) = activePlayerApi.player.addToQueue(uris)
            override suspend fun removeFromQueue(items: List<NowPlaying>) = activePlayerApi.player.removeFromQueue(items)
            override suspend fun clearQueue() = activePlayerApi.player.clearQueue()
            override suspend fun skipTo(item: NowPlaying) = activePlayerApi.player.skipTo(item)
            override suspend fun playNext(uris: List<Uri>) = activePlayerApi.player.playNext(uris)
            override suspend fun moveInQueue(item: NowPlaying, target: NowPlaying, after: Boolean) =
                activePlayerApi.player.moveInQueue(item, target, after)
            override suspend fun volume(): Double = activePlayerApi.player.volume()
            override suspend fun setVolume(level: Double) = activePlayerApi.player.setVolume(level)
            override suspend fun setSpeed(speed: Double) = activePlayerApi.player.setSpeed(speed)
            override suspend fun setLocalMute(on: Boolean) = activePlayerApi.player.setLocalMute(on)
            override val localMute: Boolean get() = activePlayerApi.player.localMute
        }

        override val search: Search get() = activeCatalog.search
        override val home: Home get() = activeCatalog.home
        override val podcasts: Podcasts get() = activeCatalog.podcasts
        override val blends: Blends get() = activeCatalog.blends
        override val devices: Devices get() = activePlayerApi.devices
        override val jam: Jam get() = activeCatalog.jam
        override val users: Users get() = activeCatalog.users
        override val recents: Recents get() = activeCatalog.recents

        override val catalog: Catalog = object : Catalog {
            override suspend fun track(uri: Uri) = activeCatalog.catalog.track(uri)
            override suspend fun album(uri: Uri) = activeCatalog.catalog.album(uri)
            override suspend fun albumTracks(uri: Uri, offset: Int, limit: Int) = activeCatalog.catalog.albumTracks(uri, offset, limit)
            override suspend fun artist(uri: Uri) = activeCatalog.catalog.artist(uri)
            override suspend fun artistTopTracks(uri: Uri) = activeCatalog.catalog.artistTopTracks(uri)
            override suspend fun artistPage(uri: Uri) = activeCatalog.catalog.artistPage(uri)
            override suspend fun isrc(trackUri: Uri) = activeCatalog.catalog.isrc(trackUri)
            override suspend fun discography(uri: Uri, offset: Int, limit: Int) = activeCatalog.catalog.discography(uri, offset, limit)
            override suspend fun relatedArtists(uri: Uri) = activeCatalog.catalog.relatedArtists(uri)
            override suspend fun appearsOn(uri: Uri) = activeCatalog.catalog.appearsOn(uri)
            override suspend fun similarAlbums(trackUri: Uri, limit: Int) = activeCatalog.catalog.similarAlbums(trackUri, limit)
        }

        override val playlists: Playlists = object : Playlists {
            override suspend fun playlist(uri: Uri) = activeCatalog.playlists.playlist(uri)
            override suspend fun tracks(uri: Uri, offset: Int, limit: Int) = activeCatalog.playlists.tracks(uri, offset, limit)
            override suspend fun sortedTracks(uri: Uri, sort: app.wavebits.api.PlaylistSort, descending: Boolean) =
                activeCatalog.playlists.sortedTracks(uri, sort, descending)
            override suspend fun mine() = activeCatalog.playlists.mine()
            override suspend fun editable() = activeCatalog.playlists.editable()
            override suspend fun create(name: String, folderUri: Uri?) = activeCatalog.playlists.create(name, folderUri)
            override suspend fun rename(uri: Uri, name: String) = activeCatalog.playlists.rename(uri, name)
            override suspend fun setDescription(uri: Uri, description: String) = activeCatalog.playlists.setDescription(uri, description)
            override suspend fun access(uri: Uri) = activeCatalog.playlists.access(uri)
            override suspend fun setAccess(uri: Uri, access: app.wavebits.api.PlaylistAccess) = activeCatalog.playlists.setAccess(uri, access)
            override suspend fun published(uri: Uri) = activeCatalog.playlists.published(uri)
            override suspend fun setPublished(uri: Uri, published: Boolean) = activeCatalog.playlists.setPublished(uri, published)
            override suspend fun setCover(uri: Uri, jpeg: ByteArray?) = activeCatalog.playlists.setCover(uri, jpeg)
            override suspend fun delete(uri: Uri) = activeCatalog.playlists.delete(uri)
            override suspend fun follow(uri: Uri) = activeCatalog.playlists.follow(uri)
            override suspend fun unfollow(uri: Uri) = activeCatalog.playlists.unfollow(uri)
            override suspend fun join(uri: Uri, invitationToken: String) = activeCatalog.playlists.join(uri, invitationToken)
            override suspend fun collaboratorInvite(uri: Uri) = activeCatalog.playlists.collaboratorInvite(uri)
            override suspend fun members(uri: Uri) = activeCatalog.playlists.members(uri)
            override suspend fun leave(uri: Uri) = activeCatalog.playlists.leave(uri)
            override suspend fun setMemberCollaborator(uri: Uri, username: String, collaborator: Boolean) =
                activeCatalog.playlists.setMemberCollaborator(uri, username, collaborator)
            override suspend fun removeMember(uri: Uri, username: String) = activeCatalog.playlists.removeMember(uri, username)
            override suspend fun stopCollaboration(uri: Uri) = activeCatalog.playlists.stopCollaboration(uri)
            override suspend fun addedBy(uri: Uri) = activeCatalog.playlists.addedBy(uri)
            override suspend fun addTracks(uri: Uri, trackUris: List<Uri>) = activeCatalog.playlists.addTracks(uri, trackUris)
            override suspend fun removeTracks(uri: Uri, uids: List<String>) = activeCatalog.playlists.removeTracks(uri, uids)
            override suspend fun moveTracks(uri: Uri, uids: List<String>, beforeUid: String?) =
                activeCatalog.playlists.moveTracks(uri, uids, beforeUid)
            override suspend fun recommended(uri: Uri, limit: Int, skip: List<Uri>) =
                activeCatalog.playlists.recommended(uri, limit, skip)
        }

        override val extras: TrackExtras = object : TrackExtras {
            override suspend fun lyrics(trackUri: Uri) = activeCatalog.extras.lyrics(trackUri)
            override suspend fun canvas(trackUri: Uri) = activeCatalog.extras.canvas(trackUri)
            override suspend fun credits(trackUri: Uri) = activeCatalog.extras.credits(trackUri)
            override suspend fun radio(seedUri: Uri) = activeCatalog.extras.radio(seedUri)
            override suspend fun setExcludedFromTaste(trackUri: Uri, excluded: Boolean) =
                activeCatalog.extras.setExcludedFromTaste(trackUri, excluded)
            override suspend fun isExcludedFromTaste(trackUris: List<Uri>) =
                activeCatalog.extras.isExcludedFromTaste(trackUris)
            override suspend fun setArtistBanned(artistUri: Uri, banned: Boolean) =
                activeCatalog.extras.setArtistBanned(artistUri, banned)
            override suspend fun isArtistBanned(artistUris: List<Uri>) =
                activeCatalog.extras.isArtistBanned(artistUris)
        }

        override val library: Library = object : Library {
            override val changes: Flow<Unit> = combine(_backendMode, _runtimeVersion) { mode, _ -> mode }.flatMapLatest { mode ->
                when (mode) {
                    BackendMode.YOUTUBE -> if (::youtube.isInitialized) youtube.library.changes else emptyFlow()
                    BackendMode.SPOTIFY -> if (::spotify.isInitialized) spotify.library.changes else emptyFlow()
                    BackendMode.JIOSAAVN -> if (::jiosaavn.isInitialized) jiosaavn.library.changes else emptyFlow()
                }
            }
            override suspend fun contains(uris: List<Uri>) = activeCatalog.library.contains(uris)
            override suspend fun add(uris: List<Uri>) = activeCatalog.library.add(uris)
            override suspend fun remove(uris: List<Uri>) = activeCatalog.library.remove(uris)
            override suspend fun items(
                filter: app.wavebits.api.LibraryFilter,
                sort: app.wavebits.api.LibrarySort,
                folderUri: Uri?,
                offset: Int,
                limit: Int,
            ) = activeCatalog.library.items(filter, sort, folderUri, offset, limit)
            override suspend fun likedTracks(offset: Int, limit: Int) = activeCatalog.library.likedTracks(offset, limit)
            override suspend fun pin(uri: Uri) = activeCatalog.library.pin(uri)
            override suspend fun unpin(uri: Uri) = activeCatalog.library.unpin(uri)
            override suspend fun createFolder(name: String, parentFolderUri: Uri?) = activeCatalog.library.createFolder(name, parentFolderUri)
            override suspend fun renameFolder(folderUri: Uri, name: String) = activeCatalog.library.renameFolder(folderUri, name)
            override suspend fun deleteFolder(folderUri: Uri) = activeCatalog.library.deleteFolder(folderUri)
            override suspend fun moveToFolder(uri: Uri, folderUri: Uri?) = activeCatalog.library.moveToFolder(uri, folderUri)
        }
    }
}
