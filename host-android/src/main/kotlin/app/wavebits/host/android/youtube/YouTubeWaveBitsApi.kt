// WaveBits YouTube Music API, GPL-3.0.
package app.wavebits.host.android.youtube

import android.content.Context
import android.util.Log
import app.wavebits.api.Account
import app.wavebits.api.Album
import app.wavebits.api.AlbumRef
import app.wavebits.api.Artist
import app.wavebits.api.ArtistPage
import app.wavebits.api.ArtistRef
import app.wavebits.api.Audiobook
import app.wavebits.api.BlendGroup
import app.wavebits.api.BlendInvitation
import app.wavebits.api.BlendInvitationState
import app.wavebits.api.BlendInvite
import app.wavebits.api.BlendStory
import app.wavebits.api.Blends
import app.wavebits.api.BrowsePage
import app.wavebits.api.Canvas
import app.wavebits.api.Catalog
import app.wavebits.api.Category
import app.wavebits.api.Credits
import app.wavebits.api.Device
import app.wavebits.api.Devices
import app.wavebits.api.Episode
import app.wavebits.api.EpisodeAudio
import app.wavebits.api.FriendActivity
import app.wavebits.api.HistoryEntry
import app.wavebits.api.Home
import app.wavebits.api.HomeChip
import app.wavebits.api.HomeFeed
import app.wavebits.api.HomeFilter
import app.wavebits.api.Image
import app.wavebits.api.Jam
import app.wavebits.api.JamAvailability
import app.wavebits.api.JamSession
import app.wavebits.api.Library
import app.wavebits.api.LibraryFilter
import app.wavebits.api.LibraryItem
import app.wavebits.api.LibrarySort
import app.wavebits.api.Lyrics
import app.wavebits.api.LyricsLine
import app.wavebits.api.MediaCard
import app.wavebits.api.MediaKind
import app.wavebits.api.WaveBitsApi
import app.wavebits.api.Page
import app.wavebits.api.Player
import app.wavebits.api.Playlist
import app.wavebits.api.PlaylistAccess
import app.wavebits.api.PlaylistMember
import app.wavebits.api.PlaylistSort
import app.wavebits.api.Playlists
import app.wavebits.api.Podcasts
import app.wavebits.api.RecentEntry
import app.wavebits.api.Recents
import app.wavebits.api.RowAdder
import app.wavebits.api.SavedTrack
import app.wavebits.api.Search
import app.wavebits.api.SearchResults
import app.wavebits.api.Shelf
import app.wavebits.api.Show
import app.wavebits.api.TimeRange
import app.wavebits.api.TopContent
import app.wavebits.api.Track
import app.wavebits.api.TrackExtras
import app.wavebits.api.Uri
import app.wavebits.api.UserProfile
import app.wavebits.api.UserRef
import app.wavebits.api.Users
import app.wavebits.host.android.WaveBitsRuntime
import app.wavebits.innertube.YouTube
import app.wavebits.innertube.models.AlbumItem
import app.wavebits.innertube.models.ArtistItem
import app.wavebits.innertube.models.EpisodeItem
import app.wavebits.innertube.models.PlaylistItem
import app.wavebits.innertube.models.SongItem
import app.wavebits.innertube.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Complete [WaveBitsApi] implementation powered by WaveBits's InnerTube engine.
 * Supports both Guest Mode (anonymous InnerTube streaming + local guest library)
 * and YouTube Music Mode (authenticated Google session).
 */
