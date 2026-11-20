package com.music.spotui.extensions

import app.wavebits.extensions.Catalog
import app.wavebits.extensions.Discovery
import app.wavebits.extensions.ExtensionException
import app.wavebits.extensions.GitHubAuth
import app.wavebits.extensions.CodeHost
import app.wavebits.extensions.Manifest
import app.wavebits.extensions.Rating
import app.wavebits.extensions.Ranking
import app.wavebits.extensions.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The repositories' indexes as the store shows them, read once per visit (or on Refresh) and
 * shared by the list, the store pages and the "update available" count, plus the extensions
 * discovered on GitHub (topic [Discovery.TOPIC], listed under [Discovery.SOURCE]) with their
 * ratings. Nothing is downloaded here but indexes, manifests and counts: a package only when
 * the user taps Install.
 */
object ExtensionStore {
    /**
     * The official repository: always read, shown only once it lists an extension (never
     * removable). Each extension in it is reviewed (one pull request per extension).
     */
    const val OFFICIAL_REPO = "https://raw.githubusercontent.com/kota-zo/myspoti-extensions/main/index.json"

    /** One repository's index: [catalog] once read, [error] if it could not be. */
    data class Repo(val url: String, val catalog: Catalog? = null, val error: String? = null, val loading: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _repos = MutableStateFlow<Map<String, Repo>>(emptyMap())
    val repos: StateFlow<Map<String, Repo>> = _repos.asStateFlow()

    /** The ids whose package is being downloaded (a spinner in place of the button). */
    val busy = MutableStateFlow<Set<String>>(emptySet())

    /** Reads [urls]' indexes not read yet (all of them when [force]); forgets the removed ones. GitHub's discovery too. */
    fun refresh(userUrls: List<String>, force: Boolean = false) {
        val urls = sources(userUrls)
        _repos.value = _repos.value.filterKeys { it in urls || it == Discovery.SOURCE }
        discover(force)
        if (force) scope.launch {
            // Refresh also re-reads the kill switch and looks for newer versions of the installed GitHub extensions.
            runCatching { ExtensionUpdates.refreshBlocks(force = true) }
            Extensions.registry.value.extensions.filter { it.origin != null }.forEach { runCatching { ExtensionUpdates.check(it.id, manual = false) } }
        }
        urls.forEach { url ->
            val now = _repos.value[url]
            if (now?.loading == true || (!force && now?.catalog != null)) return@forEach
            _repos.value = _repos.value + (url to (now ?: Repo(url)).copy(loading = true, error = null))
            scope.launch {
                val r = runCatching { Extensions.catalog(url, fresh = force) }
                val fetched = r.getOrNull() ?: now?.catalog
                val catalog = if (url == OFFICIAL_REPO) {
                    val bundled = runCatching {
                        listOf(
                            BundledLyricsExtension.catalogEntry(),
                            BundledWaveBitsDownloadExtension.catalogEntry(),
                        )
                    }.getOrDefault(emptyList())
                    val remote = fetched?.extensions.orEmpty().filterNot { e -> bundled.any { it.id == e.id } }
                    Catalog(name = fetched?.name ?: "WaveBits Official", extensions = bundled + remote)
                } else fetched
                val err = if (url == OFFICIAL_REPO && catalog != null) null else r.exceptionOrNull()?.let { it.message ?: it.toString() }
                _repos.value = _repos.value + (url to Repo(url, catalog, err))
            }
        }
    }

    /** Every catalogue, the discovered one last (an index, its sha256 checked, wins a tie). */
    fun read(): List<Pair<String, Catalog>> = _repos.value.values.sortedBy { it.url == Discovery.SOURCE }.mapNotNull { r -> r.catalog?.let { r.url to it } }

    /** The newest listing of [id] across the repositories: (index address, entry). */
    fun entry(id: String): Pair<String, Catalog.Entry>? = Store.newest(read())[id]

    fun isOfficial(url: String) = url == OFFICIAL_REPO

    /** The repositories read: the official one first, then the user's. */
    fun sources(userUrls: List<String>) = (listOf(OFFICIAL_REPO) + userUrls).distinct()

    /** The official repository has something to show: until then it is left out of the Store. */
    fun officialShown() = _repos.value[OFFICIAL_REPO]?.catalog?.extensions?.isNotEmpty() == true

    // ── GitHub discovery ──

    private const val MAX_SEARCH = 2 * 1024 * 1024

    /** Per repository (`owner/name`), its 👍 / 👎 (no account needed to read them). */
    val ratings = MutableStateFlow<Map<String, Rating>>(emptyMap())

    /** Per repository, the downloads of its release's package, read on its store page. */
    val downloads = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * The repositories with the topic: one search (cached 6 hours; [force]: Refresh, bypasses the cache), their manifests from the hosts' raw file addresses
     * (8 at a time, cached alike), one search for the ratings issues. A repository whose manifest
     * is missing or refused is left out.
     */
    fun discover(force: Boolean = false) {
        val now = _repos.value[Discovery.SOURCE]
        if (now?.loading == true || (!force && now?.catalog != null)) return
        _repos.value = _repos.value + (Discovery.SOURCE to (now ?: Repo(Discovery.SOURCE)).copy(loading = true, error = null))
        scope.launch {
            val failed = mutableListOf<String>()
            val r = runCatching {
                // One search per host: one failing (offline host, rate limit) leaves the others' results.
                val found = CodeHost.entries.flatMap { h ->
                    runCatching {
                        Discovery.parse(h, ExtensionUpdates.cached(Discovery.searchUrl(h), Discovery.CACHE_MS, MAX_SEARCH, force) ?: throw ExtensionException("${h.label} search answered 404"))
                    }.getOrElse { failed += "${h.label}: ${it.message ?: it}"; emptyList() }
                }
                if (failed.size == CodeHost.entries.size) throw ExtensionException(failed.joinToString("; "))
                val gate = Semaphore(8)
                val entries = withContext(Dispatchers.IO) {
                    found.map { f ->
                        async {
                            gate.withPermit {
                                runCatching {
                                    val text = f.manifestUrl()?.let { ExtensionUpdates.cached(it, Discovery.CACHE_MS, force = force) } ?: return@runCatching null
                                    Manifest.parse(text).takeIf { it.problems().isEmpty() }?.let { Discovery.entry(f, it) }
                                }.getOrNull()
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                // Ratings are GitHub issues' reactions: GitHub's repositories only.
                runCatching { ExtensionUpdates.cached(Discovery.RATINGS_URL, Discovery.CACHE_MS, MAX_SEARCH, force) }.getOrNull()
                    ?.let { ratings.value = Discovery.parseRatings(it, found.filter { f -> f.host == CodeHost.GITHUB }.map { f -> f.fullName }.toSet()) }
                Catalog(name = "Community", extensions = entries.distinctBy { it.id })
            }
            val err = r.exceptionOrNull()?.let { it.message ?: it.toString() }
            _repos.value = _repos.value + (Discovery.SOURCE to Repo(Discovery.SOURCE, r.getOrNull() ?: now?.catalog, err))
        }
    }

    /** The download count of [repo]'s release package, for its store page (cached 6 hours). */
    fun loadDownloads(repo: String) {
        if (repo in downloads.value) return
        scope.launch {
            runCatching { ExtensionUpdates.cached(Discovery.releaseUrl(repo), Discovery.CACHE_MS)?.let(Discovery::parseDownloads) }
                .getOrNull()?.let { downloads.value = downloads.value + (repo to it) }
        }
    }

    /** The discovered extensions in the store's order, hidden ones left out unless [showAll]. */
    fun discovered(showAll: Boolean, now: Long = System.currentTimeMillis()): List<Ranking.Ranked<Catalog.Entry>> {
        val entries = _repos.value[Discovery.SOURCE]?.catalog?.extensions.orEmpty()
        val r = ratings.value
        return Ranking.listed(entries.map { Ranking.Ranked(it, it.id, null, it.repo?.let(r::get), Ranking.time(it.updatedAt)) },
            ExtensionUpdates.blocks.value, now, showAll)
    }

    /** How many discovered extensions "Show all" would add. */
    fun hiddenCount(): Int = discovered(true).size - discovered(false).size

    // ── Rating (hidden while [GitHubAuth.GITHUB_CLIENT_ID] is empty) ──

    private val tokenFile get() = File(Extensions.dir, "github_token")
    private const val TOKEN_KEYSTORE_ALIAS = "wavebits_github_token"
    private val TOKEN_MAGIC = "WBT1".toByteArray()

    private fun getOrCreateTokenKey(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(TOKEN_KEYSTORE_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        return javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        ).apply {
            init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    TOKEN_KEYSTORE_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun readStoredToken(): String? {
        val file = tokenFile.takeIf { it.isFile } ?: return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val isSealed = bytes.size > 16 && bytes.copyOfRange(0, 4).contentEquals(TOKEN_MAGIC)
        val plain = if (isSealed) {
            runCatching {
                val key = getOrCreateTokenKey()
                val iv = bytes.copyOfRange(4, 16)
                val cipherText = bytes.copyOfRange(16, bytes.size)
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
                cipher.doFinal(cipherText).decodeToString()
            }.getOrNull()
        } else {
            // Migrate any legacy plaintext token to encrypted storage immediately
            val legacy = bytes.decodeToString().trim()
            if (legacy.isNotEmpty()) saveStoredToken(legacy)
            legacy
        }
        return plain?.trim()?.ifEmpty { null }
    }

    private fun saveStoredToken(token: String) {
        val rawBytes = token.trim().encodeToByteArray()
        val sealed: ByteArray = runCatching {
            val key = getOrCreateTokenKey()
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            TOKEN_MAGIC + cipher.iv + cipher.doFinal(rawBytes)
        }.getOrElse { rawBytes }
        val tmp = File(tokenFile.parentFile, "${tokenFile.name}.tmp")
        tmp.parentFile?.mkdirs()
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(tokenFile)) {
            tmp.copyTo(tokenFile, overwrite = true)
            tmp.delete()
        }
    }

    /** The device-flow sign-in waiting for the user to type [GitHubAuth.DeviceCode.userCode] on GitHub. */
    val signIn = MutableStateFlow<GitHubAuth.DeviceCode?>(null)

    fun signedIn(): Boolean = GitHubAuth.enabled && tokenFile.isFile

    /**
     * 👍 ([up]) or 👎 on [repo]'s ratings issue. Without a token: GitHub's device flow first
     * ([signIn] shows the code; polled until the user typed it, or it expires).
     */
    suspend fun rate(repo: String, up: Boolean) = withContext(Dispatchers.IO) {
        if (!GitHubAuth.enabled) return@withContext
        val issue = ratings.value[repo]?.issue?.takeIf { it > 0 } ?: throw ExtensionException("this extension has no ratings issue")
        suspend fun react(token: String) = ExtensionUpdates.get(GitHubAuth.reactionsUrl(repo, issue), 64 * 1024, accept = "application/vnd.github+json", method = "POST", body = GitHubAuth.reactionBody(up), token = token)
        var a = react(readStoredToken() ?: deviceFlow())
        // The OAuth app's tokens expire: a refused one is dropped and the device flow runs again, once.
        if (a.status == 401) { tokenFile.delete(); a = react(deviceFlow()) }
        if (a.status == 401) { tokenFile.delete(); throw ExtensionException("GitHub sign-in expired: try again") }
        if (a.status !in 200..299) throw ExtensionException("GitHub answered ${a.status}")
        ratings.value[repo]?.let { r -> ratings.value = ratings.value + (repo to if (up) r.copy(up = r.up + 1) else r.copy(down = r.down + 1)) }
    }

    private suspend fun deviceFlow(): String {
        val code = ExtensionUpdates.get(GitHubAuth.DEVICE_CODE_URL, 64 * 1024, accept = "application/json", method = "POST", body = GitHubAuth.deviceCodeBody())
            .body?.decodeToString()?.let(GitHubAuth::parseDeviceCode) ?: throw ExtensionException("GitHub sign-in unavailable")
        signIn.value = code
        try {
            var wait = code.interval * 1000L
            val until = System.currentTimeMillis() + code.expiresIn * 1000L
            while (System.currentTimeMillis() < until && signIn.value != null) {
                kotlinx.coroutines.delay(wait)
                val text = ExtensionUpdates.get(GitHubAuth.TOKEN_URL, 64 * 1024, accept = "application/json", method = "POST", body = GitHubAuth.tokenBody(code.deviceCode)).body?.decodeToString().orEmpty()
                when (val p = GitHubAuth.parsePoll(text)) {
                    is GitHubAuth.Poll.Token -> { saveStoredToken(p.token); return p.token }
                    GitHubAuth.Poll.Pending -> Unit
                    GitHubAuth.Poll.SlowDown -> wait += 5_000
                    is GitHubAuth.Poll.Failed -> throw ExtensionException("GitHub sign-in: ${p.reason}")
                }
            }
            throw ExtensionException("GitHub sign-in not finished")
        } finally {
            signIn.value = null
        }
    }
}
