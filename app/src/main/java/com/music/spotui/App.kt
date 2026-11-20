package com.music.spotui

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import com.music.spotui.ui.components.pressable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.music.spotui.ui.navigation.MainBottomNavigation
import com.music.spotui.ui.navigation.MyNavHost
import com.music.spotui.ui.navigation.Routes


@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun App() {
    val bottomBarState = rememberSaveable { (mutableStateOf(true)) }
    val bottomBarPlayerState = rememberSaveable { (mutableStateOf(true)) }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    // Control TopBar and BottomBar: on a route change (writing state during composition
    // forces an extra recomposition pass).
    val route = navBackStackEntry?.destination?.route
    LaunchedEffect(route) {
        when (route) {
            Routes.Home.route, Routes.Search.route, Routes.Library.route -> bottomBarState.value = true
            Routes.Album.route, Routes.DiscordStatus.route -> bottomBarState.value = false
        }
    }

    // Debug and beta builds: `wavebits-cli nav <route>` (see CliReceiver).
    LaunchedEffect(Unit) { com.music.spotui.cli.CliReceiver.nav.collect { r -> runCatching { navController.navigate(r) } } }

    // The playlists at the top of the library, fetched ahead on Wi-Fi (see PagePrefetch).
    LaunchedEffect(Unit) { com.music.spotui.data.api.PagePrefetch.start() }

    // A collaborative-playlist invitation was opened (see InviteLinks): its playlist page offers "Join".
    val invite by com.music.spotui.data.api.InviteLinks.pending.collectAsState()
    LaunchedEffect(invite) {
        val i = invite?.takeIf { !it.opened } ?: return@LaunchedEffect
        app.wavebits.host.android.WaveBitsRuntime.api.ready.first { it }
        i.opened = true
        navController.navigate(com.music.spotui.ui.navigation.playlistRoute(i.playlistId))
    }

    // A Blend invitation was opened (see InviteLinks): who invites, and "Join", over the app.
    val blendLink by com.music.spotui.data.api.InviteLinks.blend.collectAsState()
    blendLink?.let { link ->
        com.music.spotui.ui.screens.BlendInviteSheet(link, navController) {
            if (com.music.spotui.data.api.InviteLinks.blend.value === link) com.music.spotui.data.api.InviteLinks.blend.value = null
        }
    }

    // Genius's explanations of the song playing, read while it starts: ready when the lyrics open.
    val geniusLang = com.music.spotui.ui.components.readerLanguage()
    val playingUri = com.music.spotui.di.SongPlayer.nowPlaying.value.uri
    LaunchedEffect(playingUri) {
        if (!playingUri.startsWith("spotify:track:")) return@LaunchedEffect
        kotlinx.coroutines.delay(2_000) // the song's own loads first
        val st = com.music.spotui.di.Graph.currentSongState
        com.music.spotui.data.api.GeniusMeaning.prefetch(playingUri.substringAfterLast(':'), st.title.value, st.singer.value, geniusLang)
    }
    // A shared link to a list / artist / podcast: its page, over whatever was open.
    val pageLink by com.music.spotui.data.api.InviteLinks.page.collectAsState()
    LaunchedEffect(pageLink) {
        val uri = pageLink ?: return@LaunchedEffect
        com.music.spotui.data.api.InviteLinks.page.value = null
        val id = uri.substringAfterLast(':')
        com.music.spotui.ui.navigation.PlayerSheet.close()
        runCatching {
            navController.navigate(when (uri.split(':')[1]) {
                "playlist" -> com.music.spotui.ui.navigation.playlistRoute(id)
                "album" -> com.music.spotui.ui.navigation.albumRoute("", "", id)
                "artist" -> com.music.spotui.ui.navigation.artistRoute("artist", id)
                else -> com.music.spotui.ui.navigation.showRoute(id)
            })
        }
    }
    val shareTarget by com.music.spotui.ui.components.SpotifyCode.request.collectAsState()
    shareTarget?.let { t -> com.music.spotui.ui.components.SpotifyCodeSheet(t) { com.music.spotui.ui.components.SpotifyCode.request.value = null } }

    val haze = com.music.spotui.ui.immersive.shell.rememberImmersiveHazeState()
    // Liquid Glass (Settings > Display): the pages record themselves for the glass to refract.
    // Not provided while it is off or unsupported, so nothing is recorded for no reader.
    val glassPage = com.music.spotui.ui.immersive.shell.rememberImmersiveBackdrop()
    val liquidGlass = com.music.spotui.ui.immersive.shell.rememberLiquidGlassActive()
    // The floating chrome's fold (iOS 26): pages scroll it through immersiveChromeScroll().
    val chrome = com.music.spotui.ui.immersive.shell.rememberImmersiveChromeState()
    val overscrollFactory = com.music.spotui.ui.immersive.components.rememberImmersiveOverscrollFactory()
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.LocalOverscrollFactory provides overscrollFactory,
        com.music.spotui.ui.immersive.shell.LocalImmersiveHaze provides haze,
        com.music.spotui.ui.immersive.shell.LocalImmersiveBackdrop provides if (liquidGlass) glassPage else null,
        com.music.spotui.ui.immersive.shell.LocalImmersiveChrome provides chrome,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier,
            ) {
                MyNavHost(
                    navHostController = navController,
                    bottomBarState = bottomBarState,
                    bottomBarPlayerState = bottomBarPlayerState,
                    modifier = Modifier.background(com.music.spotui.ui.immersive.Immersive.Page),
                )
            }
            ImmersiveShell(navController)
            UpdatePrompt()

            var showSupportUpiSheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            if (showSupportUpiSheet) {
                com.music.spotui.ui.components.SupportUpiSheet(onDismiss = { showSupportUpiSheet = false })
            }
            com.music.spotui.ui.components.SupportAutoPopup(onOpenSupportSheet = { showSupportUpiSheet = true })
        }
    }
}



