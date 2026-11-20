package app.wavebits.host.android.jiosaavn

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Dual-Engine Native Android client integrating both JioSaavn API architectures:
 * 1. Direct JioSaavn Engine (https://github.com/cyberboysumanjay/JioSaavnAPI)
 *    - Direct `www.jiosaavn.com/api.php` endpoints with DES/ECB/PKCS5 decryption (`38346591` key)
 * 2. SumitKolhe JioSaavn Engine (https://github.com/sumitkolhe/jiosaavn-api)
 *    - REST API wrapper (saavn.dev / jiosaavn-api-v3.vercel.app) with structured JSON & quality arrays
 *
 * Automatic failover ensures maximum reliability and 320kbps high-fidelity audio stream resolution.
 */
class JioSaavnApiClient {

    data class JioTrack(
        val id: String,
        val title: String,
        val subtitle: String,
        val artist: String,
        val album: String,
        val albumId: String,
        val imageUrl: String,
        val durationSec: Int,
        val streamUrl: String,
        val year: String,
        val explicit: Boolean,
        val hasLyrics: Boolean,
        val language: String,
        val playCount: Long = 0L,
        val primaryArtists: List<Pair<String, String>> = emptyList(),
    )

    data class JioAlbum(
        val id: String,
        val title: String,
        val artist: String,
        val year: String,
        val imageUrl: String,
        val tracks: List<JioTrack>,
    )

    data class JioPlaylist(
        val id: String,
        val title: String,
        val subtitle: String,
        val imageUrl: String,
        val songCount: Int,
        val tracks: List<JioTrack>,
    )

    data class JioArtist(
        val id: String,
        val name: String,
        val imageUrl: String,
        val followerCount: Int = 0,
        val isVerified: Boolean = false,
        val bio: String = "",
        val topSongs: List<JioTrack> = emptyList(),
        val topAlbums: List<JioAlbum> = emptyList(),
        val singles: List<JioAlbum> = emptyList(),
        val similarArtists: List<JioArtist> = emptyList(),
    )

    data class JioSearchResult(
        val tracks: List<JioTrack>,
        val albums: List<JioAlbum>,
        val playlists: List<JioPlaylist>,
        val artists: List<JioArtist> = emptyList(),
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val desKey = "38346591".toByteArray(Charsets.UTF_8)
    private val queryHints = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val trackCache = java.util.concurrent.ConcurrentHashMap<String, JioTrack>()
    private val recommendationsCache = java.util.concurrent.ConcurrentHashMap<String, List<JioTrack>>()
    private val albumCache = java.util.concurrent.ConcurrentHashMap<String, JioAlbum>()
    private val playlistCache = java.util.concurrent.ConcurrentHashMap<String, JioPlaylist>()
    private val artistCache = java.util.concurrent.ConcurrentHashMap<String, JioArtist>()

    private fun normalizeId(raw: String): String =
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

    fun cacheTrack(track: JioTrack): JioTrack {
        if (track.id.isNotBlank()) {
            if (trackCache.size > 400) {
                // Low-end memory optimization: keep cache size bounded
                val keysToRemove = trackCache.keys.take(200)
                keysToRemove.forEach { trackCache.remove(it) }
            }
            val clean = normalizeId(track.id)
            trackCache[track.id] = track
            if (clean.isNotBlank()) trackCache[clean] = track
            if (track.artist.isNotBlank() && !track.artist.startsWith("WaveBits")) {
                registerQueryHint(clean, "${track.title} - ${track.artist}".trim())
            } else if (track.title.isNotBlank()) {
                registerQueryHint(clean, track.title.trim())
            }
        }
        return track
    }

    fun getCachedTrack(id: String): JioTrack? {
        val cleanId = normalizeId(id)
        return trackCache[cleanId] ?: trackCache[id]
    }

    fun registerQueryHint(id: String, hint: String) {
        val cleanHint = hint.trim()
        if (cleanHint.isNotBlank()) {
            queryHints[id] = cleanHint
            val cleanId = normalizeId(id)
            if (cleanId.isNotBlank()) {
                queryHints[cleanId] = cleanHint
            }
        }
    }

    fun getQueryHint(id: String): String? {
        val cleanId = normalizeId(id)
        return queryHints[cleanId] ?: queryHints[id]
    }

    /**
     * Decrypts the obfuscated `encrypted_media_url` returned by JioSaavn
     * using DES algorithm in ECB mode with PKCS5 padding and key `38346591`.
     */
    fun decryptMediaUrl(encrypted: String?): String? {
        if (encrypted.isNullOrBlank()) return null
        return try {
            val keySpec = SecretKeySpec(desKey, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decodedBytes = Base64.decode(encrypted.trim(), Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val decrypted = String(decryptedBytes, Charsets.UTF_8).trim()
            // Upgrade bitrate to 320 kbps high fidelity
            decrypted.replace("_96.mp4", "_320.mp4").replace("_160.mp4", "_320.mp4")
        } catch (e: Exception) {
            Log.w(TAG, "DES decryption failed: ${e.message}")
            null
        }
    }

    private fun cleanArtworkUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return url.replace("http://", "https://")
            .replace("50x50", "500x500")
            .replace("150x150", "500x500")
    }

    private fun extractSumitKolheImage(json: JSONObject): String {
        val imageArr = json.optJSONArray("image")
        if (imageArr != null && imageArr.length() > 0) {
            var best = ""
            for (i in 0 until imageArr.length()) {
                val imgObj = imageArr.optJSONObject(i)
                if (imgObj != null) {
                    val link = imgObj.optString("url").ifBlank { imgObj.optString("link") }
                    if (link.isNotBlank()) {
                        best = cleanArtworkUrl(link)
                        if (imgObj.optString("quality") == "500x500") break
                    }
                } else {
                    val str = imageArr.optString(i)
                    if (str.isNotBlank()) best = cleanArtworkUrl(str)
                }
            }
            if (best.isNotBlank()) return best
        }
        return cleanArtworkUrl(json.optString("image"))
    }

    private fun decodeHtml(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private suspend fun fetchJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Referer", "https://www.jiosaavn.com/")
                .header("Origin", "https://www.jiosaavn.com")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body.string()
                val cleanBody = body.trim().removePrefix(")]}'").trim()
                if (cleanBody.startsWith("{")) {
                    JSONObject(cleanBody)
                } else if (cleanBody.startsWith("[")) {
                    val arr = JSONArray(cleanBody)
                    JSONObject().put("results", arr)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed for $url: ${e.message}")
            null
        }
    }

    /**
     * Parses official JioSaavn API JSON song representation.
     */
    private fun parseOfficialTrackJson(json: JSONObject): JioTrack? {
        val moreInfo = json.optJSONObject("more_info")
        val id = json.optString("id").ifBlank { json.optString("songid") }
        val title = decodeHtml(json.optString("song").ifBlank { json.optString("title") })
        if (id.isBlank() || title.isBlank()) return null

        val subtitle = decodeHtml(json.optString("subtitle"))
        val artistPairs = ArrayList<Pair<String, String>>()
        if (moreInfo != null) {
            val primaryArtists = moreInfo.optJSONObject("artistMap")?.optJSONArray("primary_artists")
            if (primaryArtists != null && primaryArtists.length() > 0) {
                for (i in 0 until primaryArtists.length()) {
                    val aObj = primaryArtists.optJSONObject(i) ?: continue
                    val aId = aObj.optString("id").trim()
                    val aName = decodeHtml(aObj.optString("name"))
                    if (aName.isNotBlank()) {
                        artistPairs.add(aId to aName)
                        if (aId.isNotBlank()) registerQueryHint(aId, aName)
                    }
                }
            }
        }
        var artist = decodeHtml(
            json.optString("primary_artists")
                .ifBlank { json.optString("singers") }
                .ifBlank { artistPairs.joinToString(", ") { it.second } }
                .ifBlank { subtitle },
        )
        if (artist.isBlank() && moreInfo != null) {
            artist = decodeHtml(moreInfo.optString("music"))
        }
        if (artistPairs.isEmpty() && artist.isNotBlank()) {
            val rawIds = json.optString("primary_artists_id").ifBlank { moreInfo?.optString("primary_artists_id").orEmpty() }
                .split(",").map { it.trim() }
            val rawNames = artist.split(",").map { it.trim() }.filter { it.isNotBlank() }
            rawNames.forEachIndexed { idx, name ->
                val aid = rawIds.getOrNull(idx).orEmpty()
                artistPairs.add(aid to name)
                if (aid.isNotBlank()) registerQueryHint(aid, name)
            }
        }

        val album = decodeHtml(json.optString("album").ifBlank { moreInfo?.optString("album").orEmpty() })
        val albumId = json.optString("albumid").ifBlank { moreInfo?.optString("album_id").orEmpty() }
        val image = cleanArtworkUrl(json.optString("image"))
        val durationSec = json.optInt("duration", moreInfo?.optInt("duration", 180) ?: 180)
        val year = json.optString("year")
        val explicit = json.optBoolean("explicit", false) || json.optString("explicit") == "1" || moreInfo?.optString("explicit_content") == "1"
        val hasLyrics = json.optBoolean("has_lyrics", false) || json.optString("has_lyrics") == "true" || moreInfo?.optString("has_lyrics") == "true"
        val language = json.optString("language")
        val playCount = json.optLong("play_count", moreInfo?.optLong("play_count", 0L) ?: 0L)

        var streamUrl = json.optString("media_url").ifBlank { moreInfo?.optString("media_url").orEmpty() }
        if (streamUrl.isBlank()) {
            val enc = json.optString("encrypted_media_url").ifBlank { moreInfo?.optString("encrypted_media_url").orEmpty() }
            streamUrl = decryptMediaUrl(enc).orEmpty()
        }

        return cacheTrack(
            JioTrack(
                id = id,
                title = title,
                subtitle = subtitle,
                artist = artist.ifBlank { "WaveBits Artist" },
                album = album.ifBlank { title },
                albumId = albumId,
                imageUrl = image,
                durationSec = durationSec,
                streamUrl = streamUrl,
                year = year,
                explicit = explicit,
                hasLyrics = hasLyrics,
                language = language,
                playCount = playCount,
                primaryArtists = artistPairs,
            ),
        )
    }

    /**
     * Parses SumitKolhe JioSaavn API JSON song representation.
     */
    private fun parseSumitKolheTrackJson(json: JSONObject): JioTrack? {
        val id = json.optString("id")
        val title = decodeHtml(json.optString("name").ifBlank { json.optString("title") })
        if (id.isBlank() || title.isBlank()) return null

        val albumObj = json.optJSONObject("album")
        val albumName = decodeHtml(albumObj?.optString("name") ?: json.optString("album"))
        val albumId = albumObj?.optString("id") ?: json.optString("albumId")

        val artistsObj = json.optJSONObject("artists")
        val artistPairs = ArrayList<Pair<String, String>>()
        var artistName = ""
        if (artistsObj != null) {
            val primaryArr = artistsObj.optJSONArray("primary")
            if (primaryArr != null && primaryArr.length() > 0) {
                val names = ArrayList<String>()
                for (i in 0 until primaryArr.length()) {
                    val aObj = primaryArr.optJSONObject(i) ?: continue
                    val aId = aObj.optString("id").trim()
                    val aName = decodeHtml(aObj.optString("name"))
                    if (aName.isNotBlank()) {
                        names.add(aName)
                        artistPairs.add(aId to aName)
                        if (aId.isNotBlank()) registerQueryHint(aId, aName)
                    }
                }
                artistName = names.joinToString(", ")
            }
        }
        if (artistName.isBlank()) {
            artistName = decodeHtml(
                json.optString("primaryArtists")
                    .ifBlank { json.optString("singers") }
                    .ifBlank { json.optString("artist") }
            )
            if (artistPairs.isEmpty() && artistName.isNotBlank()) {
                val rawIds = json.optString("primaryArtistsId").split(",").map { it.trim() }
                val rawNames = artistName.split(",").map { it.trim() }.filter { it.isNotBlank() }
                rawNames.forEachIndexed { idx, name ->
                    val aid = rawIds.getOrNull(idx).orEmpty()
                    artistPairs.add(aid to name)
                    if (aid.isNotBlank()) registerQueryHint(aid, name)
                }
            }
        }

        val imageUrl = extractSumitKolheImage(json)
        val durationSec = json.optInt("duration", 180)
        val year = json.optString("year").ifBlank { json.optString("releaseDate") }
        val explicit = json.optBoolean("explicitContent", false) || json.optBoolean("explicit", false)
        val hasLyrics = json.optBoolean("hasLyrics", false) || json.optString("hasLyrics") == "true"
        val language = json.optString("language")
        val playCount = json.optLong("playCount", 0L)

        var streamUrl = ""
        val downloadArr = json.optJSONArray("downloadUrl")
        if (downloadArr != null && downloadArr.length() > 0) {
            for (i in downloadArr.length() - 1 downTo 0) {
                val dObj = downloadArr.optJSONObject(i) ?: continue
                val url = dObj.optString("url").ifBlank { dObj.optString("link") }
                val quality = dObj.optString("quality")
                if (url.isNotBlank()) {
                    streamUrl = url.replace("http://", "https://")
                    if (quality == "320kbps") break
                }
            }
        }
        if (streamUrl.isBlank()) {
            streamUrl = json.optString("media_url").ifBlank { json.optString("streamUrl") }
        }
        if (streamUrl.isBlank()) {
            val enc = json.optString("encrypted_media_url")
            streamUrl = decryptMediaUrl(enc).orEmpty()
        }

        return cacheTrack(
            JioTrack(
                id = id,
                title = title,
                subtitle = json.optString("subtitle"),
                artist = artistName.ifBlank { "WaveBits Artist" },
                album = albumName.ifBlank { title },
                albumId = albumId,
                imageUrl = imageUrl,
                durationSec = durationSec,
                streamUrl = streamUrl,
                year = year,
                explicit = explicit,
                hasLyrics = hasLyrics,
                language = language,
                playCount = playCount,
                primaryArtists = artistPairs,
            ),
        )
    }

    private fun parseTrackJsonUniversal(json: JSONObject): JioTrack? {
        return parseSumitKolheTrackJson(json) ?: parseOfficialTrackJson(json)
    }

    /**
     * Resolves a single song's full details by its ID using SumitKolhe `/api/songs/{id}` & `/api/songs?ids={id}`
     * and official `song.getDetails`.
     */
    suspend fun getSongDetails(songId: String): JioTrack? = withContext(Dispatchers.IO) {
        val cleanId = normalizeId(songId)
        trackCache[cleanId]?.let { return@withContext it }

        if (cleanId.isNotBlank() && cleanId.all { it.isDigit() || it.isLetter() || it == '_' || it == '-' }) {
            // 1. SumitKolhe API `/songs/{id}` and `/songs?ids={id}`
            for (base in SUMITKOLHE_BASES) {
                val urls = listOf("$base/songs/$cleanId", "$base/songs?ids=$cleanId")
                for (skUrl in urls) {
                    val skJson = fetchJson(skUrl) ?: continue
                    if (skJson.has("success") && !skJson.optBoolean("success", true)) continue
                    val dataObj = skJson.optJSONArray("data")?.optJSONObject(0)
                        ?: skJson.optJSONObject("data")
                    if (dataObj != null) {
                        parseTrackJsonUniversal(dataObj)?.let { return@withContext it }
                    }
                }
            }

            // 2. Direct JioSaavn API fallback
            val detailsUrl = "$OFFICIAL_BASE?__call=song.getDetails&cc=in&_marker=0&_format=json&pids=$cleanId"
            val json = fetchJson(detailsUrl)
            if (json != null) {
                val songObj = json.optJSONObject(cleanId)
                    ?: json.optJSONArray("songs")?.optJSONObject(0)
                    ?: json.optJSONArray("results")?.optJSONObject(0)
                if (songObj != null) {
                    parseOfficialTrackJson(songObj)?.let { return@withContext it }
                }
            }
        }

        val hint = getQueryHint(cleanId) ?: getQueryHint(songId)
        if (!hint.isNullOrBlank()) {
            searchTracks(hint).firstOrNull()?.let { return@withContext it }
        }
        null
    }

    /**
     * Batch resolves multiple song IDs using SumitKolhe `GET /api/songs?ids={id1,id2,...}`
     * (https://github.com/sumitkolhe/jiosaavn-api).
     */
    suspend fun getSongsByIds(songIds: List<String>): List<JioTrack> = withContext(Dispatchers.IO) {
        val cleanIds = songIds.map(::normalizeId).filter { it.isNotBlank() }.distinct()
        if (cleanIds.isEmpty()) return@withContext emptyList()

        val resolvedMap = LinkedHashMap<String, JioTrack>()
        val missingIds = ArrayList<String>()

        for (cid in cleanIds) {
            val cached = trackCache[cid]
            if (cached != null && cached.title != "WaveBits Song") {
                resolvedMap[cid] = cached
            } else {
                missingIds.add(cid)
            }
        }

        if (missingIds.isNotEmpty()) {
            val joinedIds = missingIds.joinToString(",")
            // 1. Try SumitKolhe batch `/api/songs?ids=...`
            for (base in SUMITKOLHE_BASES) {
                val skUrl = "$base/songs?ids=${URLEncoder.encode(joinedIds, "UTF-8")}"
                val skJson = fetchJson(skUrl) ?: continue
                if (skJson.has("success") && !skJson.optBoolean("success", true)) continue
                val dataArr = skJson.optJSONArray("data")
                if (dataArr != null && dataArr.length() > 0) {
                    for (i in 0 until dataArr.length()) {
                        val obj = dataArr.optJSONObject(i) ?: continue
                        parseTrackJsonUniversal(obj)?.let { track ->
                            resolvedMap[normalizeId(track.id)] = track
                        }
                    }
                    if (resolvedMap.size >= cleanIds.size) break
                }
            }

            // 2. Fallback to official batch `song.getDetails&pids=...` for any still-missing IDs
            val stillMissing = missingIds.filter { it !in resolvedMap }
            if (stillMissing.isNotEmpty()) {
                val pids = stillMissing.joinToString(",")
                val detailsUrl = "$OFFICIAL_BASE?__call=song.getDetails&cc=in&_marker=0&_format=json&pids=${URLEncoder.encode(pids, "UTF-8")}"
                val json = fetchJson(detailsUrl)
                if (json != null) {
                    for (cid in stillMissing) {
                        val songObj = json.optJSONObject(cid)
                        if (songObj != null) {
                            parseOfficialTrackJson(songObj)?.let { track ->
                                resolvedMap[cid] = track
                            }
                        }
                    }
                }
            }

            // 3. Individual fallback for any IDs needing query hint search
            for (cid in stillMissing) {
                if (cid !in resolvedMap) {
                    getSongDetails(cid)?.let { resolvedMap[cid] = it }
                }
            }
        }

        cleanIds.mapNotNull { resolvedMap[it] }
    }

    /**
     * Resolves playable audio stream URL for a song.
     * Uses official song details with DES decryption, with fallback to SumitKolhe API downloadUrl.
     */
    suspend fun resolveStreamUrl(
        songId: String,
        title: String = "",
        artist: String = "",
    ): String? = withContext(Dispatchers.IO) {
        val cleanId = normalizeId(songId)

        // 0. Check in-memory track cache first if streamUrl was already decrypted
        trackCache[cleanId]?.streamUrl?.takeIf { it.isNotBlank() }?.let { return@withContext it }

        // 1. Try getSongDetails (official song.getDetails + SumitKolhe /songs)
        getSongDetails(cleanId)?.streamUrl?.takeIf { it.isNotBlank() }?.let { return@withContext it }

        // 2. Query search fallback
        val q = title.takeIf { it.isNotBlank() && !it.startsWith("WaveBits") }
            ?: getQueryHint(cleanId)
            ?: cleanId
        val searchResults = searchTracks(q)
        val matched = searchResults.firstOrNull { t ->
            if (artist.isNotBlank() && !artist.startsWith("WaveBits") && title.isNotBlank()) {
                t.title.contains(title, ignoreCase = true) || title.contains(t.title, ignoreCase = true)
            } else true
        } ?: searchResults.firstOrNull()

        if (matched != null && matched.streamUrl.isNotBlank()) {
            return@withContext matched.streamUrl
        }

        null
    }

    /**
     * Searches JioSaavn for tracks matching [query].
     * Combines direct search with SumitKolhe endpoint failover.
     */
    suspend fun searchTracks(query: String, limit: Int = 30): List<JioTrack> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")

        // 1. Try Official Search
        val url = "$OFFICIAL_BASE?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=$limit&p=1&q=$encoded"
        val json = fetchJson(url)
        val tracks = ArrayList<JioTrack>()

        json?.optJSONArray("results")?.let { results ->
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val track = parseOfficialTrackJson(item)
                if (track != null) tracks.add(track)
            }
        }

        if (tracks.isNotEmpty()) return@withContext tracks.take(limit)

        // 2. Fallback to SumitKolhe Search API
        for (base in SUMITKOLHE_BASES) {
            val skUrl = "$base/search/songs?query=$encoded&limit=$limit"
            val skJson = fetchJson(skUrl) ?: continue
            val dataArr = skJson.optJSONArray("data")
                ?: skJson.optJSONObject("data")?.optJSONArray("results")
                ?: skJson.optJSONArray("results")
            if (dataArr != null && dataArr.length() > 0) {
                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i) ?: continue
                    val skTrack = parseTrackJsonUniversal(item)
                    if (skTrack != null) tracks.add(skTrack)
                }
                if (tracks.isNotEmpty()) return@withContext tracks.take(limit)
            }
        }

        tracks.take(limit)
    }

    /**
     * Searches JioSaavn for playlists matching [query].
     */
    suspend fun searchPlaylists(query: String, limit: Int = 15): List<JioPlaylist> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val playlists = ArrayList<JioPlaylist>()

        // 1. Official API
        val url = "$OFFICIAL_BASE?__call=search.getPlaylistResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=$limit&p=1&q=$encoded"
        val json = fetchJson(url)
        json?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { obj.optString("listid") }
                val title = decodeHtml(obj.optString("title").ifBlank { obj.optString("listname") })
                val subtitle = decodeHtml(obj.optString("subtitle").ifBlank { obj.optString("description") })
                val image = extractSumitKolheImage(obj)
                val count = obj.optInt("count", obj.optInt("song_count", 0))
                if (id.isNotBlank() && title.isNotBlank()) {
                    playlists.add(JioPlaylist(id, title, subtitle, image, count, emptyList()))
                }
            }
        }
        if (playlists.isNotEmpty()) return@withContext playlists

        // 2. SumitKolhe fallback
        for (base in SUMITKOLHE_BASES) {
            val skUrl = "$base/search/playlists?query=$encoded&limit=$limit"
            val skJson = fetchJson(skUrl) ?: continue
            val dataArr = skJson.optJSONArray("data")
                ?: skJson.optJSONObject("data")?.optJSONArray("results")
                ?: skJson.optJSONArray("results")
            if (dataArr != null && dataArr.length() > 0) {
                for (i in 0 until dataArr.length()) {
                    val obj = dataArr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val title = decodeHtml(obj.optString("title").ifBlank { obj.optString("name") })
                    val subtitle = decodeHtml(obj.optString("subtitle").ifBlank { obj.optString("description") })
                    val image = extractSumitKolheImage(obj)
                    val count = obj.optInt("songCount", obj.optInt("count", 0))
                    if (id.isNotBlank() && title.isNotBlank()) {
                        playlists.add(JioPlaylist(id, title, subtitle, image, count, emptyList()))
                    }
                }
                if (playlists.isNotEmpty()) return@withContext playlists
            }
        }
        playlists
    }

    /**
     * Searches JioSaavn for albums matching [query].
     */
    suspend fun searchAlbums(query: String, limit: Int = 15): List<JioAlbum> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val albums = ArrayList<JioAlbum>()

        // 1. Official API
        val url = "$OFFICIAL_BASE?__call=search.getAlbumResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=$limit&p=1&q=$encoded"
        val json = fetchJson(url)
        json?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { obj.optString("albumid") }
                val title = decodeHtml(obj.optString("title").ifBlank { obj.optString("album") })
                val artist = decodeHtml(obj.optString("music").ifBlank { obj.optString("primary_artists") })
                val image = extractSumitKolheImage(obj)
                val year = obj.optString("year")
                if (id.isNotBlank() && title.isNotBlank()) {
                    albums.add(JioAlbum(id, title, artist, year, image, emptyList()))
                }
            }
        }
        if (albums.isNotEmpty()) return@withContext albums

        // 2. SumitKolhe fallback
        for (base in SUMITKOLHE_BASES) {
            val skUrl = "$base/search/albums?query=$encoded&limit=$limit"
            val skJson = fetchJson(skUrl) ?: continue
            val dataArr = skJson.optJSONArray("data")
                ?: skJson.optJSONObject("data")?.optJSONArray("results")
                ?: skJson.optJSONArray("results")
            if (dataArr != null && dataArr.length() > 0) {
                for (i in 0 until dataArr.length()) {
                    val obj = dataArr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val title = decodeHtml(obj.optString("title").ifBlank { obj.optString("name") })
                    val artist = decodeHtml(obj.optString("artist").ifBlank { obj.optString("primaryArtists") })
                    val image = extractSumitKolheImage(obj)
                    val year = obj.optString("year")
                    if (id.isNotBlank() && title.isNotBlank()) {
                        albums.add(JioAlbum(id, title, artist, year, image, emptyList()))
                    }
                }
                if (albums.isNotEmpty()) return@withContext albums
            }
        }
        albums
    }

    /**
     * Searches JioSaavn for artists matching [query] using SumitKolhe `GET /api/search/artists` and official fallback.
     */
    suspend fun searchArtists(query: String, limit: Int = 15): List<JioArtist> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val artists = ArrayList<JioArtist>()

        // 1. Official API `search.getArtistResults`
        val url = "$OFFICIAL_BASE?__call=search.getArtistResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=$limit&p=1&q=$encoded"
        val json = fetchJson(url)
        json?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { obj.optString("artistid") }
                val name = decodeHtml(obj.optString("name").ifBlank { obj.optString("title") })
                val image = extractSumitKolheImage(obj)
                if (id.isNotBlank() && name.isNotBlank()) {
                    registerQueryHint(id, name)
                    artists.add(JioArtist(id = id, name = name, imageUrl = image))
                }
            }
        }
        if (artists.isNotEmpty()) return@withContext artists

        // 2. SumitKolhe `/api/search/artists?query={query}&limit={limit}`
        for (base in SUMITKOLHE_BASES) {
            val skUrl = "$base/search/artists?query=$encoded&limit=$limit"
            val skJson = fetchJson(skUrl) ?: continue
            val dataArr = skJson.optJSONArray("data")
                ?: skJson.optJSONObject("data")?.optJSONArray("results")
                ?: skJson.optJSONArray("results")
            if (dataArr != null && dataArr.length() > 0) {
                for (i in 0 until dataArr.length()) {
                    val obj = dataArr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val name = decodeHtml(obj.optString("name").ifBlank { obj.optString("title") })
                    val image = extractSumitKolheImage(obj)
                    if (id.isNotBlank() && name.isNotBlank()) {
                        registerQueryHint(id, name)
                        artists.add(JioArtist(id = id, name = name, imageUrl = image))
                    }
                }
                if (artists.isNotEmpty()) return@withContext artists
            }
        }
        artists
    }

    /**
     * Universal search across songs, albums, playlists, and artists using both API specifications.
     */
    suspend fun searchEverything(query: String): JioSearchResult = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext JioSearchResult(emptyList(), emptyList(), emptyList(), emptyList())
        val encoded = URLEncoder.encode(clean, "UTF-8")

        val songsList = ArrayList<JioTrack>()
        val albumsList = ArrayList<JioAlbum>()
        val playlistsList = ArrayList<JioPlaylist>()
        val artistsList = ArrayList<JioArtist>()

        // 1. Official Search Calls
        val songsUrl = "$OFFICIAL_BASE?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=25&p=1&q=$encoded"
        val songsJson = fetchJson(songsUrl)
        songsJson?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parseOfficialTrackJson(it)?.let(songsList::add) }
            }
        }

        val albumsUrl = "$OFFICIAL_BASE?__call=search.getAlbumResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=15&p=1&q=$encoded"
        val albumsJson = fetchJson(albumsUrl)
        albumsJson?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { obj.optString("albumid") }
                val title = decodeHtml(obj.optString("title").ifBlank { obj.optString("album") })
                val artist = decodeHtml(obj.optString("music").ifBlank { obj.optString("primary_artists") })
                val image = extractSumitKolheImage(obj)
                val year = obj.optString("year")
                if (id.isNotBlank() && title.isNotBlank()) {
                    albumsList.add(JioAlbum(id, title, artist, year, image, emptyList()))
                }
            }
        }

        val playlistsUrl = "$OFFICIAL_BASE?__call=search.getPlaylistResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=15&p=1&q=$encoded"
        val playlistsJson = fetchJson(playlistsUrl)
        playlistsJson?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { obj.optString("listid") }
                val title = decodeHtml(obj.optString("title").ifBlank { obj.optString("listname") })
                val subtitle = decodeHtml(obj.optString("subtitle").ifBlank { obj.optString("description") })
                val image = extractSumitKolheImage(obj)
                val count = obj.optInt("count", obj.optInt("song_count", 0))
                if (id.isNotBlank() && title.isNotBlank()) {
                    playlistsList.add(JioPlaylist(id, title, subtitle, image, count, emptyList()))
                }
            }
        }

        val artistsUrl = "$OFFICIAL_BASE?__call=search.getArtistResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=10&p=1&q=$encoded"
        val artistsJson = fetchJson(artistsUrl)
        artistsJson?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { obj.optString("artistid") }
                val name = decodeHtml(obj.optString("name").ifBlank { obj.optString("title") })
                val image = extractSumitKolheImage(obj)
                if (id.isNotBlank() && name.isNotBlank()) {
                    registerQueryHint(id, name)
                    artistsList.add(JioArtist(id, name, image))
                }
            }
        }

        // 2. SumitKolhe API Fallback if any section was empty
        if (songsList.isEmpty() || albumsList.isEmpty() || playlistsList.isEmpty() || artistsList.isEmpty()) {
            for (base in SUMITKOLHE_BASES) {
                val skUrl = "$base/search?query=$encoded"
                val skJson = fetchJson(skUrl) ?: continue
                val dataObj = skJson.optJSONObject("data") ?: skJson

                if (songsList.isEmpty()) {
                    dataObj.optJSONObject("songs")?.optJSONArray("results")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { parseTrackJsonUniversal(it)?.let(songsList::add) }
                        }
                    }
                }

                if (albumsList.isEmpty()) {
                    dataObj.optJSONObject("albums")?.optJSONArray("results")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val id = obj.optString("id")
                            val title = decodeHtml(obj.optString("title").ifBlank { obj.optString("name") })
                            val artist = decodeHtml(obj.optString("artist").ifBlank { obj.optString("primaryArtists") })
                            val image = extractSumitKolheImage(obj)
                            val year = obj.optString("year")
                            if (id.isNotBlank() && title.isNotBlank()) {
                                albumsList.add(JioAlbum(id, title, artist, year, image, emptyList()))
                            }
                        }
                    }
                }

                if (playlistsList.isEmpty()) {
                    dataObj.optJSONObject("playlists")?.optJSONArray("results")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val id = obj.optString("id")
                            val title = decodeHtml(obj.optString("title").ifBlank { obj.optString("name") })
                            val subtitle = decodeHtml(obj.optString("subtitle").ifBlank { obj.optString("description") })
                            val image = extractSumitKolheImage(obj)
                            val count = obj.optInt("songCount", obj.optInt("count", 0))
                            if (id.isNotBlank() && title.isNotBlank()) {
                                playlistsList.add(JioPlaylist(id, title, subtitle, image, count, emptyList()))
                            }
                        }
                    }
                }

                if (artistsList.isEmpty()) {
                    dataObj.optJSONObject("artists")?.optJSONArray("results")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val id = obj.optString("id")
                            val name = decodeHtml(obj.optString("title").ifBlank { obj.optString("name") })
                            val image = extractSumitKolheImage(obj)
                            if (id.isNotBlank() && name.isNotBlank()) {
                                registerQueryHint(id, name)
                                artistsList.add(JioArtist(id, name, image))
                            }
                        }
                    }
                }
            }
        }

        // Synthesize artist entries from song primaryArtists if artist search returned empty
        if (artistsList.isEmpty() && songsList.isNotEmpty()) {
            val seenArtists = HashSet<String>()
            for (t in songsList) {
                for ((aid, aname) in t.primaryArtists) {
                    val key = aid.ifBlank { aname.lowercase() }
                    if (aname.isNotBlank() && seenArtists.add(key)) {
                        artistsList.add(JioArtist(id = aid.ifBlank { t.id }, name = aname, imageUrl = t.imageUrl))
                    }
                }
            }
        }

        JioSearchResult(songsList, albumsList, playlistsList, artistsList)
    }

    /**
     * Resolves recommended / similar tracks for a seed track using:
     * 1. In-memory recommendations cache for consistent queue + playlist radio views
     * 2. SumitKolhe API `/songs/{id}/suggestions?limit={limit}` (https://github.com/sumitkolhe/jiosaavn-api)
     * 3. JioSaavn WebRadio station API (`webradio.createEntityStation` + `webradio.getSong`, used by sumitkolhe/jiosaavn-api)
     * 4. Official JioSaavn `reco.getrecos` endpoint
     * 5. Artist / title search fallback
     * 6. Trending songs fallback
     */
    suspend fun getSongRecommendations(
        songId: String,
        songTitle: String = "",
        artist: String = "",
        limit: Int = 25,
    ): List<JioTrack> = withContext(Dispatchers.IO) {
        val cleanId = normalizeId(songId)
        if (cleanId.isNotBlank()) {
            recommendationsCache[cleanId]?.takeIf { it.size >= 5 }?.let { cached ->
                return@withContext cached.take(limit)
            }
        }

        val results = ArrayList<JioTrack>()
        val seen = HashSet<String>()
        if (cleanId.isNotBlank()) seen.add(cleanId)

        // 1. SumitKolhe API `/songs/{id}/suggestions?limit={limit}`
        if (cleanId.isNotBlank()) {
            for (base in SUMITKOLHE_BASES) {
                val skUrl = "$base/songs/$cleanId/suggestions?limit=${limit.coerceAtLeast(25)}"
                val skJson = fetchJson(skUrl) ?: continue
                if (skJson.has("success") && !skJson.optBoolean("success", true)) continue
                val dataArr = skJson.optJSONArray("data")
                    ?: skJson.optJSONObject("data")?.optJSONArray("results")
                    ?: skJson.optJSONArray("results")
                if (dataArr != null && dataArr.length() > 0) {
                    for (i in 0 until dataArr.length()) {
                        val obj = dataArr.optJSONObject(i) ?: continue
                        parseTrackJsonUniversal(obj)?.let { t ->
                            if (seen.add(t.id)) results.add(t)
                        }
                    }
                    if (results.size >= 5) {
                        recommendationsCache[cleanId] = results
                        return@withContext results.take(limit)
                    }
                }
            }
        }

        // 2. JioSaavn WebRadio Station API (exact mechanism used by sumitkolhe/jiosaavn-api suggestions service)
        if (cleanId.isNotBlank()) {
            val encodedEntity = URLEncoder.encode("[\"$cleanId\"]", "UTF-8")
            val stationUrl = "$OFFICIAL_BASE?__call=webradio.createEntityStation&api_version=4&_format=json&_marker=0&ctx=android&entity_id=$encodedEntity&entity_type=queue"
            val stationJson = fetchJson(stationUrl)
            val stationId = stationJson?.optString("stationid").orEmpty()
            if (stationId.isNotBlank()) {
                val encodedStation = URLEncoder.encode(stationId, "UTF-8")
                val songsUrl = "$OFFICIAL_BASE?__call=webradio.getSong&api_version=4&_format=json&_marker=0&ctx=android&stationid=$encodedStation&k=${limit.coerceAtLeast(25)}&next=1"
                val songsJson = fetchJson(songsUrl)
                if (songsJson != null) {
                    val keys = songsJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key == "stationid" || key == "error") continue
                        val entry = songsJson.optJSONObject(key) ?: continue
                        val songObj = entry.optJSONObject("song") ?: entry
                        parseTrackJsonUniversal(songObj)?.let { t ->
                            if (seen.add(t.id)) results.add(t)
                        }
                    }
                    songsJson.optJSONObject("song")?.let { singleSong ->
                        parseTrackJsonUniversal(singleSong)?.let { t ->
                            if (seen.add(t.id)) results.add(t)
                        }
                    }
                }
            }
        }
        if (results.size >= 5) {
            if (cleanId.isNotBlank()) recommendationsCache[cleanId] = results
            return@withContext results.take(limit)
        }

        // 3. Official API reco.getrecos
        if (cleanId.isNotBlank()) {
            val url = "$OFFICIAL_BASE?__call=reco.getrecos&api_version=4&_format=json&_marker=0&ctx=web6dot0&pid=$cleanId"
            val json = fetchJson(url)
            val arr = json?.optJSONArray("results")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    parseTrackJsonUniversal(obj)?.let { t ->
                        if (seen.add(t.id)) results.add(t)
                    }
                }
            } else if (json != null) {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    json.optJSONObject(k)?.let { obj ->
                        parseTrackJsonUniversal(obj)?.let { t ->
                            if (seen.add(t.id)) results.add(t)
                        }
                    }
                }
            }
        }
        if (results.size >= 5) {
            if (cleanId.isNotBlank()) recommendationsCache[cleanId] = results
            return@withContext results.take(limit)
        }

        // 4. Fallback: Search by artist or title (ignoring generic WaveBits placeholders)
        val cached = trackCache[cleanId]
        val validArtist = artist.takeIf { it.isNotBlank() && !it.startsWith("WaveBits", ignoreCase = true) }
            ?: cached?.artist?.takeIf { it.isNotBlank() && !it.startsWith("WaveBits", ignoreCase = true) }
            ?: ""
        val validTitle = songTitle.takeIf { it.isNotBlank() && !it.startsWith("WaveBits", ignoreCase = true) && !it.equals("Track", ignoreCase = true) && !it.equals("Song", ignoreCase = true) }
            ?: cached?.title?.takeIf { it.isNotBlank() && !it.startsWith("WaveBits", ignoreCase = true) }
            ?: getQueryHint(cleanId).orEmpty()

        val query = validArtist.ifBlank { validTitle }.trim()
        if (query.isNotBlank()) {
            val fallbackTracks = searchTracks(query, limit = limit.coerceAtLeast(25))
            for (t in fallbackTracks) {
                if (seen.add(t.id)) results.add(t)
            }
        }

        // 5. Ultimate fallback: Trending tracks
        if (results.size < 5) {
            val trending = getTrendingTracks()
            for (t in trending) {
                if (seen.add(t.id)) results.add(t)
            }
        }

        if (cleanId.isNotBlank() && results.isNotEmpty()) {
            recommendationsCache[cleanId] = results
        }
        results.take(limit)
    }

    /**
     * Alias for [getSongRecommendations] matching SumitKolhe's `/api/songs/{id}/suggestions` specification.
     */
    suspend fun getSongSuggestions(
        songId: String,
        songTitle: String = "",
        artist: String = "",
        limit: Int = 25,
    ): List<JioTrack> = getSongRecommendations(songId, songTitle, artist, limit)

    /**
     * Fetches full artist details (top songs, top albums, singles, similar artists, bio, follower count)
     * using SumitKolhe `GET /api/artists/{id}` & `GET /api/artists?id={id}` and official `artist.getArtistPageDetails`.
     */
    suspend fun getArtistDetails(artistId: String, nameHint: String = ""): JioArtist? = withContext(Dispatchers.IO) {
        val cleanId = normalizeId(artistId)
        if (cleanId.isNotBlank()) {
            artistCache[cleanId]?.let { return@withContext it }
        }

        // 1. SumitKolhe `/api/artists/{id}` & `/api/artists?id={id}`
        if (cleanId.isNotBlank() && cleanId.all { it.isDigit() || it.isLetter() || it == '_' || it == '-' }) {
            for (base in SUMITKOLHE_BASES) {
                val urls = listOf(
                    "$base/artists/$cleanId",
                    "$base/artists?id=$cleanId",
                )
                for (skUrl in urls) {
                    val skJson = fetchJson(skUrl) ?: continue
                    if (skJson.has("success") && !skJson.optBoolean("success", true)) continue
                    val dataObj = skJson.optJSONObject("data") ?: skJson
                    val name = decodeHtml(dataObj.optString("name").ifBlank { dataObj.optString("title") })
                    val image = extractSumitKolheImage(dataObj)
                    val followerCount = dataObj.optInt("followerCount", dataObj.optInt("fanCount", 0))
                    val isVerified = dataObj.optBoolean("isVerified", false)

                    val topSongs = ArrayList<JioTrack>()
                    val songsArr = dataObj.optJSONArray("topSongs") ?: dataObj.optJSONArray("songs")
                    if (songsArr != null) {
                        for (i in 0 until songsArr.length()) {
                            songsArr.optJSONObject(i)?.let { parseTrackJsonUniversal(it)?.let(topSongs::add) }
                        }
                    }

                    val topAlbums = ArrayList<JioAlbum>()
                    val albumsArr = dataObj.optJSONArray("topAlbums") ?: dataObj.optJSONArray("albums")
                    if (albumsArr != null) {
                        for (i in 0 until albumsArr.length()) {
                            val aObj = albumsArr.optJSONObject(i) ?: continue
                            val aId = aObj.optString("id").ifBlank { aObj.optString("albumid") }
                            val aTitle = decodeHtml(aObj.optString("name").ifBlank { aObj.optString("title") })
                            val aYear = aObj.optString("year")
                            val aImage = extractSumitKolheImage(aObj)
                            if (aId.isNotBlank() && aTitle.isNotBlank()) {
                                topAlbums.add(JioAlbum(aId, aTitle, name, aYear, aImage, emptyList()))
                            }
                        }
                    }

                    val singles = ArrayList<JioAlbum>()
                    val singlesArr = dataObj.optJSONArray("singles")
                    if (singlesArr != null) {
                        for (i in 0 until singlesArr.length()) {
                            val sObj = singlesArr.optJSONObject(i) ?: continue
                            val sId = sObj.optString("id").ifBlank { sObj.optString("albumid") }
                            val sTitle = decodeHtml(sObj.optString("name").ifBlank { sObj.optString("title") })
                            val sYear = sObj.optString("year")
                            val sImage = extractSumitKolheImage(sObj)
                            if (sId.isNotBlank() && sTitle.isNotBlank()) {
                                singles.add(JioAlbum(sId, sTitle, name, sYear, sImage, emptyList()))
                            }
                        }
                    }

                    val similar = ArrayList<JioArtist>()
                    val simArr = dataObj.optJSONArray("similarArtists")
                    if (simArr != null) {
                        for (i in 0 until simArr.length()) {
                            val simObj = simArr.optJSONObject(i) ?: continue
                            val simId = simObj.optString("id")
                            val simName = decodeHtml(simObj.optString("name"))
                            val simImg = extractSumitKolheImage(simObj)
                            if (simId.isNotBlank() && simName.isNotBlank()) {
                                similar.add(JioArtist(simId, simName, simImg))
                            }
                        }
                    }

                    if (name.isNotBlank() && (topSongs.isNotEmpty() || topAlbums.isNotEmpty())) {
                        val artist = JioArtist(
                            id = cleanId,
                            name = name,
                            imageUrl = image,
                            followerCount = followerCount,
                            isVerified = isVerified,
                            topSongs = topSongs,
                            topAlbums = topAlbums,
                            singles = singles,
                            similarArtists = similar,
                        )
                        artistCache[cleanId] = artist
                        return@withContext artist
                    }
                }
            }

            // 2. Direct JioSaavn `artist.getArtistPageDetails` fallback
            val url = "$OFFICIAL_BASE?__call=artist.getArtistPageDetails&_format=json&cc=in&_marker=0&artistId=$cleanId&n_song=25&n_album=20"
            val json = fetchJson(url)
            if (json != null) {
                val name = decodeHtml(json.optString("name").ifBlank { nameHint })
                val image = extractSumitKolheImage(json)
                val followerCount = json.optInt("follower_count", 0)
                val isVerified = json.optBoolean("isVerified", false)

                val topSongs = ArrayList<JioTrack>()
                val songsArr = json.optJSONObject("topSongs")?.optJSONArray("songs") ?: json.optJSONArray("topSongs")
                if (songsArr != null) {
                    for (i in 0 until songsArr.length()) {
                        songsArr.optJSONObject(i)?.let { parseTrackJsonUniversal(it)?.let(topSongs::add) }
                    }
                }

                val topAlbums = ArrayList<JioAlbum>()
                val albumsArr = json.optJSONObject("topAlbums")?.optJSONArray("albums") ?: json.optJSONArray("topAlbums")
                if (albumsArr != null) {
                    for (i in 0 until albumsArr.length()) {
                        val aObj = albumsArr.optJSONObject(i) ?: continue
                        val aId = aObj.optString("id").ifBlank { aObj.optString("albumid") }
                        val aTitle = decodeHtml(aObj.optString("title").ifBlank { aObj.optString("name") })
                        val aYear = aObj.optString("year")
                        val aImg = extractSumitKolheImage(aObj)
                        if (aId.isNotBlank() && aTitle.isNotBlank()) {
                            topAlbums.add(JioAlbum(aId, aTitle, name, aYear, aImg, emptyList()))
                        }
                    }
                }

                if (name.isNotBlank() && (topSongs.isNotEmpty() || topAlbums.isNotEmpty())) {
                    val artist = JioArtist(
                        id = cleanId,
                        name = name,
                        imageUrl = image,
                        followerCount = followerCount,
                        isVerified = isVerified,
                        topSongs = topSongs,
                        topAlbums = topAlbums,
                    )
                    artistCache[cleanId] = artist
                    return@withContext artist
                }
            }
        }

        // 3. Fallback via search if ID was synthetic or nameHint provided
        val query = nameHint.takeIf { it.isNotBlank() && !it.startsWith("WaveBits") }
            ?: getQueryHint(cleanId)
            ?: trackCache[cleanId]?.artist?.takeIf { it.isNotBlank() && !it.startsWith("WaveBits") }
            ?: cleanId
        if (query.isNotBlank()) {
            val songs = searchTracks(query, limit = 25)
            val albums = searchAlbums(query, limit = 15)
            val displayName = nameHint.takeIf { it.isNotBlank() }
                ?: songs.firstOrNull()?.artist?.substringBefore(',')?.trim()
                ?: query
            val img = songs.firstOrNull()?.imageUrl ?: albums.firstOrNull()?.imageUrl.orEmpty()
            val artist = JioArtist(
                id = cleanId.ifBlank { query },
                name = displayName,
                imageUrl = img,
                topSongs = songs,
                topAlbums = albums,
            )
            if (cleanId.isNotBlank()) artistCache[cleanId] = artist
            return@withContext artist
        }

        null
    }

    /**
     * Fetches albums for an artist using SumitKolhe `GET /api/artists/{id}/albums?page={page}` with search fallback.
     */
    suspend fun getArtistAlbums(artistId: String, page: Int = 0): List<JioAlbum> = withContext(Dispatchers.IO) {
        val cleanId = normalizeId(artistId)
        val albums = ArrayList<JioAlbum>()

        if (cleanId.isNotBlank() && cleanId.all { it.isDigit() || it.isLetter() || it == '_' || it == '-' }) {
            for (base in SUMITKOLHE_BASES) {
                val skUrl = "$base/artists/$cleanId/albums?page=$page"
                val skJson = fetchJson(skUrl) ?: continue
                if (skJson.has("success") && !skJson.optBoolean("success", true)) continue
                val dataObj = skJson.optJSONObject("data") ?: skJson
                val albumsArr = dataObj.optJSONArray("albums")
                    ?: dataObj.optJSONArray("topAlbums")
                    ?: dataObj.optJSONArray("results")
                if (albumsArr != null && albumsArr.length() > 0) {
                    for (i in 0 until albumsArr.length()) {
                        val obj = albumsArr.optJSONObject(i) ?: continue
                        val id = obj.optString("id").ifBlank { obj.optString("albumid") }
                        val title = decodeHtml(obj.optString("name").ifBlank { obj.optString("title") })
                        val artist = decodeHtml(obj.optString("artist").ifBlank { obj.optString("primaryArtists") })
                        val year = obj.optString("year")
                        val image = extractSumitKolheImage(obj)
                        if (id.isNotBlank() && title.isNotBlank()) {
                            albums.add(JioAlbum(id, title, artist, year, image, emptyList()))
                        }
                    }
                    if (albums.isNotEmpty()) return@withContext albums
                }
            }
        }

        if (page == 0) {
            getArtistDetails(cleanId)?.topAlbums?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }
        emptyList()
    }

    /**
     * Fetches songs for an artist using SumitKolhe `GET /api/artists/{id}/songs` & `GET /api/artists?id={id}`
     * with search fallback.
     */
    suspend fun getArtistSongs(
        artistId: String,
        artistNameHint: String = "",
        limit: Int = 30,
    ): List<JioTrack> = withContext(Dispatchers.IO) {
        val cleanId = normalizeId(artistId)
        artistCache[cleanId]?.topSongs?.takeIf { it.isNotEmpty() }?.let { return@withContext it.take(limit) }

        val tracks = ArrayList<JioTrack>()
        val seen = HashSet<String>()

        if (cleanId.isNotBlank() && cleanId.all { it.isDigit() || it.isLetter() || it == '_' || it == '-' }) {
            // 1. SumitKolhe `/api/artists/{id}/songs?page=0` & `/api/artists?id={id}`
            for (base in SUMITKOLHE_BASES) {
                val urls = listOf(
                    "$base/artists/$cleanId/songs?page=0",
                    "$base/artists?id=$cleanId",
                )
                for (skUrl in urls) {
                    val skJson = fetchJson(skUrl) ?: continue
                    if (skJson.has("success") && !skJson.optBoolean("success", true)) continue
                    val dataObj = skJson.optJSONObject("data") ?: skJson
                    val songsArr = dataObj.optJSONArray("songs")
                        ?: dataObj.optJSONArray("topSongs")
                        ?: dataObj.optJSONArray("results")
                    if (songsArr != null && songsArr.length() > 0) {
                        for (i in 0 until songsArr.length()) {
                            val obj = songsArr.optJSONObject(i) ?: continue
                            parseTrackJsonUniversal(obj)?.let { t ->
                                if (seen.add(t.id)) tracks.add(t)
                            }
                        }
                        if (tracks.isNotEmpty()) return@withContext tracks.take(limit)
                    }
                }
            }

            // 2. Direct JioSaavn `artist.getArtistPageDetails` fallback
            val url = "$OFFICIAL_BASE?__call=artist.getArtistPageDetails&_format=json&cc=in&_marker=0&artistId=$cleanId&n_song=$limit"
            val json = fetchJson(url)
            val topSongs = json?.optJSONObject("topSongs")?.optJSONArray("songs")
                ?: json?.optJSONArray("topSongs")
            if (topSongs != null && topSongs.length() > 0) {
                for (i in 0 until topSongs.length()) {
                    val obj = topSongs.optJSONObject(i) ?: continue
                    parseTrackJsonUniversal(obj)?.let { t ->
                        if (seen.add(t.id)) tracks.add(t)
                    }
                }
                if (tracks.isNotEmpty()) return@withContext tracks.take(limit)
            }
        }

        val q = artistNameHint.takeIf { it.isNotBlank() && !it.startsWith("WaveBits") }
            ?: getQueryHint(cleanId)
            ?: trackCache[cleanId]?.artist?.takeIf { it.isNotBlank() && !it.startsWith("WaveBits") }
            ?: cleanId
        if (q.isNotBlank()) {
            searchTracks(q, limit = limit)
        } else {
            emptyList()
        }
    }

    /**
     * Fetches details and songs for an album using SumitKolhe `GET /api/albums?id={id}` and official fallback.
     */
    suspend fun getAlbumDetails(albumId: String): JioAlbum? = withContext(Dispatchers.IO) {
        val cleanId = normalizeId(albumId)
        if (cleanId.isNotBlank()) {
            albumCache[cleanId]?.let { return@withContext it }
        }

        // 1. SumitKolhe `/api/albums?id={cleanId}`
        for (base in SUMITKOLHE_BASES) {
            val skUrl = "$base/albums?id=$cleanId"
            val skJson = fetchJson(skUrl) ?: continue
            if (skJson.has("success") && !skJson.optBoolean("success", true)) continue
            val dataObj = skJson.optJSONObject("data") ?: skJson
            val title = decodeHtml(dataObj.optString("name").ifBlank { dataObj.optString("title") })
            val artist = decodeHtml(dataObj.optString("primaryArtists").ifBlank { dataObj.optString("artist") })
            val year = dataObj.optString("year")
            val image = extractSumitKolheImage(dataObj)
            val songsArr = dataObj.optJSONArray("songs") ?: JSONArray()
            val tracks = ArrayList<JioTrack>()
            for (i in 0 until songsArr.length()) {
                val obj = songsArr.optJSONObject(i) ?: continue
                val track = parseTrackJsonUniversal(obj)
                if (track != null) tracks.add(track)
            }
            if (title.isNotBlank() && tracks.isNotEmpty()) {
                return@withContext JioAlbum(cleanId, title, artist, year, image, tracks).also { albumCache[cleanId] = it }
            }
        }

        // 2. Direct JioSaavn fallback
        val url = "$OFFICIAL_BASE?__call=content.getAlbumDetails&_format=json&cc=in&_marker=0&albumid=$cleanId"
        val json = fetchJson(url)

        if (json != null) {
            val title = decodeHtml(json.optString("name").ifBlank { json.optString("title") })
            val artist = decodeHtml(json.optString("primary_artists").ifBlank { json.optString("artist") })
            val image = extractSumitKolheImage(json)
            val year = json.optString("year")

            val songsArr = json.optJSONArray("songs") ?: json.optJSONArray("list") ?: JSONArray()
            val tracks = ArrayList<JioTrack>()
            for (i in 0 until songsArr.length()) {
                val obj = songsArr.optJSONObject(i) ?: continue
                val track = parseTrackJsonUniversal(obj)
                if (track != null) tracks.add(track)
            }

            if (title.isNotBlank() && tracks.isNotEmpty()) {
                return@withContext JioAlbum(cleanId, title, artist, year, image, tracks).also { albumCache[cleanId] = it }
            }
        }

        null
    }

    /**
     * Fetches details and songs for a playlist using SumitKolhe `GET /api/playlists?id={id}&limit=100` and official fallback.
     */
    suspend fun getPlaylistDetails(listId: String): JioPlaylist? = withContext(Dispatchers.IO) {
        val cleanId = normalizeId(listId)
        if (cleanId.isNotBlank()) {
            playlistCache[cleanId]?.let { return@withContext it }
        }

        // 1. SumitKolhe `/api/playlists?id={cleanId}&limit=100`
        for (base in SUMITKOLHE_BASES) {
            val skUrl = "$base/playlists?id=$cleanId&limit=100"
            val skJson = fetchJson(skUrl) ?: continue
            if (skJson.has("success") && !skJson.optBoolean("success", true)) continue
            val dataObj = skJson.optJSONObject("data") ?: skJson
            val title = decodeHtml(dataObj.optString("name").ifBlank { dataObj.optString("title") })
            val subtitle = decodeHtml(dataObj.optString("subtitle").ifBlank { dataObj.optString("description") })
            val image = extractSumitKolheImage(dataObj)
            val songsArr = dataObj.optJSONArray("songs") ?: JSONArray()
            val tracks = ArrayList<JioTrack>()
            for (i in 0 until songsArr.length()) {
                val obj = songsArr.optJSONObject(i) ?: continue
                val track = parseTrackJsonUniversal(obj)
                if (track != null) tracks.add(track)
            }
            if (title.isNotBlank() && tracks.isNotEmpty()) {
                return@withContext JioPlaylist(cleanId, title, subtitle, image, tracks.size, tracks).also { playlistCache[cleanId] = it }
            }
        }

        // 2. Direct JioSaavn fallback
        val url = "$OFFICIAL_BASE?__call=playlist.getDetails&_format=json&cc=in&_marker=0&listid=$cleanId"
        val json = fetchJson(url)

        if (json != null) {
            val title = decodeHtml(json.optString("listname").ifBlank { json.optString("title") })
            val subtitle = decodeHtml(json.optString("description").ifBlank { json.optString("subtitle") })
            val image = extractSumitKolheImage(json)

            val songsArr = json.optJSONArray("songs") ?: json.optJSONArray("list") ?: JSONArray()
            val tracks = ArrayList<JioTrack>()
            for (i in 0 until songsArr.length()) {
                val obj = songsArr.optJSONObject(i) ?: continue
                val track = parseTrackJsonUniversal(obj)
                if (track != null) tracks.add(track)
            }

            if (title.isNotBlank() && tracks.isNotEmpty()) {
                return@withContext JioPlaylist(cleanId, title, subtitle, image, tracks.size, tracks).also { playlistCache[cleanId] = it }
            }
        }

        null
    }

    /**
     * Cleans HTML tags, br breaks, and entities from raw JioSaavn lyrics string.
     */
    private fun cleanLyricsHtml(raw: String): String =
        decodeHtml(
            raw.replace("<br\\s*/?>".toRegex(RegexOption.IGNORE_CASE), "\n")
                .replace("(?i)<p[^>]*>".toRegex(), "")
                .replace("(?i)</p>".toRegex(), "\n")
                .replace("<[^>]+>".toRegex(), "")
        ).trim()

    /**
     * Fetches plain lyrics for a JioSaavn song with multiple fallback strategies:
     * 1. Official lyrics.getLyrics by lyrics_id
     * 2. Official lyrics.getLyrics by song id
     * 3. Official song.getDetails lookup to resolve lyrics_id or embedded lyrics
     * 4. Active community Saavn API endpoints (/songs/{id}/lyrics and /lyrics?id=)
     */
    suspend fun getLyrics(lyricsId: String): String? = withContext(Dispatchers.IO) {
        val cleanId = normalizeId(lyricsId)
        if (cleanId.isBlank()) return@withContext null

        // 1. Try Official Lyrics Call with lyrics_id
        val url1 = "$OFFICIAL_BASE?__call=lyrics.getLyrics&ctx=web6dot0&_format=json&_marker=0&lyrics_id=$cleanId"
        val json1 = fetchJson(url1)
        val rawHtml1 = json1?.optString("lyrics")
        if (!rawHtml1.isNullOrBlank()) {
            val cleaned = cleanLyricsHtml(rawHtml1)
            if (cleaned.isNotBlank()) return@withContext cleaned
        }

        // 1b. Try Official Lyrics Call with id
        val url2 = "$OFFICIAL_BASE?__call=lyrics.getLyrics&ctx=web6dot0&_format=json&_marker=0&id=$cleanId"
        val json2 = fetchJson(url2)
        val rawHtml2 = json2?.optString("lyrics")
        if (!rawHtml2.isNullOrBlank()) {
            val cleaned = cleanLyricsHtml(rawHtml2)
            if (cleaned.isNotBlank()) return@withContext cleaned
        }

        // 1c. Try resolving actual lyrics_id from official song details
        val detailsJson = fetchJson("$OFFICIAL_BASE?__call=song.getDetails&pids=$cleanId&_format=json")
        val songObj = detailsJson?.optJSONObject(cleanId)
        val moreInfo = songObj?.optJSONObject("more_info")
        val directLyrics = moreInfo?.optString("lyrics")
        if (!directLyrics.isNullOrBlank()) {
            val cleaned = cleanLyricsHtml(directLyrics)
            if (cleaned.isNotBlank()) return@withContext cleaned
        }
        val resolvedLyricsId = moreInfo?.optString("lyrics_id")?.takeIf { it.isNotBlank() && it != cleanId }
        if (resolvedLyricsId != null) {
            val url3 = "$OFFICIAL_BASE?__call=lyrics.getLyrics&ctx=web6dot0&_format=json&_marker=0&lyrics_id=$resolvedLyricsId"
            val json3 = fetchJson(url3)
            val rawHtml3 = json3?.optString("lyrics")
            if (!rawHtml3.isNullOrBlank()) {
                val cleaned = cleanLyricsHtml(rawHtml3)
                if (cleaned.isNotBlank()) return@withContext cleaned
            }
        }

        // 2. Active Saavn API mirrors (saavn.dev, saavn.me, etc.)
        for (base in SUMITKOLHE_BASES) {
            val candidateUrls = listOf(
                "$base/songs/$cleanId/lyrics",
                "$base/lyrics?id=$cleanId",
                "$base/songs?id=$cleanId",
            )
            for (skUrl in candidateUrls) {
                val skJson = fetchJson(skUrl) ?: continue
                val dataObj = skJson.optJSONObject("data") ?: skJson
                val lyricsText = when {
                    dataObj.has("lyrics") -> dataObj.optString("lyrics")
                    dataObj.has("text") -> dataObj.optString("text")
                    dataObj.has("snippet") -> dataObj.optString("snippet")
                    skJson.has("lyrics") -> skJson.optString("lyrics")
                    else -> ""
                }
                if (lyricsText.isNotBlank()) {
                    val cleaned = cleanLyricsHtml(lyricsText)
                    if (cleaned.isNotBlank()) return@withContext cleaned
                }
            }
        }

        null
    }

    /**
     * Fallback that searches JioSaavn by track title and artist to find the song
     * and retrieve its lyrics.
     */
    suspend fun searchAndGetLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val query = "$title $artist".trim()
        if (query.isBlank()) return@withContext null
        val tracks = searchTracks(query, limit = 5)
        for (track in tracks) {
            val lyrics = getLyrics(track.id)
            if (!lyrics.isNullOrBlank()) return@withContext lyrics
        }
        null
    }

    /**
     * Fetches trending tracks for home feed.
     */
    suspend fun getTrendingTracks(): List<JioTrack> = withContext(Dispatchers.IO) {
        val url = "$OFFICIAL_BASE?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=25&p=1&q=trending"
        val json = fetchJson(url)
        val tracks = ArrayList<JioTrack>()

        json?.optJSONArray("results")?.let { results ->
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val track = parseOfficialTrackJson(item)
                if (track != null) tracks.add(track)
            }
        }

        if (tracks.isNotEmpty()) return@withContext tracks

        // SumitKolhe Modules / Trending Fallback
        for (base in SUMITKOLHE_BASES) {
            val skUrl = "$base/modules?language=hindi,english,punjabi"
            val skJson = fetchJson(skUrl) ?: continue
            val dataObj = skJson.optJSONObject("data") ?: skJson
            val trendingArr = dataObj.optJSONObject("trending")?.optJSONArray("songs")
                ?: dataObj.optJSONArray("trending")
            if (trendingArr != null && trendingArr.length() > 0) {
                for (i in 0 until trendingArr.length()) {
                    val item = trendingArr.optJSONObject(i) ?: continue
                    val track = parseTrackJsonUniversal(item)
                    if (track != null) tracks.add(track)
                }
                if (tracks.isNotEmpty()) return@withContext tracks
            }
        }

        tracks
    }

    companion object {
        val instance: JioSaavnApiClient by lazy { JioSaavnApiClient() }
        private const val TAG = "JioSaavnApiClient"
        private const val OFFICIAL_BASE = "https://www.jiosaavn.com/api.php"
        private val SUMITKOLHE_BASES = listOf(
            "https://saavn.dev/api",
            "https://saavn.me/api",
            "https://jiosaavn-api-privatecvc2.vercel.app",
            "https://jiosaavn-api-v3.vercel.app/api",
        )
    }
}
