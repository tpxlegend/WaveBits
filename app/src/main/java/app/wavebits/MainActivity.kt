// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.wavebits

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.wavebits.host.android.WaveBitsRuntime
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.spotui.App
import com.music.spotui.di.AutoBrowser
import com.music.spotui.ui.screens.WelcomeScreen
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppSans
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.SpotuiTheme
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Logcat tag, the host's own: playback and session land in one stream (`adb logcat -s Myspoti`). */
private const val LOG = "Myspoti"

/**
 * How long the player page is given to prove the session (a non-anonymous token) once it comes back.
 * The sign-in page stays in front while this runs — the address alone is never taken for a session —
 * and only a real token hands the screen to the player (see [MainActivity.watchLoginReturn]).
 */
private const val SESSION_WAIT_MS = 30_000L

/** The most a returning page may take to get its bridge ready before the wait gives up anyway. */
private const val SESSION_LOAD_MAX_MS = 60_000L

/**
 * The app's only screen host: the Compose UI, with the Web Player page attached behind it.
 * The page comes to the front only to sign in, on Spotify's own login pages.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private var signedIn by mutableStateOf(false)
    private var showingLogin by mutableStateOf(false)
    private var showingYouTubeLogin by mutableStateOf(false)
    /** Back from Spotify's login, waiting for the session: a native loading screen, not the web page. */
    private var connecting by mutableStateOf(false)
    /** A pending wait for the session to prove itself after the login page came back. */
    private var loginWait: kotlinx.coroutines.Job? = null
    /** The one-time setup screen (permissions, battery) after signing in. */
    private var setupNeeded by mutableStateOf(false)
    /** In the picture-in-picture window: only the music video is drawn. */
    private var inPip by mutableStateOf(false)
    /** Debug builds: `am start ... --ez showLook true` opens the look step alone, signed in or not. */
    private var showLook by mutableStateOf(false)
    /** The "new look" popup was already shown (or the onboarding look step covered it). */
    private var lookOffered by mutableStateOf(true)
    /** Animated starting animation shown every time the app starts. */
    private var showingStartupAnimation by mutableStateOf(true)
    /** Remembers the active backend before opening Spotify login during a platform switch. */
    private var previousBackendBeforeSpotifyLogin: app.wavebits.host.android.BackendMode? = null

    private val prefs get() = getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)


    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                splashScreen.setOnExitAnimationListener { splashScreenView ->
                    splashScreenView.remove()
                }
            }
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enableHighRefreshRateDisplay()
        // The user's rotation choice (portrait by default); changes are applied from the UI below.
        requestedOrientation = com.music.spotui.data.preferences.OrientationPref.requested()
        com.music.spotui.data.preferences.AppIconManager.init(this)
        val activeMode = com.music.spotui.ui.platform.PlatformManager.getSavedBackendMode(this)
        signedIn = com.music.spotui.ui.platform.PlatformManager.isPlatformLoggedIn(
            this,
            activeMode,
        )
        showingStartupAnimation = true
        setupNeeded = com.music.spotui.ui.screens.Setup.needed(this)
        lookOffered = com.music.spotui.data.preferences.LookPref.isOffered(this)
        root = FrameLayout(this)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingLogin) {
                    val canBack = runCatching { WaveBitsRuntime.host.view.canGoBack() }.getOrDefault(false)
                    if (canBack) {
                        runCatching { WaveBitsRuntime.host.view.goBack() }
                    } else {
                        hideLogin()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        val compose = ComposeView(this).apply {
            setContent {
                SpotuiTheme { com.music.spotui.ui.look.ProvideLook {
                    val orientation = com.music.spotui.data.preferences.OrientationPref.mode
                    val activeAppIconId = com.music.spotui.data.preferences.AppIconManager.selectedIconId
                    val currentBackend = WaveBitsRuntime.backendMode.collectAsState().value
                    val switchVersion = com.music.spotui.ui.platform.PlatformManager.switchVersion.collectAsState().value
                    androidx.compose.runtime.LaunchedEffect(orientation) {
                        requestedOrientation = com.music.spotui.data.preferences.OrientationPref.requested()
                    }
                    androidx.compose.runtime.LaunchedEffect(activeAppIconId) {
                        com.music.spotui.data.preferences.AppIconManager.applyTaskDescription(this@MainActivity)
                    }
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        androidx.compose.animation.Crossfade(
                            targetState = showingStartupAnimation && !inPip,
                            animationSpec = androidx.compose.animation.core.tween(durationMillis = 420),
                            label = "startupToMainCrossfade",
                        ) { isStarting ->
                            if (isStarting) {
                                com.music.spotui.ui.screens.StartupAnimationScreen(
                                    onFinished = { showingStartupAnimation = false },
                                )
                            } else {
                                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                                    when {
                                        showingLogin -> SpotifyLoginScreen(
                                            root = root,
                                            connecting = connecting,
                                            onClose = { hideLogin() },
                                        )
                                        showingYouTubeLogin -> com.music.spotui.ui.screens.YouTubeLoginScreen(
                                            account = WaveBitsRuntime.youtubeAccount,
                                            onDone = { success ->
                                                showingYouTubeLogin = false
                                                if (success) {
                                                    enterYouTubeMode()
                                                }
                                            },
                                        )
                                        !signedIn && connecting -> ConnectingScreen()
                                        !signedIn -> WelcomeScreen(
                                            onLogin = { showLogin() },
                                            onYouTubeLogin = { showingYouTubeLogin = true },
                                            onYouTubeGuestLogin = { enterYouTubeMode() },
                                            onJioSaavnLogin = {
                                                com.music.spotui.ui.platform.PlatformManager.activatePlatform(
                                                    this@MainActivity,
                                                    app.wavebits.host.android.BackendMode.JIOSAAVN,
                                                )
                                                setupNeeded = com.music.spotui.ui.screens.Setup.needed(this@MainActivity)
                                                signedIn = true
                                            },
                                        )
                                        showLook -> com.music.spotui.ui.look.LookStep(onNext = {
                                            com.music.spotui.data.preferences.LookPref.setChosen(this@MainActivity); showLook = false
                                            setupNeeded = com.music.spotui.ui.screens.Setup.needed(this@MainActivity)
                                        })
                                        setupNeeded -> com.music.spotui.ui.screens.SetupScreen(
                                            onDone = { setupNeeded = false },
                                        )
                                        else -> androidx.compose.animation.AnimatedContent(
                                            targetState = currentBackend to switchVersion,
                                            transitionSpec = {
                                                (androidx.compose.animation.fadeIn(
                                                    animationSpec = androidx.compose.animation.core.tween(240),
                                                ) + androidx.compose.animation.scaleIn(
                                                    initialScale = 0.97f,
                                                    animationSpec = androidx.compose.animation.core.tween(240),
                                                )) togetherWith androidx.compose.animation.fadeOut(
                                                    animationSpec = androidx.compose.animation.core.tween(160),
                                                )
                                            },
                                            label = "platformSwitchContent",
                                        ) { (mode, version) ->
                                            androidx.compose.runtime.key(mode, version) {
                                                App()
                                            }
                                        }
                                    }

                                    // Existing users, once, right after the update: the new look, live.
                                    if (signedIn && !setupNeeded && !showLook && !showingLogin && !inPip && !lookOffered) {
                                        androidx.compose.runtime.LaunchedEffect(Unit) {
                                            com.music.spotui.data.preferences.LookPref.setOffered(this@MainActivity)
                                            lookOffered = true
                                        }
                                    }
                                    // "Ask each time": the download quality question, above every screen.
                                    com.music.spotui.ui.components.DownloadAskHost()
                                    // An extension about to change the Spotify account waits for the user's yes.
                                    com.music.spotui.extensions.ExtensionConfirmHost()
                                    // An extension's sign-in page, in a web view of its own.
                                    com.music.spotui.extensions.ExtensionAuthHost()
                                    // Dedicated Platform Switch Transition Animation Overlay
                                    if (!inPip) {
                                        com.music.spotui.ui.components.PlatformSwitchAnimationOverlay()
                                    }
                                }
                            }
                        }
                        // Picture-in-picture: the clip alone, over the app
                        if (inPip) com.music.spotui.ui.components.PipVideo()
                    }
                } }
            }
        }
        root.addView(compose, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // Haptics (buttons, likes, the debug icon) go through this window.
        com.music.spotui.di.Haptics.attach(compose)
        // Attach the hidden Spotify Web Player view only when signed into Spotify mode.
        runCatching {
            if (WaveBitsRuntime.isStarted && activeMode == app.wavebits.host.android.BackendMode.SPOTIFY && signedIn) {
                WaveBitsRuntime.host.attach(root, visible = false)
            }
        }
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        showLook = debuggable && intent.getBooleanExtra("showLook", false)
        // Debug builds: `am start ... --ez resetLookOffer true` shows the new-look popup again.
        if (debuggable && intent.getBooleanExtra("resetLookOffer", false)) {
            com.music.spotui.data.preferences.LookPref.clearOffered(this); lookOffered = false
        }
        watchSession()
        watchLoginReturn()
        watchLogout()
        watchPlatformLoginRequests()
        // Only on a fresh start: on recreation (theme, locale, process restore)
        // the same intent would play its link again. New links arrive in onNewIntent.
        if (savedInstanceState == null) handle(intent)
    }

    /** Selects the highest refresh rate mode (90Hz / 120Hz / 144Hz) matching the active display resolution. */
    private fun enableHighRefreshRateDisplay() {
        if (app.wavebits.power.EnergySettings.isLowEndDevice) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    this.display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                } ?: return
                val currentMode = display.mode
                val bestMode = display.supportedModes
                    .filter {
                        it.physicalWidth == currentMode.physicalWidth &&
                            it.physicalHeight == currentMode.physicalHeight
                    }
                    .maxByOrNull { it.refreshRate }
                if (bestMode != null && bestMode.modeId != currentMode.modeId) {
                    val params = window.attributes
                    params.preferredDisplayModeId = bestMode.modeId
                    window.attributes = params
                }
            }
        }
    }

    private fun watchPlatformLoginRequests() {
        lifecycleScope.launch {
            com.music.spotui.ui.platform.PlatformManager.loginRequest.collect { target ->
                when (target) {
                    app.wavebits.host.android.BackendMode.SPOTIFY -> {
                        if (WaveBitsRuntime.backendMode.value != app.wavebits.host.android.BackendMode.SPOTIFY) {
                            previousBackendBeforeSpotifyLogin = WaveBitsRuntime.backendMode.value
                        }
                        showLogin()
                    }
                    app.wavebits.host.android.BackendMode.YOUTUBE -> {
                        showingYouTubeLogin = true
                    }
                    app.wavebits.host.android.BackendMode.JIOSAAVN -> {
                        com.music.spotui.ui.platform.PlatformManager.activatePlatform(
                            this@MainActivity,
                            app.wavebits.host.android.BackendMode.JIOSAAVN,
                        )
                        setupNeeded = com.music.spotui.ui.screens.Setup.needed(this@MainActivity)
                        signedIn = true
                    }
                }
            }
        }
    }

    // Leaving the app (Home, recents) while a music video plays: it goes on in a small window.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val mv = com.music.spotui.video.MusicVideoController
        if (!com.music.spotui.data.preferences.MusicVideoPref.on || !mv.on.value || mv.player == null) return
        if (!com.music.spotui.di.SongPlayer.isPlaying() || isInPictureInPictureMode) return
        val ratio = mv.aspect.floatValue.coerceIn(0.5f, 2.3f)
        runCatching {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational((ratio * 1000).toInt(), 1000))
                    .build(),
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        com.music.spotui.video.MusicVideoController.inPip.value = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // The clip player follows the app on screen: paused with its side jobs in the background.
    override fun onStart() {
        runCatching { app.wavebits.host.android.MainThreadWatch.start() }
        super.onStart()
        runCatching { com.music.spotui.video.MusicVideoController.onForeground() }
        // Asked for over adb (TestControlReceiver's "screenon"): One UI ignores the huge
        // `screen_off_timeout`, so a long unattended run holds the screen on from in here.
        if (runCatching { prefs.getBoolean("keep_screen_on", false) }.getOrDefault(false)) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        super.onStop()
        runCatching { app.wavebits.host.android.MainThreadWatch.stop() }
        if (!isChangingConfigurations) runCatching { com.music.spotui.video.MusicVideoController.onBackground() }
    }

    override fun onDestroy() {
        // The process-wide WebView lives in this activity's root while attached:
        // detach it, or view.parent -> root keeps the destroyed activity (and its
        // whole view tree) alive for the rest of the playback session.
        runCatching { WaveBitsRuntime.host.detach() }
        super.onDestroy()
    }

    /** Spotify's login page, in the YouTube Music-style login card, until the Web Player comes back signed in. */
    private fun showLogin() {
        WaveBitsRuntime.setSpotifyMode(this, loadPlayer = false)
        showingLogin = true
        if (WaveBitsRuntime.isStarted) runCatching { WaveBitsRuntime.host.openLogin() }
    }

    /** YouTube Music Mode: activates YouTube Music API and saves YouTube login data without overwriting Guest or Spotify. */
    private fun enterYouTubeMode() {
        val accountName = WaveBitsRuntime.youtubeAccount.accountName.value ?: "YouTube Music"
        val accountAvatar = WaveBitsRuntime.youtubeAccount.accountAvatar.value
        runCatching { com.music.spotui.data.preferences.clearYouTubeLibraryCache(this) }
        runCatching { com.music.spotui.data.api.ScreenCache.clearYouTubeBlocking() }
        runCatching { com.music.spotui.data.api.Api.HomeCache.clear() }
        if (WaveBitsRuntime.isStarted) {
            runCatching { WaveBitsRuntime.youtube.clearSessionCaches(clearUnlikedFilter = true) }
        }
        com.music.spotui.ui.platform.PlatformManager.saveYouTubeSession(this, accountName, accountAvatar)
        com.music.spotui.data.preferences.LookPref.setChosen(this)
        com.music.spotui.data.preferences.LookPref.setOffered(this)
        com.music.spotui.ui.platform.PlatformManager.activatePlatform(
            this,
            app.wavebits.host.android.BackendMode.YOUTUBE,
        )
        runCatching { com.music.spotui.data.api.LibraryEdits.reloadNow() }
        if (WaveBitsRuntime.isStarted) {
            runCatching { WaveBitsRuntime.youtube.notifyLibraryChanged() }
        }
        setupNeeded = com.music.spotui.ui.screens.Setup.needed(this)
        showingLogin = false
        showingYouTubeLogin = false
        connecting = false
        runCatching { WaveBitsRuntime.host.attach(root, visible = false) }
        signedIn = true
    }

    private fun hideLogin() {
        showingLogin = false
        connecting = false
        runCatching { WaveBitsRuntime.host.attach(root, visible = false) }
        val prev = previousBackendBeforeSpotifyLogin
        val spotifyNowSignedIn = runCatching {
            !WaveBitsRuntime.host.loginRequired.value && WaveBitsRuntime.spotify.signedInHint() == true
        }.getOrDefault(false) || com.music.spotui.ui.platform.PlatformManager.isSpotifyLoggedIn(this)
        if (!spotifyNowSignedIn && prev != null && prev != app.wavebits.host.android.BackendMode.SPOTIFY) {
            previousBackendBeforeSpotifyLogin = null
            if (com.music.spotui.ui.platform.PlatformManager.isPlatformLoggedIn(this, prev)) {
                com.music.spotui.ui.platform.PlatformManager.activatePlatform(this, prev)
                signedIn = true
            }
        }
    }

    /** Logout: leave the app's screens for the welcome or login screen. */
    private fun watchLogout() {
        lifecycleScope.launch {
            com.music.spotui.di.SessionActions.signedOut.collect { out ->
                if (out && signedIn) {
                    signedIn = false
                    showingLogin = false
                    showingYouTubeLogin = false
                    if (WaveBitsRuntime.isStarted) runCatching { WaveBitsRuntime.host.attach(root, visible = false) }
                }
            }
        }
    }

    /**
     * Spotify sent the user back to the player: the page is hidden now, the session follows.
     *
     * The address is not a session. The page comes back to open.spotify.com while its token is
     * still anonymous when the login never finished (the e-mail step that hangs — the reported
     * "stuck on the e-mail screen"), and hiding the page on that alone left a spinner that never
     * stopped. The return is bounded: no token after [SESSION_WAIT_MS], the login page comes back.
     */
    private fun watchLoginReturn() {
        lifecycleScope.launch {
            while (!WaveBitsRuntime.isStarted) kotlinx.coroutines.delay(200)
            WaveBitsRuntime.host.url.collect { url ->
                val backOnPlayer = url.startsWith("https://open.spotify.com") && !url.contains("/login")
                if (!(showingLogin && backOnPlayer)) return@collect
                // Spotify's desktop player is not shown while the session is confirmed: the app's
                // waiting screen is, behind the page made transparent (reported: it flashed).
                connecting = true
                WaveBitsRuntime.host.veil(true)
                // The address is not a session — and it comes back to open.spotify.com as soon as the
                // user leaves the app to read the sign-in e-mail. Hiding the page here showed an
                // anonymous player: an app that looked signed in with nothing playable (reported).
                // The login page therefore stays in FRONT until a real token exists.
                loginWait?.cancel()
                loginWait = launch {
                    var waited = 0L
                    var elapsed = 0L
                    while (waited < SESSION_WAIT_MS && elapsed < SESSION_LOAD_MAX_MS) {
                        if (runCatching { WaveBitsRuntime.isSignedIn() }.getOrDefault(false)) {
                            android.util.Log.d(LOG, "session established: player shown")
                            previousBackendBeforeSpotifyLogin = null
                            com.music.spotui.ui.platform.PlatformManager.saveSpotifySession(this@MainActivity)
                            com.music.spotui.ui.platform.PlatformManager.activatePlatform(
                                this@MainActivity,
                                app.wavebits.host.android.BackendMode.SPOTIFY,
                            )
                            signedIn = true
                            hideLogin()
                            return@launch
                        }
                        // The countdown only runs while the user is looking at the app: away to read
                        // the e-mail, the sign-in page must not be reset under them.
                        // Nor while the player is still loading: it answers nothing before its bridge is
                        // ready, which takes 10 s and more on a slow device (a DAP, a low-end Xiaomi).
                        // Counted from the address alone, the wait ran out under a page that was still
                        // booting and put the sign-in page back: the reported loop to the login page.
                        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                            elapsed += 150
                            if (WaveBitsRuntime.spotify.ready.value) waited += 150
                        }
                        kotlinx.coroutines.delay(150)
                    }
                    android.util.Log.d(LOG, "no session ${SESSION_WAIT_MS / 1000}s after the player came back: sign-in page restored")
                    // The page may now be the anonymous player: send it back to the e-mail step so the
                    // user is not left where nothing plays.
                    connecting = false
                    if (!showingLogin) showLogin() else WaveBitsRuntime.host.openLogin()
                }
            }
        }
    }

    /**
     * Back from the e-mail app: the session may have been established while away, or the page may
     * have reloaded into the anonymous player. Only the token decides — never the address.
     */
    override fun onResume() {
        super.onResume()
        if (!showingLogin) return
        lifecycleScope.launch {
            if (WaveBitsRuntime.awaitSignedIn(1_500)) {
                android.util.Log.d(LOG, "back with a session: player shown")
                previousBackendBeforeSpotifyLogin = null
                com.music.spotui.ui.platform.PlatformManager.saveSpotifySession(this@MainActivity)
                com.music.spotui.ui.platform.PlatformManager.activatePlatform(
                    this@MainActivity,
                    app.wavebits.host.android.BackendMode.SPOTIFY,
                )
                signedIn = true
                hideLogin()
            }
        }
    }

    /**
     * Signed in = the Web Player is ready with a user token. A Spotify login page while
     * signed in (expired session) brings the page back to the front.
     */
    private fun watchSession() {
        lifecycleScope.launch {
            while (!WaveBitsRuntime.isStarted) kotlinx.coroutines.delay(200)
            val api = WaveBitsRuntime.spotify
            combine(api.ready, WaveBitsRuntime.loginRequired, WaveBitsRuntime.backendMode) { ready, login, mode ->
                Triple(ready, login, mode)
            }.collect { (ready, login, mode) ->
                if (com.music.spotui.di.SessionActions.signedOut.value && !showingLogin && !showingYouTubeLogin) {
                    signedIn = false
                    connecting = false
                    return@collect
                }
                if (mode != app.wavebits.host.android.BackendMode.SPOTIFY) {
                    // YouTube Mode; never force Spotify WebView login.
                    val modeSignedIn = com.music.spotui.ui.platform.PlatformManager.isPlatformLoggedIn(
                        this@MainActivity,
                        mode,
                    )
                    if (modeSignedIn) {
                        com.music.spotui.data.api.ProfileCache.ensure(this@MainActivity)
                        if (!signedIn) {
                            prefs.edit().putBoolean("LoggedIn", true).apply()
                            signedIn = true
                        }
                    } else if (signedIn) {
                        signedIn = false
                    }
                    connecting = false
                    if (showingLogin) hideLogin()
                    return@collect
                }
                when {
                    login && !showingLogin && signedIn -> {
                        if (!com.music.spotui.ui.platform.PlatformManager.isSpotifyLoggedIn(this@MainActivity)) {
                            showLogin()
                        }
                    }
                    ready && !login -> {
                        var user = runCatching { WaveBitsRuntime.isSignedIn() }.getOrDefault(false)
                        val isSavedLoggedIn = com.music.spotui.ui.platform.PlatformManager.isSpotifyLoggedIn(this@MainActivity)
                        if (!user && isSavedLoggedIn) {
                            user = WaveBitsRuntime.awaitSignedIn(2_000)
                        }
                        if (user || isSavedLoggedIn) {
                            previousBackendBeforeSpotifyLogin = null
                            if (user) {
                                com.music.spotui.ui.platform.PlatformManager.saveSpotifySession(this@MainActivity)
                            }
                            com.music.spotui.data.api.ProfileCache.ensure(this@MainActivity)
                            if (com.music.spotui.di.SessionActions.signedOut.value) {
                                com.music.spotui.di.SessionActions.signedIn()
                                prefs.edit().putBoolean("LoggedIn", true).apply()
                                WaveBitsRuntime.host.attach(root, visible = false)
                                startActivity(Intent(this@MainActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                                finish()
                                return@collect
                            }
                            if (!signedIn) {
                                setupNeeded = com.music.spotui.ui.screens.Setup.needed(this@MainActivity)
                                prefs.edit().putBoolean("LoggedIn", true).apply()
                                signedIn = true
                            }
                            connecting = false
                            if (showingLogin) hideLogin()
                        }
                    }
                }
            }
        }
    }



    /** Spotify links (open.spotify.com/...) play their item; voice search plays its best match. */
    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val link = intent.data?.toString() ?: return
                // A collaborative-playlist invitation (…?pt=) or a spotify.link short link: kept / resolved there.
                if (com.music.spotui.data.api.InviteLinks.handle(link)) return
                val uri = spotifyUri(link) ?: return
                // A shared list (or artist, podcast) opens on its page, as in the official app
                // (reported: a shared playlist started playing and was never shown). A song or an
                // episode still plays.
                if (uri.split(':').getOrNull(1) in setOf("playlist", "album", "artist", "show")) {
                    com.music.spotui.data.api.InviteLinks.page.value = uri
                    return
                }
                // A song, with an audio-source extension on: asked there first, like any play.
                if (uri.startsWith("spotify:track:") && com.music.spotui.extensions.Extensions.hasAudioSource()) {
                    com.music.spotui.di.SongPlayer.playSong(uri)
                    return
                }
                lifecycleScope.launch {
                    WaveBitsRuntime.api.ready.first { it }
                    runCatching { WaveBitsRuntime.api.player.play(uri) }
                }
            }
            android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val query = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty()
                if (query.isNotBlank()) lifecycleScope.launch { AutoBrowser.playFromSearch(query) }
            }
        }
    }

    /** `https://open.spotify.com/intl-fr/album/{id}?si=…` -> `spotify:album:{id}`. */
    private fun spotifyUri(link: String): String? {
        val m = Regex("(?:open|www|play)\\.spotify\\.com/(?:intl-[a-z-]+/)?(track|album|playlist|artist|show|episode)/([A-Za-z0-9]{22})").find(link) ?: return null
        return "spotify:${m.groupValues[1]}:${m.groupValues[2]}"
    }
}