/**
 * The Immersive look's floating chrome: the mini player capsule over the tab bar (both hidden
 * on the full-screen routes; folded into one row while a page scrolls down), and its player over
 * everything.
 */
@Composable
private fun ImmersiveShell(navController: androidx.navigation.NavHostController) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        com.music.spotui.ui.immersive.shell.ImmersiveBottomChrome(
            navController,
            miniPlayer = { m -> com.music.spotui.ui.immersive.player.ImmersiveMiniPlayer(navController, m) },
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
        com.music.spotui.ui.immersive.player.ImmersivePlayerOverlay(navController)
    }
}

/** The update card is on screen: the donation reminder waits for another launch. */
private val updateCardOpen = mutableStateOf(false)

/**
 * The release the update card shows. The launch check fills it; so does the manual check in
 * Settings, which is why it is not private to [UpdatePrompt].
 */
internal val updateRelease = mutableStateOf<com.music.spotui.data.api.UpdateChecker.Release?>(null)

/**
 * The donation reminder (see SupportReminder): a card a few seconds after launch, only
 * when due, signed in, and no update card is open. "No thanks" closes it for 2 weeks.
 */
@Composable
private fun SupportPrompt() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var show by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(12_000)
        val signedIn = context.getSharedPreferences("myspoti_prefs", android.content.Context.MODE_PRIVATE).getBoolean("LoggedIn", false)
        show = signedIn && !updateCardOpen.value && com.music.spotui.data.api.SupportReminder.isDue(context)
    }
    if (!show) return
    val close = { com.music.spotui.data.api.SupportReminder.shown(context); show = false }
    SupportDialog(
        supporter = com.music.spotui.data.api.SupportReminder.level != com.music.spotui.data.api.SupportReminder.Level.NONE,
        onSupport = { close(); com.music.spotui.data.api.SupportReminder.open(context) },
        onSponsor = { close(); com.music.spotui.data.api.SupportReminder.openSponsors(context) },
        onDecline = close,
        onSupported = { level -> com.music.spotui.data.api.SupportReminder.setLevel(context, level); close() },
    )
}

/**
 * The reminder card: a request, or a thank-you for someone who already gave. "I've
 * supported" (on trust) asks once or monthly, and unlocks the supporter thank-yous.
 */
