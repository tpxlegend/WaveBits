package app.wavebits.spotify

import app.wavebits.api.Image
import app.wavebits.api.Library
import app.wavebits.api.LibraryFilter
import app.wavebits.api.LibraryItem
import app.wavebits.api.LibrarySort
import app.wavebits.api.MediaKind
import app.wavebits.api.Page
import app.wavebits.api.Playlist
import app.wavebits.api.PlaylistAccess
import app.wavebits.api.PlaylistMember
import app.wavebits.api.PlaylistMerge
import app.wavebits.api.RowAdder
import app.wavebits.api.PlaylistSort
import app.wavebits.api.Playlists
import app.wavebits.api.SavedTrack
import app.wavebits.api.Track
import app.wavebits.api.Uri
import app.wavebits.api.sortedFor
import app.wavebits.spotify.bridge.Bridge
import app.wavebits.spotify.bridge.service
import app.wavebits.spotify.gql.Gql
import app.wavebits.spotify.gql.GqlException
import app.wavebits.spotify.gql.SpotifyHttp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

private fun uris(list: List<Uri>) = json(mapOf("uris" to list))

class SpotifyLibrary(private val bridge: Bridge, private val gql: Gql) : Library {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val changes: SharedFlow<Unit> = _changes
    private fun changed() { _changes.tryEmit(Unit) }
    internal fun notifyChanged() = changed()
    internal val followedPlaylists = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * The page's LibraryAPI rejects the whole list ("Unknown URI type!") as soon as it holds a
     * playlist: playlists are asked to the rootlist (the followed / own playlists), the rest to
     * the library, and the answers come back in the order of [uris].
     */
    private companion object {
        /** Items per library.contains call. */
        const val CONTAINS_BATCH = 50
    }

    override suspend fun contains(uris: List<Uri>): List<Boolean> {
        if (uris.isEmpty()) return emptyList()
        val (lists, others) = uris.withIndex().partition { it.value.contains(":playlist:") }
        val out = BooleanArray(uris.size)
        // In batches: the page answers a long list only in part, and a missing answer read as
        // "not liked" showed liked songs without their heart (and was kept as such).
        for (batch in others.chunked(CONTAINS_BATCH)) {
            val batchUris = batch.map { it.value }
            val flags = runCatching {
                bridge.call("library.contains", uris(batchUris) as kotlinx.serialization.json.JsonObject) as JsonArray
            }.getOrNull()
            if (flags != null && flags.size == batch.size) {
                batch.forEachIndexed { i, v -> out[v.index] = flags[i].jsonPrimitive.boolean }
            } else {
                val gqlFlags = runCatching {
                    gql.query("areEntitiesInLibrary", vars("uris" to batchUris))
                        .at("data.lookup")
                        .let { it as? JsonArray }
                        ?.map { item -> item.bool("data.saved") == true }
                }.getOrNull()
                if (gqlFlags != null && gqlFlags.size == batch.size) {
                    batch.forEachIndexed { i, v -> out[v.index] = gqlFlags[i] }
                } else {
                    throw IllegalStateException("library.contains failed for ${batch.size} items")
                }
            }
        }
        if (lists.isNotEmpty()) {
            val arg = JsonArray(lists.map { JsonPrimitive(it.value) })
            val flags = runCatching { bridge.service("RootlistAPI", "contains", arg) as? JsonArray }.getOrNull()
            val gqlFlags = if (flags == null || flags.size != lists.size) {
                runCatching {
                    gql.query("areEntitiesInLibrary", vars("uris" to lists.map { it.value }))
                        .at("data.lookup")
                        .let { it as? JsonArray }
                        ?.map { item -> item.bool("data.saved") == true }
                }.getOrNull()
            } else null
            lists.forEachIndexed { i, v ->
                val remote = flags?.getOrNull(i)?.jsonPrimitive?.boolean
                    ?: gqlFlags?.getOrNull(i)
                    ?: false
                out[v.index] = followedPlaylists[v.value] ?: remote
            }
        }
        return out.toList()
    }

