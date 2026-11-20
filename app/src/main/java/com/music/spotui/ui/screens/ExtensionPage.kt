package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import app.wavebits.engine.R
import app.wavebits.extensions.Action
import app.wavebits.extensions.PageContent
import app.wavebits.extensions.PageNetwork
import app.wavebits.extensions.SurfaceGuard
import com.music.spotui.extensions.Extensions
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

internal fun extensionPageRoute(id: String) = "extension-page/${android.net.Uri.encode(id)}"

/**
 * An extension's own page (webview.page, critical): its HTML in a WebView the app locks down.
 * No file or content access, no navigation, no new windows, no storage, no cookies: what the page
 * loads is fetched by the app, GET over https from the hosts its network action lists, never a
 * local or Spotify address ([PageNetwork]), with a content policy on top. The only bridge is
 * `MyspotiPage.postMessage(text)`, to its own extension; answers come back as `message` events.
 * It is drawn in the app's page (dialogs, the protected ones included, are above it) and hidden
 * while a protected surface is on screen.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ExtensionPageScreen(navController: NavController, id: String) {
    val registry by Extensions.registry.collectAsState()
    val e = registry.find(id)
    val m = remember(id, e?.sha256) { Extensions.manifest(id) }
    val loaded by produceState<Result<PageContent?>?>(null, id) { value = runCatching { Extensions.page(id) } }
    val shield by SurfaceGuard.app.state.collectAsState()
    val granted = e?.granted.orEmpty()
    val hosts = m?.hosts().orEmpty()
    val network = Action.NETWORK.key in granted
    val any = Action.NETWORK_ANY in granted

    Column(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = AppText)
            }
            Column {
                Text(loaded?.getOrNull()?.title ?: e?.name ?: id, color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.ext_from, e?.name ?: id), color = AppMuted, fontSize = 12.sp, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding()) {
            val page = loaded?.getOrNull()
            when {
                loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = AppAccent)
                page == null -> Text(stringResource(R.string.ext_page_failed), color = AppMuted, modifier = Modifier.padding(20.dp))
                else -> {
                    val html = remember(page) { PageNetwork.wrap(page.html, PageNetwork.csp(hosts, network, any)) }
                    val web = remember { arrayOfNulls<WebView>(1) }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                web[0] = this
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                @Suppress("DEPRECATION")
                                settings.allowFileAccessFromFileURLs = false
                                @Suppress("DEPRECATION")
                                settings.allowUniversalAccessFromFileURLs = false
                                settings.domStorageEnabled = false
                                settings.setGeolocationEnabled(false)
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                settings.setSupportMultipleWindows(false)
                                settings.mediaPlaybackRequiresUserGesture = true
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    settings.safeBrowsingEnabled = true
                                }
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                                setBackgroundColor(android.graphics.Color.BLACK)
                                webViewClient = SandboxClient(hosts, network, any)
                                addJavascriptInterface(PageBridge(id), "MyspotiPage")
                                Extensions.attachPage(id) { json -> evaluateJavascript("window.dispatchEvent(new MessageEvent('message', { data: $json }));", null) }
                                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                            }
                        },
                        update = { wv ->
                            if (shield.isNotEmpty()) { wv.onPause(); wv.visibility = View.INVISIBLE } else { wv.onResume(); wv.visibility = View.VISIBLE }
                        },
                    )
                    DisposableEffect(id) {
                        onDispose {
                            Extensions.attachPage(id, null)
                            web[0]?.destroy()
                        }
                    }
                }
            }
        }
    }
}

/** The page's one door: a message to its own extension. */
private class PageBridge(private val id: String) {
    @JavascriptInterface
    fun postMessage(data: String) = Extensions.pageMessage(id, data)
}

/** No navigation; every load checked by [PageNetwork] and fetched here, without cookies. */
private class SandboxClient(private val hosts: List<String>, private val network: Boolean, private val any: Boolean) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (url.startsWith("data:") || url.startsWith("about:")) return null
        if (com.music.spotui.data.DataSaver.active || !PageNetwork.allows(url, request.method, hosts, network, any)) return refused()
        return runCatching {
            val targetUrl = URL(url)
            val resolvedAddresses = java.net.InetAddress.getAllByName(targetUrl.host)
            if (resolvedAddresses.isEmpty() || resolvedAddresses.any { app.wavebits.extensions.ExtensionRuntime.isPrivateAddress(it) }) {
                return refused()
            }
            val c = targetUrl.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "Myspoti-Extension-Page/1")
            val status = c.responseCode
            if (status !in 200..299) { c.disconnect(); return refused() }
            val type = c.contentType?.substringBefore(';')?.trim() ?: "application/octet-stream"
            val charset = c.contentType?.substringAfter("charset=", "")?.trim()?.ifBlank { null }
            val maxBytes = 8 * 1024 * 1024
            if (c.contentLengthLong > maxBytes) { c.disconnect(); return refused() }
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { s ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) {
                        c.disconnect()
                        return refused()
                    }
                    out.write(buf, 0, n)
                }
            }
            c.disconnect()
            val securityHeaders = mapOf(
                "Access-Control-Allow-Origin" to "null",
                "Cross-Origin-Resource-Policy" to "cross-origin",
                "X-Content-Type-Options" to "nosniff",
                "X-Frame-Options" to "DENY",
                "Referrer-Policy" to "no-referrer",
                "Cache-Control" to "no-store",
            )
            WebResourceResponse(type, charset, status, "OK", securityHeaders, ByteArrayInputStream(out.toByteArray()))
        }.getOrElse { refused() }
    }

    private fun refused() = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Refused",
        mapOf(
            "X-Content-Type-Options" to "nosniff",
            "X-Frame-Options" to "DENY",
            "Cache-Control" to "no-store",
        ),
        ByteArrayInputStream(ByteArray(0))
    )
}