@Composable
private fun SupportDialog(supporter: Boolean, onSupport: () -> Unit, onSponsor: () -> Unit, onDecline: () -> Unit, onSupported: (com.music.spotui.data.api.SupportReminder.Level) -> Unit) {
    // The owner's reminder: no extension draws, listens or acts over it.
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.wavebits.extensions.ProtectedSurface.SUPPORT)
    var choosing by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDecline) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(tone(0xFF242424))
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(app.wavebits.engine.R.drawable.wavebits_logo),
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(tone(0xFF181818)),
            )
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(if (supporter) app.wavebits.engine.R.string.supporter_thanks_title else app.wavebits.engine.R.string.support_title),
                color = AppText, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(if (supporter) app.wavebits.engine.R.string.supporter_thanks_body else app.wavebits.engine.R.string.support_body),
                color = AppText.copy(alpha = 0.8f),
                fontFamily = com.music.spotui.ui.theme.AppSans, fontSize = 14.sp, lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            androidx.compose.foundation.layout.Box(
                contentAlignment = androidx.compose.ui.Alignment.Center,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .pressable(0.96f, onClick = onSupport)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(com.music.spotui.ui.theme.AppAccent),
            ) {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(if (supporter) app.wavebits.engine.R.string.support_again else app.wavebits.engine.R.string.support_action),
                    color = androidx.compose.ui.graphics.Color.Black, fontFamily = com.music.spotui.ui.theme.AppSans,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp,
                )
            }
            com.music.spotui.ui.components.GitHubSponsorButton(Modifier.padding(top = 10.dp), onClick = onSponsor)
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(app.wavebits.engine.R.string.support_decline),
                color = AppText, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp).pressable(0.96f, onClick = onDecline).padding(horizontal = 24.dp, vertical = 12.dp),
            )
            if (!supporter && com.music.spotui.data.api.SupportReminder.PERKS_ENABLED) {
                if (choosing) androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 8.dp),
                ) { com.music.spotui.ui.components.SupportChoice(onChosen = onSupported) }
                else androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(app.wavebits.engine.R.string.supporter_i_supported),
                    color = AppText.copy(alpha = 0.6f), fontFamily = com.music.spotui.ui.theme.AppSans,
                    fontSize = 13.sp,
                    modifier = Modifier.pressable(0.96f) { choosing = true }.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * "New version" dialog: checked at launch (off the UI thread), shown as soon as the answer is
 * known. "Later" hides it; Settings > About offers the update again.
 */
@Composable
private fun UpdatePrompt() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var release by updateRelease
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Runs in background at startup only when automatic update checks are enabled and background network is allowed.
        if (com.music.spotui.data.preferences.AutoUpdateCheckPref.on && app.wavebits.power.EnergySettings.allowBackgroundPrefetch) {
            release = com.music.spotui.data.api.UpdateChecker.check(context)
        }
    }
    updateCardOpen.value = release != null
    val r = release ?: return
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.wavebits.extensions.ProtectedSurface.UPDATE)

    val dismiss = {
        // Temporary session dismissal: resets in-memory state without persisting,
        // so on app restart it will check and show pop-up again if update is still available
        release = null
    }

    UpdateDialog(
        version = r.version,
        notes = r.notes,
        onUpdate = {
            runCatching {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://wavebits.vercel.app")
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        },
        onLater = { dismiss() },
    )
}

/** The update card, in the app's own style (dark sheet, green pill), not a system dialog. */
@Composable
private fun UpdateDialog(
    version: String,
    notes: String,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onLater,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(tone(0xFF242424))
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(app.wavebits.engine.R.drawable.wavebits_logo),
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(tone(0xFF181818)),
            )
            androidx.compose.material3.Text(
                "Update available",
                color = AppText, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            androidx.compose.material3.Text(
                "${androidx.compose.ui.res.stringResource(app.wavebits.engine.R.string.app_name)} v$version",
                color = AppText.copy(alpha = 0.7f),
                fontFamily = com.music.spotui.ui.theme.AppSans, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp),
            )
            if (notes.isNotBlank()) androidx.compose.material3.Text(
                notes, color = AppText.copy(alpha = 0.8f),
                fontFamily = com.music.spotui.ui.theme.AppSans, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(max = 240.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            )
            androidx.compose.foundation.layout.Box(
                contentAlignment = androidx.compose.ui.Alignment.Center,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .pressable(0.96f, onClick = onUpdate)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(com.music.spotui.ui.theme.AppAccent),
            ) {
                androidx.compose.material3.Text(
                    "Update now",
                    color = androidx.compose.ui.graphics.Color.Black, fontFamily = com.music.spotui.ui.theme.AppSans,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp,
                )
            }
            androidx.compose.material3.Text(
                "Maybe later",
                color = AppText, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp).pressable(0.96f, onClick = onLater).padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}