    override suspend fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val (playlists, others) = uris.partition { it.contains(":playlist:") }
        for (p in playlists) {
            followedPlaylists[p] = true
            runCatching {
                bridge.service("RootlistAPI", "add", json(listOf(p)), json(mapOf("before" to "start")))
            }.recoverCatching {
                gql.query("addToLibrary", vars("libraryItemUris" to listOf(p)))
            }.getOrThrow()
        }
        if (others.isNotEmpty()) {
            runCatching {
                bridge.call("library.add", uris(others) as kotlinx.serialization.json.JsonObject)
            }.recoverCatching {
                gql.query("addToLibrary", vars("libraryItemUris" to others))
            }.getOrThrow()
        }
        changed()
    }

    override suspend fun remove(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val (playlists, others) = uris.partition { it.contains(":playlist:") }
        for (p in playlists) {
            followedPlaylists[p] = false
            runCatching {
                bridge.service("RootlistAPI", "remove", json(listOf(mapOf("uri" to p))))
            }.recoverCatching {
                gql.query("removeFromLibrary", vars("libraryItemUris" to listOf(p)))
            }.getOrThrow()
        }
        if (others.isNotEmpty()) {
            runCatching {
                bridge.call("library.remove", uris(others) as kotlinx.serialization.json.JsonObject)
            }.recoverCatching {
                gql.query("removeFromLibrary", vars("libraryItemUris" to others))
            }.getOrThrow()
        }
        changed()
    }

    override suspend fun items(filter: LibraryFilter, sort: LibrarySort, folderUri: Uri?, offset: Int, limit: Int): Page<LibraryItem> {
        val queryVars = vars(
            "filters" to listOfNotNull(filter.id),
            "order" to sort.id,
            "textFilter" to "",
            "features" to listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "PRERELEASES_V2", "CLIPS", "EVENTS"),
            "limit" to limit, "offset" to offset,
            "flatten" to false, "expandedFolders" to emptyList<String>(),
            "folderUri" to folderUri, "includeFoldersWhenFlattening" to true,
        )
        var lib = runCatching { gql.query("libraryV3", queryVars).at("data.me.libraryV3") }.getOrNull()
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull && it.typename() != "NotFound" }
        if (lib == null) {
            kotlinx.coroutines.delay(450)
            lib = runCatching { gql.query("libraryV3", queryVars).at("data.me.libraryV3") }.getOrNull()
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull && it.typename() != "NotFound" }
        }
        if (lib == null) {
            return fallbackRootlistItems(filter, folderUri, offset, limit)
        }
        val rows = lib.list("items")
        val items = rows.mapNotNull { row ->
            val c = card(row.at("item")) ?: return@mapNotNull null
            val d = row.at("item.data")
            val kind = if (d.typename() == "Folder") MediaKind.FOLDER else c.kind
            LibraryItem(
                uri = if (kind == MediaKind.FOLDER) stableFolderUri(c.uri) else c.uri, kind = kind, name = c.title, subtitle = c.subtitle, images = c.images,
                pinned = row.bool("pinned") ?: false, addedAt = row.str("addedAt.isoString"),
                count = d.int("playlistCount")?.plus(d.int("folderCount") ?: 0) ?: d.int("count"),
            )
        }
        val total = lib.int("totalCount") ?: items.size
        // Advance by the raw row count: rows that do not map (unknown kinds) still
        // occupy positions, or the next page overlaps and rows repeat.
        return Page(items, offset, total, (offset + rows.size).takeIf { rows.isNotEmpty() && it < total })
    }

    private suspend fun fallbackRootlistItems(
        filter: LibraryFilter,
        folderUri: Uri?,
        offset: Int,
        limit: Int,
    ): Page<LibraryItem> {
        if (filter != LibraryFilter.ALL && filter != LibraryFilter.PLAYLISTS) {
            return Page(emptyList(), offset, 0, null)
        }
        val root = runCatching { bridge.service("RootlistAPI", "getContents") }.getOrNull()
            ?: return Page(emptyList(), offset, 0, null)
        val rawItems = if (folderUri == null) {
            root.list("items")
        } else {
            val targetStable = stableFolderUri(folderUri)
            fun findFolder(list: List<JsonElement>): List<JsonElement>? {
                for (item in list) {
                    if (item.str("type") != "folder") continue
                    val u = item.str("uri") ?: continue
                    if (stableFolderUri(u) == targetStable) return item.list("items")
                    findFolder(item.list("items"))?.let { return it }
                }
                return null
            }
            findFolder(root.list("items")).orEmpty()
        }
        val mapped = rawItems.mapNotNull { item ->
            val type = item.str("type")
            val uri = item.str("uri") ?: return@mapNotNull null
            when {
                type == "folder" -> {
                    val subItems = item.list("items")
                    LibraryItem(
                        uri = stableFolderUri(uri),
                        kind = MediaKind.FOLDER,
                        name = item.str("name") ?: "Folder",
                        subtitle = "Folder",
                        images = emptyList(),
                        pinned = false,
                        addedAt = null,
                        count = subItems.size,
                    )
                }
                uri.startsWith("spotify:playlist:") -> {
                    val owner = item.str("owner.displayName") ?: item.str("owner.name")
                    val imgs = item.list("images").mapNotNull { i ->
                        i.str("url")?.let { Image(it, i.int("width"), i.int("height")) }
                    }
                    LibraryItem(
                        uri = uri,
                        kind = MediaKind.PLAYLIST,
                        name = item.str("name") ?: "",
                        subtitle = owner,
                        images = imgs,
                        pinned = false,
                        addedAt = item.str("addedAt"),
                        count = item.int("totalLength"),
                    )
                }
                else -> null
            }
        }
        val slice = mapped.drop(offset).take(limit)
        val next = (offset + slice.size).takeIf { slice.isNotEmpty() && it < mapped.size }
        return Page(slice, offset, mapped.size, next)
    }

    override suspend fun likedTracks(offset: Int, limit: Int): Page<SavedTrack> {
        var page = runCatching { gql.query("fetchLibraryTracks", vars("offset" to offset, "limit" to limit)).at("data.me.library.tracks") }.getOrNull()
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        if (page == null) {
            kotlinx.coroutines.delay(450)
            page = runCatching { gql.query("fetchLibraryTracks", vars("offset" to offset, "limit" to limit)).at("data.me.library.tracks") }.getOrNull()
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        }
        if (page == null) return Page(emptyList(), offset, 0, null)
        val rows = page.list("items")
        val items = rows.mapNotNull { row ->
            val data = row.at("track.data")
            val withUri = if (data.str("uri") == null) row.str("track._uri")?.let { u ->
                kotlinx.serialization.json.JsonObject((data as kotlinx.serialization.json.JsonObject) + ("uri" to JsonPrimitive(u)))
            } else data
            track(withUri)?.let { SavedTrack(it, row.str("addedAt.isoString")) }
        }
        val total = page.int("totalCount") ?: items.size
        return Page(items, offset, total, (offset + rows.size).takeIf { rows.isNotEmpty() && it < total })
    }

    override suspend fun pin(uri: Uri) { gql.query("pinLibraryItem", vars("pinnableItemUri" to uri)); changed() }
    override suspend fun unpin(uri: Uri) { gql.query("unpinLibraryItem", vars("pinnableItemUri" to uri)); changed() }

    /**
     * Folders have two uri forms: `…:folder:{hash}` (stable, used by the library list and this API)
     * and `…:folder:{hash}:{name}` in the rootlist, which changes on rename. Writes need the latter.
     */
    private fun stableFolderUri(uri: Uri): Uri {
        val i = uri.indexOf(":folder:")
        if (i < 0) return uri
        val rest = uri.substring(i + ":folder:".length)
        return uri.substring(0, i) + ":folder:" + rest.substringBefore(':')
    }

    private suspend fun rootlistFolderUri(uri: Uri): Uri {
        val stable = stableFolderUri(uri)
        fun find(items: List<JsonElement>): Uri? {
            for (item in items) {
                if (item.str("type") != "folder") continue
                val u = item.str("uri") ?: continue
                if (stableFolderUri(u) == stable) return u
                find(item.list("items"))?.let { return it }
            }
            return null
        }
        return find(bridge.service("RootlistAPI", "getContents").list("items")) ?: throw GqlException("folder", "no folder $stable")
    }

    override suspend fun createFolder(name: String, parentFolderUri: Uri?): Uri {
        val r = bridge.service("RootlistAPI", "createFolder", JsonPrimitive(name), json(mapOf("before" to "start")))
        val uri = stableFolderUri(r.str("uri") ?: throw GqlException("createFolder", "no uri"))
        if (parentFolderUri != null) moveToFolder(uri, parentFolderUri)
        changed()
        return uri
    }

    override suspend fun renameFolder(folderUri: Uri, name: String) {
        bridge.service("RootlistAPI", "renameFolder", json(mapOf("uri" to rootlistFolderUri(folderUri))), JsonPrimitive(name)); changed()
    }

    override suspend fun deleteFolder(folderUri: Uri) {
        bridge.service("RootlistAPI", "removeFolder", JsonPrimitive(rootlistFolderUri(folderUri))); changed()
    }

    /** Right after a folder's start marker is inside the folder; "start" is the top level. */
    override suspend fun moveToFolder(uri: Uri, folderUri: Uri?) {
        val to = json(folderUri?.let { mapOf("after" to mapOf("uri" to rootlistFolderUri(it))) } ?: mapOf("before" to "start"))
        if (":folder:" in uri) bridge.service("RootlistAPI", "moveFolder", json(mapOf("uri" to rootlistFolderUri(uri))), to)
        else bridge.service("RootlistAPI", "move", json(listOf(mapOf("uri" to uri))), to)
        changed()
    }
}

