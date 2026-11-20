// WaveBits YouTube Music Sign-In, GPL-3.0.
package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.wavebits.host.android.youtube.YouTubeAccount
import app.wavebits.innertube.YouTube
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppSans
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.launch
import org.json.JSONArray

private const val TAG = "WaveBitsYTLogin"
private const val MUSIC_URL = "https://music.youtube.com"
private const val YOUTUBE_URL = "https://www.youtube.com"
private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com"

private val YOUTUBE_ALLOWED_HOSTS = listOf(
    "google.com",
    "youtube.com",
    "youtu.be",
    "gstatic.com",
    "googleapis.com",
    "googleusercontent.com",
    "gvt1.com",
)
private val GOOGLE_REGIONAL_HOST = Regex("""^(?:[a-z0-9-]+\.)*google\.[a-z]{2,3}(?:\.[a-z]{2})?$""")

private fun isAllowedYouTubeAuthUrl(rawUrl: String): Boolean {
    val parsed = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
    if (parsed.scheme?.lowercase() != "https") return false
    val host = parsed.host?.lowercase().orEmpty()
    if (host.isBlank()) return false
    return YOUTUBE_ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") } || GOOGLE_REGIONAL_HOST.matches(host)
}

private fun readYouTubeCookies(): String {
    val cm = CookieManager.getInstance()
    runCatching { cm.flush() }
    val musicCookies = cm.getCookie(MUSIC_URL).orEmpty()
    val ytCookies = cm.getCookie(YOUTUBE_URL).orEmpty()
    if (ytCookies.isBlank()) return musicCookies
    if (musicCookies.isBlank()) return ytCookies

    val map = LinkedHashMap<String, String>()
    fun parseInto(raw: String) {
        raw.split(";").forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val k = trimmed.substring(0, eq).trim()
                val v = trimmed.substring(eq + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    map[k] = v
                }
            }
        }
    }
    parseInto(ytCookies)
    parseInto(musicCookies) // music.youtube.com takes precedence
    return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

private fun hasAuthCookie(cookie: String): Boolean =
    "SAPISID=" in cookie || "__Secure-3PAPISID=" in cookie || "__Secure-1PAPISID=" in cookie