class YouTubeWaveBitsApi(
    val context: Context,
    val account: YouTubeAccount,
    val scope: CoroutineScope,
) : WaveBitsApi {
    private val tag = "WaveBitsYTApi"
    val ytPlayer = YouTubePlayer(context, account, scope)

    private val guestPrefs by lazy {
        context.getSharedPreferences("wavebits_guest_library", Context.MODE_PRIVATE)
    }
    private val appPrefs by lazy {
        context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)
    }

    override val player: Player get() = ytPlayer
    override val ready: StateFlow<Boolean> = MutableStateFlow(true)

    private fun cleanId(raw: String): String =
        raw.substringAfterLast(':').removePrefix("yt_").substringBefore('|')

    private val playlistPageCache = java.util.concurrent.ConcurrentHashMap<String, app.wavebits.innertube.pages.PlaylistPage>()
    private val albumPageCache = java.util.concurrent.ConcurrentHashMap<String, app.wavebits.innertube.pages.AlbumPage>()
    private val artistPageCache = java.util.concurrent.ConcurrentHashMap<String, app.wavebits.innertube.pages.ArtistPage>()

    private suspend fun ensureSessionReady() {
        if (account.isAuthenticatedUser) {
            YouTube.ensureSessionScope()
        } else if (YouTube.visitorData.isNullOrBlank()) {
            runCatching {
                YouTube.visitorData().getOrNull()?.let { YouTube.visitorData = it }
            }
        }
    }

    private fun ensureVisitorData() {
        if (YouTube.visitorData.isNullOrBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    if (account.isAuthenticatedUser) {
                        YouTube.ensureSessionScope()
                    } else {
                        YouTube.visitorData().getOrNull()?.let { YouTube.visitorData = it }
                    }
                }
            }
        }
    }

    private fun trackFromData(d: YouTubeTrackData): Track = Track(
        uri = d.uri,
        name = d.title,
        durationMs = d.durationMs,
        artists = listOf(ArtistRef(uri = "spotify:artist:yt_${d.id}", name = d.artist)),
        album = AlbumRef(
            uri = "spotify:album:yt_${d.id}",
            name = d.album.ifBlank { d.title },
            images = listOf(Image(url = d.coverUrl.ifBlank { "https://i.ytimg.com/vi/${d.id}/hqdefault.jpg" }, width = 500, height = 500)),
        ),
        explicit = false,
        playable = true,
        hasVideo = false,
    )

    private fun toTrack(item: SongItem): Track {
        val artists = item.artists.map {
            ArtistRef(uri = "spotify:artist:yt_${it.id.orEmpty()}", name = it.name)
        }
        val album = item.album?.let {
            AlbumRef(
                uri = "spotify:album:yt_${it.id.orEmpty()}",
                name = it.name,
                images = listOf(Image(url = item.thumbnail, width = 500, height = 500)),
            )
        } ?: AlbumRef(
            uri = "spotify:album:yt_${item.id}",
            name = item.title,
            images = listOf(Image(url = item.thumbnail, width = 500, height = 500)),
        )
        return Track(
            uri = "spotify:track:yt_${item.id}",
            name = item.title,
            durationMs = (item.duration ?: 180) * 1000L,
            artists = artists.ifEmpty { listOf(ArtistRef("spotify:artist:yt_unknown", "Unknown Artist")) },
            album = album,
            explicit = item.explicit,
            playable = true,
            hasVideo = false,
        ).also { track ->
            ytPlayer.cacheTrack(track)
        }
    }

    private fun registerTracksInPlayers(
        items: List<Triple<String, String, String>>,
        meta: List<Triple<String, String, out Number>>,
    ) {
        ytPlayer.registerTracks(items, meta)
    }

    private fun registerContextTracksInPlayers(
        ctx: String,
        items: List<Triple<String, String, String>>,
        meta: List<Triple<String, String, out Number>>,
    ) {
        ytPlayer.registerContextTracks(ctx, items, meta)
    }

    private fun toPlaylist(item: PlaylistItem): Playlist {
        return Playlist(
            uri = "spotify:playlist:yt_${item.id}",
            name = item.title,
            description = "YouTube Music Playlist",
            owner = item.author?.name ?: "YouTube Music",
            images = listOf(Image(url = item.thumbnail.orEmpty(), width = 500, height = 500)),
            trackCount = item.songCountText?.filter { it.isDigit() }?.toIntOrNull() ?: 20,
            editable = false,
        )
    }

    private fun toAlbum(item: AlbumItem): Album {
        val artists = item.artists?.map {
            ArtistRef(uri = "spotify:artist:yt_${it.id.orEmpty()}", name = it.name)
        } ?: emptyList()
        return Album(
            uri = "spotify:album:yt_${item.browseId}",
            name = item.title,
            artists = artists.ifEmpty { listOf(ArtistRef("spotify:artist:yt_unknown", "Unknown Artist")) },
            images = listOf(Image(url = item.thumbnail, width = 500, height = 500)),
            type = "album",
            releaseDate = item.year?.toString(),
            trackCount = 10,
        )
    }

    private fun toArtist(item: ArtistItem): Artist {
        return Artist(
            uri = "spotify:artist:yt_${item.id}",
            name = item.title,
            images = listOf(Image(url = item.thumbnail.orEmpty(), width = 500, height = 500)),
        )
    }

    override val search: Search = object : Search {
        override suspend fun all(query: String, limit: Int): SearchResults = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val q = query.ifBlank { "Top Hits 2025" }
            coroutineScope {
                val songDef = async { YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrNull() }
                val albumDef = async { YouTube.search(q, YouTube.SearchFilter.FILTER_ALBUM).getOrNull() }
                val artistDef = async { YouTube.search(q, YouTube.SearchFilter.FILTER_ARTIST).getOrNull() }
                val playlistDef = async { YouTube.search(q, YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST).getOrNull() }

                val songs = songDef.await()?.items?.filterIsInstance<SongItem>() ?: emptyList()
                val albums = albumDef.await()?.items?.filterIsInstance<AlbumItem>() ?: emptyList()
                val artists = artistDef.await()?.items?.filterIsInstance<ArtistItem>() ?: emptyList()
                val playlists = playlistDef.await()?.items?.filterIsInstance<PlaylistItem>() ?: emptyList()

                val items = songs.map { Triple(it.id, it.title, it.artists.joinToString(", ") { a -> a.name }) }
                val meta = songs.map { Triple(it.thumbnail, it.album?.name.orEmpty(), (it.duration ?: 180) * 1000L) }
                registerTracksInPlayers(items, meta)

                SearchResults(
                    tracks = Page(songs.take(limit).map(::toTrack), 0, songs.size, null),
                    artists = Page(artists.take(limit).map(::toArtist), 0, artists.size, null),
                    albums = Page(albums.take(limit).map(::toAlbum), 0, albums.size, null),
                    playlists = Page(playlists.take(limit).map(::toPlaylist), 0, playlists.size, null),
                )
            }
        }

        override suspend fun tracks(query: String, offset: Int, limit: Int): Page<Track> = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val q = query.ifBlank { "Top Hits" }
            val songs = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                ?.items?.filterIsInstance<SongItem>() ?: emptyList()

            val items = songs.map { Triple(it.id, it.title, it.artists.joinToString(", ") { a -> a.name }) }
            val meta = songs.map { Triple(it.thumbnail, it.album?.name.orEmpty(), (it.duration ?: 180) * 1000L) }
            registerTracksInPlayers(items, meta)

            val paged = songs.drop(offset).take(limit).map(::toTrack)
            Page(paged, offset, songs.size, if (offset + limit < songs.size) offset + limit else null)
        }

        override suspend fun artists(query: String, offset: Int, limit: Int): Page<Artist> = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val artists = YouTube.search(query, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                ?.items?.filterIsInstance<ArtistItem>() ?: emptyList()
            val paged = artists.drop(offset).take(limit).map(::toArtist)
            Page(paged, offset, artists.size, null)
        }

        override suspend fun albums(query: String, offset: Int, limit: Int): Page<Album> = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val albums = YouTube.search(query, YouTube.SearchFilter.FILTER_ALBUM).getOrNull()
                ?.items?.filterIsInstance<AlbumItem>() ?: emptyList()
            val paged = albums.drop(offset).take(limit).map(::toAlbum)
            Page(paged, offset, albums.size, null)
        }

        override suspend fun playlists(query: String, offset: Int, limit: Int): Page<Playlist> = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val playlists = YouTube.search(query, YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST).getOrNull()
                ?.items?.filterIsInstance<PlaylistItem>() ?: emptyList()
            val paged = playlists.drop(offset).take(limit).map(::toPlaylist)
            Page(paged, offset, playlists.size, null)
        }

        override suspend fun shows(query: String, offset: Int, limit: Int): Page<MediaCard> = Page(emptyList(), 0, 0, null)
        override suspend fun episodes(query: String, offset: Int, limit: Int): Page<Episode> = Page(emptyList(), 0, 0, null)
        override suspend fun audiobooks(query: String, offset: Int, limit: Int): Page<MediaCard> = Page(emptyList(), 0, 0, null)
        override suspend fun users(query: String, offset: Int, limit: Int): Page<MediaCard> = Page(emptyList(), 0, 0, null)
        override suspend fun genres(query: String, offset: Int, limit: Int): Page<MediaCard> = Page(emptyList(), 0, 0, null)

        override suspend fun suggestions(query: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            ensureVisitorData()
            YouTube.searchSuggestions(query).getOrNull()?.queries.orEmpty().distinctBy { it.lowercase() }.take(limit)
        }

        override suspend fun recent(): List<MediaCard> = emptyList()
        override suspend fun clearRecent() {}
    }

    private suspend inline fun <T> wherever(
        empty: (T) -> Boolean,
        call: () -> Result<T>,
    ): Result<T> {
        val first = call()
        val v = first.getOrNull()
        if (v != null && !empty(v)) return first
        if (account.isAuthenticatedUser || YouTube.locale.gl.equals("US", ignoreCase = true)) return first
        val saved = YouTube.locale
        YouTube.locale = saved.copy(gl = "US")
        return try {
            val retry = call()
            if (retry.isSuccess) retry else first
        } finally {
            YouTube.locale = saved
        }
    }

    companion object {
        // WaveBits default mood chips
        private val WAVEBITS_FALLBACK_CHIPS = listOf(
            HomeChip(title = "Energize", params = "ggMPOg1uXzJZRk92Y09ucjZO"),
            HomeChip(title = "Relax", params = "ggMPOg1uX2UzWG9uWG92YWRV"),
            HomeChip(title = "Feel good", params = "ggMPOg1uX1pJcW55bVdoN3h3"),
            HomeChip(title = "Focus", params = "ggMPOg1uX04zSTR4b25RRG5F"),
            HomeChip(title = "Workout", params = "ggMPOg1uX2lRZ2p5b25RRG5F"),
            HomeChip(title = "Sad", params = "ggMPOg1uX2h4YUZ2Y09ucjZO"),
            HomeChip(title = "Romance", params = "ggMPOg1uX3o0eUZ2Y09ucjZO"),
        )
    }

    private fun normalizeShelfTitle(title: String?): String =
        title.orEmpty().trim().lowercase().replace(Regex("""\s+"""), " ")

    private fun sectionToShelf(
        sec: app.wavebits.innertube.pages.HomeSection,
        idx: Int,
        itemsPerShelf: Int,
        keyPrefix: String = "home",
    ): Shelf? {
        val songsInSection = sec.items.mapNotNull { item ->
            when (item) {
                is SongItem -> item
                is EpisodeItem -> item.asSongItem()
                else -> null
            }
        }
        if (songsInSection.isNotEmpty()) {
            val items = songsInSection.map { Triple(it.id, it.title, it.artists.joinToString(", ") { a -> a.name }) }
            val meta = songsInSection.map { Triple(it.thumbnail, it.album?.name.orEmpty(), (it.duration ?: 180) * 1000L) }
            registerTracksInPlayers(items, meta)
        }

        val tracks = songsInSection.map(::toTrack)
        val cards = sec.items.mapNotNull { item ->
            when (item) {
                is PlaylistItem -> MediaCard(
                    uri = "spotify:playlist:yt_${item.id}",
                    kind = MediaKind.PLAYLIST,
                    title = item.title,
                    subtitle = item.author?.name ?: item.songCountText ?: "Playlist",
                    images = listOf(Image(item.thumbnail.orEmpty(), 500, 500)),
                )
                is AlbumItem -> MediaCard(
                    uri = "spotify:album:yt_${item.browseId}",
                    kind = MediaKind.ALBUM,
                    title = item.title,
                    subtitle = item.artists?.joinToString(", ") { it.name }
                        ?: item.year?.toString()
                        ?: "Album",
                    images = listOf(Image(item.thumbnail, 500, 500)),
                )
                is ArtistItem -> MediaCard(
                    uri = "spotify:artist:yt_${item.id}",
                    kind = MediaKind.ARTIST,
                    title = item.title,
                    subtitle = "Artist",
                    images = listOf(Image(item.thumbnail.orEmpty(), 500, 500)),
                )
                else -> null
            }
        }.toMutableList()

        // If a carousel shelf is predominantly cards (Albums/Playlists) with a few single songs mixed in,
        // expose those songs as playable Radio/Song cards so mixed shelves render cohesively.
        val isPredominantlyCardShelf = cards.isNotEmpty() && songsInSection.size in 1..3 && cards.size >= songsInSection.size
        if (isPredominantlyCardShelf) {
            songsInSection.forEach { song ->
                cards.add(
                    MediaCard(
                        uri = "spotify:playlist:yt_RDAMVM${song.id}",
                        kind = MediaKind.PLAYLIST,
                        title = song.title,
                        subtitle = song.artists.joinToString(", ") { it.name }.ifBlank { "Song Radio" },
                        images = listOf(Image(song.thumbnail, 500, 500)),
                    )
                )
            }
        }

        val finalTracks = if (isPredominantlyCardShelf) emptyList() else tracks.take(itemsPerShelf)
        val finalCards = cards.take(itemsPerShelf)

        if (finalTracks.isEmpty() && finalCards.isEmpty()) return null
        return Shelf(
            uri = "spotify:section:yt_${keyPrefix}_${sec.title.hashCode()}_$idx",
            title = sec.title.ifBlank { "Quick picks" },
            items = finalCards,
            total = finalCards.size + finalTracks.size,
            strapline = sec.label,
            tracks = finalTracks,
        )
    }

    /**
     * WaveBits Top Picks builder:
     * Builds the "Top Picks for You" shelf combining personalized stations from seed tracks,
     * time-of-day mood mixes, and `FEmusic_mixed_for_you` mixes.
     */
    private fun buildWaveBitsTopPicksShelf(
        seedSongs: List<SongItem>,
        mixedSections: List<app.wavebits.innertube.pages.HomeSection>,
        itemsPerShelf: Int,
    ): Shelf? {
        val picks = mutableListOf<MediaCard>()
        val seenUris = HashSet<String>()

        fun addCard(card: MediaCard) {
            if (card.images.firstOrNull()?.url.isNullOrBlank()) return
            if (seenUris.add(card.uri)) {
                picks.add(card)
            }
        }

        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val moodLabel = when (hour) {
            in 5..11 -> "Morning Energy"
            in 12..16 -> "Afternoon Focus"
            in 17..21 -> "Evening Chill"
            else -> "Late Night Vibes"
        }

        // 1. Personal Station from primary seed song
        seedSongs.getOrNull(0)?.let { primary ->
            val artistName = primary.artists.firstOrNull()?.name ?: "YouTube Music"
            addCard(
                MediaCard(
                    uri = "spotify:playlist:yt_RDAMVM${primary.id}",
                    kind = MediaKind.PLAYLIST,
                    title = "$artistName Radio",
                    subtitle = "Personal Station • ${primary.title}",
                    images = listOf(Image(primary.thumbnail, 500, 500)),
                )
            )
        }

        // 2. Include YouTube Music's "Mixed for you" playlists (My Supermix, My Mix 1.., Discover Mix)
        mixedSections.flatMap { it.items }.forEach { item ->
            when (item) {
                is PlaylistItem -> addCard(
                    MediaCard(
                        uri = "spotify:playlist:yt_${item.id}",
                        kind = MediaKind.PLAYLIST,
                        title = item.title,
                        subtitle = item.author?.name ?: item.songCountText ?: "Made for You",
                        images = listOf(Image(item.thumbnail.orEmpty(), 500, 500)),
                    )
                )
                is AlbumItem -> addCard(
                    MediaCard(
                        uri = "spotify:album:yt_${item.browseId}",
                        kind = MediaKind.ALBUM,
                        title = item.title,
                        subtitle = item.artists?.joinToString(", ") { it.name } ?: "New Release",
                        images = listOf(Image(item.thumbnail, 500, 500)),
                    )
                )
                else -> {}
            }
        }

        // 3. Time-of-day Mood Station from second seed song
        seedSongs.getOrNull(1)?.let { moodSeed ->
            val artistName = moodSeed.artists.firstOrNull()?.name ?: "YouTube Music"
            addCard(
                MediaCard(
                    uri = "spotify:playlist:yt_RDAMVM${moodSeed.id}",
                    kind = MediaKind.PLAYLIST,
                    title = moodLabel,
                    subtitle = "Mood for You • $artistName",
                    images = listOf(Image(moodSeed.thumbnail, 500, 500)),
                )
            )
        }

        // 4. Discovery Station from third seed song (with distinct artist)
        val usedArtists = seedSongs.take(2).mapNotNull { it.artists.firstOrNull()?.name?.lowercase() }.toSet()
        val discoverySeed = seedSongs.drop(2).firstOrNull {
            it.artists.firstOrNull()?.name?.lowercase() !in usedArtists
        } ?: seedSongs.getOrNull(2)

        discoverySeed?.let { disc ->
            val artistName = disc.artists.firstOrNull()?.name ?: "YouTube Music"
            addCard(
                MediaCard(
                    uri = "spotify:playlist:yt_RDAMVM${disc.id}",
                    kind = MediaKind.PLAYLIST,
                    title = "Discovery Station",
                    subtitle = "$artistName & Similar",
                    images = listOf(Image(disc.thumbnail, 500, 500)),
                )
            )
        }

        // 5. Additional Artist Mixes if we still need more cards
        seedSongs.drop(3).distinctBy { it.artists.firstOrNull()?.name?.lowercase() }.take(3).forEach { seed ->
            val artistName = seed.artists.firstOrNull()?.name ?: return@forEach
            addCard(
                MediaCard(
                    uri = "spotify:playlist:yt_RDAMVM${seed.id}",
                    kind = MediaKind.PLAYLIST,
                    title = "$artistName Mix",
                    subtitle = "Top Pick • Radio Station",
                    images = listOf(Image(seed.thumbnail, 500, 500)),
                )
            )
        }

        if (picks.isEmpty()) return null
        return Shelf(
            uri = "spotify:section:yt_wavebits_top_picks",
            title = "Top Picks for You",
            items = picks.take(itemsPerShelf),
            total = picks.size,
            strapline = "MADE FOR YOU",
        )
    }

    override val home: Home = object : Home {
        override suspend fun feed(filter: HomeFilter, itemsPerShelf: Int): HomeFeed =
            feedPage(cursor = null, params = null, itemsPerShelf = itemsPerShelf)

        override suspend fun feedPage(cursor: String?, params: String?, itemsPerShelf: Int): HomeFeed = withContext(Dispatchers.IO) {
            ensureSessionReady()
            val shelves = mutableListOf<Shelf>()
            val seenTitles = HashSet<String>()

            fun addShelfDedup(shelf: Shelf?) {
                if (shelf == null) return
                val norm = normalizeShelfTitle(shelf.title)
                // Allow multiple continuation "More picks" shelves, deduplicate named shelves
                if (cursor != null || seenTitles.add(norm)) {
                    shelves.add(shelf)
                }
            }

            if (cursor != null || !params.isNullOrBlank()) {
                // Continuation or filtered chip view
                val page = wherever({ it.sections.isEmpty() }) {
                    val primary = YouTube.home(continuation = cursor, params = params)
                    if (cursor == null && !params.isNullOrBlank() && primary.getOrNull()?.sections.isNullOrEmpty()) {
                        // Also support mood category params (`FEmusic_moods_and_genres_category`)
                        YouTube.browseShelves("FEmusic_moods_and_genres_category", params = params)
                    } else {
                        primary
                    }
                }.getOrNull()

                page?.sections?.forEachIndexed { idx, sec ->
                    addShelfDedup(sectionToShelf(sec, idx, itemsPerShelf, keyPrefix = "filtered"))
                }

                val chips = page?.chips?.mapNotNull { chip ->
                    val p = chip.endpoint?.params ?: return@mapNotNull null
                    HomeChip(title = chip.title, params = p)
                }.orEmpty().ifEmpty { WAVEBITS_FALLBACK_CHIPS }

                val greetingName = account.accountName.value?.let { "Welcome back, $it" } ?: "YouTube Music"
                return@withContext HomeFeed(
                    greeting = greetingName,
                    filters = listOf(HomeFilter.ALL, HomeFilter.MUSIC),
                    shelves = shelves,
                    chips = chips,
                    continuation = page?.continuation,
                )
            }

            // Initial unfiltered YT Music Home
            val (page, historySongs, mixedPage, newReleasesPage, explorePage) = coroutineScope {
                val homeDef = async {
                    wherever({ it.sections.isEmpty() }) {
                        YouTube.home(continuation = null, params = null)
                    }.getOrNull()
                }
                val histDef = async {
                    if (account.isAuthenticatedUser) YouTube.history().getOrNull().orEmpty() else emptyList()
                }
                val mixedDef = async {
                    YouTube.browseShelves("FEmusic_mixed_for_you").getOrNull()
                }
                val newRelDef = async {
                    wherever({ it.sections.isEmpty() }) {
                        YouTube.browseShelves("FEmusic_new_releases")
                    }.getOrNull()
                }
                val expDef = async {
                    wherever({ it.sections.isEmpty() }) {
                        YouTube.browseShelves("FEmusic_explore")
                    }.getOrNull()
                }
                Quintuple(
                    homeDef.await(),
                    histDef.await(),
                    mixedDef.await(),
                    newRelDef.await(),
                    expDef.await(),
                )
            }

            // 1. Signed-in listening history ("Recents")
            if (historySongs.isNotEmpty()) {
                val items = historySongs.map { Triple(it.id, it.title, it.artists.joinToString(", ") { a -> a.name }) }
                val meta = historySongs.map { Triple(it.thumbnail, it.album?.name.orEmpty(), (it.duration ?: 180) * 1000L) }
                registerTracksInPlayers(items, meta)
                val historyTracks = historySongs.take(20).map(::toTrack)
                addShelfDedup(
                    Shelf(
                        uri = "spotify:section:yt_wavebits_history",
                        title = "Recents",
                        items = emptyList(),
                        total = historyTracks.size,
                        strapline = "RECENTLY PLAYED",
                        tracks = historyTracks,
                    )
                )
            }

            // 2. Collect seed songs for "Top Picks for You"
            val homeSongs = page?.sections.orEmpty()
                .flatMap { it.items }
                .filterIsInstance<SongItem>()
            val allSeedSongs = (historySongs + homeSongs).distinctBy { it.id }

            // 3. Primary FEmusic_home shelves (insert "Top Picks for You" after the first quick-picks/recents shelf)
            val homeSections = page?.sections.orEmpty()
            var topPicksInserted = false
            val topPicksShelf = buildWaveBitsTopPicksShelf(
                seedSongs = allSeedSongs,
                mixedSections = mixedPage?.sections.orEmpty(),
                itemsPerShelf = itemsPerShelf,
            )

            for ((idx, sec) in homeSections.withIndex()) {
                addShelfDedup(sectionToShelf(sec, idx, itemsPerShelf, keyPrefix = "main"))
                if (!topPicksInserted && idx == 0 && topPicksShelf != null) {
                    addShelfDedup(topPicksShelf)
                    topPicksInserted = true
                }
            }
            if (!topPicksInserted && topPicksShelf != null) {
                addShelfDedup(topPicksShelf)
            }

            // 4. Supplementary shelves (`FEmusic_new_releases` and `FEmusic_explore`)
            // appended without duplicate titles (`withoutRepeatsOf`)
            newReleasesPage?.sections?.forEachIndexed { idx, sec ->
                addShelfDedup(sectionToShelf(sec, idx, itemsPerShelf, keyPrefix = "new_rel"))
            }
            explorePage?.sections?.forEachIndexed { idx, sec ->
                addShelfDedup(sectionToShelf(sec, idx, itemsPerShelf, keyPrefix = "explore"))
            }

            // 5. Guaranteed non-blank fallback: if browse endpoints returned no shelves, populate via search
            if (shelves.isEmpty()) {
                coroutineScope {
                    val quickDef = async { YouTube.search("Trending Music Hits", YouTube.SearchFilter.FILTER_SONG).getOrNull() }
                    val plDef = async { YouTube.search("Top Hits Playlists", YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST).getOrNull() }
                    val albDef = async { YouTube.search("New Release Albums", YouTube.SearchFilter.FILTER_ALBUM).getOrNull() }

                    val quickSongs = quickDef.await()?.items?.filterIsInstance<SongItem>().orEmpty()
                    if (quickSongs.isNotEmpty()) {
                        addShelfDedup(
                            sectionToShelf(
                                app.wavebits.innertube.pages.HomeSection(title = "Quick picks", label = "START RADIO FROM A SONG", items = quickSongs),
                                0,
                                itemsPerShelf,
                                keyPrefix = "fallback_quick",
                            )
                        )
                    }
                    val plItems = plDef.await()?.items?.filterIsInstance<PlaylistItem>().orEmpty()
                    if (plItems.isNotEmpty()) {
                        addShelfDedup(
                            sectionToShelf(
                                app.wavebits.innertube.pages.HomeSection(title = "Featured Playlists", label = "RECOMMENDED FOR YOU", items = plItems),
                                1,
                                itemsPerShelf,
                                keyPrefix = "fallback_pl",
                            )
                        )
                    }
                    val albItems = albDef.await()?.items?.filterIsInstance<AlbumItem>().orEmpty()
                    if (albItems.isNotEmpty()) {
                        addShelfDedup(
                            sectionToShelf(
                                app.wavebits.innertube.pages.HomeSection(title = "New Releases", label = "ALBUMS & SINGLES", items = albItems),
                                2,
                                itemsPerShelf,
                                keyPrefix = "fallback_alb",
                            )
                        )
                    }
                }
            }

            val chips = page?.chips?.mapNotNull { chip ->
                val p = chip.endpoint?.params ?: return@mapNotNull null
                HomeChip(title = chip.title, params = p)
            }.orEmpty().ifEmpty { WAVEBITS_FALLBACK_CHIPS }

            val greetingName = account.accountName.value?.let { "Welcome back, $it" } ?: "YouTube Music"

            HomeFeed(
                greeting = greetingName,
                filters = listOf(HomeFilter.ALL, HomeFilter.MUSIC),
                shelves = shelves,
                chips = chips,
                continuation = page?.continuation,
            )
        }

        override suspend fun categories(): List<Category> = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val moodGroups = wherever({ it.isEmpty() }) { YouTube.moodAndGenres() }.getOrNull().orEmpty()
            moodGroups.flatMap { group ->
                group.items.map { item ->
                    val bId = item.endpoint.browseId ?: "FEmusic_moods_and_genres_category"
                    val params = item.endpoint.params.orEmpty()
                    Category(
                        uri = "spotify:genre:yt_${bId}_$params",
                        title = item.title,
                        color = item.stripeColor.toString(16),
                        images = emptyList(),
                    )
                }
            }
        }

        override suspend fun category(uri: Uri): BrowsePage = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val raw = uri.substringAfter("yt_", "")
            val browseId = if (raw.startsWith("FEmusic_")) {
                raw.substringBefore("_gg").substringBefore("_8g")
            } else {
                "FEmusic_moods_and_genres_category"
            }
            val params = raw.removePrefix("${browseId}_").takeIf { it.isNotBlank() && it != raw }
            val page = YouTube.browseShelves(browseId, params = params).getOrNull()
            val shelves = page?.sections?.mapIndexedNotNull { idx, sec ->
                sectionToShelf(sec, idx, 20, keyPrefix = "cat")
            }.orEmpty()
            BrowsePage(
                uri = uri,
                title = "Moods & Genres",
                shelves = shelves,
            )
        }

        override suspend fun whatsNew(offset: Int, limit: Int): Page<Album> = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val albums = YouTube.explore().getOrNull()?.newReleaseAlbums.orEmpty().map(::toAlbum)
            val paged = albums.drop(offset).take(limit)
            Page(paged, offset, albums.size, if (offset + limit < albums.size) offset + limit else null)
        }
    }

    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
    )

    private val _libraryChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private val likedSet = java.util.Collections.synchronizedSet(HashSet<String>())
    private val unlikedSet = java.util.Collections.synchronizedSet(HashSet<String>())

    init {
        val savedIds = guestPrefs.getStringSet("liked_ids", emptySet()).orEmpty()
        likedSet.addAll(savedIds)
        val removedIds = guestPrefs.getStringSet("unliked_ids", emptySet()).orEmpty()
        unlikedSet.addAll(removedIds)
        loadSavedPlaylists().forEach { pl ->
            markEntitySaved(pl.uri, true, persist = false)
        }
        loadSavedAlbums().forEach { al ->
            markEntitySaved(al.uri, true, persist = false)
        }
    }

    private fun persistLikedIds() {
        guestPrefs.edit()
            .putStringSet("liked_ids", HashSet(likedSet))
            .putStringSet("unliked_ids", HashSet(unlikedSet))
            .apply()
    }

    private fun markEntitySaved(uri: String, saved: Boolean, persist: Boolean = true) {
        val cid = cleanId(uri)
        val rawNoVl = cid.removePrefix("VL")
        val variants = listOf(
            uri,
            cid,
            rawNoVl,
            "VL$rawNoVl",
            "spotify:playlist:yt_$cid",
            "spotify:playlist:$cid",
            "spotify:playlist:yt_$rawNoVl",
            "spotify:playlist:yt_VL$rawNoVl",
            "spotify:album:yt_$cid",
            "spotify:album:$cid",
            "spotify:track:yt_$cid",
            "spotify:track:$cid",
            "spotify:artist:yt_$cid",
            "spotify:artist:$cid",
        )
        if (saved) {
            likedSet.addAll(variants)
            unlikedSet.removeAll(variants.toSet())
        } else {
            likedSet.removeAll(variants.toSet())
            unlikedSet.add(cid)
            unlikedSet.add(rawNoVl)
            unlikedSet.add("VL$rawNoVl")
        }
        if (persist) persistLikedIds()
    }

    fun notifyLibraryChanged() {
        _libraryChanges.tryEmit(Unit)
    }

    fun clearSessionCaches(clearUnlikedFilter: Boolean = false) {
        playlistPageCache.clear()
        albumPageCache.clear()
        artistPageCache.clear()
        if (clearUnlikedFilter) {
            unlikedSet.clear()
            guestPrefs.edit().remove("unliked_ids").apply()
        }
        _libraryChanges.tryEmit(Unit)
    }

    private fun isEntitySaved(uri: String): Boolean {
        val cid = cleanId(uri)
        val rawNoVl = cid.removePrefix("VL")
        return likedSet.contains(uri) ||
            likedSet.contains(cid) ||
            likedSet.contains(rawNoVl) ||
            likedSet.contains("VL$rawNoVl") ||
            likedSet.contains("spotify:playlist:yt_$cid") ||
            likedSet.contains("spotify:playlist:$cid") ||
            likedSet.contains("spotify:album:yt_$cid") ||
            likedSet.contains("spotify:album:$cid") ||
            likedSet.contains("spotify:track:yt_$cid")
    }

    private fun loadSavedPlaylists(): List<Playlist> {
        val raw = guestPrefs.getString("saved_playlists_json", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = mutableListOf<Playlist>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) continue
                val uri = o.optString("uri").ifBlank { "spotify:playlist:yt_$id" }
                val name = o.optString("name", "Playlist")
                val desc = o.optString("description", "")
                val owner = o.optString("owner", "YouTube Music")
                val cover = o.optString("coverUrl", "")
                val count = o.optInt("trackCount", 0)
                val editable = o.optBoolean("editable", false)
                list.add(
                    Playlist(
                        uri = uri,
                        name = name,
                        description = desc,
                        owner = owner,
                        images = if (cover.isNotBlank()) listOf(Image(cover, 500, 500)) else emptyList(),
                        trackCount = count,
                        editable = editable,
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun saveSavedPlaylists(playlists: List<Playlist>) {
        runCatching {
            val arr = JSONArray()
            for (p in playlists) {
                val id = cleanId(p.uri)
                val o = JSONObject()
                o.put("id", id)
                o.put("uri", p.uri)
                o.put("name", p.name)
                o.put("description", p.description.orEmpty())
                o.put("owner", p.owner.orEmpty())
                o.put("coverUrl", p.images.firstOrNull()?.url.orEmpty())
                o.put("trackCount", p.trackCount)
                o.put("editable", p.editable)
                arr.put(o)
            }
            guestPrefs.edit().putString("saved_playlists_json", arr.toString()).apply()
        }
    }

    private fun loadCustomPlaylistTracks(playlistId: String): List<Track> {
        val key = "custom_pl_tracks_${playlistId.removePrefix("VL")}"
        val raw = guestPrefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = mutableListOf<Track>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) continue
                val title = o.optString("title", "Track")
                val artist = o.optString("artist", "Artist")
                val album = o.optString("album", "")
                val cover = o.optString("coverUrl", "https://i.ytimg.com/vi/$id/hqdefault.jpg")
                val duration = o.optLong("durationMs", 180_000L)
                list.add(
                    Track(
                        uri = "spotify:track:yt_$id",
                        uid = id,
                        name = title,
                        durationMs = duration,
                        artists = listOf(ArtistRef("spotify:artist:yt_$id", artist)),
                        album = AlbumRef("spotify:album:yt_$id", album, listOf(Image(cover, 500, 500))),
                        explicit = false,
                        playable = true,
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun saveCustomPlaylistTracks(playlistId: String, tracks: List<Track>) {
        val key = "custom_pl_tracks_${playlistId.removePrefix("VL")}"
        runCatching {
            val arr = JSONArray()
            for (t in tracks) {
                val id = cleanId(t.uri)
                val o = JSONObject()
                o.put("id", id)
                o.put("title", t.name)
                o.put("artist", t.artists.joinToString(", ") { it.name })
                o.put("album", t.album?.name.orEmpty())
                o.put("coverUrl", t.album?.images?.firstOrNull()?.url.orEmpty())
                o.put("durationMs", t.durationMs)
                arr.put(o)
            }
            guestPrefs.edit().putString(key, arr.toString()).apply()
        }
    }

    private fun loadSavedAlbums(): List<Album> {
        val raw = guestPrefs.getString("saved_albums_json", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = mutableListOf<Album>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) continue
                val uri = o.optString("uri").ifBlank { "spotify:album:yt_$id" }
                val name = o.optString("name", "Album")
                val artist = o.optString("artist", "Artist")
                val cover = o.optString("coverUrl", "")
                val releaseDate = o.optString("releaseDate", "").takeIf { it.isNotBlank() }
                val count = o.optInt("trackCount", 10)
                list.add(
                    Album(
                        uri = uri,
                        name = name,
                        artists = listOf(ArtistRef("spotify:artist:yt_$id", artist)),
                        images = if (cover.isNotBlank()) listOf(Image(cover, 500, 500)) else emptyList(),
                        type = "album",
                        releaseDate = releaseDate,
                        trackCount = count,
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun saveSavedAlbums(albums: List<Album>) {
        runCatching {
            val arr = JSONArray()
            for (a in albums) {
                val id = cleanId(a.uri)
                val o = JSONObject()
                o.put("id", id)
                o.put("uri", a.uri)
                o.put("name", a.name)
                o.put("artist", a.artists.joinToString(", ") { it.name })
                o.put("coverUrl", a.images.firstOrNull()?.url.orEmpty())
                o.put("releaseDate", a.releaseDate.orEmpty())
                o.put("trackCount", a.trackCount)
                arr.put(o)
            }
            guestPrefs.edit().putString("saved_albums_json", arr.toString()).apply()
        }
    }

    override val playlists: Playlists = object : Playlists {
        override suspend fun playlist(uri: Uri): Playlist = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val id = cleanId(uri)
            val savedCustom = loadSavedPlaylists().firstOrNull {
                cleanId(it.uri).removePrefix("VL") == id.removePrefix("VL")
            }
            if (id.startsWith("custom_") && savedCustom != null) {
                val customTracks = loadCustomPlaylistTracks(id)
                val cover = savedCustom.images.firstOrNull()?.url?.takeIf { it.isNotBlank() }
                    ?: customTracks.firstOrNull()?.album?.images?.firstOrNull()?.url.orEmpty()
                return@withContext savedCustom.copy(
                    uri = uri,
                    images = if (cover.isNotBlank()) listOf(Image(cover, 500, 500)) else emptyList(),
                    trackCount = customTracks.size,
                )
            }
            if (id.startsWith("RDAMPL") || id.startsWith("RDAMVM")) {
                val videoId = id.removePrefix("RDAMPL").removePrefix("RDAMVM")
                // Fast-path: return immediately from registered track metadata without firing a duplicate YouTube.next() call
                val cachedSeed = ytPlayer.getRegisteredTrack(videoId)
                if (cachedSeed != null) {
                    return@withContext Playlist(
                        uri = uri,
                        name = "${cachedSeed.title} Radio",
                        description = cachedSeed.artist.ifBlank { "YouTube Music Radio" },
                        owner = "YouTube Music",
                        images = listOf(Image(url = cachedSeed.coverUrl.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }, width = 500, height = 500)),
                        trackCount = ytPlayer.getRegisteredContext(uri)?.size ?: 25,
                        editable = false,
                    )
                }
                val nextRes = YouTube.next(WatchEndpoint(videoId = videoId, playlistId = id)).getOrNull()
                    ?: YouTube.next(WatchEndpoint(videoId = videoId)).getOrNull()
                val songs = nextRes?.items.orEmpty()
                if (songs.isNotEmpty()) {
                    val items = songs.map { Triple(it.id, it.title, it.artists.joinToString(", ") { a -> a.name }) }
                    val meta = songs.map { Triple(it.thumbnail, it.album?.name.orEmpty(), (it.duration ?: 180) * 1000L) }
                    registerContextTracksInPlayers(uri, items, meta)
                }
                val firstSong = songs.firstOrNull()
                return@withContext Playlist(
                    uri = uri,
                    name = firstSong?.title?.let { "$it Radio" } ?: "Track Radio",
                    description = firstSong?.artists?.joinToString(", ") { it.name } ?: "YouTube Music Radio",
                    owner = "YouTube Music",
                    images = listOf(Image(url = firstSong?.thumbnail.orEmpty(), width = 500, height = 500)),
                    trackCount = songs.size,
                    editable = false,
                )
            }
            val res = playlistPageCache[id] ?: YouTube.playlist(id).getOrNull()?.also { playlistPageCache[id] = it }
            if (res != null) {
                val pl = res.playlist
                Playlist(
                    uri = uri,
                    name = savedCustom?.name?.takeIf { savedCustom.editable } ?: pl.title,
                    description = pl.author?.name ?: "YouTube Music Playlist",
                    owner = pl.author?.name ?: "YouTube Music",
                    images = listOf(Image(url = pl.thumbnail.orEmpty(), width = 500, height = 500)),
                    trackCount = res.songs.size,
                    editable = savedCustom?.editable ?: pl.isEditable,
                )
            } else if (savedCustom != null) {
                savedCustom.copy(uri = uri)
            } else {
                Playlist(
                    uri = uri,
                    name = "Playlist",
                    description = "",
                    owner = "YouTube Music",
                    images = emptyList(),
                    trackCount = 0,
                    editable = false,
                )
            }
        }

        override suspend fun tracks(uri: Uri, offset: Int, limit: Int): Page<Track> = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val id = cleanId(uri)
            val customTracks = loadCustomPlaylistTracks(id)
            if (id.startsWith("custom_") || (customTracks.isNotEmpty() && playlistPageCache[id] == null)) {
                val items = customTracks.map { Triple(cleanId(it.uri), it.name, it.artists.joinToString(", ") { a -> a.name }) }
                val meta = customTracks.map { Triple(it.album?.images?.firstOrNull()?.url.orEmpty(), it.album?.name.orEmpty(), it.durationMs) }
                registerContextTracksInPlayers(uri, items, meta)
                val paged = customTracks.drop(offset).take(limit)
                return@withContext Page(paged, offset, customTracks.size, if (offset + limit < customTracks.size) offset + limit else null)
            }
            val cachedCtx = ytPlayer.getRegisteredContext(uri)
            if (cachedCtx != null && cachedCtx.size > 1) {
                val allTracks = cachedCtx.map(::trackFromData)
                val paged = allTracks.drop(offset).take(limit)
                return@withContext Page(paged, offset, allTracks.size, if (offset + limit < allTracks.size) offset + limit else null)
            }

            val songs = if (id.startsWith("RDAMPL") || id.startsWith("RDAMVM")) {
                val videoId = id.removePrefix("RDAMPL").removePrefix("RDAMVM")
                val rawList = (YouTube.next(WatchEndpoint(videoId = videoId, playlistId = id)).getOrNull()
                    ?: YouTube.next(WatchEndpoint(videoId = videoId)).getOrNull())?.items ?: emptyList()
                val seedIdx = rawList.indexOfFirst { it.id == videoId }
                if (seedIdx > 0) {
                    val mutable = rawList.toMutableList()
                    val seed = mutable.removeAt(seedIdx)
                    mutable.add(0, seed)
                    mutable
                } else {
                    rawList
                }
            } else {
                val page = playlistPageCache[id] ?: YouTube.playlist(id).getOrNull()?.also { playlistPageCache[id] = it }
                page?.songs ?: emptyList()
            }

            val items = songs.map { Triple(it.id, it.title, it.artists.joinToString(", ") { a -> a.name }) }
            val meta = songs.map { Triple(it.thumbnail, it.album?.name.orEmpty(), (it.duration ?: 180) * 1000L) }
            registerContextTracksInPlayers(uri, items, meta)

            val paged = songs.drop(offset).take(limit).map(::toTrack)
            Page(paged, offset, songs.size, if (offset + limit < songs.size) offset + limit else null)
        }

        override suspend fun sortedTracks(uri: Uri, sort: PlaylistSort, descending: Boolean): List<Track> =
            tracks(uri, 0, 200).items

        override suspend fun mine(): List<Playlist> = withContext(Dispatchers.IO) {
            val local = loadSavedPlaylists()
            val remote = if (account.isAuthenticatedUser) {
                ensureSessionReady()
                coroutineScope {
                    val likedDef = async { YouTube.library("FEmusic_liked_playlists", maxContinuationPages = 3).getOrNull() }
                    val landingDef = async { YouTube.library("FEmusic_library_landing", maxContinuationPages = 2).getOrNull() }
                    val allItems = likedDef.await()?.items.orEmpty() + landingDef.await()?.items.orEmpty()
                    allItems.filterIsInstance<PlaylistItem>()
                        .filterNot { it.id == "LM" || it.id == "SE" || it.id in unlikedSet || it.id.removePrefix("VL") in unlikedSet }
                        .map(::toPlaylist)
                }
            } else emptyList()
            (local + remote).distinctBy { cleanId(it.uri).removePrefix("VL") }
        }

        override suspend fun editable(): List<Playlist> = withContext(Dispatchers.IO) {
            val all = mine()
            val editables = all.filter { it.editable }
            editables.ifEmpty { all }
        }

        override suspend fun create(name: String, folderUri: Uri?): Uri = withContext(Dispatchers.IO) {
            val remoteId = if (account.isSignedIn) {
                runCatching { YouTube.createPlaylist(name) }.getOrNull()
            } else null
            val id = remoteId?.takeIf { it.isNotBlank() } ?: "custom_${System.currentTimeMillis()}"
            val uri = "spotify:playlist:yt_$id"
            val newPl = Playlist(
                uri = uri,
                name = name,
                description = "Created Playlist",
                owner = account.accountName.value ?: "You",
                images = emptyList(),
                trackCount = 0,
                editable = true,
            )
            val current = loadSavedPlaylists().filterNot { cleanId(it.uri).removePrefix("VL") == id.removePrefix("VL") }.toMutableList()
            current.add(0, newPl)
            saveSavedPlaylists(current)
            markEntitySaved(uri, true)
            _libraryChanges.tryEmit(Unit)
            uri
        }

        override suspend fun rename(uri: Uri, name: String) = withContext(Dispatchers.IO) {
            val id = cleanId(uri)
            val current = loadSavedPlaylists().map {
                if (cleanId(it.uri).removePrefix("VL") == id.removePrefix("VL")) it.copy(name = name) else it
            }
            saveSavedPlaylists(current)
            if (account.isSignedIn && id.isNotBlank() && !id.startsWith("custom_")) {
                runCatching { YouTube.renamePlaylist(id.removePrefix("VL"), name) }
            }
            _libraryChanges.tryEmit(Unit)
            Unit
        }
        override suspend fun setDescription(uri: Uri, description: String) = withContext(Dispatchers.IO) {
            val id = cleanId(uri)
            val current = loadSavedPlaylists().map {
                if (cleanId(it.uri).removePrefix("VL") == id.removePrefix("VL")) it.copy(description = description) else it
            }
            saveSavedPlaylists(current)
            _libraryChanges.tryEmit(Unit)
            Unit
        }
        override suspend fun access(uri: Uri): PlaylistAccess = PlaylistAccess.PUBLIC
        override suspend fun setAccess(uri: Uri, access: PlaylistAccess) {}
        override suspend fun published(uri: Uri): Boolean = true
        override suspend fun setPublished(uri: Uri, published: Boolean) {}
        override suspend fun setCover(uri: Uri, jpeg: ByteArray?) {}
        override suspend fun delete(uri: Uri) = withContext(Dispatchers.IO) {
            val id = cleanId(uri)
            markEntitySaved(uri, false)
            val updated = loadSavedPlaylists().filterNot {
                cleanId(it.uri).removePrefix("VL") == id.removePrefix("VL")
            }
            saveSavedPlaylists(updated)
            if (account.isSignedIn && id.isNotBlank() && !id.startsWith("custom_")) {
                runCatching { YouTube.deletePlaylist(id.removePrefix("VL")) }
                runCatching { YouTube.likePlaylist(id.removePrefix("VL"), false) }
            }
            _libraryChanges.tryEmit(Unit)
            Unit
        }
        override suspend fun follow(uri: Uri) = withContext(Dispatchers.IO) {
            val id = cleanId(uri)
            if (id.isBlank()) return@withContext
            markEntitySaved(uri, true)
            val pl = runCatching { playlist(uri) }.getOrNull() ?: Playlist(
                uri = "spotify:playlist:yt_$id",
                name = "Saved Playlist",
                description = "YouTube Music Playlist",
                owner = "YouTube Music",
                images = emptyList(),
                trackCount = 0,
                editable = false,
            )
            val canonicalUri = if (uri.startsWith("spotify:playlist:yt_")) uri else "spotify:playlist:yt_$id"
            val current = loadSavedPlaylists().filterNot {
                cleanId(it.uri).removePrefix("VL") == id.removePrefix("VL")
            }.toMutableList()
            current.add(0, pl.copy(uri = canonicalUri))
            saveSavedPlaylists(current)
            if (account.isSignedIn && !id.startsWith("custom_")) {
                runCatching { YouTube.likePlaylist(id.removePrefix("VL"), true) }
            }
            _libraryChanges.tryEmit(Unit)
            Unit
        }
        override suspend fun unfollow(uri: Uri) = withContext(Dispatchers.IO) {
            val id = cleanId(uri)
            if (id.isBlank()) return@withContext
            markEntitySaved(uri, false)
            val updated = loadSavedPlaylists().filterNot {
                cleanId(it.uri).removePrefix("VL") == id.removePrefix("VL")
            }
            saveSavedPlaylists(updated)
            if (account.isSignedIn && !id.startsWith("custom_")) {
                runCatching { YouTube.likePlaylist(id.removePrefix("VL"), false) }
            }
            _libraryChanges.tryEmit(Unit)
            Unit
        }
        override suspend fun join(uri: Uri, invitationToken: String): PlaylistAccess = PlaylistAccess.PUBLIC
        override suspend fun collaboratorInvite(uri: Uri): String = ""
        override suspend fun members(uri: Uri): List<PlaylistMember> = emptyList()
        override suspend fun leave(uri: Uri) = unfollow(uri)
        override suspend fun setMemberCollaborator(uri: Uri, username: String, collaborator: Boolean) {}
        override suspend fun removeMember(uri: Uri, username: String) {}
        override suspend fun stopCollaboration(uri: Uri) {}
        override suspend fun addedBy(uri: Uri): List<RowAdder> = emptyList()
        override suspend fun addTracks(uri: Uri, trackUris: List<Uri>) = withContext(Dispatchers.IO) {
            val playlistId = cleanId(uri)
            if (playlistId.isNotBlank()) {
                val existing = loadCustomPlaylistTracks(playlistId).toMutableList()
                for (tUri in trackUris) {
                    val videoId = cleanId(tUri)
                    if (videoId.isNotBlank()) {
                        val tr = runCatching { catalog.track("spotify:track:yt_$videoId") }.getOrNull()
                        if (tr != null && existing.none { cleanId(it.uri) == videoId }) {
                            existing.add(tr)
                        }
                        if (account.isSignedIn && !playlistId.startsWith("custom_")) {
                            runCatching { YouTube.addToPlaylist(playlistId.removePrefix("VL"), videoId) }
                        }
                    }
                }
                saveCustomPlaylistTracks(playlistId, existing)
                val updatedPlaylists = loadSavedPlaylists().map {
                    if (cleanId(it.uri).removePrefix("VL") == playlistId.removePrefix("VL")) {
                        val cover = it.images.firstOrNull()?.url?.takeIf { u -> u.isNotBlank() }
                            ?: existing.firstOrNull()?.album?.images?.firstOrNull()?.url.orEmpty()
                        it.copy(
                            trackCount = existing.size.coerceAtLeast(it.trackCount),
                            images = if (cover.isNotBlank()) listOf(Image(cover, 500, 500)) else it.images,
                        )
                    } else it
                }
                saveSavedPlaylists(updatedPlaylists)
                playlistPageCache.remove(playlistId)
                _libraryChanges.tryEmit(Unit)
            }
            Unit
        }
        override suspend fun removeTracks(uri: Uri, uids: List<String>) = withContext(Dispatchers.IO) {
            val playlistId = cleanId(uri)
            if (playlistId.isNotBlank()) {
                val removeVideoIds = uids.map { cleanId(it.substringBefore(':')) }.toSet()
                val existing = loadCustomPlaylistTracks(playlistId).filterNot { cleanId(it.uri) in removeVideoIds }
                saveCustomPlaylistTracks(playlistId, existing)
                for (uid in uids) {
                    val videoId = cleanId(uid.substringBefore(':'))
                    val setVideoId = uid.substringAfter(':', "")
                    if (videoId.isNotBlank() && account.isSignedIn && !playlistId.startsWith("custom_")) {
                        runCatching { YouTube.removeFromPlaylist(playlistId.removePrefix("VL"), videoId, setVideoId) }
                    }
                }
                playlistPageCache.remove(playlistId)
                _libraryChanges.tryEmit(Unit)
            }
            Unit
        }
        override suspend fun moveTracks(uri: Uri, uids: List<String>, beforeUid: String?) {}
        override suspend fun recommended(uri: Uri, limit: Int, skip: List<Uri>): List<Track> = emptyList()
    }

    override val catalog: Catalog = object : Catalog {
        override suspend fun album(uri: Uri): Album = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val id = cleanId(uri)
            val page = albumPageCache[id] ?: YouTube.album(id).getOrNull()?.also { albumPageCache[id] = it }
            if (page != null) {
                toAlbum(page.album).copy(trackCount = page.songs.size)
            } else {
                loadSavedAlbums().firstOrNull { cleanId(it.uri) == id }?.copy(uri = uri)
                    ?: Album(
                        uri = uri,
                        name = "Album",
                        artists = listOf(ArtistRef("spotify:artist:yt_unknown", "Unknown Artist")),
                        images = emptyList(),
                        type = "album",
                        releaseDate = null,
                        trackCount = 0,
                    )
            }
        }

        override suspend fun albumTracks(uri: Uri, offset: Int, limit: Int): Page<Track> = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val id = cleanId(uri)
            val page = albumPageCache[id] ?: YouTube.album(id).getOrNull()?.also { albumPageCache[id] = it }
            val songs = page?.songs ?: emptyList()

            val items = songs.map { Triple(it.id, it.title, it.artists.joinToString(", ") { a -> a.name }) }
            val meta = songs.map { Triple(it.thumbnail, it.album?.name.orEmpty(), (it.duration ?: 180) * 1000L) }
            registerContextTracksInPlayers(uri, items, meta)

            val paged = songs.drop(offset).take(limit).map(::toTrack)
            Page(paged, offset, songs.size, if (offset + limit < songs.size) offset + limit else null)
        }

        override suspend fun artist(uri: Uri): Artist = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val id = cleanId(uri)
            val page = artistPageCache[id] ?: YouTube.artist(id).getOrNull()?.also { artistPageCache[id] = it }
            if (page != null) {
                Artist(
                    uri = uri,
                    name = page.artist.title,
                    images = listOf(Image(page.artist.thumbnail.orEmpty(), 500, 500)),
                )
            } else {
                Artist(uri = uri, name = "Artist", images = emptyList())
            }
        }

        override suspend fun artistTopTracks(uri: Uri): List<Track> = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val id = cleanId(uri)
            val page = artistPageCache[id] ?: YouTube.artist(id).getOrNull()?.also { artistPageCache[id] = it }
            val topSection = page?.sections?.firstOrNull { it.items.any { item -> item is SongItem } }
            val songs = topSection?.items?.filterIsInstance<SongItem>() ?: emptyList()

            val items = songs.map { Triple(it.id, it.title, it.artists.joinToString(", ") { a -> a.name }) }
            val meta = songs.map { Triple(it.thumbnail, it.album?.name.orEmpty(), (it.duration ?: 180) * 1000L) }
            registerContextTracksInPlayers(uri, items, meta)

            songs.map(::toTrack)
        }

        override suspend fun artistPage(uri: Uri): ArtistPage = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val id = cleanId(uri)
            val page = artistPageCache[id] ?: YouTube.artist(id).getOrNull()?.also { artistPageCache[id] = it }
            val artistObj = page?.artist?.let(::toArtist) ?: Artist(uri, "Artist", emptyList())
            val topSongs = page?.sections?.firstOrNull { it.items.any { item -> item is SongItem } }
                ?.items?.filterIsInstance<SongItem>() ?: emptyList()

            if (topSongs.isNotEmpty()) {
                val items = topSongs.map { Triple(it.id, it.title, it.artists.joinToString(", ") { a -> a.name }) }
                val meta = topSongs.map { Triple(it.thumbnail, it.album?.name.orEmpty(), (it.duration ?: 180) * 1000L) }
                registerContextTracksInPlayers(uri, items, meta)
            }

            val albums = page?.sections?.flatMap { it.items }?.filterIsInstance<AlbumItem>()?.map(::toAlbum) ?: emptyList()
            val related = page?.sections?.flatMap { it.items }?.filterIsInstance<ArtistItem>()?.map(::toArtist) ?: emptyList()

            ArtistPage(
                artist = artistObj,
                biography = page?.description,
                headerImages = listOf(Image(page?.artist?.thumbnail.orEmpty(), 500, 500)),
                topTracks = topSongs.map { app.wavebits.api.TopTrack(toTrack(it), null) },
                popularReleases = albums,
                appearsOn = emptyList(),
                related = related,
            )
        }

        override suspend fun track(uri: Uri): Track = withContext(Dispatchers.IO) {
            val id = cleanId(uri)
            // Fast-path: return cached track metadata immediately without firing a network search
            ytPlayer.getRegisteredTrack(id)?.let { return@withContext trackFromData(it) }

            ensureVisitorData()
            val searchRes = YouTube.search(id, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            val song = searchRes?.items?.filterIsInstance<SongItem>()?.firstOrNull { it.id == id }
                ?: searchRes?.items?.filterIsInstance<SongItem>()?.firstOrNull()

            if (song != null) toTrack(song)
            else Track(
                uri = uri,
                name = "YouTube Track",
                durationMs = 180_000L,
                artists = listOf(ArtistRef("spotify:artist:yt_unknown", "YouTube Music")),
                album = null,
            )
        }

        override suspend fun isrc(trackUri: Uri): String? = null
        override suspend fun discography(uri: Uri, offset: Int, limit: Int): Page<Album> = Page(emptyList(), 0, 0, null)
        override suspend fun relatedArtists(uri: Uri): List<Artist> = emptyList()
        override suspend fun appearsOn(uri: Uri): List<Album> = emptyList()
        override suspend fun similarAlbums(trackUri: Uri, limit: Int): List<Album> = emptyList()
    }

    override val library: Library = object : Library {
        override val changes: Flow<Unit> = _libraryChanges

        private fun loadGuestLikedTracks(): List<Track> {
            val raw = guestPrefs.getString("liked_tracks_json", null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                val list = mutableListOf<Track>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    val title = o.optString("title", "Track")
                    val artist = o.optString("artist", "Artist")
                    val album = o.optString("album", "")
                    val cover = o.optString("coverUrl", "https://i.ytimg.com/vi/$id/hqdefault.jpg")
                    val duration = o.optLong("durationMs", 180_000L)
                    list.add(
                        Track(
                            uri = "spotify:track:yt_$id",
                            name = title,
                            durationMs = duration,
                            artists = listOf(ArtistRef("spotify:artist:yt_$id", artist)),
                            album = AlbumRef("spotify:album:yt_$id", album, listOf(Image(cover, 500, 500))),
                            explicit = false,
                            playable = true,
                        )
                    )
                }
                list
            }.getOrDefault(emptyList())
        }

        private fun saveGuestLikedTracks(tracks: List<Track>) {
            runCatching {
                val arr = JSONArray()
                for (t in tracks) {
                    val id = cleanId(t.uri)
                    val o = JSONObject()
                    o.put("id", id)
                    o.put("title", t.name)
                    o.put("artist", t.artists.joinToString(", ") { it.name })
                    o.put("album", t.album?.name.orEmpty())
                    o.put("coverUrl", t.album?.images?.firstOrNull()?.url.orEmpty())
                    o.put("durationMs", t.durationMs)
                    arr.put(o)
                }
                guestPrefs.edit().putString("liked_tracks_json", arr.toString()).apply()
            }
        }

        override suspend fun contains(uris: List<Uri>): List<Boolean> =
            uris.map { u -> isEntitySaved(u) }

        override suspend fun add(uris: List<Uri>) = withContext(Dispatchers.IO) {
            val currentGuestTracks = loadGuestLikedTracks().toMutableList()
            val currentSavedAlbums = loadSavedAlbums().toMutableList()
            for (u in uris) {
                val cid = cleanId(u)
                markEntitySaved(u, true, persist = false)
                when {
                    u.startsWith("spotify:playlist:") -> {
                        playlists.follow(u)
                    }
                    u.startsWith("spotify:album:") -> {
                        val al = runCatching { catalog.album(u) }.getOrNull()
                        if (al != null) {
                            currentSavedAlbums.removeAll { cleanId(it.uri) == cid }
                            currentSavedAlbums.add(0, al.copy(uri = "spotify:album:yt_$cid"))
                        }
                        if (account.isSignedIn) {
                            val playlistId = albumPageCache[cid]?.album?.playlistId
                            if (!playlistId.isNullOrBlank()) {
                                runCatching { YouTube.likePlaylist(playlistId.removePrefix("VL"), true) }
                            }
                        }
                    }
                    u.startsWith("spotify:track:") -> {
                        if (currentGuestTracks.none { cleanId(it.uri) == cid }) {
                            val tr = runCatching { catalog.track(u) }.getOrNull()
                            if (tr != null) {
                                currentGuestTracks.add(0, tr)
                            }
                        }
                        if (account.isSignedIn) {
                            runCatching { YouTube.likeVideo(cid, true) }
                        }
                    }
                    u.startsWith("spotify:artist:") -> {
                        if (account.isSignedIn) {
                            runCatching { YouTube.subscribeChannel(cid, true) }
                        }
                    }
                }
            }
            persistLikedIds()
            saveGuestLikedTracks(currentGuestTracks)
            saveSavedAlbums(currentSavedAlbums)
            _libraryChanges.tryEmit(Unit)
            Unit
        }

        override suspend fun remove(uris: List<Uri>) = withContext(Dispatchers.IO) {
            val removeIds = uris.map(::cleanId).toSet()
            uris.forEach { u ->
                val cid = cleanId(u)
                markEntitySaved(u, false, persist = false)
                when {
                    u.startsWith("spotify:playlist:") -> {
                        playlists.unfollow(u)
                    }
                    u.startsWith("spotify:album:") -> {
                        if (account.isSignedIn) {
                            val playlistId = albumPageCache[cid]?.album?.playlistId
                            if (!playlistId.isNullOrBlank()) {
                                runCatching { YouTube.likePlaylist(playlistId.removePrefix("VL"), false) }
                            }
                        }
                    }
                    u.startsWith("spotify:track:") -> {
                        if (account.isSignedIn) {
                            runCatching { YouTube.likeVideo(cid, false) }
                        }
                    }
                    u.startsWith("spotify:artist:") -> {
                        if (account.isSignedIn) {
                            runCatching { YouTube.subscribeChannel(cid, false) }
                        }
                    }
                }
            }
            persistLikedIds()
            saveGuestLikedTracks(loadGuestLikedTracks().filterNot { cleanId(it.uri) in removeIds })
            saveSavedAlbums(loadSavedAlbums().filterNot { cleanId(it.uri) in removeIds })
            _libraryChanges.tryEmit(Unit)
            Unit
        }

        override suspend fun items(
            filter: LibraryFilter,
            sort: LibrarySort,
            folderUri: Uri?,
            offset: Int,
            limit: Int,
        ): Page<LibraryItem> = withContext(Dispatchers.IO) {
            val localPlaylistItems = loadSavedPlaylists().map { pl ->
                markEntitySaved(pl.uri, true, persist = false)
                LibraryItem(
                    uri = if (pl.uri.startsWith("spotify:playlist:yt_")) pl.uri else "spotify:playlist:yt_${cleanId(pl.uri)}",
                    kind = MediaKind.PLAYLIST,
                    name = pl.name,
                    subtitle = pl.owner ?: "Playlist",
                    images = pl.images,
                    pinned = false,
                    addedAt = null,
                    count = pl.trackCount.takeIf { it > 0 },
                )
            }
            val localAlbumItems = loadSavedAlbums().map { al ->
                markEntitySaved(al.uri, true, persist = false)
                LibraryItem(
                    uri = if (al.uri.startsWith("spotify:album:yt_")) al.uri else "spotify:album:yt_${cleanId(al.uri)}",
                    kind = MediaKind.ALBUM,
                    name = al.name,
                    subtitle = al.artists.joinToString(", ") { it.name }.ifBlank { "Album" },
                    images = al.images,
                    pinned = false,
                    addedAt = null,
                )
            }

            if (!account.isAuthenticatedUser) {
                val allLocal = when (filter) {
                    LibraryFilter.PLAYLISTS -> localPlaylistItems
                    LibraryFilter.ALBUMS -> localAlbumItems
                    LibraryFilter.ARTISTS -> emptyList()
                    else -> localPlaylistItems + localAlbumItems
                }
                val paged = allLocal.drop(offset).take(limit)
                return@withContext Page(paged, offset, allLocal.size, if (offset + limit < allLocal.size) offset + limit else null)
            }

            ensureSessionReady()

            if (filter == LibraryFilter.ARTISTS) {
                val (trackArtistLib, subArtistLib, landingLib) = coroutineScope {
                    val aDef = async { YouTube.library("FEmusic_library_corpus_track_artists", maxContinuationPages = 3).getOrNull() }
                    val sDef = async { YouTube.library("FEmusic_library_corpus_artists", maxContinuationPages = 3).getOrNull() }
                    val lDef = async { YouTube.library("FEmusic_library_landing", maxContinuationPages = 2).getOrNull() }
                    Triple(aDef.await(), sDef.await(), lDef.await())
                }
                val artistItems = (trackArtistLib?.items.orEmpty() + subArtistLib?.items.orEmpty() + landingLib?.items.orEmpty())
                    .filterIsInstance<ArtistItem>()
                    .distinctBy { it.id.removePrefix("MPLA") }
                    .map {
                        val cleanArtistId = it.id.removePrefix("MPLA")
                        val uri = "spotify:artist:yt_$cleanArtistId"
                        markEntitySaved(uri, true, persist = false)
                        LibraryItem(
                            uri = uri,
                            kind = MediaKind.ARTIST,
                            name = it.title,
                            subtitle = "Artist",
                            images = listOf(Image(it.thumbnail.orEmpty(), 500, 500)),
                            pinned = false,
                            addedAt = null,
                        )
                    }
                if (artistItems.isNotEmpty()) persistLikedIds()
                val paged = artistItems.drop(offset).take(limit)
                return@withContext Page(paged, offset, artistItems.size, if (offset + limit < artistItems.size) offset + limit else null)
            }

            val (libPage, albPage, landingPage) = coroutineScope {
                val plDef = async {
                    if (filter == LibraryFilter.ALBUMS) null else YouTube.library("FEmusic_liked_playlists", maxContinuationPages = 3).getOrNull()
                }
                val alDef = async {
                    if (filter == LibraryFilter.PLAYLISTS) null else YouTube.library("FEmusic_liked_albums", maxContinuationPages = 3).getOrNull()
                }
                val landDef = async {
                    YouTube.library("FEmusic_library_landing", maxContinuationPages = 2).getOrNull()
                }
                Triple(plDef.await(), alDef.await(), landDef.await())
            }

            val allRemoteItems = libPage?.items.orEmpty() + albPage?.items.orEmpty() + landingPage?.items.orEmpty()

            val remotePlItems = allRemoteItems.filterIsInstance<PlaylistItem>()
                .filterNot { it.id == "LM" || it.id == "SE" || it.id in unlikedSet || it.id.removePrefix("VL") in unlikedSet }
                .map {
                    val uri = "spotify:playlist:yt_${it.id.removePrefix("VL")}"
                    markEntitySaved(uri, true, persist = false)
                    LibraryItem(
                        uri = uri,
                        kind = MediaKind.PLAYLIST,
                        name = it.title,
                        subtitle = it.author?.name ?: "Playlist",
                        images = listOf(Image(it.thumbnail.orEmpty(), 500, 500)),
                        pinned = false,
                        addedAt = null,
                        count = it.songCountText?.filter { c -> c.isDigit() }?.toIntOrNull(),
                    )
                }

            val remoteAlbumItems = allRemoteItems.filterIsInstance<AlbumItem>()
                .filterNot { it.browseId in unlikedSet }
                .map {
                    val uri = "spotify:album:yt_${it.browseId}"
                    markEntitySaved(uri, true, persist = false)
                    LibraryItem(
                        uri = uri,
                        kind = MediaKind.ALBUM,
                        name = it.title,
                        subtitle = it.artists?.joinToString(", ") { a -> a.name } ?: "Album",
                        images = listOf(Image(it.thumbnail, 500, 500)),
                        pinned = false,
                        addedAt = null,
                    )
                }

            if (remotePlItems.isNotEmpty() || remoteAlbumItems.isNotEmpty()) {
                persistLikedIds()
            }

            val plItems = (localPlaylistItems + remotePlItems).distinctBy { cleanId(it.uri).removePrefix("VL") }
            val albumItems = (localAlbumItems + remoteAlbumItems).distinctBy { cleanId(it.uri) }

            val all = when (filter) {
                LibraryFilter.PLAYLISTS -> plItems
                LibraryFilter.ALBUMS -> albumItems
                else -> plItems + albumItems
            }
            val paged = all.drop(offset).take(limit)
            Page(paged, offset, all.size, if (offset + limit < all.size) offset + limit else null)
        }

        override suspend fun likedTracks(offset: Int, limit: Int): Page<SavedTrack> = withContext(Dispatchers.IO) {
            val guestTracks = loadGuestLikedTracks()
            if (!account.isAuthenticatedUser) {
                if (guestTracks.isNotEmpty()) {
                    val items = guestTracks.map { Triple(cleanId(it.uri), it.name, it.artists.joinToString(", ") { a -> a.name }) }
                    val meta = guestTracks.map { Triple(it.album?.images?.firstOrNull()?.url.orEmpty(), it.album?.name.orEmpty(), it.durationMs) }
                    registerContextTracksInPlayers("spotify:collection:tracks", items, meta)
                }
                val paged = guestTracks.drop(offset).take(limit).map { SavedTrack(it, null) }
                return@withContext Page(paged, offset, guestTracks.size, if (offset + limit < guestTracks.size) offset + limit else null)
            }

            ensureSessionReady()
            val firstPl = YouTube.playlist("LM").getOrNull()
            val collected = firstPl?.songs.orEmpty().toMutableList()
            val seenIds = collected.mapTo(HashSet()) { it.id }
            var contToken = firstPl?.continuation
            val seenTokens = HashSet<String>()
            var contPages = 0
            while (!contToken.isNullOrBlank() && seenTokens.add(contToken) && contPages < 10) {
                contPages++
                val nextPl = YouTube.playlistContinuation(contToken, "LM").getOrNull() ?: break
                var addedAny = false
                for (song in nextPl.songs) {
                    if (seenIds.add(song.id)) {
                        collected.add(song)
                        addedAny = true
                    }
                }
                contToken = if (addedAny) nextPl.continuation else null
            }

            var songs: List<SongItem> = collected
            if (songs.isEmpty()) {
                val likedLib = YouTube.library("FEmusic_liked_videos").getOrNull()
                songs = likedLib?.items?.filterIsInstance<SongItem>().orEmpty()
            }

            val remoteTracks = songs.map(::toTrack)
            if (songs.isNotEmpty()) {
                songs.forEach { markEntitySaved("spotify:track:yt_${it.id}", true, persist = false) }
                persistLikedIds()
            }

            val combined = (guestTracks + remoteTracks).distinctBy { cleanId(it.uri) }
            if (combined.isNotEmpty()) {
                val items = combined.map { Triple(cleanId(it.uri), it.name, it.artists.joinToString(", ") { a -> a.name }) }
                val meta = combined.map { Triple(it.album?.images?.firstOrNull()?.url.orEmpty(), it.album?.name.orEmpty(), it.durationMs) }
                registerContextTracksInPlayers("spotify:collection:tracks", items, meta)
            }

            val paged = combined.drop(offset).take(limit).map { SavedTrack(it, null) }
            Page(paged, offset, combined.size, if (offset + limit < combined.size) offset + limit else null)
        }

        override suspend fun pin(uri: Uri) {}
        override suspend fun unpin(uri: Uri) {}
        override suspend fun createFolder(name: String, parentFolderUri: Uri?): Uri = "spotify:folder:yt_folder"
        override suspend fun renameFolder(folderUri: Uri, name: String) {}
        override suspend fun deleteFolder(folderUri: Uri) {}
        override suspend fun moveToFolder(uri: Uri, folderUri: Uri?) {}
    }

    override val users: Users = object : Users {
        private fun displayName(): String {
            if (account.isSignedIn) {
                return account.accountName.value ?: "YouTube User"
            }
            return "YouTube Music"
        }

        private fun avatarImages(): List<Image> {
            val url = account.accountAvatar.value
            return url?.takeIf { it.isNotBlank() }?.let { listOf(Image(it, 300, 300)) } ?: emptyList()
        }

        override suspend fun me(): Account = Account(
            uri = "spotify:user:youtube_user",
            username = displayName(),
            name = displayName(),
            images = avatarImages(),
            premium = true,
            country = "US",
        )

        override suspend fun profile(userUri: Uri): UserProfile = UserProfile(
            uri = userUri,
            name = displayName(),
            images = avatarImages(),
            followers = 0,
            following = 0,
            publicPlaylists = emptyList(),
        )

        override suspend fun followers(userUri: Uri): List<UserRef> = emptyList()
        override suspend fun following(userUri: Uri): List<UserRef> = emptyList()
        override suspend fun top(range: TimeRange, limit: Int): TopContent = withContext(Dispatchers.IO) {
            if (!account.isAuthenticatedUser) {
                return@withContext TopContent(artists = emptyList(), tracks = emptyList())
            }
            coroutineScope {
                val histDef = async { YouTube.history().getOrNull().orEmpty() }
                val artDef = async { YouTube.library("FEmusic_library_corpus_track_artists").getOrNull() }
                val songs = histDef.await().take(limit)
                val artists = artDef.await()?.items?.filterIsInstance<ArtistItem>()?.take(limit)?.map(::toArtist).orEmpty()
                TopContent(
                    artists = artists,
                    tracks = songs.map(::toTrack),
                )
            }
        }
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

    override val podcasts: Podcasts = object : Podcasts {
        override suspend fun show(uri: Uri): Show = Show(
            uri = uri, name = "Podcast", publisher = "", description = null, images = emptyList(),
            saved = false, rating = null, topics = emptyList(),
        )
        override suspend fun audioUrl(uri: Uri): EpisodeAudio = EpisodeAudio(url = null, passthrough = "NONE", format = "MP4_128")
        override suspend fun episodes(uri: Uri, offset: Int, limit: Int): Page<Episode> = Page(emptyList(), 0, 0, null)
        override suspend fun episode(uri: Uri): Episode = Episode(
            uri = uri, name = "Episode", description = null, durationMs = 0L, releaseDate = null,
            images = emptyList(), show = null, playedPositionMs = 0L, played = false,
            explicit = false, playable = false, previewUrl = null,
        )
        override suspend fun audiobook(uri: Uri): Audiobook = Audiobook(
            uri = uri, name = "Audiobook", authors = emptyList(), narrators = emptyList(),
            description = null, images = emptyList(), chapterCount = 0,
        )
        override suspend fun chapters(uri: Uri, offset: Int, limit: Int): Page<Episode> = Page(emptyList(), 0, 0, null)
        override suspend fun savedEpisodes(offset: Int, limit: Int): Page<Episode> = Page(emptyList(), 0, 0, null)
        override suspend fun setEpisodeSaved(uri: Uri, saved: Boolean) {}
    }

    override val extras: TrackExtras = object : TrackExtras {
        private val lrcRegex = Regex("""^\[(\d{2}):(\d{2})\.(\d{2,3})](.*)$""")

        override suspend fun lyrics(trackUri: Uri): Lyrics? = withContext(Dispatchers.IO) {
            ensureVisitorData()
            val id = cleanId(trackUri)
            if (id.isBlank()) return@withContext null

            // 1. Try WaveBits YouTubeSubtitleLyricsProvider (synced LRC transcript)
            val transcriptLrc = runCatching { YouTube.transcript(id).getOrNull() }.getOrNull()
            if (!transcriptLrc.isNullOrBlank()) {
                val syncedLines = transcriptLrc.lines().mapNotNull { line ->
                    val m = lrcRegex.find(line.trim()) ?: return@mapNotNull null
                    val min = m.groupValues[1].toLongOrNull() ?: 0L
                    val sec = m.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = m.groupValues[3]
                    val ms = if (fracStr.length == 2) (fracStr.toLongOrNull() ?: 0L) * 10L else (fracStr.toLongOrNull() ?: 0L)
                    val text = m.groupValues[4].trim()
                    if (text.isBlank()) null
                    else LyricsLine(startMs = min * 60_000L + sec * 1_000L + ms, endMs = null, text = text)
                }
                if (syncedLines.isNotEmpty()) {
                    return@withContext Lyrics(
                        lines = syncedLines,
                        synced = true,
                        language = null,
                        provider = "YouTube Music",
                        background = null,
                        text = null,
                        highlight = null,
                    )
                }
            }

            // 2. Fallback to YouTube Music watch-next browse lyrics
            val nextRes = runCatching {
                YouTube.next(WatchEndpoint(videoId = id)).getOrNull()
            }.getOrNull() ?: return@withContext null
            val lyricsEndpoint = nextRes.lyricsEndpoint ?: return@withContext null
            val rawLyrics = runCatching { YouTube.lyrics(lyricsEndpoint).getOrNull() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: return@withContext null
            val lines = rawLyrics.lines().map { LyricsLine(startMs = 0L, endMs = null, text = it) }
            Lyrics(
                lines = lines,
                synced = false,
                language = null,
                provider = "YouTube Music",
                background = null,
                text = null,
                highlight = null,
            )
        }

        override suspend fun canvas(trackUri: Uri): Canvas? = null
        override suspend fun credits(trackUri: Uri): Credits = Credits(roles = emptyList())
        override suspend fun radio(seedUri: Uri): Uri? = "spotify:playlist:yt_RDAMPL${cleanId(seedUri)}"
        override suspend fun setExcludedFromTaste(trackUri: Uri, excluded: Boolean) {}
        override suspend fun isExcludedFromTaste(trackUris: List<Uri>): List<Boolean> = trackUris.map { false }
        override suspend fun setArtistBanned(artistUri: Uri, banned: Boolean) {}
        override suspend fun isArtistBanned(artistUris: List<Uri>): List<Boolean> = artistUris.map { false }
    }

    override val devices: Devices = object : Devices {
        override val devices: StateFlow<List<Device>> = MutableStateFlow(
            listOf(Device(id = "yt_device", name = "This Device (YouTube Music)", type = "Smartphone", active = true, volume = 1.0, local = true))
        )
        override suspend fun refresh() {}
        override suspend fun transfer(deviceId: String, play: Boolean) {}
        override suspend fun pullHere() {}
    }

    override val jam: Jam = object : Jam {
        override suspend fun availability(): JamAvailability = JamAvailability.RESTRICTED
        override val session: StateFlow<JamSession?> = MutableStateFlow(null)
        override suspend fun refresh() {}
        override suspend fun start(): JamSession = throw UnsupportedOperationException("Jam not available in Guest / YouTube Music mode")
        override suspend fun join(joinToken: String): JamSession = throw UnsupportedOperationException("Jam not available in Guest / YouTube Music mode")
        override suspend fun leave() {}
        override suspend fun end() {}
        override suspend fun inviteLink(): String? = null
        override suspend fun removeMember(userUri: Uri) {}
        override suspend fun setQueueOnly(on: Boolean) {}
        override suspend fun setParticipantVolumeControl(on: Boolean) {}
    }

    override val blends: Blends = object : Blends {
        override suspend fun invite(): BlendInvite = BlendInvite(id = "yt", link = "")
        override suspend fun invitation(linkOrId: String): BlendInvitation = BlendInvitation(
            state = BlendInvitationState.JOINABLE, title = null, subtitle = null,
            inviter = null, members = emptyList(), playlist = null,
        )
        override suspend fun join(linkOrId: String): Uri = "spotify:playlist:yt_blend"
        override suspend fun group(playlist: Uri): BlendGroup = BlendGroup(
            title = null, inviteLabel = null, link = null, members = emptyList(),
        )
        override suspend fun story(playlist: Uri): BlendStory? = null
        override suspend fun rename(playlist: Uri, name: String) {}
        override suspend fun leave(playlist: Uri) {}
    }

    override val recents: Recents = object : Recents {
        override suspend fun recentEntries(offset: Int, limit: Int): Page<RecentEntry> = Page(emptyList(), 0, 0, null)
    }
}
