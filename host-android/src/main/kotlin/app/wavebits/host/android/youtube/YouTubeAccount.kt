// WaveBits, GPL-3.0.
package app.wavebits.host.android.youtube

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.wavebits.innertube.YouTube
import app.wavebits.innertube.models.YouTubeChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The signed-in Google account for YouTube Music.
 *
 * Captures and persists the Google session cookie, visitorData, and dataSyncId
 * in EncryptedSharedPreferences, configuring InnerTube with the authenticated headers.
 */
class YouTubeAccount(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            FILE_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    private val _accountName = MutableStateFlow(sanitizeName(prefs.getString(KEY_NAME, null)))
    val accountName: StateFlow<String?> = _accountName.asStateFlow()

    private val _accountAvatar = MutableStateFlow(prefs.getString(KEY_AVATAR, null))
    val accountAvatar: StateFlow<String?> = _accountAvatar.asStateFlow()

    private val _isGuest = MutableStateFlow(prefs.getBoolean(KEY_GUEST, false))
    val isGuest: StateFlow<Boolean> = _isGuest.asStateFlow()

    val isSignedIn: Boolean
        get() = !prefs.getString(KEY_COOKIE, null).isNullOrBlank() || prefs.getBoolean(KEY_GUEST, false)

    val isAuthenticatedUser: Boolean
        get() = !_isGuest.value && !prefs.getBoolean(KEY_GUEST, false) && hasGoogleAuthCookie(prefs.getString(KEY_COOKIE, null))

    private val _expired = MutableStateFlow(false)
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    init {
        val rawName = prefs.getString(KEY_NAME, null)
        val cleanName = sanitizeName(rawName)
        if (rawName != cleanName) {
            prefs.edit().putString(KEY_NAME, cleanName).apply()
            _accountName.value = cleanName
        }
        applyToInnerTube()
    }

    private fun hasGoogleAuthCookie(cookie: String?): Boolean {
        if (cookie.isNullOrBlank()) return false
        return "SAPISID=" in cookie || "__Secure-3PAPISID=" in cookie || "__Secure-1PAPISID=" in cookie
    }

    private fun applyToInnerTube() {
        val guest = prefs.getBoolean(KEY_GUEST, false)
        if (guest) {
            val guestCookie = prefs.getString(KEY_GUEST_COOKIE, null)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_GUEST_COOKIES
            YouTube.cookie = guestCookie
            YouTube.visitorData = prefs.getString(KEY_VISITOR_DATA, null)?.takeIf { it.isNotBlank() }
            YouTube.dataSyncId = null
            YouTube.pageId = null
            YouTube.authUser = null
            YouTube.clientVersion = null
            YouTube.useLoginForBrowse = false
        } else {
            val cookie = prefs.getString(KEY_COOKIE, null)?.takeIf { it.isNotBlank() }
            val savedPageId = prefs.getString(KEY_PAGE_ID, null)?.takeIf { it.isNotBlank() }
            val savedSyncId = prefs.getString(KEY_DATA_SYNC_ID, null)?.let(::cleanDataSyncId)?.takeIf { it.isNotBlank() }
            val savedAuthUser = prefs.getString(KEY_AUTH_USER, null)?.takeIf { it.isNotBlank() }
            val savedVisitor = prefs.getString(KEY_VISITOR_DATA, null)?.takeIf { it.isNotBlank() }
            val savedClientVer = prefs.getString(KEY_CLIENT_VERSION, null)?.takeIf { it.isNotBlank() }
            YouTube.cookie = cookie
            YouTube.visitorData = savedVisitor
            YouTube.pageId = savedPageId
            YouTube.authUser = savedAuthUser ?: "0"
            YouTube.dataSyncId = savedPageId ?: savedSyncId
            YouTube.clientVersion = savedClientVer
            YouTube.useLoginForBrowse = hasGoogleAuthCookie(cookie)
            if (hasGoogleAuthCookie(cookie)) {
                YouTube.adoptSessionScope(
                    pageId = savedPageId,
                    dataSyncId = savedPageId ?: savedSyncId,
                    authUser = savedAuthUser ?: "0",
                    visitorData = savedVisitor,
                    clientVersion = savedClientVer,
                )
            }
        }
    }

    suspend fun channels(): List<YouTubeChannel> =
        if (!isSignedIn || _isGuest.value) emptyList() else YouTube.accounts().getOrElse {
            android.util.Log.w(TAG, "channel list unavailable: $it")
            emptyList()
        }

    private val _pageId = MutableStateFlow(prefs.getString(KEY_PAGE_ID, null))
    val pageId: StateFlow<String?> = _pageId.asStateFlow()

    suspend fun useChannel(channel: YouTubeChannel): Boolean {
        val previousPageId = prefs.getString(KEY_PAGE_ID, null)
        val previousSyncId = prefs.getString(KEY_DATA_SYNC_ID, null)
        val previousName = prefs.getString(KEY_NAME, null)
        val previousAvatar = prefs.getString(KEY_AVATAR, null)

        applyChannel(channel.pageId, channel.dataSyncId.orEmpty(), channel.name, channel.thumbnailUrl)
        val attempt = YouTube.accountInfo()
        val worked = attempt.isSuccess
        if (!worked) {
            android.util.Log.w(TAG, "channel refused, going back to previous: ${attempt.exceptionOrNull()?.message}")
            applyChannel(previousPageId, previousSyncId.orEmpty(), previousName.orEmpty(), previousAvatar)
        }
        return worked
    }

    private fun applyChannel(pageId: String?, dataSyncId: String, name: String, avatarUrl: String? = null) {
        val cleanSync = cleanDataSyncId(dataSyncId)
        val safeName = sanitizeName(name) ?: "YouTube Music"
        prefs.edit()
            .putString(KEY_PAGE_ID, pageId)
            .putString(KEY_DATA_SYNC_ID, cleanSync)
            .putString(KEY_NAME, safeName)
            .putString(KEY_AVATAR, avatarUrl)
            .commit()
        _pageId.value = pageId
        _accountName.value = safeName
        _accountAvatar.value = avatarUrl
        applyToInnerTube()
    }

    fun reapply() = applyToInnerTube()

    fun save(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        name: String,
        avatarUrl: String? = null,
        pageId: String? = null,
        authUser: String? = null,
        clientVersion: String? = null,
    ) {
        val cleanSync = cleanDataSyncId(dataSyncId)
        val safeName = sanitizeName(name) ?: "YouTube Music"
        val cleanAuthUser = authUser?.trim()?.takeIf { it.isNotBlank() }
        val cleanClientVer = clientVersion?.trim()?.takeIf { it.isNotBlank() }
        val editor = prefs.edit()
            .putBoolean(KEY_GUEST, false)
            .remove(KEY_GUEST_COOKIE)
            .putString(KEY_COOKIE, cookie)
            .putString(KEY_VISITOR_DATA, visitorData)
            .putString(KEY_DATA_SYNC_ID, cleanSync)
            .putString(KEY_NAME, safeName)
            .putString(KEY_AVATAR, avatarUrl)
            .putString(KEY_PAGE_ID, pageId)
        if (cleanAuthUser != null) {
            editor.putString(KEY_AUTH_USER, cleanAuthUser)
        } else {
            editor.remove(KEY_AUTH_USER)
        }
        if (cleanClientVer != null) {
            editor.putString(KEY_CLIENT_VERSION, cleanClientVer)
        } else {
            editor.remove(KEY_CLIENT_VERSION)
        }
        editor.commit()
        _isGuest.value = false
        _accountName.value = safeName
        _accountAvatar.value = avatarUrl
        _pageId.value = pageId
        _expired.value = false
        applyToInnerTube()
    }

    fun saveGuest(
        name: String = "Google Guest",
        guestCookie: String? = null,
        visitorData: String? = null,
    ) {
        val safeName = sanitizeName(name)?.takeIf { it.isNotBlank() } ?: "Google Guest"
        val existingGuestCookie = guestCookie?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_GUEST_COOKIE, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_GUEST_COOKIES
        val existingVisitor = visitorData?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_VISITOR_DATA, null)?.takeIf { it.isNotBlank() }
            ?: YouTube.visitorData?.takeIf { it.isNotBlank() }

        val editor = prefs.edit()
            .putBoolean(KEY_GUEST, true)
            .remove(KEY_COOKIE)
            .remove(KEY_DATA_SYNC_ID)
            .remove(KEY_PAGE_ID)
            .remove(KEY_AUTH_USER)
            .remove(KEY_CLIENT_VERSION)
            .remove(KEY_AVATAR)
            .putString(KEY_GUEST_COOKIE, existingGuestCookie)
            .putString(KEY_NAME, safeName)
        if (!existingVisitor.isNullOrBlank()) {
            editor.putString(KEY_VISITOR_DATA, existingVisitor)
        }
        editor.commit()
        _isGuest.value = true
        _accountName.value = safeName
        _accountAvatar.value = null
        _pageId.value = null
        _expired.value = false
        applyToInnerTube()
    }

    /**
     * Performs a real anonymous Guest login handshake with YouTube Music (`https://music.youtube.com`
     * and `sw.js_data`), capturing genuine `VISITOR_INFO1_live`, `YSC`, `PREF`, `SOCS`, and `CONSENT`
     * guest session cookies along with a live `VISITOR_DATA` token and syncing them into both
     * Android `CookieManager` and [YouTube] InnerTube headers.
     */
    suspend fun establishRealYouTubeGuestSession(
        name: String = "Google Guest",
        webViewCookie: String? = null,
        webViewVisitorData: String? = null,
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!_isGuest.value && hasGoogleAuthCookie(prefs.getString(KEY_COOKIE, null))) {
            return@withContext false
        }
        val cookiePairs = LinkedHashMap<String, String>()
        fun absorbCookieString(raw: String?) {
            if (raw.isNullOrBlank()) return
            raw.split(';').forEach { segment ->
                val trimmed = segment.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    val k = trimmed.substring(0, eq).trim()
                    val v = trimmed.substring(eq + 1).trim()
                    if (k.isNotEmpty() && v.isNotEmpty() && k !in AUTH_COOKIE_KEYS && !k.lowercase().isinCookieAttr()) {
                        cookiePairs[k] = v
                    }
                }
            }
        }

        absorbCookieString(DEFAULT_GUEST_COOKIES)
        absorbCookieString(prefs.getString(KEY_GUEST_COOKIE, null))
        absorbCookieString(webViewCookie)

        var resolvedVisitor = webViewVisitorData?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_VISITOR_DATA, null)?.takeIf { it.isNotBlank() }
            ?: YouTube.visitorData?.takeIf { it.isNotBlank() }

        runCatching {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val req = okhttp3.Request.Builder()
                .url("https://music.youtube.com/?cbrd=1")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", cookiePairs.entries.joinToString("; ") { "${it.key}=${it.value}" })
                .build()
            client.newCall(req).execute().use { resp ->
                resp.headers("Set-Cookie").forEach { setCookie ->
                    val firstPart = setCookie.substringBefore(';')
                    absorbCookieString(firstPart)
                }
                val html = resp.body?.string().orEmpty()
                if (resolvedVisitor.isNullOrBlank()) {
                    val match = VISITOR_DATA_HTML_REGEX.find(html)
                    val fromHtml = match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                    if (!fromHtml.isNullOrBlank()) {
                        resolvedVisitor = fromHtml
                    }
                }
            }
        }

        if (resolvedVisitor.isNullOrBlank()) {
            resolvedVisitor = runCatching { YouTube.visitorData().getOrNull() }.getOrNull()
        }

        // Guard again after network I/O in case the user completed a Google login in the meantime.
        if (!_isGuest.value && hasGoogleAuthCookie(prefs.getString(KEY_COOKIE, null))) {
            return@withContext false
        }

        val finalCookie = cookiePairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
            .ifBlank { DEFAULT_GUEST_COOKIES }

        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cookiePairs.forEach { (k, v) ->
                cm.setCookie("https://music.youtube.com", "$k=$v; Path=/; Domain=.youtube.com; Secure")
                cm.setCookie("https://www.youtube.com", "$k=$v; Path=/; Domain=.youtube.com; Secure")
            }
            cm.flush()
        }

        saveGuest(
            name = name,
            guestCookie = finalCookie,
            visitorData = resolvedVisitor,
        )
        !resolvedVisitor.isNullOrBlank()
    }

    suspend fun revalidate() {
        if (!isSignedIn) return
        if (_isGuest.value || prefs.getBoolean(KEY_GUEST, false)) {
            _expired.value = false
            establishRealYouTubeGuestSession(
                name = _accountName.value ?: "Google Guest",
            )
            return
        }
        // Refresh session scope from music.youtube.com (matching BitChord's ensureSessionScope)
        val scopeRefreshed = YouTube.ensureSessionScope()
        if (scopeRefreshed) {
            val editor = prefs.edit()
            var scopeSaved = false
            YouTube.visitorData?.takeIf { it.isNotBlank() }?.let {
                if (it != prefs.getString(KEY_VISITOR_DATA, null)) {
                    editor.putString(KEY_VISITOR_DATA, it)
                    scopeSaved = true
                }
            }
            YouTube.dataSyncId?.takeIf { it.isNotBlank() }?.let {
                val cleaned = cleanDataSyncId(it)
                if (cleaned.isNotBlank() && cleaned != prefs.getString(KEY_DATA_SYNC_ID, null)) {
                    editor.putString(KEY_DATA_SYNC_ID, cleaned)
                    scopeSaved = true
                }
            }
            YouTube.pageId?.takeIf { it.isNotBlank() }?.let {
                if (it != prefs.getString(KEY_PAGE_ID, null)) {
                    editor.putString(KEY_PAGE_ID, it)
                    _pageId.value = it
                    scopeSaved = true
                }
            }
            YouTube.authUser?.takeIf { it.isNotBlank() }?.let {
                if (it != prefs.getString(KEY_AUTH_USER, null)) {
                    editor.putString(KEY_AUTH_USER, it)
                    scopeSaved = true
                }
            }
            YouTube.clientVersion?.takeIf { it.isNotBlank() }?.let {
                if (it != prefs.getString(KEY_CLIENT_VERSION, null)) {
                    editor.putString(KEY_CLIENT_VERSION, it)
                    scopeSaved = true
                }
            }
            if (scopeSaved) {
                editor.apply()
                applyToInnerTube()
            }
        } else if (YouTube.visitorData.isNullOrBlank()) {
            YouTube.visitorData()
                .onSuccess { fresh ->
                    prefs.edit().putString(KEY_VISITOR_DATA, fresh).apply()
                    YouTube.visitorData = fresh
                }
        }
        YouTube.accountInfo()
            .onSuccess { info ->
                _expired.value = false
                val avatar = info.thumbnailUrl
                val editor = prefs.edit()
                var changed = false
                val cleanInfoName = sanitizeName(info.name).orEmpty()
                if (cleanInfoName.isNotBlank() && cleanInfoName != _accountName.value) {
                    editor.putString(KEY_NAME, cleanInfoName)
                    _accountName.value = cleanInfoName
                    changed = true
                }
                if (!avatar.isNullOrBlank() && avatar != _accountAvatar.value) {
                    editor.putString(KEY_AVATAR, avatar)
                    _accountAvatar.value = avatar
                    changed = true
                }
                if (_pageId.value.isNullOrBlank() || _accountAvatar.value.isNullOrBlank() || YouTube.dataSyncId.isNullOrBlank()) {
                    val selectedChannel = YouTube.accounts().getOrNull()?.let { list ->
                        list.firstOrNull { it.selected } ?: list.firstOrNull()
                    }
                    if (selectedChannel != null) {
                        val chPageId = selectedChannel.pageId.trim().takeIf { it.isNotEmpty() }
                        val chSync = cleanDataSyncId(selectedChannel.dataSyncId.orEmpty())
                        if (chPageId != null && chPageId != _pageId.value) {
                            editor.putString(KEY_PAGE_ID, chPageId)
                            _pageId.value = chPageId
                            changed = true
                        }
                        if (chSync.isNotBlank() && chSync != prefs.getString(KEY_DATA_SYNC_ID, null)) {
                            editor.putString(KEY_DATA_SYNC_ID, chSync)
                            changed = true
                        }
                        if (_accountAvatar.value.isNullOrBlank() && !selectedChannel.thumbnailUrl.isNullOrBlank()) {
                            editor.putString(KEY_AVATAR, selectedChannel.thumbnailUrl)
                            _accountAvatar.value = selectedChannel.thumbnailUrl
                            changed = true
                        }
                    }
                }
                if (changed) {
                    editor.apply()
                    applyToInnerTube()
                }
            }
            .onFailure {
                val refused = it.message.orEmpty().let { message ->
                    "401" in message || "403" in message
                }
                android.util.Log.w(TAG, "youtube session check failed (refused=$refused): $it")
                _expired.value = refused
            }
    }

    fun signOut() {
        prefs.edit().clear().commit()
        _isGuest.value = false
        _accountName.value = null
        _accountAvatar.value = null
        _pageId.value = null
        _expired.value = false
        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
        applyToInnerTube()
    }

    companion object {
        private const val FILE_NAME = "wavebits_youtube_account"
        private const val KEY_COOKIE = "cookie"
        private const val KEY_GUEST_COOKIE = "guest_cookie"
        private const val KEY_VISITOR_DATA = "visitor_data"
        private const val KEY_DATA_SYNC_ID = "data_sync_id"
        private const val KEY_NAME = "name"
        private const val KEY_AVATAR = "avatar"
        private const val KEY_PAGE_ID = "page_id"
        private const val KEY_AUTH_USER = "auth_user"
        private const val KEY_CLIENT_VERSION = "client_version"
        private const val KEY_GUEST = "guest_mode"
        private const val TAG = "WaveBitsYouTubeAccount"

        const val DEFAULT_GUEST_COOKIES =
            "CONSENT=YES+cb.20210328-17-p0.en+FX+100; SOCS=CAISEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg; PREF=f6=40000000&hl=en"

        private val AUTH_COOKIE_KEYS = setOf(
            "SAPISID",
            "__Secure-1PAPISID",
            "__Secure-3PAPISID",
            "SID",
            "__Secure-1PSID",
            "__Secure-3PSID",
            "HSID",
            "SSID",
            "APISID",
            "LOGIN_INFO",
        )

        private val VISITOR_DATA_HTML_REGEX = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")

        private fun String.isinCookieAttr(): Boolean =
            this == "path" || this == "domain" || this == "expires" || this == "max-age" ||
                this == "secure" || this == "httponly" || this == "samesite" || this == "priority"

        fun sanitizeName(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return raw.trim()
        }

        /**
         * BitChord `normalizeDataSyncId`:
         * `ytcfg.DATASYNC_ID` is formatted as `<delegatedOrPrimary>||<userId>` or `||<id>`.
         * Prefers the non-empty token after `||`, falling back to the token before `||`.
         */
        fun cleanDataSyncId(raw: String): String {
            return app.wavebits.innertube.InnerTube.normalizeDataSyncId(raw).orEmpty()
        }
    }
}
