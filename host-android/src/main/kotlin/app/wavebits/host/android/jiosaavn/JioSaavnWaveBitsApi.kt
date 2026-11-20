package app.wavebits.host.android.jiosaavn

import android.content.Context
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
import app.wavebits.api.NowPlaying
import app.wavebits.api.Page
import app.wavebits.api.Player
import app.wavebits.api.PlayerState
import app.wavebits.api.Playlist
import app.wavebits.api.PlaylistAccess
import app.wavebits.api.PlaylistMember
import app.wavebits.api.PlaylistSort
import app.wavebits.api.Playlists
import app.wavebits.api.Podcasts
import app.wavebits.api.Queue
import app.wavebits.api.RecentEntry
import app.wavebits.api.Recents
import app.wavebits.api.Repeat
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
import app.wavebits.api.sortedFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Full [WaveBitsApi] implementation powered by [JioSaavnApiClient]
 * matching https://github.com/cyberboysumanjay/JioSaavnAPI.
 */
class JioSaavnWaveBitsApi(
    private val context: Context,
    val client: JioSaavnApiClient = JioSaavnApiClient(),
    val scope: CoroutineScope,
) : WaveBitsApi {

    val jioPlayer = JioSaavnPlayer(context, client, scope)

    private val _ready = MutableStateFlow(true)
    override val ready: StateFlow<Boolean> = _ready

    private val libraryPrefs by lazy {
        context.getSharedPreferences("jiosaavn_library_prefs", Context.MODE_PRIVATE)
    }

    private fun cleanId(uri: Uri): String =
        uri.substringAfterLast(':').removePrefix("jio_").removePrefix("yt_").substringBefore('|')

    private fun trackOf(t: JioSaavnApiClient.JioTrack): Track {
        val cid = t.id
        val artistRefs = if (t.primaryArtists.isNotEmpty()) {
            t.primaryArtists.map { (aid, aname) ->
                val effectiveId = aid.ifBlank { cid.take(8) }
                client.registerQueryHint(effectiveId, aname)
                ArtistRef(uri = "spotify:artist:jio_$effectiveId", name = aname)
            }
        } else {
            val fallbackId = cid.take(8)
            client.registerQueryHint(fallbackId, t.artist)
            listOf(ArtistRef(uri = "spotify:artist:jio_$fallbackId", name = t.artist))
        }
        return Track(
            uri = "spotify:track:jio_$cid",
            name = t.title,
            artists = artistRefs,
            album = AlbumRef(
                uri = "spotify:album:jio_${t.albumId.ifBlank { cid.take(8) }}",
                name = t.album,
                images = listOf(Image(url = t.imageUrl, width = 500, height = 500)),
            ),
            durationMs = (t.durationSec * 1000L).coerceAtLeast(15_000L),
            explicit = t.explicit,
            playable = true,
        ).also {
            jioPlayer.cacheTrack(it)
            jioPlayer.registerQueryHint(cid, "${t.title} - ${t.artist}")
        }
    }

    override val player: Player = object : Player {
        override val state: StateFlow<PlayerState?> = jioPlayer.state
        override val queue: StateFlow<Queue?> = jioPlayer.queue

        override suspend fun play(
            contextUri: Uri,
            trackUri: Uri?,
            index: Int?,
            positionMs: Long?,
            order: List<Uri>?,
        ) {
            jioPlayer.play(contextUri = contextUri, trackUri = trackUri, index = index, positionMs = positionMs, order = order)
        }

        override suspend fun pause() = jioPlayer.pause()
        override suspend fun resume() = jioPlayer.resume()
        override suspend fun seek(positionMs: Long) = jioPlayer.seekTo(positionMs)
        override suspend fun next() = jioPlayer.next()
        override suspend fun previous() = jioPlayer.previous()
        override suspend fun setShuffle(on: Boolean) = jioPlayer.setShuffle(on)
        override suspend fun setRepeat(mode: Repeat) = jioPlayer.setRepeatMode(mode)
        override suspend fun addToQueue(uris: List<Uri>) = jioPlayer.addToQueue(uris)
        override suspend fun removeFromQueue(items: List<NowPlaying>) = jioPlayer.removeFromQueue(items)
        override suspend fun clearQueue() = jioPlayer.clearQueue()
        override suspend fun skipTo(item: NowPlaying) = jioPlayer.skipTo(item)
        override suspend fun playNext(uris: List<Uri>) = jioPlayer.playNext(uris)
        override suspend fun moveInQueue(item: NowPlaying, target: NowPlaying, after: Boolean) = jioPlayer.moveInQueue(item, target, after)
        override suspend fun volume(): Double = 1.0
        override suspend fun setVolume(level: Double) {}
        override suspend fun setSpeed(speed: Double) {}
    }

    override val home: Home = object : Home {
        override suspend fun feed(filter: HomeFilter, itemsPerShelf: Int): HomeFeed = withContext(Dispatchers.IO) {
            val shelves = ArrayList<Shelf>()

            coroutineScope {
                val trendingDeferred = async { runCatching { client.getTrendingTracks() }.getOrDefault(emptyList()) }
                val playlistsDeferred = async { runCatching { client.searchPlaylists("Top Hindi") }.getOrDefault(emptyList()) }
                val bollywoodDeferred = async { runCatching { client.searchTracks("Bollywood Top Hits") }.getOrDefault(emptyList()) }
                val albumsDeferred = async { runCatching { client.searchAlbums("Bollywood") }.getOrDefault(emptyList()) }
                val punjabiDeferred = async { runCatching { client.searchTracks("Punjabi Hits") }.getOrDefault(emptyList()) }
                val arijitDeferred = async { runCatching { client.searchTracks("Arijit Singh") }.getOrDefault(emptyList()) }

                val trendingTracks = trendingDeferred.await()
                if (trendingTracks.isNotEmpty()) {
                    val mappedTracks = trendingTracks.take(itemsPerShelf).map(::trackOf)
                    val trendingItems = trendingTracks.take(itemsPerShelf).map { track ->
                        MediaCard(
                            uri = "spotify:track:jio_${track.id}",
                            title = track.title,
                            subtitle = track.artist,
                            images = if (track.imageUrl.isNotBlank()) listOf(Image(track.imageUrl, 500, 500)) else emptyList(),
                            kind = MediaKind.TRACK,
                        )
                    }
                    shelves.add(
                        Shelf(
                            uri = "spotify:shelf:trending",
                            title = "Trending on WaveBits",
                            items = trendingItems,
                            total = trendingItems.size,
                            strapline = "QUICK PICKS",
                            tracks = mappedTracks,
                        )
                    )
                }

                val featuredPlaylists = playlistsDeferred.await()
                if (featuredPlaylists.isNotEmpty()) {
                    val pItems = featuredPlaylists.take(itemsPerShelf).map { p ->
                        client.registerQueryHint(p.id, p.title)
                        MediaCard(
                            uri = "spotify:playlist:jio_${p.id}",
                            title = p.title,
                            subtitle = p.subtitle.ifBlank { "Playlist" },
                            images = if (p.imageUrl.isNotBlank()) listOf(Image(p.imageUrl, 500, 500)) else emptyList(),
                            kind = MediaKind.PLAYLIST,
                        )
                    }
                    shelves.add(
                        Shelf(
                            uri = "spotify:shelf:playlists",
                            title = "Featured Playlists",
                            items = pItems,
                            total = pItems.size,
                            strapline = "MADE FOR YOU",
                        )
                    )
                }

                val bollywoodTracks = bollywoodDeferred.await()
                if (bollywoodTracks.isNotEmpty()) {
                    val mappedTracks = bollywoodTracks.take(itemsPerShelf).map(::trackOf)
                    val bItems = bollywoodTracks.take(itemsPerShelf).map { track ->
                        MediaCard(
                            uri = "spotify:track:jio_${track.id}",
                            title = track.title,
                            subtitle = track.artist,
                            images = if (track.imageUrl.isNotBlank()) listOf(Image(track.imageUrl, 500, 500)) else emptyList(),
                            kind = MediaKind.TRACK,
                        )
                    }
                    shelves.add(
                        Shelf(
                            uri = "spotify:shelf:bollywood",
                            title = "Bollywood Top Hits",
                            items = bItems,
                            total = bItems.size,
                            strapline = "CHARTBUSTERS",
                            tracks = mappedTracks,
                        )
                    )
                }

                val albums = albumsDeferred.await()
                if (albums.isNotEmpty()) {
                    val aItems = albums.take(itemsPerShelf).map { a ->
                        client.registerQueryHint(a.id, a.title)
                        MediaCard(
                            uri = "spotify:album:jio_${a.id}",
                            title = a.title,
                            subtitle = a.artist.ifBlank { "Album" },
                            images = if (a.imageUrl.isNotBlank()) listOf(Image(a.imageUrl, 500, 500)) else emptyList(),
                            kind = MediaKind.ALBUM,
                        )
                    }
                    shelves.add(
                        Shelf(
                            uri = "spotify:shelf:albums",
                            title = "Top Albums",
                            items = aItems,
                            total = aItems.size,
                            strapline = "NEW & POPULAR",
                        )
                    )
                }

                val punjabiTracks = punjabiDeferred.await()
                if (punjabiTracks.isNotEmpty()) {
                    val mappedTracks = punjabiTracks.take(itemsPerShelf).map(::trackOf)
                    val pItems = punjabiTracks.take(itemsPerShelf).map { track ->
                        MediaCard(
                            uri = "spotify:track:jio_${track.id}",
                            title = track.title,
                            subtitle = track.artist,
                            images = if (track.imageUrl.isNotBlank()) listOf(Image(track.imageUrl, 500, 500)) else emptyList(),
                            kind = MediaKind.TRACK,
                        )
                    }
                    shelves.add(
                        Shelf(
                            uri = "spotify:shelf:punjabi",
                            title = "Punjabi Chartbusters",
                            items = pItems,
                            total = pItems.size,
                            strapline = "HIGH ENERGY",
                            tracks = mappedTracks,
                        )
                    )
                }

                val arijitTracks = arijitDeferred.await()
                if (arijitTracks.isNotEmpty()) {
                    val mappedTracks = arijitTracks.take(itemsPerShelf).map(::trackOf)
                    val arItems = arijitTracks.take(itemsPerShelf).map { track ->
                        MediaCard(
                            uri = "spotify:track:jio_${track.id}",
                            title = track.title,
                            subtitle = track.artist,
                            images = if (track.imageUrl.isNotBlank()) listOf(Image(track.imageUrl, 500, 500)) else emptyList(),
                            kind = MediaKind.TRACK,
                        )
                    }
                    shelves.add(
                        Shelf(
                            uri = "spotify:shelf:arijit",
                            title = "Romantic & Soulful",
                            items = arItems,
                            total = arItems.size,
                            strapline = "FOR FANS OF ARIJIT SINGH",
                            tracks = mappedTracks,
                        )
                    )
                }
            }

            HomeFeed(
                greeting = "Welcome to WaveBits",
                filters = listOf(HomeFilter.ALL, HomeFilter.MUSIC),
                shelves = shelves,
            )
        }

        override suspend fun categories(): List<Category> = listOf(
            Category(uri = "spotify:category:hindi", title = "Hindi", images = emptyList(), color = null),
            Category(uri = "spotify:category:punjabi", title = "Punjabi", images = emptyList(), color = null),
            Category(uri = "spotify:category:english", title = "English", images = emptyList(), color = null),
            Category(uri = "spotify:category:tamil", title = "Tamil", images = emptyList(), color = null),
            Category(uri = "spotify:category:telugu", title = "Telugu", images = emptyList(), color = null),
            Category(uri = "spotify:category:romantic", title = "Romance", images = emptyList(), color = null),
            Category(uri = "spotify:category:dance", title = "Party & Dance", images = emptyList(), color = null),
        )

        override suspend fun category(uri: Uri): BrowsePage = withContext(Dispatchers.IO) {
            val id = cleanId(uri)
            val res = client.searchEverything(id)
            val playlists = res.playlists.map { p ->
                Playlist(
                    uri = "spotify:playlist:jio_${p.id}",
                    name = p.title,
                    description = p.subtitle,
                    owner = "Jiosavan",
                    images = listOf(Image(p.imageUrl, 500, 500)),
                    trackCount = p.songCount,
                )
            }
            BrowsePage(
                uri = uri,
                title = id.replaceFirstChar { it.uppercase() },
                shelves = listOf(
                    Shelf(
                        uri = "spotify:shelf:playlists",
                        title = "Featured Playlists",
                        items = playlists.map {
                            MediaCard(
                                uri = it.uri,
                                title = it.name,
                                subtitle = it.description ?: "Jiosavan Playlist",
                                images = it.images,
                                kind = MediaKind.PLAYLIST,
                            )
                        },
                        total = playlists.size,
                    )
                ),
            )
        }

        override suspend fun whatsNew(offset: Int, limit: Int): Page<Album> = withContext(Dispatchers.IO) {
            val trending = client.getTrendingTracks()
            val albums = trending.map { t ->
                Album(
                    uri = "spotify:album:jio_${t.albumId.ifBlank { t.id }}",
                    name = t.album,
                    artists = listOf(ArtistRef(uri = "spotify:artist:jio_${t.id.take(8)}", name = t.artist)),
                    images = listOf(Image(t.imageUrl, 500, 500)),
                    type = "album",
                    releaseDate = t.year,
                    trackCount = 1,
                )
            }.distinctBy { it.uri }.drop(offset).take(limit)
            Page(albums, offset, trending.size, if (offset + limit < trending.size) offset + limit else null)
        }
    }

    override val search: Search = object : Search {
        override suspend fun all(query: String, limit: Int): SearchResults = withContext(Dispatchers.IO) {
            val res = client.searchEverything(query)
            val tracks = res.tracks.take(limit).map(::trackOf)
            val albums = res.albums.take(limit).map { a ->
                client.registerQueryHint(a.id, a.title)
                Album(
                    uri = "spotify:album:jio_${a.id}",
                    name = a.title,
                    artists = listOf(ArtistRef(uri = "spotify:artist:jio_${a.id.take(8)}", name = a.artist)),
                    images = if (a.imageUrl.isNotBlank()) listOf(Image(a.imageUrl, 500, 500)) else emptyList(),
                    type = "album",
                    releaseDate = a.year,
                    trackCount = a.tracks.size.coerceAtLeast(1),
                )
            }
            val artists = if (res.artists.isNotEmpty()) {
                res.artists.take(limit).map { ar ->
                    client.registerQueryHint(ar.id, ar.name)
                    Artist(
                        uri = "spotify:artist:jio_${ar.id}",
                        name = ar.name,
                        images = if (ar.imageUrl.isNotBlank()) listOf(Image(ar.imageUrl, 500, 500)) else emptyList(),
                    )
                }
            } else {
                res.tracks.map { t ->
                    val (aid, aname) = t.primaryArtists.firstOrNull() ?: (t.id.take(8) to t.artist)
                    val effectiveId = aid.ifBlank { t.id.take(8) }
                    client.registerQueryHint(effectiveId, aname)
                    Artist(
                        uri = "spotify:artist:jio_$effectiveId",
                        name = aname,
                        images = if (t.imageUrl.isNotBlank()) listOf(Image(t.imageUrl, 500, 500)) else emptyList(),
                    )
                }.distinctBy { it.name }.take(limit)
            }
            val playlists = res.playlists.take(limit).map { p ->
                client.registerQueryHint(p.id, p.title)
                Playlist(
                    uri = "spotify:playlist:jio_${p.id}",
                    name = p.title,
                    description = p.subtitle,
                    images = if (p.imageUrl.isNotBlank()) listOf(Image(p.imageUrl, 500, 500)) else emptyList(),
                    owner = "Jiosavan",
                    trackCount = p.songCount,
                )
            }
            SearchResults(
                tracks = Page(tracks, 0, tracks.size, null),
                albums = Page(albums, 0, albums.size, null),
                artists = Page(artists, 0, artists.size, null),
                playlists = Page(playlists, 0, playlists.size, null),
            )
        }

        override suspend fun tracks(query: String, offset: Int, limit: Int): Page<Track> = withContext(Dispatchers.IO) {
            val all = client.searchTracks(query, limit = (offset + limit).coerceAtLeast(30))
            val list = all.drop(offset).take(limit).map(::trackOf)
            Page(list, offset, all.size, if (offset + limit < all.size) offset + limit else null)
        }

        override suspend fun albums(query: String, offset: Int, limit: Int): Page<Album> = withContext(Dispatchers.IO) {
            val all = client.searchAlbums(query, limit = (offset + limit).coerceAtLeast(25))
            val list = all.drop(offset).take(limit).map { a ->
                client.registerQueryHint(a.id, a.title)
                Album(
                    uri = "spotify:album:jio_${a.id}",
                    name = a.title,
                    artists = listOf(ArtistRef(uri = "spotify:artist:jio_${a.id.take(8)}", name = a.artist)),
                    images = if (a.imageUrl.isNotBlank()) listOf(Image(a.imageUrl, 500, 500)) else emptyList(),
                    type = "album",
                    releaseDate = a.year,
                    trackCount = a.tracks.size.coerceAtLeast(1),
                )
            }
            Page(list, offset, all.size, if (offset + limit < all.size) offset + limit else null)
        }

        override suspend fun artists(query: String, offset: Int, limit: Int): Page<Artist> = withContext(Dispatchers.IO) {
            val all = client.searchArtists(query, limit = (offset + limit).coerceAtLeast(20))
            val list = all.drop(offset).take(limit).map { ar ->
                client.registerQueryHint(ar.id, ar.name)
                Artist(
                    uri = "spotify:artist:jio_${ar.id}",
                    name = ar.name,
                    images = if (ar.imageUrl.isNotBlank()) listOf(Image(ar.imageUrl, 500, 500)) else emptyList(),
                )
            }
            Page(list, offset, all.size, if (offset + limit < all.size) offset + limit else null)
        }

        override suspend fun playlists(query: String, offset: Int, limit: Int): Page<Playlist> = withContext(Dispatchers.IO) {
            val all = client.searchPlaylists(query, limit = (offset + limit).coerceAtLeast(25))
            val list = all.drop(offset).take(limit).map { p ->
                client.registerQueryHint(p.id, p.title)
                Playlist(
                    uri = "spotify:playlist:jio_${p.id}",
                    name = p.title,
                    description = p.subtitle,
                    images = if (p.imageUrl.isNotBlank()) listOf(Image(p.imageUrl, 500, 500)) else emptyList(),
                    owner = "Jiosavan",
                    trackCount = p.songCount,
                )
            }
            Page(list, offset, all.size, if (offset + limit < all.size) offset + limit else null)
        }

        override suspend fun shows(query: String, offset: Int, limit: Int): Page<MediaCard> = Page(emptyList(), offset, 0, null)
        override suspend fun episodes(query: String, offset: Int, limit: Int): Page<Episode> = Page(emptyList(), offset, 0, null)
        override suspend fun audiobooks(query: String, offset: Int, limit: Int): Page<MediaCard> = Page(emptyList(), offset, 0, null)
        override suspend fun users(query: String, offset: Int, limit: Int): Page<MediaCard> = Page(emptyList(), offset, 0, null)
        override suspend fun genres(query: String, offset: Int, limit: Int): Page<MediaCard> = Page(emptyList(), offset, 0, null)
        override suspend fun suggestions(query: String, limit: Int): List<String> =
            client.searchTracks(query, limit = (limit * 2).coerceAtLeast(limit))
                .map { "${it.title} - ${it.artist}" }
                .distinctBy { it.lowercase() }
                .take(limit)
        override suspend fun recent(): List<MediaCard> = emptyList()
        override suspend fun clearRecent() {}
    }

    override val catalog: Catalog = object : Catalog {
        override suspend fun track(uri: Uri): Track = withContext(Dispatchers.IO) {
            val cid = cleanId(uri)
            val res = client.getSongDetails(cid) ?: JioSaavnApiClient.JioTrack(
                id = cid,
                title = client.getQueryHint(cid) ?: "WaveBits Track",
                subtitle = "WaveBits",
                artist = "WaveBits Artist",
                album = "Single",
                albumId = "",
                imageUrl = "",
                durationSec = 180,
                streamUrl = "",
                year = "2026",
                explicit = false,
                hasLyrics = false,
                language = "Hindi",
            )
            trackOf(res)
        }

        override suspend fun album(uri: Uri): Album = withContext(Dispatchers.IO) {
            val cid = cleanId(uri)
            val a = client.getAlbumDetails(cid)
            if (a != null) {
                val tracks = a.tracks.map(::trackOf)
                val items = tracks.map { Triple(cleanId(it.uri), it.name, it.artists.joinToString(", ") { ar -> ar.name }) }
                val meta = tracks.map { Triple(it.album?.images?.firstOrNull()?.url.orEmpty(), it.album?.name.orEmpty(), it.durationMs) }
                jioPlayer.registerContextTracks(uri, items, meta)
                val albumArtistRefs = tracks.firstOrNull()?.artists
                    ?: listOf(ArtistRef(uri = "spotify:artist:jio_${cid.take(8)}", name = a.artist))
                Album(
                    uri = uri,
                    name = a.title,
                    artists = albumArtistRefs,
                    images = if (a.imageUrl.isNotBlank()) listOf(Image(a.imageUrl, 500, 500)) else emptyList(),
                    type = "album",
                    releaseDate = a.year,
                    trackCount = tracks.size,
                )
            } else {
                Album(uri, "WaveBits Album", emptyList(), emptyList(), "album", null, 0)
            }
        }

        override suspend fun albumTracks(uri: Uri, offset: Int, limit: Int): Page<Track> = withContext(Dispatchers.IO) {
            val cid = cleanId(uri)
            val a = client.getAlbumDetails(cid)
            val allTracks = a?.tracks?.map(::trackOf).orEmpty()
            if (allTracks.isNotEmpty()) {
                val items = allTracks.map { Triple(cleanId(it.uri), it.name, it.artists.joinToString(", ") { ar -> ar.name }) }
                val meta = allTracks.map { Triple(it.album?.images?.firstOrNull()?.url.orEmpty(), it.album?.name.orEmpty(), it.durationMs) }
                jioPlayer.registerContextTracks(uri, items, meta)
            }
            val paged = allTracks.drop(offset).take(limit)
            Page(paged, offset, allTracks.size, if (offset + limit < allTracks.size) offset + limit else null)
        }

        override suspend fun artist(uri: Uri): Artist = withContext(Dispatchers.IO) {
            val cid = cleanId(uri)
            val details = client.getArtistDetails(cid)
            val name = details?.name?.takeIf { it.isNotBlank() } ?: client.getQueryHint(cid) ?: "WaveBits Artist"
            val images = if (!details?.imageUrl.isNullOrBlank()) listOf(Image(details!!.imageUrl, 500, 500)) else emptyList()
            Artist(
                uri = uri,
                name = name,
                images = images,
                followers = details?.followerCount?.takeIf { it > 0 }?.toLong(),
            )
        }

        override suspend fun artistTopTracks(uri: Uri): List<Track> = withContext(Dispatchers.IO) {
            val cid = cleanId(uri)
            val details = client.getArtistDetails(cid)
            val rawTracks = if (details != null && details.topSongs.isNotEmpty()) {
                details.topSongs
            } else {
                client.getArtistSongs(cid, limit = 25)
            }
            val tracks = rawTracks.map(::trackOf)
            if (tracks.isNotEmpty()) {
                val items = tracks.map { Triple(cleanId(it.uri), it.name, it.artists.joinToString(", ") { ar -> ar.name }) }
                val meta = tracks.map { Triple(it.album?.images?.firstOrNull()?.url.orEmpty(), it.album?.name.orEmpty(), it.durationMs) }
                jioPlayer.registerContextTracks(uri, items, meta)
            }
            tracks
        }

        override suspend fun artistPage(uri: Uri): ArtistPage = withContext(Dispatchers.IO) {
            val cid = cleanId(uri)
            val details = client.getArtistDetails(cid)
            val top = if (details != null && details.topSongs.isNotEmpty()) {
                details.topSongs.map(::trackOf).also { tracks ->
                    val items = tracks.map { Triple(cleanId(it.uri), it.name, it.artists.joinToString(", ") { ar -> ar.name }) }
                    val meta = tracks.map { Triple(it.album?.images?.firstOrNull()?.url.orEmpty(), it.album?.name.orEmpty(), it.durationMs) }
                    jioPlayer.registerContextTracks(uri, items, meta)
                }
            } else {
                artistTopTracks(uri)
            }
            val name = details?.name?.takeIf { it.isNotBlank() } ?: client.getQueryHint(cid) ?: "WaveBits Artist"
            val images = if (!details?.imageUrl.isNullOrBlank()) {
                listOf(Image(details!!.imageUrl, 500, 500))
            } else {
                top.firstOrNull()?.album?.images.orEmpty()
            }
            val popAlbums = details?.topAlbums.orEmpty().map { a ->
                client.registerQueryHint(a.id, a.title)
                Album(
                    uri = "spotify:album:jio_${a.id}",
                    name = a.title,
                    artists = listOf(ArtistRef(uri = uri, name = a.artist.ifBlank { name })),
                    images = if (a.imageUrl.isNotBlank()) listOf(Image(a.imageUrl, 500, 500)) else emptyList(),
                    type = "album",
                    releaseDate = a.year,
                    trackCount = a.tracks.size.coerceAtLeast(1),
                )
            }
            ArtistPage(
                artist = Artist(
                    uri = uri,
                    name = name,
                    images = images,
                    followers = details?.followerCount?.takeIf { it > 0 }?.toLong(),
                ),
                biography = details?.bio?.takeIf { it.isNotBlank() },
                headerImages = images,
                topTracks = top.map { app.wavebits.api.TopTrack(it, null) },
                popularReleases = popAlbums,
                appearsOn = emptyList(),
                related = emptyList(),
            )
        }

        override suspend fun isrc(trackUri: Uri): String? = null

        override suspend fun discography(uri: Uri, offset: Int, limit: Int): Page<Album> = withContext(Dispatchers.IO) {
            val cid = cleanId(uri)
            val pageIdx = (offset / limit.coerceAtLeast(1)).coerceAtLeast(0)
            val rawAlbums = client.getArtistAlbums(cid, page = pageIdx)
            val list = rawAlbums.map { a ->
                client.registerQueryHint(a.id, a.title)
                Album(
                    uri = "spotify:album:jio_${a.id}",
                    name = a.title,
                    artists = listOf(ArtistRef(uri = uri, name = a.artist)),
                    images = if (a.imageUrl.isNotBlank()) listOf(Image(a.imageUrl, 500, 500)) else emptyList(),
                    type = "album",
                    releaseDate = a.year,
                    trackCount = a.tracks.size.coerceAtLeast(1),
                )
            }
            Page(list, offset, offset + list.size, if (list.size >= limit) offset + limit else null)
        }

        override suspend fun relatedArtists(uri: Uri): List<Artist> = withContext(Dispatchers.IO) {
            val cid = cleanId(uri)
            val name = client.getQueryHint(cid) ?: client.getArtistDetails(cid)?.name ?: return@withContext emptyList()
            client.searchArtists(name, limit = 10)
                .filter { it.id != cid }
                .take(8)
                .map { ar ->
                    client.registerQueryHint(ar.id, ar.name)
                    Artist(
                        uri = "spotify:artist:jio_${ar.id}",
                        name = ar.name,
                        images = if (ar.imageUrl.isNotBlank()) listOf(Image(ar.imageUrl, 500, 500)) else emptyList(),
                    )
                }
        }
        override suspend fun appearsOn(uri: Uri): List<Album> = emptyList()
        override suspend fun similarAlbums(trackUri: Uri, limit: Int): List<Album> = emptyList()
    }

    private val _libraryChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    private fun getLikedTrackIds(): Set<String> =
        libraryPrefs.getStringSet("liked_ids", emptySet()).orEmpty()

    private fun getSavedEntityIds(): Set<String> =
        libraryPrefs.getStringSet("saved_entity_ids", emptySet()).orEmpty()

    private fun markEntitySaved(uri: String, saved: Boolean) {
        val cid = cleanId(uri)
        val entities = HashSet(getSavedEntityIds())
        val variants = listOf(
            uri,
            cid,
            "spotify:playlist:jio_$cid",
            "spotify:playlist:$cid",
            "spotify:album:jio_$cid",
            "spotify:album:$cid",
            "spotify:artist:jio_$cid",
            "spotify:artist:$cid",
        )
        if (saved) {
            entities.addAll(variants)
        } else {
            entities.removeAll(variants.toSet())
        }
        libraryPrefs.edit().putStringSet("saved_entity_ids", entities).apply()
    }

    private fun loadSavedPlaylists(): List<Playlist> {
        val raw = libraryPrefs.getString("saved_playlists_json", null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<Playlist>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) continue
                val uri = o.optString("uri").ifBlank { "spotify:playlist:jio_$id" }
                val name = o.optString("name", "Playlist")
                val desc = o.optString("description", "")
                val owner = o.optString("owner", "JioSaavn")
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
            val arr = org.json.JSONArray()
            for (p in playlists) {
                val id = cleanId(p.uri)
                val o = org.json.JSONObject()
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
            libraryPrefs.edit().putString("saved_playlists_json", arr.toString()).apply()
        }
    }

    private fun loadCustomPlaylistTracks(playlistId: String): List<Track> {
        val raw = libraryPrefs.getString("custom_pl_tracks_$playlistId", null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<Track>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) continue
                val title = o.optString("title", "Track")
                val artist = o.optString("artist", "Artist")
                val album = o.optString("album", "")
                val cover = o.optString("coverUrl", "")
                val duration = o.optLong("durationMs", 180_000L)
                list.add(
                    Track(
                        uri = "spotify:track:jio_$id",
                        uid = id,
                        name = title,
                        durationMs = duration,
                        artists = listOf(ArtistRef("spotify:artist:jio_$id", artist)),
                        album = AlbumRef(
                            "spotify:album:jio_$id",
                            album,
                            if (cover.isNotBlank()) listOf(Image(cover, 500, 500)) else emptyList(),
                        ),
                        explicit = false,
                        playable = true,
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun saveCustomPlaylistTracks(playlistId: String, tracks: List<Track>) {
        runCatching {
            val arr = org.json.JSONArray()
            for (t in tracks) {
                val id = cleanId(t.uri)
                val o = org.json.JSONObject()
                o.put("id", id)
                o.put("title", t.name)
                o.put("artist", t.artists.joinToString(", ") { it.name })
                o.put("album", t.album?.name.orEmpty())
                o.put("coverUrl", t.album?.images?.firstOrNull()?.url.orEmpty())
                o.put("durationMs", t.durationMs)
                arr.put(o)
            }
            libraryPrefs.edit().putString("custom_pl_tracks_$playlistId", arr.toString()).apply()
        }
    }

    private fun loadSavedAlbums(): List<Album> {
        val raw = libraryPrefs.getString("saved_albums_json", null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<Album>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) continue
                val uri = o.optString("uri").ifBlank { "spotify:album:jio_$id" }
                val name = o.optString("name", "Album")
                val artist = o.optString("artist", "Artist")
                val cover = o.optString("coverUrl", "")
                val releaseDate = o.optString("releaseDate", "").takeIf { it.isNotBlank() }
                val count = o.optInt("trackCount", 10)
                list.add(
                    Album(
                        uri = uri,
                        name = name,
                        artists = listOf(ArtistRef("spotify:artist:jio_$id", artist)),
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
            val arr = org.json.JSONArray()
            for (a in albums) {
                val id = cleanId(a.uri)
                val o = org.json.JSONObject()
                o.put("id", id)
                o.put("uri", a.uri)
                o.put("name", a.name)
                o.put("artist", a.artists.joinToString(", ") { it.name })
                o.put("coverUrl", a.images.firstOrNull()?.url.orEmpty())
                o.put("releaseDate", a.releaseDate.orEmpty())
                o.put("trackCount", a.trackCount)
                arr.put(o)
            }
            libraryPrefs.edit().putString("saved_albums_json", arr.toString()).apply()
        }
    }

    override val library: Library = object : Library {
        override val changes: Flow<Unit> = _libraryChanges

        override suspend fun contains(uris: List<Uri>): List<Boolean> {
            val likedTracks = getLikedTrackIds()
            val savedEntities = getSavedEntityIds()
            val savedPlIds = loadSavedPlaylists().map { cleanId(it.uri) }.toSet()
            val savedAlbIds = loadSavedAlbums().map { cleanId(it.uri) }.toSet()
            return uris.map { u ->
                val cid = cleanId(u)
                u in savedEntities || cid in savedEntities || cid in likedTracks || cid in savedPlIds || cid in savedAlbIds
            }
        }

        override suspend fun add(uris: List<Uri>) = withContext(Dispatchers.IO) {
            val trackSet = HashSet(getLikedTrackIds())
            val savedAlbums = loadSavedAlbums().toMutableList()
            for (u in uris) {
                val cid = cleanId(u)
                when {
                    u.startsWith("spotify:playlist:") -> {
                        playlists.follow(u)
                    }
                    u.startsWith("spotify:album:") -> {
                        markEntitySaved(u, true)
                        val al = runCatching { catalog.album(u) }.getOrNull()
                        if (al != null) {
                            savedAlbums.removeAll { cleanId(it.uri) == cid }
                            savedAlbums.add(0, al.copy(uri = "spotify:album:jio_$cid"))
                        }
                    }
                    u.startsWith("spotify:artist:") -> {
                        markEntitySaved(u, true)
                    }
                    else -> {
                        trackSet.add(cid)
                    }
                }
            }
            libraryPrefs.edit().putStringSet("liked_ids", trackSet).apply()
            saveSavedAlbums(savedAlbums)
            _libraryChanges.tryEmit(Unit)
            Unit
        }

        override suspend fun remove(uris: List<Uri>) = withContext(Dispatchers.IO) {
            val trackSet = HashSet(getLikedTrackIds())
            val removeIds = uris.map(::cleanId).toSet()
            for (u in uris) {
                val cid = cleanId(u)
                when {
                    u.startsWith("spotify:playlist:") -> {
                        playlists.unfollow(u)
                    }
                    u.startsWith("spotify:album:") || u.startsWith("spotify:artist:") -> {
                        markEntitySaved(u, false)
                        trackSet.remove(cid)
                    }
                    else -> {
                        trackSet.remove(cid)
                        markEntitySaved(u, false)
                    }
                }
            }
            libraryPrefs.edit().putStringSet("liked_ids", trackSet).apply()
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
            val plItems = loadSavedPlaylists().map { pl ->
                markEntitySaved(pl.uri, true)
                LibraryItem(
                    uri = if (pl.uri.startsWith("spotify:playlist:jio_")) pl.uri else "spotify:playlist:jio_${cleanId(pl.uri)}",
                    kind = MediaKind.PLAYLIST,
                    name = pl.name,
                    subtitle = pl.owner ?: "JioSaavn Playlist",
                    images = pl.images,
                    pinned = false,
                    addedAt = null,
                    count = pl.trackCount.takeIf { it > 0 },
                )
            }
            val albumItems = loadSavedAlbums().map { al ->
                markEntitySaved(al.uri, true)
                LibraryItem(
                    uri = if (al.uri.startsWith("spotify:album:jio_")) al.uri else "spotify:album:jio_${cleanId(al.uri)}",
                    kind = MediaKind.ALBUM,
                    name = al.name,
                    subtitle = al.artists.joinToString(", ") { it.name }.ifBlank { "Album" },
                    images = al.images,
                    pinned = false,
                    addedAt = null,
                )
            }
            val all = when (filter) {
                LibraryFilter.PLAYLISTS -> plItems
                LibraryFilter.ALBUMS -> albumItems
                else -> plItems + albumItems
            }
            val paged = all.drop(offset).take(limit)
            Page(paged, offset, all.size, if (offset + limit < all.size) offset + limit else null)
        }

        override suspend fun likedTracks(offset: Int, limit: Int): Page<SavedTrack> = withContext(Dispatchers.IO) {
            val nonTrackIds = (loadSavedPlaylists().map { cleanId(it.uri) } + loadSavedAlbums().map { cleanId(it.uri) }).toSet()
            val ids = getLikedTrackIds().filterNot { it in nonTrackIds }
            val pageIds = ids.drop(offset).take(limit)
            val uncachedIds = pageIds.filter { client.getCachedTrack(it) == null }
            if (uncachedIds.isNotEmpty()) {
                runCatching { client.getSongsByIds(uncachedIds) }
            }
            val paged = pageIds.map { id ->
                val cached = client.getCachedTrack(id)
                val tr = if (cached != null) {
                    trackOf(cached)
                } else {
                    val reg = jioPlayer.getRegisteredTrack(id)
                    Track(
                        uri = "spotify:track:jio_$id",
                        name = reg?.title ?: client.getQueryHint(id) ?: "Liked Song",
                        artists = listOf(ArtistRef(uri = "spotify:artist:jio", name = reg?.artist ?: "WaveBits Artist")),
                        album = AlbumRef(
                            uri = "spotify:album:jio",
                            name = reg?.album ?: "WaveBits",
                            images = if (!reg?.coverUrl.isNullOrBlank()) listOf(Image(reg!!.coverUrl, 500, 500)) else emptyList(),
                        ),
                        durationMs = reg?.durationMs ?: 180_000L,
                        explicit = false,
                        playable = true,
                    )
                }
                SavedTrack(tr, null)
            }
            if (offset == 0 && paged.isNotEmpty()) {
                val items = paged.map { Triple(cleanId(it.track.uri), it.track.name, it.track.artists.joinToString(", ") { ar -> ar.name }) }
                val meta = paged.map { Triple(it.track.album?.images?.firstOrNull()?.url.orEmpty(), it.track.album?.name.orEmpty(), it.track.durationMs) }
                jioPlayer.registerContextTracks("spotify:collection:tracks", items, meta)
            }
            Page(paged, offset, ids.size, if (offset + limit < ids.size) offset + limit else null)
        }

        override suspend fun pin(uri: Uri) {}
        override suspend fun unpin(uri: Uri) {}
        override suspend fun createFolder(name: String, parentFolderUri: Uri?): Uri = "spotify:folder:jio"
        override suspend fun renameFolder(folderUri: Uri, name: String) {}
        override suspend fun deleteFolder(folderUri: Uri) {}
        override suspend fun moveToFolder(uri: Uri, folderUri: Uri?) {}
    }

    private suspend fun buildSongRadioTracks(uri: Uri, realTrackId: String): List<Track> {
        val existingCtx = jioPlayer.getRegisteredContext(uri)
        if (!existingCtx.isNullOrEmpty() && existingCtx.size > 1) {
            return existingCtx.map { d ->
                Track(
                    uri = d.uri,
                    name = d.title,
                    artists = listOf(ArtistRef(uri = "spotify:artist:jio_${d.id.take(8)}", name = d.artist)),
                    album = AlbumRef(
                        uri = "spotify:album:jio_${d.id.take(8)}",
                        name = d.album.ifBlank { d.title },
                        images = if (d.coverUrl.isNotBlank()) listOf(Image(d.coverUrl, 500, 500)) else emptyList(),
                    ),
                    durationMs = d.durationMs.coerceAtLeast(60_000L),
                    explicit = false,
                    playable = true,
                )
            }
        }
        val t = client.getSongDetails(realTrackId)
        val seedTrack = if (t != null) trackOf(t) else {
            val reg = jioPlayer.getRegisteredTrack(realTrackId)
            Track(
                uri = "spotify:track:jio_$realTrackId",
                name = reg?.title ?: client.getQueryHint(realTrackId) ?: "Track",
                artists = listOf(ArtistRef("spotify:artist:jio", reg?.artist ?: "WaveBits")),
                album = AlbumRef(
                    uri = "spotify:album:jio",
                    name = reg?.album.orEmpty(),
                    images = if (!reg?.coverUrl.isNullOrBlank()) listOf(Image(reg!!.coverUrl, 500, 500)) else emptyList(),
                ),
                durationMs = reg?.durationMs ?: 180_000L,
                playable = true,
            )
        }
        val seedTitle = seedTrack.name
        val seedArtist = seedTrack.artists.joinToString(", ") { it.name }
        val recos = client.getSongRecommendations(realTrackId, seedTitle, seedArtist, limit = 25)
        val recoTracks = recos.filter { it.id != realTrackId }.map(::trackOf)
        val allTracks = (listOf(seedTrack) + recoTracks).distinctBy { it.uri }
        val items = allTracks.map { Triple(cleanId(it.uri), it.name, it.artists.joinToString(", ") { ar -> ar.name }) }
        val meta = allTracks.map { Triple(it.album?.images?.firstOrNull()?.url.orEmpty(), it.album?.name.orEmpty(), it.durationMs) }
        jioPlayer.registerContextTracks(uri, items, meta)
        return allTracks
    }

    override val playlists: Playlists = object : Playlists {
        override suspend fun playlist(uri: Uri): Playlist = withContext(Dispatchers.IO) {
            val rawPart = uri.substringAfterLast(':').substringBefore('|')
            val cid = cleanId(uri)
            val savedCustom = loadSavedPlaylists().firstOrNull { cleanId(it.uri) == cid }
            if (cid.startsWith("custom_") && savedCustom != null) {
                val customTracks = loadCustomPlaylistTracks(cid)
                val cover = savedCustom.images.firstOrNull()?.url?.takeIf { it.isNotBlank() }
                    ?: customTracks.firstOrNull()?.album?.images?.firstOrNull()?.url.orEmpty()
                return@withContext savedCustom.copy(
                    uri = uri,
                    images = if (cover.isNotBlank()) listOf(Image(cover, 500, 500)) else emptyList(),
                    trackCount = customTracks.size,
                )
            }
            if (rawPart.startsWith("song_") || rawPart.startsWith("jio_song_") || cid.startsWith("song_") || cid.startsWith("jio_song_")) {
                val realTrackId = cid.removePrefix("song_").removePrefix("jio_song_").removePrefix("jio_")
                val allTracks = buildSongRadioTracks(uri, realTrackId)
                val first = allTracks.firstOrNull()
                val title = first?.name ?: client.getQueryHint(realTrackId) ?: "Track"
                val artist = first?.artists?.joinToString(", ") { it.name } ?: "WaveBits"
                val img = first?.album?.images?.firstOrNull()?.url.orEmpty()
                return@withContext Playlist(
                    uri = uri,
                    name = title,
                    description = "Song Radio • $artist",
                    images = if (img.isNotBlank()) listOf(Image(img, 500, 500)) else emptyList(),
                    owner = "WaveBits",
                    trackCount = allTracks.size,
                )
            }
            val p = client.getPlaylistDetails(cid)
            if (p != null) {
                val tracks = p.tracks.map(::trackOf)
                val items = tracks.map { Triple(cleanId(it.uri), it.name, it.artists.joinToString(", ") { ar -> ar.name }) }
                val meta = tracks.map { Triple(it.album?.images?.firstOrNull()?.url.orEmpty(), it.album?.name.orEmpty(), it.durationMs) }
                jioPlayer.registerContextTracks(uri, items, meta)
                Playlist(
                    uri = uri,
                    name = savedCustom?.name?.takeIf { savedCustom.editable } ?: p.title,
                    description = p.subtitle,
                    images = listOf(Image(p.imageUrl, 500, 500)),
                    owner = "WaveBits",
                    trackCount = tracks.size,
                    editable = savedCustom?.editable ?: false,
                )
            } else if (savedCustom != null) {
                savedCustom.copy(uri = uri)
            } else {
                Playlist(uri, "WaveBits Playlist", "", "WaveBits", emptyList(), 0)
            }
        }

        override suspend fun tracks(uri: Uri, offset: Int, limit: Int): Page<Track> = withContext(Dispatchers.IO) {
            val rawPart = uri.substringAfterLast(':').substringBefore('|')
            val cid = cleanId(uri)
            val customTracks = loadCustomPlaylistTracks(cid)
            if (cid.startsWith("custom_") || customTracks.isNotEmpty()) {
                val items = customTracks.map { Triple(cleanId(it.uri), it.name, it.artists.joinToString(", ") { ar -> ar.name }) }
                val meta = customTracks.map { Triple(it.album?.images?.firstOrNull()?.url.orEmpty(), it.album?.name.orEmpty(), it.durationMs) }
                jioPlayer.registerContextTracks(uri, items, meta)
                val paged = customTracks.drop(offset).take(limit)
                return@withContext Page(paged, offset, customTracks.size, if (offset + limit < customTracks.size) offset + limit else null)
            }
            if (rawPart.startsWith("song_") || rawPart.startsWith("jio_song_") || cid.startsWith("song_") || cid.startsWith("jio_song_")) {
                val realTrackId = cid.removePrefix("song_").removePrefix("jio_song_").removePrefix("jio_")
                val allTracks = buildSongRadioTracks(uri, realTrackId)
                val paged = allTracks.drop(offset).take(limit)
                return@withContext Page(paged, offset, allTracks.size, if (offset + limit < allTracks.size) offset + limit else null)
            }
            val p = client.getPlaylistDetails(cid)
            val allTracks = p?.tracks?.map(::trackOf).orEmpty()
            if (allTracks.isNotEmpty()) {
                val items = allTracks.map { Triple(cleanId(it.uri), it.name, it.artists.joinToString(", ") { ar -> ar.name }) }
                val meta = allTracks.map { Triple(it.album?.images?.firstOrNull()?.url.orEmpty(), it.album?.name.orEmpty(), it.durationMs) }
                jioPlayer.registerContextTracks(uri, items, meta)
            }
            val paged = allTracks.drop(offset).take(limit)
            Page(paged, offset, allTracks.size, if (offset + limit < allTracks.size) offset + limit else null)
        }

        override suspend fun sortedTracks(uri: Uri, sort: PlaylistSort, descending: Boolean): List<Track> {
            val raw = tracks(uri, 0, 500).items
            return raw.sortedFor(sort, descending)
        }

        override suspend fun mine(): List<Playlist> = withContext(Dispatchers.IO) {
            loadSavedPlaylists()
        }

        override suspend fun editable(): List<Playlist> = withContext(Dispatchers.IO) {
            val all = loadSavedPlaylists()
            val editables = all.filter { it.editable }
            editables.ifEmpty { all }
        }

        override suspend fun create(name: String, folderUri: Uri?): Uri = withContext(Dispatchers.IO) {
            val id = "custom_${System.currentTimeMillis()}"
            val uri = "spotify:playlist:jio_$id"
            val newPl = Playlist(
                uri = uri,
                name = name,
                description = "Created Playlist",
                owner = "Jiosavan",
                images = emptyList(),
                trackCount = 0,
                editable = true,
            )
            val current = loadSavedPlaylists().toMutableList()
            current.add(0, newPl)
            saveSavedPlaylists(current)
            markEntitySaved(uri, true)
            _libraryChanges.tryEmit(Unit)
            uri
        }

        override suspend fun rename(uri: Uri, name: String) = withContext(Dispatchers.IO) {
            val cid = cleanId(uri)
            val updated = loadSavedPlaylists().map {
                if (cleanId(it.uri) == cid) it.copy(name = name) else it
            }
            saveSavedPlaylists(updated)
            _libraryChanges.tryEmit(Unit)
            Unit
        }

        override suspend fun setDescription(uri: Uri, description: String) = withContext(Dispatchers.IO) {
            val cid = cleanId(uri)
            val updated = loadSavedPlaylists().map {
                if (cleanId(it.uri) == cid) it.copy(description = description) else it
            }
            saveSavedPlaylists(updated)
            _libraryChanges.tryEmit(Unit)
            Unit
        }

        override suspend fun access(uri: Uri): PlaylistAccess = PlaylistAccess.PUBLIC
        override suspend fun setAccess(uri: Uri, access: PlaylistAccess) {}
        override suspend fun published(uri: Uri): Boolean = true
        override suspend fun setPublished(uri: Uri, published: Boolean) {}
        override suspend fun setCover(uri: Uri, jpeg: ByteArray?) {}

        override suspend fun delete(uri: Uri) = withContext(Dispatchers.IO) {
            unfollow(uri)
        }

        override suspend fun follow(uri: Uri) = withContext(Dispatchers.IO) {
            val cid = cleanId(uri)
            if (cid.isBlank()) return@withContext
            markEntitySaved(uri, true)
            val pl = runCatching { playlist(uri) }.getOrNull() ?: Playlist(
                uri = "spotify:playlist:jio_$cid",
                name = "Saved Playlist",
                description = "JioSaavn Playlist",
                owner = "WaveBits",
                images = emptyList(),
                trackCount = 0,
                editable = false,
            )
            val canonicalUri = if (uri.startsWith("spotify:playlist:jio_")) uri else "spotify:playlist:jio_$cid"
            val current = loadSavedPlaylists().filterNot { cleanId(it.uri) == cid }.toMutableList()
            current.add(0, pl.copy(uri = canonicalUri))
            saveSavedPlaylists(current)
            _libraryChanges.tryEmit(Unit)
            Unit
        }

        override suspend fun unfollow(uri: Uri) = withContext(Dispatchers.IO) {
            val cid = cleanId(uri)
            if (cid.isBlank()) return@withContext
            markEntitySaved(uri, false)
            val trackSet = HashSet(getLikedTrackIds())
            trackSet.remove(cid)
            libraryPrefs.edit().putStringSet("liked_ids", trackSet).apply()
            val updated = loadSavedPlaylists().filterNot { cleanId(it.uri) == cid }
            saveSavedPlaylists(updated)
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
                    val tid = cleanId(tUri)
                    if (tid.isNotBlank() && existing.none { cleanId(it.uri) == tid }) {
                        val tr = runCatching { catalog.track("spotify:track:jio_$tid") }.getOrNull()
                        if (tr != null) existing.add(tr)
                    }
                }
                saveCustomPlaylistTracks(playlistId, existing)
                val updated = loadSavedPlaylists().map {
                    if (cleanId(it.uri) == playlistId) {
                        val cover = it.images.firstOrNull()?.url?.takeIf { u -> u.isNotBlank() }
                            ?: existing.firstOrNull()?.album?.images?.firstOrNull()?.url.orEmpty()
                        it.copy(
                            trackCount = existing.size.coerceAtLeast(it.trackCount),
                            images = if (cover.isNotBlank()) listOf(Image(cover, 500, 500)) else it.images,
                        )
                    } else it
                }
                saveSavedPlaylists(updated)
                _libraryChanges.tryEmit(Unit)
            }
            Unit
        }

        override suspend fun removeTracks(uri: Uri, uids: List<String>) = withContext(Dispatchers.IO) {
            val playlistId = cleanId(uri)
            if (playlistId.isNotBlank()) {
                val removeIds = uids.map { cleanId(it.substringBefore(':')) }.toSet()
                val existing = loadCustomPlaylistTracks(playlistId).filterNot { cleanId(it.uri) in removeIds }
                saveCustomPlaylistTracks(playlistId, existing)
                _libraryChanges.tryEmit(Unit)
            }
            Unit
        }

        override suspend fun moveTracks(uri: Uri, uids: List<String>, beforeUid: String?) {}
        override suspend fun recommended(uri: Uri, limit: Int, skip: List<Uri>): List<Track> = withContext(Dispatchers.IO) {
            val cid = cleanId(uri).removePrefix("song_").removePrefix("jio_song_")
            val recos = client.getSongRecommendations(cid, limit = limit)
            recos.map(::trackOf)
        }
    }

    override val extras: TrackExtras = object : TrackExtras {
        override suspend fun lyrics(trackUri: Uri): Lyrics? = withContext(Dispatchers.IO) {
            val cid = cleanId(trackUri).removePrefix("song_").removePrefix("jio_song_")
            var l = client.getLyrics(cid)
            if (l.isNullOrBlank()) {
                val hint = client.getQueryHint(cid)
                if (!hint.isNullOrBlank()) {
                    l = client.searchAndGetLyrics(hint, "")
                }
            }
            if (!l.isNullOrBlank()) {
                val lines = l.lines().filter { it.isNotBlank() }.map { LyricsLine(startMs = 0L, endMs = null, text = it) }
                if (lines.isNotEmpty()) {
                    Lyrics(
                        lines = lines,
                        synced = false,
                        language = null,
                        provider = "Jiosavan",
                        background = null,
                        text = null,
                        highlight = null,
                    )
                } else null
            } else null
        }

        override suspend fun canvas(trackUri: Uri): Canvas? = null
        override suspend fun credits(trackUri: Uri): Credits = Credits(roles = emptyList())
        override suspend fun radio(seedUri: Uri): Uri? {
            val cid = cleanId(seedUri).removePrefix("song_").removePrefix("jio_song_")
            return if (cid.isNotBlank()) "spotify:playlist:jio_song_$cid" else null
        }
        override suspend fun setExcludedFromTaste(trackUri: Uri, excluded: Boolean) {}
        override suspend fun isExcludedFromTaste(trackUris: List<Uri>): List<Boolean> = trackUris.map { false }
        override suspend fun setArtistBanned(artistUri: Uri, banned: Boolean) {}
        override suspend fun isArtistBanned(artistUris: List<Uri>): List<Boolean> = artistUris.map { false }
    }

    override val users: Users = object : Users {
        override suspend fun me(): Account = Account(
            uri = "spotify:user:jiosavan_user",
            username = "Jiosavan",
            name = "Jiosavan",
            images = listOf(Image("https://www.jiosaavn.com/favicon.ico", 128, 128)),
            premium = true,
            country = "IN",
        )

        override suspend fun profile(userUri: Uri): UserProfile = UserProfile(
            uri = userUri,
            name = "Jiosavan",
            images = emptyList(),
            followers = 0,
            following = 0,
            publicPlaylists = emptyList(),
        )

        override suspend fun followers(userUri: Uri): List<UserRef> = emptyList()
        override suspend fun following(userUri: Uri): List<UserRef> = emptyList()
        override suspend fun top(range: TimeRange, limit: Int): TopContent = TopContent(artists = emptyList(), tracks = emptyList())
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

    override val devices: Devices = object : Devices {
        override val devices: StateFlow<List<Device>> = MutableStateFlow(
            listOf(Device(id = "jiosavan_phone", name = "This Device (Jiosavan)", type = "Smartphone", active = true, volume = 1.0, local = true))
        )
        override suspend fun refresh() {}
        override suspend fun transfer(deviceId: String, play: Boolean) {}
        override suspend fun pullHere() {}
    }

    override val podcasts: Podcasts = object : Podcasts {
        override suspend fun show(uri: Uri): Show = Show(
            uri = uri, name = "Jiosavan Podcast", publisher = "Jiosavan", description = null, images = emptyList(),
            saved = false, rating = null, topics = emptyList(),
        )
        override suspend fun audioUrl(uri: Uri): EpisodeAudio = EpisodeAudio(url = null, passthrough = "NONE", format = "MP4_128")
        override suspend fun episodes(uri: Uri, offset: Int, limit: Int): Page<Episode> = Page(emptyList(), offset, 0, null)
        override suspend fun episode(uri: Uri): Episode = Episode(
            uri = uri, name = "Episode", description = null, durationMs = 0L, releaseDate = null,
            images = emptyList(), show = null, playedPositionMs = 0L, played = false,
            explicit = false, playable = false, previewUrl = null,
        )
        override suspend fun audiobook(uri: Uri): Audiobook = Audiobook(
            uri = uri, name = "Audiobook", authors = emptyList(), narrators = emptyList(),
            description = null, images = emptyList(), chapterCount = 0,
        )
        override suspend fun chapters(uri: Uri, offset: Int, limit: Int): Page<Episode> = Page(emptyList(), offset, 0, null)
        override suspend fun savedEpisodes(offset: Int, limit: Int): Page<Episode> = Page(emptyList(), offset, 0, null)
        override suspend fun setEpisodeSaved(uri: Uri, saved: Boolean) {}
    }

    override val jam: Jam = object : Jam {
        override suspend fun availability(): JamAvailability = JamAvailability.RESTRICTED
        override val session: StateFlow<JamSession?> = MutableStateFlow(null)
        override suspend fun refresh() {}
        override suspend fun start(): JamSession = throw UnsupportedOperationException("Jam not available in Jiosavan mode")
        override suspend fun join(joinToken: String): JamSession = throw UnsupportedOperationException("Jam not available in Jiosavan mode")
        override suspend fun leave() {}
        override suspend fun end() {}
        override suspend fun inviteLink(): String? = null
        override suspend fun removeMember(userUri: Uri) {}
        override suspend fun setQueueOnly(on: Boolean) {}
        override suspend fun setParticipantVolumeControl(on: Boolean) {}
    }

    override val blends: Blends = object : Blends {
        override suspend fun invite(): BlendInvite = BlendInvite(id = "jio", link = "")
        override suspend fun invitation(linkOrId: String): BlendInvitation = BlendInvitation(
            state = BlendInvitationState.JOINABLE, title = null, subtitle = null,
            inviter = null, members = emptyList(), playlist = null,
        )
        override suspend fun join(linkOrId: String): Uri = "spotify:playlist:jio_blend"
        override suspend fun group(playlist: Uri): BlendGroup = BlendGroup(
            title = null, inviteLabel = null, link = null, members = emptyList(),
        )
        override suspend fun story(playlist: Uri): BlendStory? = null
        override suspend fun rename(playlist: Uri, name: String) {}
        override suspend fun leave(playlist: Uri) {}
    }

    override val recents: Recents = object : Recents {
        override suspend fun recentEntries(offset: Int, limit: Int): Page<RecentEntry> = Page(emptyList(), offset, 0, null)
    }
}
