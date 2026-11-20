package app.wavebits.host.android.jiosaavn

import android.util.Log
import app.wavebits.api.AlbumRef
import app.wavebits.api.ArtistRef
import app.wavebits.api.Image
import app.wavebits.api.ItemKind
import app.wavebits.api.NowPlaying
import app.wavebits.api.Queue
import app.wavebits.api.Repeat
import app.wavebits.api.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated Queue Management System for JioSaavn built around the
 * SumitKolhe JioSaavn API specification (https://github.com/sumitkolhe/jiosaavn-api):
 * - `GET /api/songs/{id}` & `GET /api/songs?ids={ids}` for song & batch queue item resolution
 * - `GET /api/songs/{id}/suggestions?limit=25` for dynamic infinite radio & autoplay queue replenishment
 * - `GET /api/albums?id={id}` for album context queues
 * - `GET /api/playlists?id={id}&limit=100` for playlist context queues
 * - `GET /api/artists/{id}/songs` for artist top-tracks queues
 *
 * Architecture:
 * - Maintains distinct [userQueue] ("Next in Queue" / priority user additions) and
 *   [contextQueue] ("Next Up" / context tracks + SumitKolhe song suggestions), plus [history] for Previous navigation.
 * - Assigns a stable, unique queue entry `uid` to every queued item so duplicate songs,
 *   drag-and-drop reorders (within or across sections), swipe-to-remove, and skipTo work deterministically.
 */
class JioSaavnQueueManager(
    private val client: JioSaavnApiClient,
    private val scope: CoroutineScope,
    private val onSnapshotChanged: (QueueSnapshot) -> Unit,
) {
    data class QueueEntry(
        val uid: String,
        val track: JioSaavnPlayer.JioTrackData,
        val isUserQueued: Boolean,
        val isAutoSuggestion: Boolean = false,
    ) {
        fun toNowPlaying(): NowPlaying = NowPlaying(
            kind = ItemKind.TRACK,
            uri = track.uri,
            uid = uid,
            name = track.title,
            artists = listOf(ArtistRef(uri = "spotify:artist:jio_${track.id.take(8)}", name = track.artist)),
            album = AlbumRef(
                uri = "spotify:album:jio_${track.id.take(8)}",
                name = track.album,
                images = if (track.coverUrl.isNotBlank()) listOf(Image(url = track.coverUrl, width = 500, height = 500)) else emptyList(),
            ),
            imageUrl = track.coverUrl,
            durationMs = track.durationMs,
            explicit = false,
            provider = when {
                isUserQueued -> "queue"
                isAutoSuggestion -> "autoplay"
                else -> "context"
            },
        )
    }

    data class QueueSnapshot(
        val current: QueueEntry?,
        val previous: QueueEntry?,
        val next: QueueEntry?,
        val userQueue: List<QueueEntry>,
        val contextQueue: List<QueueEntry>,
        val contextUri: String?,
        val contextName: String?,
        val shuffle: Boolean,
        val repeat: Repeat,
    )

    private val tag = "JioSaavnQueueManager"
    private val mutex = Mutex()
    private val uidCounter = AtomicLong(1000L)

    private val _queue = MutableStateFlow<Queue?>(null)
    val queue: StateFlow<Queue?> = _queue.asStateFlow()

    // Track & Context Caches
    private val trackRegistry = ConcurrentHashMap<String, JioSaavnPlayer.JioTrackData>()
    private val contextRegistry = ConcurrentHashMap<String, List<JioSaavnPlayer.JioTrackData>>()
    private val contextNames = ConcurrentHashMap<String, String>()
    private val queryHints = ConcurrentHashMap<String, String>()

    // Active Queue State (guarded by mutex)
    private val history = ArrayDeque<QueueEntry>()
    private var currentEntry: QueueEntry? = null
    private val userQueue = mutableListOf<QueueEntry>()
    private val contextQueue = mutableListOf<QueueEntry>()
    private var unshuffledContextBackup: List<QueueEntry>? = null
    private var fullContextTracks: List<JioSaavnPlayer.JioTrackData> = emptyList()

    fun getCurrentEntry(): QueueEntry? = currentEntry
    fun getContextQueueSize(): Int = contextQueue.size

    @Volatile var contextUri: String? = null
        private set
    @Volatile var contextName: String? = "JioSaavn"
        private set
    @Volatile var isShuffle: Boolean = false
        private set
    @Volatile var repeatMode: Repeat = Repeat.OFF
        private set
    @Volatile var autoplayEnabled: Boolean = true

    private var suggestionJob: Job? = null

    private fun nextUid(prefix: String = "jq"): String = "${prefix}_${uidCounter.incrementAndGet()}"

    fun normalizeId(raw: String): String =
        raw.substringAfterLast(':')
            .removePrefix("jio_song_")
            .removePrefix("jio_playlist_")
            .removePrefix("jio_album_")
            .removePrefix("jio_artist_")
            .removePrefix("jio_")
            .removePrefix("song_")
            .removePrefix("yt_")
            .substringBefore('|')
            .trim()

    private fun isGenericPlaceholder(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        val t = text.trim()
        return t.equals("WaveBits Song", ignoreCase = true) ||
            t.equals("WaveBits", ignoreCase = true) ||
            t.equals("WaveBits Artist", ignoreCase = true) ||
            t.equals("Unknown", ignoreCase = true) ||
            t.equals("Track", ignoreCase = true) ||
            t.equals("Song", ignoreCase = true)
    }

    // ── Registration & Metadata Caching ──

    fun cacheTrack(track: Track) {
        val cleanId = normalizeId(track.uri)
        if (cleanId.isBlank()) return
        val existing = trackRegistry[cleanId]
        val title = track.name.takeIf { !isGenericPlaceholder(it) } ?: existing?.title ?: "WaveBits Song"
        val artist = track.artists.joinToString(", ") { it.name }.takeIf { !isGenericPlaceholder(it) }
            ?: existing?.artist ?: "WaveBits"
        val album = track.album?.name?.takeIf { !isGenericPlaceholder(it) } ?: existing?.album ?: title
        val cover = track.album?.images?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
            ?: existing?.coverUrl.orEmpty()
        val dur = track.durationMs.takeIf { it > 0 } ?: existing?.durationMs ?: 210_000L

        val data = JioSaavnPlayer.JioTrackData(
            id = cleanId,
            uri = "spotify:track:jio_$cleanId",
            title = title,
            artist = artist,
            album = album,
            coverUrl = cover,
            durationMs = dur,
        )
        trackRegistry[cleanId] = data
        trackRegistry[data.uri] = data
        trackRegistry[track.uri] = data
        if (!isGenericPlaceholder(title)) {
            val hint = if (!isGenericPlaceholder(artist)) "$title - $artist" else title
            registerQueryHint(cleanId, hint)
        }
    }

    fun registerQueryHint(trackId: String, hint: String) {
        val cleanHint = hint.trim()
        if (cleanHint.isNotBlank()) {
            queryHints[trackId] = cleanHint
            val cleanId = normalizeId(trackId)
            if (cleanId.isNotBlank()) {
                queryHints[cleanId] = cleanHint
            }
            client.registerQueryHint(trackId, cleanHint)
        }
    }

    fun getRegisteredTrack(idOrUri: String): JioSaavnPlayer.JioTrackData? {
        val cleanId = normalizeId(idOrUri)
        return trackRegistry[idOrUri] ?: trackRegistry[cleanId] ?: trackRegistry["spotify:track:jio_$cleanId"]
    }

    fun getRegisteredContext(contextUri: String): List<JioSaavnPlayer.JioTrackData>? = contextRegistry[contextUri]

    fun registerTracks(
        items: List<Triple<String, String, String>>,
        meta: List<Triple<String, String, out Number>>,
    ) {
        items.forEachIndexed { i, (id, title, artist) ->
            val cleanId = normalizeId(id)
            if (cleanId.isBlank()) return@forEachIndexed
            val (cover, album, dur) = meta.getOrNull(i) ?: Triple("", "", 180_000L)
            val existing = getRegisteredTrack(cleanId)
            val effectiveTitle = title.takeIf { !isGenericPlaceholder(it) } ?: existing?.title ?: "WaveBits Song"
            val effectiveArtist = artist.takeIf { !isGenericPlaceholder(it) } ?: existing?.artist ?: "WaveBits"
            val effectiveAlbum = album.takeIf { !isGenericPlaceholder(it) } ?: existing?.album ?: effectiveTitle
            val effectiveCover = cover.takeIf { it.isNotBlank() } ?: existing?.coverUrl.orEmpty()
            val effectiveDur = dur.toLong().takeIf { it > 0 } ?: existing?.durationMs ?: 180_000L

            val d = JioSaavnPlayer.JioTrackData(
                id = cleanId,
                uri = if (id.startsWith("spotify:track:")) id else "spotify:track:jio_$cleanId",
                title = effectiveTitle,
                artist = effectiveArtist,
                album = effectiveAlbum,
                coverUrl = effectiveCover,
                durationMs = effectiveDur,
            )
            trackRegistry[cleanId] = d
            trackRegistry[d.uri] = d
            if (!isGenericPlaceholder(effectiveTitle)) {
                registerQueryHint(cleanId, if (!isGenericPlaceholder(effectiveArtist)) "$effectiveTitle - $effectiveArtist" else effectiveTitle)
            }
        }
    }

    fun registerContextTracks(
        contextUri: String,
        items: List<Triple<String, String, String>>,
        meta: List<Triple<String, String, out Number>>,
    ) {
        if (contextUri.isBlank()) return
        registerTracks(items, meta)
        val list = items.mapIndexedNotNull { i, (id, title, artist) ->
            val cleanId = normalizeId(id)
            if (cleanId.isBlank()) return@mapIndexedNotNull null
            getRegisteredTrack(cleanId) ?: run {
                val (cover, album, dur) = meta.getOrNull(i) ?: Triple("", "", 180_000L)
                JioSaavnPlayer.JioTrackData(
                    id = cleanId,
                    uri = if (id.startsWith("spotify:track:")) id else "spotify:track:jio_$cleanId",
                    title = title,
                    artist = artist,
                    album = album,
                    coverUrl = cover,
                    durationMs = dur.toLong().takeIf { it > 0 } ?: 180_000L,
                )
            }
        }
        if (list.isNotEmpty()) {
            contextRegistry[contextUri] = list
            if (this.contextUri == contextUri && list.size > 1) {
                scope.launch {
                    mutex.withLock {
                        val curId = currentEntry?.track?.id
                        if (this@JioSaavnQueueManager.contextUri == contextUri && contextQueue.isEmpty() && curId != null) {
                            fullContextTracks = list
                            val idx = list.indexOfFirst { it.id == curId }.takeIf { it >= 0 } ?: 0
                            if (isShuffle) {
                                val remaining = list.filterIndexed { i, _ -> i != idx }.map {
                                    QueueEntry(uid = nextUid("ctx"), track = it, isUserQueued = false)
                                }
                                unshuffledContextBackup = remaining
                                contextQueue.addAll(remaining.shuffled())
                            } else {
                                for (i in (idx + 1) until list.size) {
                                    contextQueue.add(QueueEntry(uid = nextUid("ctx"), track = list[i], isUserQueued = false))
                                }
                            }
                            emitQueueStateLocked()
                        }
                    }
                }
            }
        }
    }

    private fun mapClientTrack(ct: JioSaavnApiClient.JioTrack): JioSaavnPlayer.JioTrackData {
        val cleanId = normalizeId(ct.id)
        return JioSaavnPlayer.JioTrackData(
            id = cleanId,
            uri = "spotify:track:jio_$cleanId",
            title = ct.title,
            artist = ct.artist,
            album = ct.album,
            coverUrl = ct.imageUrl,
            durationMs = (ct.durationSec * 1000L).coerceAtLeast(60_000L),
        ).also {
            trackRegistry[cleanId] = it
            trackRegistry[it.uri] = it
        }
    }

    private fun mapClientTracks(tracks: List<JioSaavnApiClient.JioTrack>): List<JioSaavnPlayer.JioTrackData> =
        tracks.map(::mapClientTrack)

    /**
     * Resolves full track details from cache or SumitKolhe `/api/songs/{id}`.
     */
    suspend fun resolveTrack(uriOrId: String): JioSaavnPlayer.JioTrackData {
        val cleanId = normalizeId(uriOrId)
        getRegisteredTrack(cleanId)?.let { cached ->
            if (!isGenericPlaceholder(cached.title) && cached.coverUrl.isNotBlank()) {
                return cached
            }
        }
        client.getCachedTrack(cleanId)?.let { ct ->
            return mapClientTrack(ct)
        }
        val fetched = withContext(Dispatchers.IO) {
            runCatching { client.getSongDetails(cleanId) }.getOrNull()
        }
        if (fetched != null) {
            return mapClientTrack(fetched)
        }
        val rawHint = queryHints[cleanId] ?: client.getQueryHint(cleanId)
        val hintParts = rawHint?.split(" — ", " - ", limit = 2)
        val hintTitle = hintParts?.getOrNull(0)?.trim()?.takeIf { !isGenericPlaceholder(it) }
        val hintArtist = hintParts?.getOrNull(1)?.trim()?.takeIf { !isGenericPlaceholder(it) }
        return getRegisteredTrack(cleanId) ?: JioSaavnPlayer.JioTrackData(
            id = cleanId,
            uri = "spotify:track:jio_$cleanId",
            title = hintTitle ?: "WaveBits Song",
            artist = hintArtist ?: "WaveBits",
            album = hintTitle ?: "JioSaavn",
            coverUrl = "",
            durationMs = 210_000L,
        )
    }

    /**
     * Restores a previously played track and queue into the manager (paused at startup) without starting ExoPlayer.
     */
    fun restoreSession(
        current: JioSaavnPlayer.JioTrackData,
        upcoming: List<JioSaavnPlayer.JioTrackData>,
        ctxUri: String?,
        ctxName: String?,
    ) {
        val allTracks = listOf(current) + upcoming
        allTracks.forEach { t ->
            val cleanId = normalizeId(t.id.ifBlank { t.uri })
            if (cleanId.isNotBlank()) {
                val fixed = t.copy(id = cleanId, uri = if (t.uri.startsWith("spotify:track:")) t.uri else "spotify:track:jio_$cleanId")
                trackRegistry[cleanId] = fixed
                trackRegistry[fixed.uri] = fixed
                if (!isGenericPlaceholder(fixed.title)) {
                    registerQueryHint(cleanId, if (!isGenericPlaceholder(fixed.artist)) "${fixed.title} - ${fixed.artist}" else fixed.title)
                }
            }
        }
        scope.launch {
            mutex.withLock {
                if (currentEntry != null) return@withLock
                val cleanCurId = normalizeId(current.id.ifBlank { current.uri })
                val fixedCur = current.copy(
                    id = cleanCurId,
                    uri = if (current.uri.startsWith("spotify:track:")) current.uri else "spotify:track:jio_$cleanCurId",
                )
                contextUri = ctxUri?.takeIf { it.isNotBlank() } ?: fixedCur.uri
                contextName = ctxName?.takeIf { it.isNotBlank() } ?: fixedCur.title
                currentEntry = QueueEntry(uid = nextUid("now"), track = fixedCur, isUserQueued = false)
                contextQueue.clear()
                upcoming.forEach { u ->
                    val cid = normalizeId(u.id.ifBlank { u.uri })
                    val fixedU = u.copy(
                        id = cid,
                        uri = if (u.uri.startsWith("spotify:track:")) u.uri else "spotify:track:jio_$cid",
                    )
                    contextQueue.add(QueueEntry(uid = nextUid("ctx"), track = fixedU, isUserQueued = false))
                }
                fullContextTracks = listOf(fixedCur) + contextQueue.map { it.track }
                emitQueueStateLocked()
            }
        }
    }

    /**
     * Batch resolves multiple URIs/IDs via SumitKolhe `GET /api/songs?ids=...`.
     */
    suspend fun resolveTracksBatch(uris: List<String>): List<JioSaavnPlayer.JioTrackData> {
        if (uris.isEmpty()) return emptyList()
        val cleanIds = uris.map(::normalizeId).filter { it.isNotBlank() }
        val missingIds = cleanIds.filter { cid ->
            val reg = getRegisteredTrack(cid)
            reg == null || isGenericPlaceholder(reg.title) || reg.coverUrl.isBlank()
        }
        if (missingIds.isNotEmpty()) {
            val fetched = withContext(Dispatchers.IO) {
                runCatching { client.getSongsByIds(missingIds) }.getOrDefault(emptyList())
            }
            for (ct in fetched) {
                mapClientTrack(ct)
            }
        }
        return cleanIds.map { cid -> resolveTrack(cid) }
    }

    /**
     * Loads tracks for an Album, Playlist, Artist, or Song Radio context using SumitKolhe API endpoints:
     * - Album: `GET /api/albums?id={id}`
     * - Playlist: `GET /api/playlists?id={id}&limit=100`
     * - Artist: `GET /api/artists/{id}/songs?page=0`
     * - Song / Radio: `GET /api/songs/{id}/suggestions?limit=25`
     */
    private suspend fun fetchContextFromSumitKolheApi(
        ctxUri: String,
        seedTrack: JioSaavnPlayer.JioTrackData?,
    ): Pair<String, List<JioSaavnPlayer.JioTrackData>> = withContext(Dispatchers.IO) {
        val cleanId = normalizeId(ctxUri)
        val isSongRadioContext = ctxUri.contains("jio_song_") || ctxUri.contains(":song_")
        when {
            ctxUri.startsWith("spotify:album:") || ctxUri.contains(":album:") -> {
                val album = runCatching { client.getAlbumDetails(cleanId) }.getOrNull()
                if (album != null && album.tracks.isNotEmpty()) {
                    val mapped = mapClientTracks(album.tracks)
                    contextRegistry[ctxUri] = mapped
                    contextNames[ctxUri] = album.title
                    return@withContext album.title to mapped
                }
            }
            (ctxUri.startsWith("spotify:playlist:") || ctxUri.contains(":playlist:")) && !isSongRadioContext -> {
                val playlist = runCatching { client.getPlaylistDetails(cleanId) }.getOrNull()
                if (playlist != null && playlist.tracks.isNotEmpty()) {
                    val mapped = mapClientTracks(playlist.tracks)
                    contextRegistry[ctxUri] = mapped
                    contextNames[ctxUri] = playlist.title
                    return@withContext playlist.title to mapped
                }
            }
            ctxUri.startsWith("spotify:artist:") || ctxUri.contains(":artist:") -> {
                val artistHint = client.getQueryHint(cleanId) ?: seedTrack?.artist.orEmpty()
                val songs = runCatching { client.getArtistSongs(cleanId, artistHint, limit = 30) }.getOrDefault(emptyList())
                if (songs.isNotEmpty()) {
                    val mapped = mapClientTracks(songs)
                    contextRegistry[ctxUri] = mapped
                    val name = artistHint.ifBlank { mapped.firstOrNull()?.artist ?: "Artist" }
                    contextNames[ctxUri] = name
                    return@withContext name to mapped
                }
            }
        }

        // Single track or radio fallback: SumitKolhe `/api/songs/{id}/suggestions?limit=25`
        if (seedTrack != null) {
            val effectiveSeed = if (isGenericPlaceholder(seedTrack.title)) {
                runCatching { client.getSongDetails(seedTrack.id) }.getOrNull()?.let(::mapClientTrack) ?: seedTrack
            } else {
                seedTrack
            }
            val suggestions = runCatching {
                client.getSongSuggestions(effectiveSeed.id, effectiveSeed.title, effectiveSeed.artist, limit = 25)
            }.getOrDefault(emptyList())
            val mapped = mapClientTracks(suggestions).filter { it.id != effectiveSeed.id }
            val resultList = listOf(effectiveSeed) + mapped
            if (ctxUri.isNotBlank() && mapped.isNotEmpty()) {
                contextRegistry[ctxUri] = resultList
            }
            return@withContext effectiveSeed.title to resultList
        } else if (cleanId.isNotBlank()) {
            val single = runCatching { client.getSongDetails(cleanId) }.getOrNull()?.let(::mapClientTrack)
            if (single != null) {
                val suggestions = runCatching {
                    client.getSongSuggestions(single.id, single.title, single.artist, limit = 25)
                }.getOrDefault(emptyList())
                val mapped = mapClientTracks(suggestions).filter { it.id != single.id }
                val resultList = listOf(single) + mapped
                if (ctxUri.isNotBlank() && mapped.isNotEmpty()) {
                    contextRegistry[ctxUri] = resultList
                }
                return@withContext single.title to resultList
            }
        }

        "JioSaavn" to emptyList()
    }

    // ── Core Queue Playback & Lifecycle Operations ──

    /**
     * Initializes the queue for a new playback request and returns the [QueueEntry] to start playing.
     */
    suspend fun startPlayback(
        requestedContextUri: String,
        trackUri: String? = null,
        index: Int? = null,
        order: List<String>? = null,
    ): QueueEntry? {
        suggestionJob?.cancel()
        val effectiveTrackUri = trackUri ?: requestedContextUri.takeIf { it.startsWith("spotify:track:") }
        val cachedContextTracks = contextRegistry[requestedContextUri]

        if (!cachedContextTracks.isNullOrEmpty()) {
            val entry = mutex.withLock {
                contextUri = requestedContextUri
                contextName = contextNames[requestedContextUri] ?: deriveContextName(requestedContextUri)

                val orderedTracks = applyOrder(cachedContextTracks, order)
                fullContextTracks = orderedTracks

                val targetId = effectiveTrackUri?.let(::normalizeId).orEmpty()
                val chosenIndex = when {
                    targetId.isNotBlank() -> orderedTracks.indexOfFirst { it.id == targetId || it.uri == effectiveTrackUri }.takeIf { it >= 0 } ?: (index ?: 0)
                    index != null -> index.coerceIn(0, orderedTracks.lastIndex)
                    else -> 0
                }.coerceIn(0, orderedTracks.lastIndex)

                val chosenTrack = orderedTracks[chosenIndex]
                val nowEntry = QueueEntry(uid = nextUid("now"), track = chosenTrack, isUserQueued = false)
                currentEntry = nowEntry

                history.clear()
                contextQueue.clear()

                if (isShuffle && orderedTracks.size > 1) {
                    val unshuffledRemaining = orderedTracks.filterIndexed { i, _ -> i != chosenIndex }.map {
                        QueueEntry(uid = nextUid("ctx"), track = it, isUserQueued = false)
                    }
                    unshuffledContextBackup = unshuffledRemaining
                    contextQueue.addAll(unshuffledRemaining.shuffled())
                } else {
                    unshuffledContextBackup = null
                    for (i in 0 until chosenIndex) {
                        history.addLast(QueueEntry(uid = nextUid("hist"), track = orderedTracks[i], isUserQueued = false))
                    }
                    for (i in (chosenIndex + 1) until orderedTracks.size) {
                        contextQueue.add(QueueEntry(uid = nextUid("ctx"), track = orderedTracks[i], isUserQueued = false))
                    }
                }

                emitQueueStateLocked()
                nowEntry
            }

            // If context only had 1 track (or we're near the end of context), asynchronously load SumitKolhe suggestions
            if (contextQueue.size < 3 && autoplayEnabled) {
                triggerAsyncSuggestions(entry.track, requestedContextUri)
            }
            return entry
        }

        // Case 2: Specific track URI requested (start playing immediately, then load context or SumitKolhe suggestions)
        if (effectiveTrackUri != null) {
            val resolvedTrack = resolveTrack(effectiveTrackUri)
            val entry = mutex.withLock {
                val prevEntry = currentEntry
                if (prevEntry != null && prevEntry.track.id != resolvedTrack.id) {
                    pushHistoryLocked(prevEntry)
                }
                contextUri = requestedContextUri.ifBlank { resolvedTrack.uri }
                contextName = resolvedTrack.title
                fullContextTracks = listOf(resolvedTrack)
                contextQueue.clear()
                unshuffledContextBackup = null
                val nowEntry = QueueEntry(uid = nextUid("now"), track = resolvedTrack, isUserQueued = false)
                currentEntry = nowEntry
                emitQueueStateLocked()
                nowEntry
            }

            suggestionJob = scope.launch(Dispatchers.IO) {
                val (resolvedName, loadedTracks) = fetchContextFromSumitKolheApi(requestedContextUri, resolvedTrack)
                if (loadedTracks.size > 1) {
                    mutex.withLock {
                        if (currentEntry?.track?.id == resolvedTrack.id) {
                            val ordered = applyOrder(loadedTracks, order)
                            fullContextTracks = ordered
                            contextName = resolvedName.ifBlank { resolvedTrack.title }
                            val idx = ordered.indexOfFirst { it.id == resolvedTrack.id }.takeIf { it >= 0 } ?: 0

                            history.clear()
                            contextQueue.clear()
                            if (isShuffle && ordered.size > 1) {
                                val remaining = ordered.filterIndexed { i, _ -> i != idx }.map {
                                    QueueEntry(
                                        uid = nextUid("ctx"),
                                        track = it,
                                        isUserQueued = false,
                                        isAutoSuggestion = false,
                                    )
                                }
                                unshuffledContextBackup = remaining
                                contextQueue.addAll(remaining.shuffled())
                            } else {
                                unshuffledContextBackup = null
                                for (i in 0 until idx) {
                                    history.addLast(QueueEntry(uid = nextUid("hist"), track = ordered[i], isUserQueued = false))
                                }
                                for (i in (idx + 1) until ordered.size) {
                                    contextQueue.add(
                                        QueueEntry(
                                            uid = nextUid("ctx"),
                                            track = ordered[i],
                                            isUserQueued = false,
                                            isAutoSuggestion = false,
                                        )
                                    )
                                }
                            }
                            emitQueueStateLocked()
                        }
                    }
                } else if (autoplayEnabled) {
                    triggerAsyncSuggestions(resolvedTrack, requestedContextUri)
                }
            }
            return entry
        }

        // Case 3: Context URI without specific track (e.g., hitting play on an Album, Playlist, or Artist)
        val (resolvedName, loadedTracks) = fetchContextFromSumitKolheApi(requestedContextUri, null)
        if (loadedTracks.isEmpty()) return null

        val entry = mutex.withLock {
            contextUri = requestedContextUri
            contextName = resolvedName.ifBlank { deriveContextName(requestedContextUri) }
            val ordered = applyOrder(loadedTracks, order)
            fullContextTracks = ordered

            val chosenIndex = (index ?: 0).coerceIn(0, ordered.lastIndex)
            val chosenTrack = ordered[chosenIndex]
            val nowEntry = QueueEntry(uid = nextUid("now"), track = chosenTrack, isUserQueued = false)
            currentEntry = nowEntry

            history.clear()
            contextQueue.clear()

            if (isShuffle && ordered.size > 1) {
                val remaining = ordered.filterIndexed { i, _ -> i != chosenIndex }.map {
                    QueueEntry(uid = nextUid("ctx"), track = it, isUserQueued = false)
                }
                unshuffledContextBackup = remaining
                contextQueue.addAll(remaining.shuffled())
            } else {
                unshuffledContextBackup = null
                for (i in 0 until chosenIndex) {
                    history.addLast(QueueEntry(uid = nextUid("hist"), track = ordered[i], isUserQueued = false))
                }
                for (i in (chosenIndex + 1) until ordered.size) {
                    contextQueue.add(QueueEntry(uid = nextUid("ctx"), track = ordered[i], isUserQueued = false))
                }
            }

            emitQueueStateLocked()
            nowEntry
        }

        if (contextQueue.size < 3 && autoplayEnabled) {
            triggerAsyncSuggestions(entry.track, requestedContextUri)
        }
        return entry
    }

    /**
     * Advances to the next track in the queue:
     * 1. If [fromTrackEnd] and [repeatMode] == [Repeat.TRACK], replays [currentEntry].
     * 2. Pops from [userQueue] ("Next in Queue") if non-empty.
     * 3. Pops from [contextQueue] ("Next Up") if non-empty.
     * 4. Wraps around [fullContextTracks] if [repeatMode] == [Repeat.CONTEXT].
     * 5. Fetches new suggestions via SumitKolhe `GET /api/songs/{id}/suggestions` if [autoplayEnabled].
     */
    suspend fun advanceToNext(fromTrackEnd: Boolean = false): QueueEntry? {
        val immediateNext = mutex.withLock {
            val cur = currentEntry
            if (fromTrackEnd && repeatMode == Repeat.TRACK && cur != null) {
                emitQueueStateLocked()
                return@withLock cur
            }

            if (cur != null) {
                pushHistoryLocked(cur)
            }

            when {
                userQueue.isNotEmpty() -> {
                    val nextUser = userQueue.removeAt(0)
                    currentEntry = nextUser
                    emitQueueStateLocked()
                    nextUser
                }
                contextQueue.isNotEmpty() -> {
                    val nextCtx = contextQueue.removeAt(0)
                    currentEntry = nextCtx
                    emitQueueStateLocked()
                    nextCtx
                }
                repeatMode == Repeat.CONTEXT && fullContextTracks.isNotEmpty() -> {
                    val list = if (isShuffle) fullContextTracks.shuffled() else fullContextTracks
                    val first = list.first()
                    val now = QueueEntry(uid = nextUid("now"), track = first, isUserQueued = false)
                    currentEntry = now
                    history.clear()
                    contextQueue.clear()
                    for (i in 1 until list.size) {
                        contextQueue.add(QueueEntry(uid = nextUid("ctx"), track = list[i], isUserQueued = false))
                    }
                    emitQueueStateLocked()
                    now
                }
                else -> null
            }
        }

        if (immediateNext != null) {
            replenishSuggestionsIfNeeded()
            return immediateNext
        }

        // Queue exhausted: fetch fresh suggestions from SumitKolhe `/api/songs/{id}/suggestions`
        if (autoplayEnabled) {
            val seed = currentEntry?.track ?: history.lastOrNull()?.track ?: return null
            val suggestions = withContext(Dispatchers.IO) {
                runCatching {
                    client.getSongSuggestions(seed.id, seed.title, seed.artist, limit = 25)
                }.getOrDefault(emptyList())
            }
            val mapped = mapClientTracks(suggestions).filter { it.id != seed.id }
            if (mapped.isNotEmpty()) {
                return mutex.withLock {
                    val first = mapped.first()
                    val now = QueueEntry(uid = nextUid("auto"), track = first, isUserQueued = false, isAutoSuggestion = true)
                    currentEntry = now
                    for (i in 1 until mapped.size) {
                        contextQueue.add(QueueEntry(uid = nextUid("auto"), track = mapped[i], isUserQueued = false, isAutoSuggestion = true))
                    }
                    emitQueueStateLocked()
                    now
                }
            }
        }

        return null
    }

    /**
     * Non-mutating peek at the upcoming track for crossfade pre-buffering.
     */
    suspend fun peekNextEntry(): QueueEntry? = mutex.withLock {
        if (repeatMode == Repeat.TRACK) return@withLock null
        when {
            userQueue.isNotEmpty() -> userQueue.firstOrNull()
            contextQueue.isNotEmpty() -> contextQueue.firstOrNull()
            repeatMode == Repeat.CONTEXT && fullContextTracks.isNotEmpty() ->
                fullContextTracks.firstOrNull()?.let { QueueEntry(uid = "peek_${it.id}", track = it, isUserQueued = false) }
            else -> null
        }
    }

    /**
     * Skips to the previously played track in [history], pushing the current track back to the front of [contextQueue].
     */
    suspend fun skipToPrevious(): QueueEntry? = mutex.withLock {
        if (history.isEmpty()) return@withLock null
        val prev = history.removeLast()
        currentEntry?.let { cur ->
            contextQueue.add(0, cur.copy(isUserQueued = false))
        }
        currentEntry = prev
        emitQueueStateLocked()
        prev
    }

    /**
     * Skips directly to a specific queue item (matched by unique `uid` first, then `uri` / track ID).
     */
    suspend fun skipTo(target: NowPlaying): QueueEntry? {
        val targetId = normalizeId(target.uri)
        val matched = mutex.withLock {
            // 1. Check userQueue ("Next in Queue")
            val userIdx = userQueue.indexOfFirst {
                (target.uid != null && it.uid == target.uid)
            }.takeIf { it >= 0 } ?: userQueue.indexOfFirst {
                it.track.uri == target.uri || it.track.id == targetId
            }

            if (userIdx >= 0) {
                currentEntry?.let(::pushHistoryLocked)
                val chosen = userQueue.removeAt(userIdx)
                currentEntry = chosen
                emitQueueStateLocked()
                return@withLock chosen
            }

            // 2. Check contextQueue ("Next Up")
            val ctxIdx = contextQueue.indexOfFirst {
                (target.uid != null && it.uid == target.uid)
            }.takeIf { it >= 0 } ?: contextQueue.indexOfFirst {
                it.track.uri == target.uri || it.track.id == targetId
            }

            if (ctxIdx >= 0) {
                currentEntry?.let(::pushHistoryLocked)
                for (i in 0 until ctxIdx) {
                    pushHistoryLocked(contextQueue[i])
                }
                val chosen = contextQueue[ctxIdx]
                contextQueue.subList(0, ctxIdx + 1).clear()
                currentEntry = chosen
                emitQueueStateLocked()
                return@withLock chosen
            }

            null
        }

        if (matched != null) {
            replenishSuggestionsIfNeeded()
            return matched
        }

        // 3. Fallback: resolve track via SumitKolhe `/api/songs/{id}` and play immediately
        val resolved = resolveTrack(target.uri)
        return mutex.withLock {
            currentEntry?.let(::pushHistoryLocked)
            val chosen = QueueEntry(uid = target.uid?.takeIf { it.isNotBlank() } ?: nextUid("now"), track = resolved, isUserQueued = false)
            currentEntry = chosen
            emitQueueStateLocked()
            chosen
        }
    }

    /**
     * Appends tracks to the end of [userQueue] ("Add to Queue").
     * Returns a non-null [QueueEntry] only if nothing was playing and playback should start immediately.
     */
    suspend fun addToQueue(uris: List<String>): QueueEntry? {
        if (uris.isEmpty()) return null
        val resolvedTracks = resolveTracksBatch(uris)
        if (resolvedTracks.isEmpty()) return null

        return mutex.withLock {
            val entries = resolvedTracks.map { track ->
                QueueEntry(uid = nextUid("uq"), track = track, isUserQueued = true)
            }
            if (currentEntry == null) {
                val first = entries.first()
                currentEntry = first
                userQueue.addAll(entries.drop(1))
                emitQueueStateLocked()
                first
            } else {
                userQueue.addAll(entries)
                emitQueueStateLocked()
                null
            }
        }
    }

    /**
     * Inserts tracks at the front of [userQueue] ("Play Next"), right after the currently playing song.
     * Returns a non-null [QueueEntry] only if nothing was playing and playback should start immediately.
     */
    suspend fun playNext(uris: List<String>): QueueEntry? {
        if (uris.isEmpty()) return null
        val resolvedTracks = resolveTracksBatch(uris)
        if (resolvedTracks.isEmpty()) return null

        return mutex.withLock {
            val entries = resolvedTracks.map { track ->
                QueueEntry(uid = nextUid("uq"), track = track, isUserQueued = true)
            }
            if (currentEntry == null) {
                val first = entries.first()
                currentEntry = first
                userQueue.addAll(0, entries.drop(1))
                emitQueueStateLocked()
                first
            } else {
                userQueue.addAll(0, entries)
                emitQueueStateLocked()
                null
            }
        }
    }

    /**
     * Removes the specified items from [userQueue] or [contextQueue].
     */
    suspend fun removeFromQueue(items: List<NowPlaying>) {
        if (items.isEmpty()) return
        mutex.withLock {
            for (item in items) {
                val itemUid = item.uid?.takeIf { it.isNotBlank() }
                val itemId = normalizeId(item.uri)

                var removed = false
                if (itemUid != null) {
                    val uIdx = userQueue.indexOfFirst { it.uid == itemUid }
                    if (uIdx >= 0) {
                        userQueue.removeAt(uIdx)
                        removed = true
                    } else {
                        val cIdx = contextQueue.indexOfFirst { it.uid == itemUid }
                        if (cIdx >= 0) {
                            val removedEntry = contextQueue.removeAt(cIdx)
                            unshuffledContextBackup = unshuffledContextBackup?.filterNot { it.uid == removedEntry.uid }
                            removed = true
                        }
                    }
                }

                if (!removed) {
                    val uIdx = userQueue.indexOfFirst { it.track.uri == item.uri || it.track.id == itemId }
                    if (uIdx >= 0) {
                        userQueue.removeAt(uIdx)
                    } else {
                        val cIdx = contextQueue.indexOfFirst { it.track.uri == item.uri || it.track.id == itemId }
                        if (cIdx >= 0) {
                            val removedEntry = contextQueue.removeAt(cIdx)
                            unshuffledContextBackup = unshuffledContextBackup?.filterNot { it.uid == removedEntry.uid }
                        }
                    }
                }
            }
            emitQueueStateLocked()
        }
        replenishSuggestionsIfNeeded()
    }

    /**
     * Clears all user-queued tracks ([userQueue]), preserving [contextQueue].
     */
    suspend fun clearUserQueue() {
        mutex.withLock {
            userQueue.clear()
            emitQueueStateLocked()
        }
    }

    /**
     * Moves [item] relative to [target] (before or after).
     * Seamlessly supports:
     * 1. Reordering within [userQueue]
     * 2. Reordering within [contextQueue]
     * 3. Moving an item from [contextQueue] into [userQueue]
     * 4. Moving an item from [userQueue] into [contextQueue]
     * Preserves the item's `uid` so UI drag-and-drop animations remain glitch-free.
     */
    suspend fun moveInQueue(item: NowPlaying, target: NowPlaying, after: Boolean) {
        mutex.withLock {
            val sourceEntry = extractEntryLocked(item) ?: return@withLock

            // Find where target lives
            val targetInUserIdx = findIndexLocked(userQueue, target)
            if (targetInUserIdx >= 0) {
                val insertAt = (if (after) targetInUserIdx + 1 else targetInUserIdx).coerceIn(0, userQueue.size)
                userQueue.add(insertAt, sourceEntry.copy(isUserQueued = true))
                emitQueueStateLocked()
                return@withLock
            }

            val targetInContextIdx = findIndexLocked(contextQueue, target)
            if (targetInContextIdx >= 0) {
                val insertAt = (if (after) targetInContextIdx + 1 else targetInContextIdx).coerceIn(0, contextQueue.size)
                contextQueue.add(insertAt, sourceEntry.copy(isUserQueued = false))
                emitQueueStateLocked()
                return@withLock
            }

            // Target wasn't found; put source back in its original list
            if (sourceEntry.isUserQueued) {
                userQueue.add(sourceEntry)
            } else {
                contextQueue.add(0, sourceEntry)
            }
            emitQueueStateLocked()
        }
    }

    /**
     * Moves an item from [contextQueue] into [userQueue] before/after [target] (or at the front of [userQueue] when [target] is null).
     */
    suspend fun moveIntoUserQueue(item: NowPlaying, target: NowPlaying?, after: Boolean) {
        mutex.withLock {
            val sourceEntry = extractEntryLocked(item) ?: return@withLock
            val movedEntry = sourceEntry.copy(isUserQueued = true)

            if (target == null) {
                userQueue.add(0, movedEntry)
            } else {
                val targetIdx = findIndexLocked(userQueue, target)
                if (targetIdx >= 0) {
                    val insertAt = (if (after) targetIdx + 1 else targetIdx).coerceIn(0, userQueue.size)
                    userQueue.add(insertAt, movedEntry)
                } else {
                    userQueue.add(movedEntry)
                }
            }
            emitQueueStateLocked()
        }
        replenishSuggestionsIfNeeded()
    }

    /**
     * Toggles shuffle mode on or off.
     * When turned ON, saves the current unshuffled [contextQueue] order and shuffles [contextQueue].
     * When turned OFF, restores the unshuffled [contextQueue] order for remaining unplayed tracks.
     */
    suspend fun setShuffle(enabled: Boolean) {
        mutex.withLock {
            if (isShuffle == enabled) {
                emitQueueStateLocked()
                return@withLock
            }
            isShuffle = enabled
            if (contextQueue.size > 1) {
                if (enabled) {
                    unshuffledContextBackup = contextQueue.toList()
                    contextQueue.shuffle()
                } else {
                    val backup = unshuffledContextBackup
                    unshuffledContextBackup = null
                    if (backup != null) {
                        val remainingByUid = contextQueue.associateBy { it.uid }
                        val restored = ArrayList<QueueEntry>(contextQueue.size)
                        for (orig in backup) {
                            remainingByUid[orig.uid]?.let(restored::add)
                        }
                        val restoredUids = restored.map { it.uid }.toHashSet()
                        for (extra in contextQueue) {
                            if (extra.uid !in restoredUids) restored.add(extra)
                        }
                        contextQueue.clear()
                        contextQueue.addAll(restored)
                    }
                }
            }
            emitQueueStateLocked()
        }
    }

    /**
     * Updates repeat mode (`Repeat.OFF`, `Repeat.CONTEXT`, `Repeat.TRACK`).
     */
    suspend fun setRepeatMode(mode: Repeat) {
        mutex.withLock {
            repeatMode = mode
            emitQueueStateLocked()
        }
    }

    /**
     * Updates metadata of the currently playing track if it was resolved from a placeholder.
     */
    suspend fun updateCurrentTrackMetadata(updated: JioSaavnPlayer.JioTrackData) {
        mutex.withLock {
            trackRegistry[updated.id] = updated
            trackRegistry[updated.uri] = updated
            val cur = currentEntry
            if (cur != null && cur.track.id == updated.id) {
                currentEntry = cur.copy(track = updated)
                emitQueueStateLocked()
            }
        }
    }

    /**
     * Called when the user opens the Queue UI (`QueueScreen`, `QueueSheet`, `ImmersiveQueuePanel`).
     * Ensures the latest queue snapshot is emitted and replenishes SumitKolhe suggestions if needed.
     */
    fun onQueueWatched() {
        scope.launch {
            mutex.withLock {
                emitQueueStateLocked()
            }
            replenishSuggestionsIfNeeded()
        }
    }

    /**
     * Clears and resets active playback state when switching platforms or stopping the player.
     */
    fun reset() {
        suggestionJob?.cancel()
    }

    // ── SumitKolhe `/api/songs/{id}/suggestions` Auto-Replenishment ──

    private fun triggerAsyncSuggestions(seedTrack: JioSaavnPlayer.JioTrackData, forContextUri: String?) {
        suggestionJob?.cancel()
        suggestionJob = scope.launch(Dispatchers.IO) {
            val effectiveSeed = if (isGenericPlaceholder(seedTrack.title)) resolveTrack(seedTrack.id) else seedTrack
            val suggestions = runCatching {
                client.getSongSuggestions(effectiveSeed.id, effectiveSeed.title, effectiveSeed.artist, limit = 25)
            }.getOrDefault(emptyList())

            if (suggestions.isNotEmpty()) {
                val mapped = mapClientTracks(suggestions)
                mutex.withLock {
                    if (currentEntry?.track?.id == effectiveSeed.id) {
                        val existingIds = buildExistingTrackIdsLocked()
                        val newEntries = mapped
                            .filter { it.id !in existingIds }
                            .let { if (isShuffle) it.shuffled() else it }
                            .map { QueueEntry(uid = nextUid("auto"), track = it, isUserQueued = false, isAutoSuggestion = true) }

                        if (newEntries.isNotEmpty()) {
                            contextQueue.addAll(newEntries)
                            if (!forContextUri.isNullOrBlank() && (forContextUri.startsWith("spotify:track:") || forContextUri.contains("jio_song_"))) {
                                contextRegistry[forContextUri] = listOf(effectiveSeed) + contextQueue.map { it.track }
                            }
                            emitQueueStateLocked()
                        }
                    }
                }
            }
        }
    }

    @Volatile private var isReplenishing = false

    fun replenishSuggestionsIfNeeded() {
        if (!autoplayEnabled || isReplenishing || suggestionJob?.isActive == true) return
        scope.launch(Dispatchers.IO) {
            val seed: JioSaavnPlayer.JioTrackData? = mutex.withLock {
                if (contextQueue.size >= 5) return@withLock null
                contextQueue.lastOrNull()?.track ?: currentEntry?.track
            }
            if (seed == null || suggestionJob?.isActive == true) return@launch

            isReplenishing = true
            try {
                val effectiveSeed = if (isGenericPlaceholder(seed.title)) resolveTrack(seed.id) else seed
                val suggestions = runCatching {
                    client.getSongSuggestions(effectiveSeed.id, effectiveSeed.title, effectiveSeed.artist, limit = 20)
                }.getOrDefault(emptyList())
                if (suggestions.isNotEmpty()) {
                    val mapped = mapClientTracks(suggestions)
                    mutex.withLock {
                        val existingIds = buildExistingTrackIdsLocked()
                        val additions = mapped
                            .filter { it.id !in existingIds }
                            .map { QueueEntry(uid = nextUid("auto"), track = it, isUserQueued = false, isAutoSuggestion = true) }
                        if (additions.isNotEmpty()) {
                            contextQueue.addAll(additions)
                            emitQueueStateLocked()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Queue replenishment failed: ${e.message}")
            } finally {
                isReplenishing = false
            }
        }
    }

    // ── Internal Helpers (Must be called inside `mutex.withLock`) ──

    private fun pushHistoryLocked(entry: QueueEntry) {
        if (history.lastOrNull()?.uid != entry.uid) {
            history.addLast(entry.copy(isUserQueued = false))
            while (history.size > 100) {
                history.removeFirst()
            }
        }
    }

    private fun buildExistingTrackIdsLocked(): Set<String> {
        val ids = HashSet<String>()
        currentEntry?.track?.id?.let(ids::add)
        userQueue.forEach { ids.add(it.track.id) }
        contextQueue.forEach { ids.add(it.track.id) }
        history.takeLast(15).forEach { ids.add(it.track.id) }
        return ids
    }

    private fun findIndexLocked(list: List<QueueEntry>, target: NowPlaying): Int {
        val targetUid = target.uid?.takeIf { it.isNotBlank() }
        if (targetUid != null) {
            val byUid = list.indexOfFirst { it.uid == targetUid }
            if (byUid >= 0) return byUid
        }
        val targetId = normalizeId(target.uri)
        return list.indexOfFirst { it.track.uri == target.uri || (targetId.isNotBlank() && it.track.id == targetId) }
    }

    private fun extractEntryLocked(target: NowPlaying): QueueEntry? {
        val uIdx = findIndexLocked(userQueue, target)
        if (uIdx >= 0) {
            return userQueue.removeAt(uIdx)
        }
        val cIdx = findIndexLocked(contextQueue, target)
        if (cIdx >= 0) {
            val removed = contextQueue.removeAt(cIdx)
            unshuffledContextBackup = unshuffledContextBackup?.filterNot { it.uid == removed.uid }
            return removed
        }
        // Fallback if item wasn't in either list yet
        val cleanId = normalizeId(target.uri)
        val track = getRegisteredTrack(cleanId) ?: JioSaavnPlayer.JioTrackData(
            id = cleanId,
            uri = target.uri.ifBlank { "spotify:track:jio_$cleanId" },
            title = target.name.ifBlank { "WaveBits Song" },
            artist = target.artists.joinToString(", ") { it.name }.ifBlank { "WaveBits" },
            album = target.album?.name?.ifBlank { "JioSaavn" } ?: "JioSaavn",
            coverUrl = target.album?.images?.firstOrNull()?.url.orEmpty(),
            durationMs = target.durationMs.takeIf { it > 0 } ?: 210_000L,
        )
        return QueueEntry(
            uid = target.uid?.takeIf { it.isNotBlank() } ?: nextUid("uq"),
            track = track,
            isUserQueued = true,
        )
    }

    private fun applyOrder(tracks: List<JioSaavnPlayer.JioTrackData>, order: List<String>?): List<JioSaavnPlayer.JioTrackData> {
        if (order.isNullOrEmpty()) return tracks
        val orderMap = HashMap<String, Int>(order.size * 2)
        order.forEachIndexed { idx, raw ->
            val base = raw.substringBefore('|')
            orderMap[base] = idx
            orderMap[normalizeId(base)] = idx
        }
        return tracks.sortedBy { orderMap[it.uri] ?: orderMap[it.id] ?: Int.MAX_VALUE }
    }

    private fun deriveContextName(uri: String): String = when {
        uri.contains("collection:tracks") -> "Liked Songs"
        uri.startsWith("spotify:album:") -> "Album"
        uri.startsWith("spotify:playlist:") -> "Playlist"
        uri.startsWith("spotify:artist:") -> "Artist"
        else -> "JioSaavn"
    }

    private fun emitQueueStateLocked() {
        val cur = currentEntry
        val prev = history.lastOrNull()
        val next = userQueue.firstOrNull() ?: contextQueue.firstOrNull()
        val userCopy = userQueue.toList()
        val ctxCopy = contextQueue.toList()

        _queue.value = Queue(
            current = cur?.toNowPlaying(),
            queued = userCopy.map { it.toNowPlaying() },
            nextUp = ctxCopy.map { it.toNowPlaying() },
        )

        onSnapshotChanged(
            QueueSnapshot(
                current = cur,
                previous = prev,
                next = next,
                userQueue = userCopy,
                contextQueue = ctxCopy,
                contextUri = contextUri,
                contextName = contextName,
                shuffle = isShuffle,
                repeat = repeatMode,
            )
        )
    }
}