private const val ADDED_BY_PAGE = 300

class SpotifyPlaylists(
    private val gql: Gql,
    private val bridge: Bridge,
    private val http: SpotifyHttp,
    private val library: SpotifyLibrary? = null,
) : Playlists {
    override suspend fun playlist(uri: Uri): Playlist =
        playlist(gql.query("fetchPlaylist", vars(
            "uri" to uri, "offset" to 0, "limit" to 1, "enableWatchFeedEntrypoint" to false, "includeEpisodeContentRatingsV2" to true,
        )).entity("data.playlistV2", "fetchPlaylist")) ?: throw GqlException("fetchPlaylist", "bad playlist")

    override suspend fun tracks(uri: Uri, offset: Int, limit: Int): Page<Track> {
        val p = gql.query("fetchPlaylistContents", vars(
            "uri" to uri, "offset" to offset, "limit" to limit, "includeEpisodeContentRatingsV2" to true,
        )).entity("data.playlistV2", "fetchPlaylistContents")
        val content = p.at("content")
        val items = content.list("items").mapNotNull { playlistItem(it.at("itemV2.data"), it.str("uid"))?.copy(addedAt = it.str("addedAt.isoString")) }
        val total = content.int("totalCount") ?: items.size
        return Page(items, offset, total, (offset + limit).takeIf { it < total })
    }

    private fun rootPlaylists(root: JsonElement): List<Playlist> {
        val out = mutableListOf<Playlist>()
        fun walk(items: List<JsonElement>) {
            for (item in items) when (item.str("type")) {
                "folder" -> walk(item.list("items"))
                else -> item.str("uri")?.takeIf { it.startsWith("spotify:playlist:") }?.let { uri ->
                    out += Playlist(
                        uri = uri,
                        name = item.str("name") ?: "",
                        description = item.str("description")?.ifBlank { null },
                        owner = item.str("owner.displayName") ?: item.str("owner.name"),
                        images = item.list("images").mapNotNull { i -> i.str("url")?.let { Image(it, i.int("width"), i.int("height")) } },
                        trackCount = item.int("totalLength") ?: 0,
                        // Owned, or open to this account (a collaborator: not the owner, yet can add).
                        // `isOwnedBySelf ?: canAdd` never read canAdd for a collaborator (false is not
                        // null): the playlist they joined stayed read-only in the app (reported).
                        editable = item.bool("isOwnedBySelf") == true || item.bool("canAdd") == true,
                    )
                }
            }
        }
        walk(root.list("items"))
        return out
    }

    override suspend fun mine(): List<Playlist> = rootPlaylists(bridge.service("RootlistAPI", "getContents"))

    override suspend fun editable(): List<Playlist> {
        val me = bridge.service("UserAPI", "getUser").str("username")
        return mine().filter { it.editable || (me != null && it.owner == me) }
    }

    override suspend fun create(name: String, folderUri: Uri?): Uri {
        // "After the folder" is the first place inside it.
        val where = if (folderUri != null) json(mapOf("after" to mapOf("uri" to folderUri))) else json(mapOf("before" to "start"))
        val r = bridge.service("RootlistAPI", "createPlaylist", JsonPrimitive(name), where)
        val uri = r.str("uri") ?: (r as? JsonPrimitive)?.content ?: throw GqlException("createPlaylist", "no uri")
        library?.followedPlaylists?.put(uri, true)
        library?.notifyChanged()
        return uri
    }

    override suspend fun rename(uri: Uri, name: String) = updateDetails(uri, "name", JsonPrimitive(name))

    override suspend fun setDescription(uri: Uri, description: String) = updateDetails(uri, "description", JsonPrimitive(description))

    /** The base permission the page's "Make private" and "Invite collaborators" set. */
    override suspend fun access(uri: Uri): PlaylistAccess =
        PlaylistAccess.of((bridge.service("PlaylistPermissionsAPI", "getBasePermission", JsonPrimitive(uri)) as? JsonPrimitive)?.content)

    /**
     * As the Web Player's "Make private": the playlist also leaves the profile (made private with
     * the base permission alone, it stayed listed on the profile, unlike in the official apps).
     * Made public again it is not put back on the profile, as there either ([setPublished]).
     */
    override suspend fun setAccess(uri: Uri, access: PlaylistAccess) {
        if (access == PlaylistAccess.PRIVATE && published(uri)) setPublished(uri, false)
        bridge.service("PlaylistPermissionsAPI", "setBasePermission", JsonPrimitive(uri), JsonPrimitive(access.permission))
    }

    /** The library's own "public" flag on the playlist: listed on the profile. */
    override suspend fun published(uri: Uri): Boolean =
        (bridge.service("RootlistAPI", "getPublishedState", JsonPrimitive(uri)) as? JsonPrimitive)?.content == "true"

    override suspend fun setPublished(uri: Uri, published: Boolean) {
        bridge.service("RootlistAPI", "setPublishedState", JsonPrimitive(uri), JsonPrimitive(published))
    }

    override suspend fun setCover(uri: Uri, jpeg: ByteArray?) {
        bridge.call("playlist.setCover", json(mapOf("uri" to uri, "jpeg" to jpeg?.let { java.util.Base64.getEncoder().encodeToString(it) })) as kotlinx.serialization.json.JsonObject, timeoutMs = 60_000)
    }

    /** Two updates sent back to back can lose the second one: check it landed, and resend once. */
    private suspend fun updateDetails(uri: Uri, field: String, value: JsonElement) {
        repeat(2) {
            bridge.service("PlaylistAPI", "updateDetails", JsonPrimitive(uri), json(mapOf(field to value)))
            repeat(10) {
                if (bridge.service("PlaylistAPI", "getMetadata", JsonPrimitive(uri)).at(field) == value) {
                    library?.notifyChanged()
                    return
                }
                kotlinx.coroutines.delay(300)
            }
        }
        throw GqlException("updateDetails", "$field not applied")
    }

    override suspend fun delete(uri: Uri) = unfollow(uri)

    override suspend fun follow(uri: Uri) {
        library?.followedPlaylists?.put(uri, true)
        runCatching {
            bridge.service("RootlistAPI", "add", json(listOf(uri)), json(mapOf("before" to "start")))
        }.recoverCatching {
            gql.query("addToLibrary", vars("libraryItemUris" to listOf(uri)))
        }.getOrThrow()
        library?.notifyChanged()
    }

    override suspend fun unfollow(uri: Uri) {
        library?.followedPlaylists?.put(uri, false)
        runCatching {
            bridge.service("RootlistAPI", "remove", json(listOf(mapOf("uri" to uri))))
        }.recoverCatching {
            gql.query("removeFromLibrary", vars("libraryItemUris" to listOf(uri)))
        }.getOrThrow()
        library?.notifyChanged()
    }

    /**
     * The page's `claimPermissions` (spclient `POST playlist-permission/v1/playlist/{id}/permission-grant/token/{pt}/claim`,
     * no body, answers `{userPermission:{permissionLevel,revision},capabilities}`); open.spotify.com does the same when
     * it is opened with `?pt=`. Claiming does not say whether the playlist is in the rootlist, so it is followed when not.
     */
    override suspend fun join(uri: Uri, invitationToken: String): PlaylistAccess {
        val claimed = bridge.service("PlaylistPermissionsAPI", "claimPermissions", JsonPrimitive(uri), JsonPrimitive(invitationToken))
        val inLibrary = (bridge.service("RootlistAPI", "contains", JsonArray(listOf(JsonPrimitive(uri)))) as? JsonArray)
            ?.firstOrNull()?.jsonPrimitive?.boolean == true
        if (!inLibrary) follow(uri)
        return PlaylistAccess.of(claimed.str("userPermission.permissionLevel"))
    }

    /**
     * The page's `getPermissionGrant(uri, "CONTRIBUTOR")` (spclient `POST playlist-permission/v1/playlist/{id}/permission-grant`,
     * `{"permission":{"permissionLevel":"CONTRIBUTOR"},"ttlMs":604800000}`, answers `{token, permissionGrantOptions, createdAt}`):
     * what the web player's "Invite collaborators" shares as `open.spotify.com/playlist/{id}?pt={token}`.
     */
    override suspend fun collaboratorInvite(uri: Uri): String {
        val grant = bridge.service("PlaylistPermissionsAPI", "getPermissionGrant", JsonPrimitive(uri), JsonPrimitive("CONTRIBUTOR"))
        val token = grant.str("token")?.takeIf { it.isNotBlank() } ?: error("no invitation token")
        return "https://open.spotify.com/playlist/${uri.substringAfterLast(':')}?pt=$token"
    }

    /**
     * The page's `getMembers(uri)`: `{members:[{permissionLevel, isOwner, user:{displayName, images:[{url}],
     * uri, username}, songsAdded}]}` — the faces shown on a collaborative playlist.
     */
    override suspend fun members(uri: Uri): List<PlaylistMember> =
        bridge.service("PlaylistPermissionsAPI", "getMembers", JsonPrimitive(uri)).list("members").mapNotNull { m ->
            val username = m.str("user.username") ?: return@mapNotNull null
            PlaylistMember(username, m.str("user.displayName") ?: username,
                m.list("user.images").firstNotNullOfOrNull { it.str("url") }, m.bool("isOwner") == true,
                isCollaborator = m.str("permissionLevel") == "CONTRIBUTOR")
        }.sortedByDescending { it.isOwner }

    /**
     * The members menu's "Remove as collaborator" / "Make collaborator": the page's
     * `setMemberPermission(uri, username, level)` (spclient `POST /playlist/{id}/permission/member/user/{username}`,
     * `{revision, permissionLevel}`; the page reads the member's revision itself first).
     */
    override suspend fun setMemberCollaborator(uri: Uri, username: String, collaborator: Boolean) {
        bridge.service("PlaylistPermissionsAPI", "setMemberPermission", JsonPrimitive(uri), JsonPrimitive(username),
            JsonPrimitive(if (collaborator) "CONTRIBUTOR" else "VIEWER"))
    }

    /** The members menu's "Remove from playlist": `removeMember(uri, username)` (`DELETE …/permission/member/user/{username}`). */
    override suspend fun removeMember(uri: Uri, username: String) {
        bridge.service("PlaylistPermissionsAPI", "removeMember", JsonPrimitive(uri), JsonPrimitive(username))
    }

    /**
     * Members first (a failure leaves the playlist collaborative, so the owner can retry), then the
     * base permission. Public (listed on the profile): collaborators are removed; private: they become listeners.
     */
    override suspend fun stopCollaboration(uri: Uri) {
        val public = published(uri)
        members(uri).filter { it.isCollaborator && !it.isOwner }.forEach {
            if (public) removeMember(uri, it.username) else setMemberCollaborator(uri, it.username, false)
        }
        setAccess(uri, if (public) PlaylistAccess.PUBLIC else PlaylistAccess.PRIVATE)
    }

    /**
     * The page's "Leave playlist" (menu entry gated by `canCancelMembership`, then its dialog):
     * `PlaylistPermissionsAPI.removeMember(uri, ownUsername)`, which is spclient
     * `DELETE /playlist/{id}/permission/member/user/{username}`. As in the dialog, a private
     * playlist is taken out of the rootlist first; a public one stays followed (read-only).
     */
    override suspend fun leave(uri: Uri) {
        val me = bridge.service("UserAPI", "getUser").str("username")?.takeIf { it.isNotBlank() } ?: error("no signed-in user")
        if (access(uri) == PlaylistAccess.PRIVATE) unfollow(uri)
        bridge.service("PlaylistPermissionsAPI", "removeMember", JsonPrimitive(uri), JsonPrimitive(me))
    }

    /**
     * The page's `PlaylistAPI.getContents(uri, {offset, limit})`: each row carries `uid` and
     * `addedBy {username}`. Read a page at a time to the end (a few hundred rows a request).
     */
    override suspend fun addedBy(uri: Uri): List<RowAdder> {
        val out = ArrayList<RowAdder>()
        var offset = 0
        while (offset < 10_000) {
            val page = bridge.service("PlaylistAPI", "getContents", JsonPrimitive(uri),
                json(mapOf("offset" to offset, "limit" to ADDED_BY_PAGE)))
            val items = page.list("items")
            items.forEach { r ->
                val who = r.str("addedBy.username") ?: return@forEach
                out += RowAdder(r.str("uid").orEmpty(), r.str("uri").orEmpty(), who)
            }
            if (items.size < ADDED_BY_PAGE) break
            offset += items.size
        }
        return out
    }

    /** Pages through the whole playlist (100 rows a request), then sorts it on the client. */
    override suspend fun sortedTracks(uri: Uri, sort: PlaylistSort, descending: Boolean): List<Track> {
        val all = mutableListOf<Track>()
        var offset: Int? = 0
        while (offset != null) {
            val page = tracks(uri, offset, 100)
            if (page.items.isEmpty()) break
            all += page.items
            offset = page.nextOffset
        }
        return all.sortedFor(sort, descending)
    }

    /** At most [PlaylistMerge.CHUNK] songs a request, each at the bottom, so a whole album or playlist keeps its order. */
    override suspend fun addTracks(uri: Uri, trackUris: List<Uri>) {
        for (chunk in trackUris.chunked(PlaylistMerge.CHUNK)) {
            gql.query("addToPlaylist", vars(
                "playlistItemUris" to chunk, "playlistUri" to uri,
                "newPosition" to mapOf("moveType" to "BOTTOM_OF_PLAYLIST", "fromUid" to null),
            ))
        }
    }

    override suspend fun removeTracks(uri: Uri, uids: List<String>) {
        gql.query("removeFromPlaylist", vars("playlistUri" to uri, "uids" to uids))
    }

    override suspend fun moveTracks(uri: Uri, uids: List<String>, beforeUid: String?) {
        val position = if (beforeUid == null) mapOf("moveType" to "BOTTOM_OF_PLAYLIST", "fromUid" to null)
        else mapOf("moveType" to "BEFORE_UID", "fromUid" to beforeUid)
        gql.query("moveItemsInPlaylist", vars("playlistUri" to uri, "uids" to uids, "newPosition" to position))
    }

    /**
     * The "Recommended songs" list under an own playlist (playlistextender). An empty playlist
     * gets some too, from its name (checked live). [skip]: tracks not to suggest again.
     */
    override suspend fun recommended(uri: Uri, limit: Int, skip: List<Uri>): List<Track> {
        val body = json(mapOf("playlistURI" to uri, "trackSkipIDs" to skip.map { it.substringAfterLast(':') }, "numResults" to limit)).toString()
        val (code, text) = http.send("POST", "https://spclient.wg.spotify.com/playlistextender/extendp/", body, contentType = "application/json")
        if (code != 200) throw app.wavebits.spotify.gql.HttpException(code, text.take(200))
        return Bridge.json.parseToJsonElement(text).list("recommendedTracks").mapNotNull { t ->
            val uri = t.str("originalId") ?: t.str("id")?.let { "spotify:track:$it" } ?: return@mapNotNull null
            Track(
                uri = uri,
                name = t.str("name") ?: "",
                durationMs = t.long("duration") ?: 0,
                artists = t.list("artists").mapNotNull { a -> a.str("id")?.let { app.wavebits.api.ArtistRef("spotify:artist:$it", a.str("name") ?: "") } },
                album = t.obj("album")?.let { a ->
                    app.wavebits.api.AlbumRef("spotify:album:" + (a.str("id") ?: ""), a.str("name") ?: "",
                        listOfNotNull(a.str("largeImageUrl")?.let { Image(it, 640, 640) }, a.str("imageUrl")?.let { Image(it, 300, 300) }))
                },
                explicit = t.bool("explicit") ?: false,
            )
        }
    }
}