/**
 * YouTube Music WebView authentication screen.
 *
 * Directs user to Google's official sign-in page, captures authentication cookies and
 * InnerTube tokens upon landing on music.youtube.com, and registers the session with [YouTubeAccount].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginScreen(
    account: YouTubeAccount,
    onDone: (success: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var completing by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var currentHost by remember { mutableStateOf("accounts.google.com") }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }

    val close: (success: Boolean) -> Unit = { success ->
        webViewHolder[0]?.apply {
            stopLoading()
            clearFocus()
            isFocusable = false
        }
        onDone(success)
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewHolder[0]?.apply {
                stopLoading()
                webChromeClient = null
                removeAllViews()
                destroy()
            }
            webViewHolder[0] = null
        }
    }

    val clearYouTubeCaches: () -> Unit = {
        runCatching { com.music.spotui.data.preferences.clearYouTubeLibraryCache(context) }
        runCatching { com.music.spotui.data.api.ScreenCache.clearYouTubeBlocking() }
        runCatching { com.music.spotui.data.api.Api.HomeCache.clear() }
        runCatching { ContextViewModel.clearCache() }
        runCatching { com.music.spotui.data.api.ProfileCache.clearSession() }
        if (app.wavebits.host.android.WaveBitsRuntime.isStarted) {
            runCatching { app.wavebits.host.android.WaveBitsRuntime.youtube.clearSessionCaches(clearUnlikedFilter = true) }
        }
        runCatching { com.music.spotui.data.api.LibraryEdits.reloadNow() }
        Unit
    }

    fun complete(
        visitorData: String = "",
        delegatedSessionId: String = "",
        datasyncId: String = "",
        fallbackName: String = "",
        sessionIndex: String = "0",
        clientVersion: String = "",
    ) {
        if (completing) return
        val cookie = readYouTubeCookies()
        if (cookie.isBlank() || !hasAuthCookie(cookie)) {
            // Do not close the login screen prematurely; wait until auth cookies are set.
            return
        }

        completing = true
        scope.launch {
            // Give CookieManager a brief moment to flush any remaining __Secure-1PSIDTS / __Secure-3PSIDTS cookies
            kotlinx.coroutines.delay(350)
            val flushedCookie = readYouTubeCookies().ifBlank { cookie }
            var resolvedVisitor = visitorData.trim()
            val cleanPageIdFromWeb = delegatedSessionId.trim().takeIf { it.isNotBlank() }
            var resolvedSync = cleanPageIdFromWeb
                ?: YouTubeAccount.cleanDataSyncId(datasyncId.trim())
            var resolvedAuthUser = sessionIndex.trim().ifBlank { "0" }
            var resolvedClientVersion = clientVersion.trim().takeIf { it.isNotBlank() }

            YouTube.cookie = flushedCookie
            YouTube.visitorData = resolvedVisitor.takeIf { it.isNotBlank() }
            YouTube.pageId = cleanPageIdFromWeb
            YouTube.dataSyncId = resolvedSync.takeIf { it.isNotBlank() }
            YouTube.authUser = resolvedAuthUser
            YouTube.clientVersion = resolvedClientVersion
            YouTube.useLoginForBrowse = true
            YouTube.adoptSessionScope(
                pageId = cleanPageIdFromWeb,
                dataSyncId = resolvedSync,
                authUser = resolvedAuthUser,
                visitorData = resolvedVisitor,
                clientVersion = resolvedClientVersion,
            )

            // Ensure session scope from music.youtube.com if WebView ytcfg did not populate visitorData or dataSyncId yet
            if (resolvedVisitor.isBlank() || resolvedSync.isBlank()) {
                val fetched = YouTube.ensureSessionScope(force = true)
                if (fetched) {
                    YouTube.visitorData?.takeIf { it.isNotBlank() }?.let { resolvedVisitor = it }
                    YouTube.dataSyncId?.takeIf { it.isNotBlank() }?.let { resolvedSync = it }
                    YouTube.authUser?.takeIf { it.isNotBlank() }?.let { resolvedAuthUser = it }
                    YouTube.clientVersion?.takeIf { it.isNotBlank() }?.let { resolvedClientVersion = it }
                }
            }

            if (resolvedVisitor.isBlank()) {
                YouTube.visitorData().getOrNull()?.takeIf { it.isNotBlank() }?.let { fresh ->
                    resolvedVisitor = fresh
                    YouTube.visitorData = fresh
                }
            }

            fun keep(
                name: String,
                avatarUrl: String? = null,
                syncId: String = "",
                pageId: String? = null,
            ) {
                webViewHolder[0]?.apply {
                    stopLoading()
                    clearHistory()
                    clearFocus()
                    isFocusable = false
                }
                val finalName = name.takeIf { it.isNotBlank() }
                    ?: fallbackName.takeIf { it.isNotBlank() }
                    ?: "YouTube Music"
                account.save(
                    cookie = flushedCookie,
                    visitorData = resolvedVisitor,
                    dataSyncId = syncId,
                    name = finalName,
                    avatarUrl = avatarUrl,
                    pageId = pageId,
                    authUser = resolvedAuthUser,
                    clientVersion = resolvedClientVersion,
                )
                com.music.spotui.ui.platform.PlatformManager.saveYouTubeSession(
                    context = context,
                    name = finalName,
                    avatar = avatarUrl,
                )
                clearYouTubeCaches()
                close(true)
            }

            val info = YouTube.accountInfo().getOrNull()
            val channels = YouTube.accounts().getOrNull().orEmpty()
            val selectedChannel = channels.firstOrNull { it.selected } ?: channels.firstOrNull()
            val resolvedPageId = selectedChannel?.pageId?.trim()?.takeIf { it.isNotEmpty() }
                ?: cleanPageIdFromWeb
                ?: YouTube.pageId?.trim()?.takeIf { it.isNotEmpty() }
            val resolvedChannelSync = resolvedPageId
                ?: selectedChannel?.dataSyncId?.let(YouTubeAccount::cleanDataSyncId)?.takeIf { it.isNotEmpty() }
                ?: resolvedSync.ifEmpty { YouTube.dataSyncId.orEmpty() }
            val resolvedName = info?.name?.takeIf { it.isNotBlank() }
                ?: selectedChannel?.name?.takeIf { it.isNotBlank() }
                ?: fallbackName.ifBlank { "YouTube Music" }
            val resolvedAvatar = info?.thumbnailUrl?.takeIf { it.isNotBlank() }
                ?: selectedChannel?.thumbnailUrl?.takeIf { it.isNotBlank() }

            keep(
                name = resolvedName,
                avatarUrl = resolvedAvatar,
                syncId = resolvedChannelSync,
                pageId = resolvedPageId,
            )
        }
    }

    BackHandler {
        val wv = webViewHolder[0]
        if (wv?.canGoBack() == true) {
            wv.goBack()
        } else {
            close(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .systemBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { close(false) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(tone(0xFF222222))
                        .testTag("youtube_login_close_button"),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = AppText,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sign in with YouTube Music",
                        color = AppText,
                        fontFamily = AppSans,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Color(0xFFFF4E45),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = currentHost,
                            color = AppText.copy(alpha = 0.60f),
                            fontFamily = AppSans,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // Progress bar
            val animatedProgress by animateFloatAsState(
                targetValue = progress / 100f,
                label = "loginProgress",
            )
            val barAlpha by animateFloatAsState(
                targetValue = if (progress in 1..99) 1f else 0f,
                label = "loginProgressAlpha",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .padding(horizontal = 16.dp)
                    .graphicsLayer { alpha = barAlpha },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFFFF0033)),
                )
            }

            // WebView container
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().testTag("youtube_webview"),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            if (!app.wavebits.host.android.WebPlayerHost.hasHardwareRenderNode) {
                                setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                            }
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    url?.let { Uri.parse(it).host }?.let { currentHost = it }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    val reqUrl = request.url
                                    val rawUrl = reqUrl.toString()
                                    if (!request.isForMainFrame && rawUrl.equals("about:blank", ignoreCase = true)) {
                                        return false
                                    }
                                    val scheme = reqUrl.scheme?.lowercase()
                                    if (scheme == "https") {
                                        return !isAllowedYouTubeAuthUrl(rawUrl)
                                    }

                                    if (scheme == "intent") {
                                        val fallback = runCatching {
                                            android.content.Intent.parseUri(
                                                reqUrl.toString(),
                                                android.content.Intent.URI_INTENT_SCHEME,
                                            ).getStringExtra("browser_fallback_url")
                                        }.getOrNull()
                                        if (!fallback.isNullOrBlank() && isAllowedYouTubeAuthUrl(fallback)) {
                                            view.loadUrl(fallback)
                                            return true
                                        }
                                    }

                                    if (hasAuthCookie(readYouTubeCookies())) {
                                        val host = reqUrl.host?.lowercase().orEmpty()
                                        if (host == "music.youtube.com") {
                                            finishIfSignedIn(view, reqUrl.toString())
                                        } else {
                                            view.loadUrl(MUSIC_URL)
                                        }
                                    } else if (reqUrl.toString().contains("youtube.com")) {
                                        view.loadUrl(MUSIC_URL)
                                    }
                                    return true
                                }

                                override fun onPageCommitVisible(view: WebView, url: String?) {
                                    // Do not stopLoading in onPageCommitVisible — allow music.youtube.com
                                    // to finish loading so __Secure-1PSIDTS/__Secure-3PSIDTS and ytcfg are set.
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    finishIfSignedIn(view, url, attempt = 0)
                                }

                                private fun finishIfSignedIn(view: WebView, url: String?, attempt: Int = 0) {
                                    if (completing) return
                                    val cookie = readYouTubeCookies()
                                    if (!hasAuthCookie(cookie)) return

                                    val host = url?.let { runCatching { Uri.parse(it).host?.lowercase() }.getOrNull() }.orEmpty()
                                    val onMusic = host == "music.youtube.com"
                                    if (!onMusic) {
                                        // Only redirect when Google finishes sign-in on youtube.com; do not interrupt accounts.google.com flows
                                        if (host == "www.youtube.com" || host == "m.youtube.com" || host == "youtube.com") {
                                            view.loadUrl(MUSIC_URL)
                                        }
                                        return
                                    }

                                    view.evaluateJavascript(
                                        "(function(){try{" +
                                            "var c=window.ytcfg;" +
                                            "var g=c&&c.get?function(k){return c.get(k);}:function(k){return c&&c.data_?c.data_[k]:null;};" +
                                            "var li=String(g('LOGGED_IN')===true);" +
                                            "var vd=String(g('VISITOR_DATA')||'');" +
                                            "var del=String(g('DELEGATED_SESSION_ID')||'');" +
                                            "var ds=String(g('DATASYNC_ID')||'');" +
                                            "var nm=String(g('USER_DISPLAY_NAME')||'');" +
                                            "var si=String(g('SESSION_INDEX')||'0');" +
                                            "var cv=String(g('INNERTUBE_CLIENT_VERSION')||'');" +
                                            "return [li,vd,del,ds,nm,si,cv];" +
                                            "}catch(e){return ['false','','','','','0',''];}})()",
                                    ) { result ->
                                        val ids = runCatching { JSONArray(result) }.getOrNull()
                                        val loggedIn = ids?.optString(0) == "true"
                                        val vd = ids?.optString(1).orEmpty()
                                        val del = ids?.optString(2).orEmpty()
                                        val ds = ids?.optString(3).orEmpty()
                                        val nm = ids?.optString(4).orEmpty()
                                        val si = ids?.optString(5).orEmpty().ifBlank { "0" }
                                        val cv = ids?.optString(6).orEmpty()

                                        if (!loggedIn && vd.isBlank() && ds.isBlank() && attempt < 8) {
                                            view.postDelayed({ finishIfSignedIn(view, view.url ?: url, attempt + 1) }, 350L)
                                            return@evaluateJavascript
                                        }

                                        complete(
                                            visitorData = vd,
                                            delegatedSessionId = del,
                                            datasyncId = ds,
                                            fallbackName = nm,
                                            sessionIndex = si,
                                            clientVersion = cv,
                                        )
                                    }
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }
                            }

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            @Suppress("DEPRECATION")
                            settings.allowFileAccessFromFileURLs = false
                            @Suppress("DEPRECATION")
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                settings.safeBrowsingEnabled = true
                            }
                            settings.setGeolocationEnabled(false)
                            settings.setSupportMultipleWindows(false)
                            settings.userAgentString = settings.userAgentString
                                .replace("; wv)", ")")
                                .replace("Version/4.0 ", "")

                            webViewHolder[0] = this
                            loadUrl(SIGN_IN_URL)
                        }
                    },
                )

                // Loading overlay once signed in
                androidx.compose.animation.AnimatedVisibility(
                    visible = completing,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF121212).copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = Color(0xFFFF0033),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Connecting your YouTube Music account...",
                                color = AppText,
                                fontFamily = AppSans,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }

            // Bottom Need help button + security note
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { showHelpDialog = true },
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        contentColor = AppText,
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = 0.22f),
                    ),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("youtube_login_need_help_button"),
                ) {
                    Text(
                        text = "Need help?",
                        fontFamily = AppSans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = AppText.copy(alpha = 0.55f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Your credentials go straight to Google. WaveBits only saves your session cookie.",
                        color = AppText.copy(alpha = 0.55f),
                        fontFamily = AppSans,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        if (showHelpDialog) {
            LoginHelpDialog(onDismiss = { showHelpDialog = false })
        }
    }
}

@Composable
fun LoginHelpDialog(
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF18181C),
        titleContentColor = AppText,
        textContentColor = AppText,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "Login Help",
                fontFamily = AppSans,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = AppText,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .testTag("login_help_dialog_content"),
            ) {
                Text(
                    text = "How to login on YT Music ?",
                    fontFamily = AppSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFFFF4E45),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "1. Create a New Gmail or Login your existing Account\n" +
                        "2. After login press continue or I agree\n" +
                        "3. Press on Sing Up button on YT music .",
                    fontFamily = AppSans,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = AppText.copy(alpha = 0.90f),
                )

                Spacer(Modifier.height(18.dp))

                Text(
                    text = "How to login on Spotify ?",
                    fontFamily = AppSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF1ED760),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "1. Enter Gmail or login with phone number of the existing Spotify account\n" +
                        "2. Get Otp and fill the OTP",
                    fontFamily = AppSans,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = AppText.copy(alpha = 0.90f),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("login_help_dialog_close"),
            ) {
                Text(
                    text = "Got it",
                    fontFamily = AppSans,
                    fontWeight = FontWeight.Bold,
                    color = AppText,
                )
            }
        },
    )
}