/** Shown between Spotify's login page and the first screen. */
@androidx.compose.runtime.Composable
private fun ConnectingScreen() {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        com.music.spotui.ui.screens.LiquidSilkWavesBackground(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        )
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            modifier = androidx.compose.ui.Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Color(0xFF14181A).copy(alpha = 0.88f))
                .padding(horizontal = 28.dp, vertical = 18.dp),
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                color = androidx.compose.ui.graphics.Color(0xFFF3F4EF),
                strokeWidth = 2.5.dp,
                modifier = androidx.compose.ui.Modifier.size(22.dp),
            )
            androidx.compose.material3.Text(
                text = "Synchronizing audio engine...",
                color = androidx.compose.ui.graphics.Color(0xFFF3F4EF),
                fontFamily = com.music.spotui.ui.theme.AppSans,
                fontSize = 14.sp,
            )
        }
    }
}

/** Spotify sign-in screen matching the YouTubeLoginScreen interface. */
@androidx.compose.runtime.Composable
private fun SpotifyLoginScreen(
    root: FrameLayout,
    connecting: Boolean,
    onClose: () -> Unit,
) {
    val currentUrl by WaveBitsRuntime.host.url.collectAsState()
    val progress by WaveBitsRuntime.host.progress.collectAsState()
    var showHelpDialog by remember { mutableStateOf(false) }
    val currentHost = remember(currentUrl) {
        runCatching { android.net.Uri.parse(currentUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "accounts.spotify.com"
    }

    DisposableEffect(Unit) {
        onDispose {
            WaveBitsRuntime.host.attach(root, visible = false)
        }
    }

    androidx.activity.compose.BackHandler {
        val canBack = runCatching { WaveBitsRuntime.host.view.canGoBack() }.getOrDefault(false)
        if (canBack) {
            runCatching { WaveBitsRuntime.host.view.goBack() }
        } else {
            onClose()
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
                    onClick = onClose,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(tone(0xFF222222))
                        .testTag("spotify_login_close_button"),
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
                        text = "Sign in with Spotify",
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
                            tint = Color(0xFF1DB954),
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
                label = "spotifyLoginProgress",
            )
            val barAlpha by animateFloatAsState(
                targetValue = if (progress in 1..99) 1f else 0f,
                label = "spotifyLoginProgressAlpha",
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
                        .background(Color(0xFF1DB954)),
                )
            }

            // WebView container card
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF121212)),
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("spotify_webview"),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            WaveBitsRuntime.host.attach(this, visible = true, applySystemBarMargins = false)
                        }
                    },
                    update = { container ->
                        if (WaveBitsRuntime.host.view.parent !== container) {
                            WaveBitsRuntime.host.attach(container, visible = true, applySystemBarMargins = false)
                        }
                    },
                )

                // Loading overlay once signed in
                androidx.compose.animation.AnimatedVisibility(
                    visible = connecting,
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
                                color = Color(0xFF1DB954),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Connecting your Spotify account...",
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
                        .testTag("spotify_login_need_help_button"),
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
                        text = "Your credentials go straight to Spotify. WaveBits only saves your session cookie.",
                        color = AppText.copy(alpha = 0.55f),
                        fontFamily = AppSans,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        if (showHelpDialog) {
            com.music.spotui.ui.screens.LoginHelpDialog(onDismiss = { showHelpDialog = false })
        }
    }
}


