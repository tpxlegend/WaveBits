package app.wavebits.extensions

import kotlinx.serialization.Serializable

/**
 * What the user accepted of an extension, kept with it: its actions with their reasons and
 * hosts, its exclusive roles, what it replaces or conflicts with, its author and source. An
 * update is always weighed against this, the last snapshot the user accepted (at install, or by
 * tapping Accept), never against the version running: an update that silently drops an action
 * and a later one that adds it back with another reason are both seen for what they ask.
 */
@Serializable
data class Snapshot(
    val id: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    /** `owner/name` on GitHub; null for a package from a file or an address. */
    val repo: String? = null,
    /** [Manifest.declared]: each action with its why and, for network, its hosts. */
    val actions: List<ActionRequest> = emptyList(),
    /** The [Mode.EXCLUSIVE] actions it takes (one extension at a time acts for each). */
    val exclusive: List<String> = emptyList(),
    val replaces: List<String> = emptyList(),
    val conflictsWith: List<String> = emptyList(),
) {
    fun hosts(): List<String> = actions.firstOrNull { it.name == Action.NETWORK.key }?.hosts.orEmpty()

    /** The Spotify writes accepted, as grants ([Manifest.declaredWrites]). */
    fun writes(): List<String> = toManifest().declaredWrites().map { it.toString() }

    /** A manifest holding what was accepted, for [app.wavebits.extensions.Manifest.asks]-based comparisons. */
    fun toManifest() = Manifest(id, id, version, versionCode, author = author, actions = actions, replaces = replaces, conflictsWith = conflictsWith)

    companion object {
        fun of(m: Manifest, repo: String?) = Snapshot(
            id = m.id, version = m.version, versionCode = m.versionCode, author = m.author, repo = repo,
            actions = m.declared(), exclusive = m.actionSet().filter { it.mode == Mode.EXCLUSIVE }.map { it.key },
            replaces = m.replaces, conflictsWith = m.conflictsWith,
        )
    }
}

/**
 * One thing an update asks that the user has not accepted, as the "Update needs your OK" card
 * lists it: [key] is the action key (or the host, or the new repo/author/id), [why] the
 * author's new reason, [before] what the user accepted (the old reason, repo or author).
 */
@Serializable
data class Change(val kind: Kind, val key: String, val why: String = "", val before: String = "", val critical: Boolean = false) {
    enum class Kind {
        /** An action not accepted before (a critical one, `webview.page` and exclusive roles included). */
        ACTION,
        /** A network host not accepted before (`*`: any host, critical). */
        HOST,
        /** A Spotify write declared under spotify.raw not accepted before (allowed with the update: critical). */
        WRITE,
        /** An exclusive role taken by an action already accepted (the app made it exclusive since). */
        EXCLUSIVE,
        /** An accepted action whose reason changed: any change counts, the user judges the meaning. */
        WHY,
        /** It now comes from another repository. */
        REPO,
        /** Another author. */
        AUTHOR,
        /** It now turns off another extension when installed. */
        REPLACES,
        /** It now refuses to run with another extension. */
        CONFLICTS,
    }

    /** The action concerned, for its icon. */
    val action: Action? get() = when (kind) {
        Kind.ACTION, Kind.EXCLUSIVE, Kind.WHY -> Action.of(key)
        Kind.HOST -> Action.NETWORK
        Kind.WRITE -> Action.SPOTIFY_RAW
        else -> null
    }
}

/** Whether an update found on GitHub is applied by itself, or waits for the user's OK. */
object UpdateGate {
    sealed interface Decision {
        /** It asks nothing new: applied, the previous version kept to revert to. */
        data object Apply : Decision
        /** It asks [changes]: the old version keeps running, the user reads the card. */
        data class Ask(val changes: List<Change>) : Decision
    }

    fun decide(accepted: Snapshot, next: Snapshot): Decision = diff(accepted, next).let { if (it.isEmpty()) Decision.Apply else Decision.Ask(it) }

    /**
     * Everything [next] asks beyond [accepted]: new actions (critical, page and exclusive ones
     * included), new hosts, exclusive roles, reasons that changed, another repository or author,
     * new replacements and conflicts. What it drops is not a change: it asks less.
     */
    fun diff(accepted: Snapshot, next: Snapshot): List<Change> = buildList {
        if (next.id != accepted.id) add(Change(Change.Kind.REPO, next.id, before = accepted.id, critical = true))
        if (next.repo != accepted.repo && accepted.repo != null) add(Change(Change.Kind.REPO, next.repo.orEmpty(), before = accepted.repo, critical = true))
        if (next.author.trim() != accepted.author.trim()) add(Change(Change.Kind.AUTHOR, next.author, before = accepted.author, critical = true))
        val was = accepted.actions.associateBy { it.name }
        next.actions.forEach { r ->
            val a = Action.of(r.name)
            val old = was[r.name]
            when {
                old == null -> add(Change(Change.Kind.ACTION, r.name, r.why, critical = a == null || a.level == Level.CRITICAL || (a == Action.NETWORK && "*" in r.hosts)))
                old.why.trim() != r.why.trim() -> add(Change(Change.Kind.WHY, r.name, r.why, before = old.why, critical = a?.level == Level.CRITICAL))
            }
        }
        val hostsBefore = accepted.hosts().toSet()
        next.hosts().filter { it !in hostsBefore }.forEach { add(Change(Change.Kind.HOST, it, critical = it == "*")) }
        val writesBefore = accepted.writes().toSet()
        next.writes().filter { it !in writesBefore }.forEach { add(Change(Change.Kind.WRITE, it, critical = true)) }
        val newActions = next.actions.map { it.name }.filter { it !in was }.toSet()
        next.exclusive.filter { it !in accepted.exclusive && it !in newActions }.forEach { add(Change(Change.Kind.EXCLUSIVE, it, critical = true)) }
        next.replaces.filter { it !in accepted.replaces }.forEach { add(Change(Change.Kind.REPLACES, it)) }
        next.conflictsWith.filter { it !in accepted.conflictsWith }.forEach { add(Change(Change.Kind.CONFLICTS, it)) }
    }
}

/**
 * The official repository's `blocked.json` ([GitHub.BLOCKED_URL]), read once a day: [blocked]
 * extensions (all versions, or the packages listed) are turned off on every phone with a notice;
 * [flagged] ones (reported and confirmed) stay installed but are hidden from the store behind
 * "Show all". A missing file is an empty list.
 *
 * `{ "blocked": [{ "id": "…", "sha256": ["…"], "reason": "…" }], "flagged": [{ "id": "…", "reason": "…" }] }`
 */
@Serializable
data class BlockList(val blocked: List<Catalog.Block> = emptyList(), val flagged: List<Flag> = emptyList()) {
    @Serializable
    data class Flag(val id: String, val reason: String = "")

    /** Whether [id] in the package [sha256] is to be turned off (an entry without hashes: every package). */
    fun blocks(id: String, sha256: String?): Catalog.Block? =
        blocked.firstOrNull { b -> b.id == id && (b.sha256.isEmpty() || b.sha256.any { it.equals(sha256, ignoreCase = true) }) }

    fun flags(id: String): Flag? = flagged.firstOrNull { it.id == id }

    companion object {
        val EMPTY = BlockList()

        fun parse(text: String): BlockList = if (text.isBlank()) EMPTY else ExtJson.decodeFromString(serializer(), text)

        /** The body of an answer: a 404 (the file does not exist yet) is an empty list, other failures are thrown by the caller. */
        fun of(status: Int, text: String?): BlockList = if (status == 404) EMPTY else parse(text.orEmpty())
    }
}
