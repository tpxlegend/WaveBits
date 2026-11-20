package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Platform account pages (Spotify, YouTube Music, JioSaavn) in a browser that shares the
 * app's sign-in: WebViews of one app share cookies, so no second login.
 */
class AccountBrowser : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val defaultUrl = accountUrlForCurrentPlatform()
        val rawUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: defaultUrl

        val isJioSaavn = rawUrl == JIOSAAVN_ACCOUNT_URL ||
            app.wavebits.host.android.WaveBitsRuntime.backendMode.value == app.wavebits.host.android.BackendMode.JIOSAAVN
        if (isJioSaavn) {
            setContent {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black,
                ) {
                    com.music.spotui.ui.components.JioSaavnProfileEditContent(
                        onDismiss = { finish() },
                        onSaved = { finish() },
                    )
                }
            }
            return
        }

        web = WebView(this).apply {
            if (!app.wavebits.host.android.WebPlayerHost.hasHardwareRenderNode) {
                setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setGeolocationEnabled(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val raw = request.url.toString()
                    if (!request.isForMainFrame && raw == "about:blank") return false
                    val scheme = request.url.scheme?.lowercase()
                    val host = request.url.host?.lowercase().orEmpty()
                    val allowed = scheme == "https" && isAllowedAccountHost(host)
                    return !allowed
                }
            }
        }
        val safeUrl = rawUrl.toHttpUrlOrNull()?.takeIf {
            it.scheme == "https" && isAllowedAccountHost(it.host.lowercase())
        }?.toString() ?: defaultUrl
        val language = com.music.spotui.data.AppLanguage.current(this).ifEmpty { null }
            ?.let { java.util.Locale.forLanguageTag(it).language } ?: resources.configuration.locales[0].language
        lifecycleScope.launch {
            web.loadUrl(withContext(Dispatchers.IO) { runCatching { localized(safeUrl, language) }.getOrNull() } ?: safeUrl)
        }
        // Android draws apps edge to edge: the page sits in a frame kept out from under the
        // status and navigation bars (a WebView's own padding does not move its content).
        val frame = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK); addView(web) }
        setContentView(frame)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else finish() }
        })
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private val http by lazy {
            OkHttpClient.Builder().followRedirects(false).callTimeout(6, TimeUnit.SECONDS).build()
        }
        private val MARKET = Regex("^/([a-z]{2})(?:-[a-z]{2})?/")
        private val ALLOWED_DOMAINS = listOf(
            "spotify.com",
            "scdn.co",
            "youtube.com",
            "youtu.be",
            "google.com",
            "gstatic.com",
            "googleapis.com",
            "googleusercontent.com",
            "gvt1.com",
            "jiosaavn.com",
            "saavn.com",
            "saavncdn.com",
        )
        private val GOOGLE_REGIONAL_HOST = Regex("""^(?:[a-z0-9-]+\.)*google\.[a-z]{2,3}(?:\.[a-z]{2})?$""")

        private fun isAllowedAccountHost(host: String): Boolean {
            if (host.isBlank()) return false
            return ALLOWED_DOMAINS.any { host == it || host.endsWith(".$it") } || GOOGLE_REGIONAL_HOST.matches(host)
        }

        /**
         * www.spotify.com picks the page language from the country it sees ("/ch-de/"), not
         * from the app: the same page in the app's language ("/ch-en/") when Spotify has it.
         */
        private fun localized(url: String, language: String): String? {
            val target = url.toHttpUrlOrNull() ?: return null
            if (target.host != "www.spotify.com") return null
            val path = target.encodedPath
            val market = MARKET.find(path)?.groupValues?.get(1) ?: run {
                val probe = Request.Builder().url("https://www.spotify.com/premium/").head().build()
                val where = http.newCall(probe).execute().use { it.header("Location") } ?: return null
                MARKET.find(where.toHttpUrlOrNull()?.encodedPath ?: return null)?.groupValues?.get(1) ?: return null
            }
            val prefix = "/$market-$language/"
            // Spotify answers 200 for the combinations it has, and redirects the others.
            val check = Request.Builder().url("https://www.spotify.com${prefix}premium/").head().build()
            if (http.newCall(check).execute().use { it.code } != 200) return null
            val rest = MARKET.find(path)?.let { path.substring(it.range.last + 1) } ?: path.removePrefix("/")
            return target.newBuilder().encodedPath(prefix + rest).build().toString()
        }

        const val ACCOUNT_URL = "https://www.spotify.com/account/overview/"
        const val YOUTUBE_ACCOUNT_URL = "https://www.youtube.com/account"
        const val JIOSAAVN_ACCOUNT_URL = "https://www.jiosaavn.com/me"
        private const val EXTRA_URL = "url"

        fun accountUrlForPlatform(mode: app.wavebits.host.android.BackendMode): String = when (mode) {
            app.wavebits.host.android.BackendMode.SPOTIFY -> ACCOUNT_URL
            app.wavebits.host.android.BackendMode.YOUTUBE -> YOUTUBE_ACCOUNT_URL
            app.wavebits.host.android.BackendMode.JIOSAAVN -> JIOSAAVN_ACCOUNT_URL
        }

        fun accountUrlForCurrentPlatform(): String =
            accountUrlForPlatform(app.wavebits.host.android.WaveBitsRuntime.backendMode.value)

        fun accountPageTitle(mode: app.wavebits.host.android.BackendMode, spotifyDefault: String): String = when (mode) {
            app.wavebits.host.android.BackendMode.SPOTIFY -> spotifyDefault
            app.wavebits.host.android.BackendMode.YOUTUBE -> "YouTube account"
            app.wavebits.host.android.BackendMode.JIOSAAVN -> "JioSaavn account"
        }

        fun accountPageDetail(mode: app.wavebits.host.android.BackendMode, spotifyDefault: String): String = when (mode) {
            app.wavebits.host.android.BackendMode.SPOTIFY -> spotifyDefault
            app.wavebits.host.android.BackendMode.YOUTUBE -> "Plan, privacy and devices, on youtube.com"
            app.wavebits.host.android.BackendMode.JIOSAAVN -> "Plan, privacy and devices, on jiosaavn.com"
        }

        fun open(context: Context, url: String = accountUrlForCurrentPlatform()) {
            val resolvedUrl = if (url == ACCOUNT_URL) accountUrlForCurrentPlatform() else url
            context.startActivity(Intent(context, AccountBrowser::class.java)
                .putExtra(EXTRA_URL, resolvedUrl)
                .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }
}
