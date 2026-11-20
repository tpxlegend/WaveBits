package com.music.spotui.ui.immersive.pages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.wavebits.engine.R
import com.music.spotui.data.preferences.DownloadQuality
import com.music.spotui.data.preferences.StreamingQuality
import com.music.spotui.data.preferences.getDownloadQuality
import com.music.spotui.data.preferences.getStreamingQuality
import com.music.spotui.data.preferences.setDownloadQuality
import com.music.spotui.data.preferences.setStreamingQuality
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.look.rememberReduceMotion
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.screens.installedRoute
import com.music.spotui.ui.screens.shareDiagnosticReport
import kotlinx.coroutines.launch

import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn

/** Where the Immersive Settings are: the list of categories, one category, or a choice inside it. */
private enum class SettingsPage { Root, Playback, StreamingWifi, StreamingCell, Crossfade, Downloads, DownloadQuality, Data, Display, AppIcon, Rotation, Language, App }

private val StreamingChoices = listOf(
    Triple(StreamingQuality.AUTO, R.string.quality_streaming_auto, R.string.quality_streaming_auto_detail),
    Triple(StreamingQuality.LOW, R.string.quality_streaming_low, R.string.quality_streaming_low_detail),
    Triple(StreamingQuality.NORMAL, R.string.quality_streaming_normal, R.string.quality_streaming_normal_detail),
    Triple(StreamingQuality.HIGH, R.string.quality_streaming_high, R.string.quality_streaming_high_detail),
)

private val DownloadChoices = listOf(
    Triple(DownloadQuality.OFF, R.string.quality_download_off, R.string.quality_download_off_detail),
    Triple(DownloadQuality.SMALL, R.string.quality_download_small, R.string.quality_download_small_detail),
    Triple(DownloadQuality.CD, R.string.quality_download_cd, R.string.quality_download_cd_detail),
    Triple(DownloadQuality.HI_RES, R.string.quality_download_hires, R.string.quality_download_hires_detail),
    Triple(DownloadQuality.ASK, R.string.quality_download_ask, R.string.quality_download_ask_detail),
)

/**
 * Settings of the Immersive look, replacing the Classic `SettingsScreen`: the same preferences
 * and actions (every switch writes through the same stores) as iOS-style inset groups, one page
 * per category and one for each choice (quality, language, look...). A pushed page slides in on a
 * spring and back leaves it; with "reduce motion" they cross-fade.
 */
@Composable
fun ImmersiveSettingsScreen(navController: NavController) {
    // The pages pushed so far, so back pops one before it leaves Settings.
    val stack = remember { mutableStateListOf<SettingsPage>() }
    val page = stack.lastOrNull() ?: SettingsPage.Root
    val reduce = rememberReduceMotion()
    var pushing by remember { mutableStateOf(true) }
    val pop: () -> Unit = { pushing = false; stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isNotEmpty(), onBack = pop)
    val open: (SettingsPage) -> Unit = { pushing = true; stack += it }
    val back: () -> Unit = { if (stack.isNotEmpty()) pop() else navController.popBackStack() }
    val rootHaze = com.music.spotui.ui.immersive.shell.LocalImmersiveHaze.current
    val rootBackdrop = com.music.spotui.ui.immersive.shell.LocalImmersiveBackdrop.current
    val curve = remember { androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            when {
                reduce -> fadeIn(tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.FADE_MS)) togetherWith
                    fadeOut(tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.FADE_MS))
                !pushing ->
                    (
                        (slideInHorizontally(tween(280, easing = curve)) { -it / 4 } +
                            fadeIn(tween(280, easing = curve), initialAlpha = 0.88f)) togetherWith
                            slideOutHorizontally(tween(280, easing = curve)) { it }
                        ).apply { targetContentZIndex = -1f }
                else ->
                    (
                        slideInHorizontally(tween(280, easing = curve)) { it } togetherWith
                            (slideOutHorizontally(tween(280, easing = curve)) { -it / 4 } +
                                fadeOut(tween(280, easing = curve), targetAlpha = 0.88f))
                        ).apply { targetContentZIndex = 1f }
            }
        },
        label = "settings pages",
    ) { p ->
        val isTarget = transition.targetState == androidx.compose.animation.EnterExitState.Visible
        androidx.compose.runtime.CompositionLocalProvider(
            com.music.spotui.ui.immersive.shell.LocalImmersiveHaze provides if (isTarget) rootHaze else null,
            com.music.spotui.ui.immersive.shell.LocalImmersiveBackdrop provides if (isTarget) rootBackdrop else null,
        ) {
            Box(Modifier.background(Immersive.Page)) {
                when (p) {
                    SettingsPage.Root -> SettingsRoot(navController, open, back)
                    SettingsPage.Playback -> PlaybackPage(navController, open, back)
                    SettingsPage.StreamingWifi -> StreamingPage(cellular = false, back)
                    SettingsPage.StreamingCell -> StreamingPage(cellular = true, back)
                    SettingsPage.Crossfade -> CrossfadePage(back)
                    SettingsPage.Downloads -> DownloadsPage(navController, open, back)
                    SettingsPage.DownloadQuality -> DownloadQualityPage(back)
                    SettingsPage.Data -> DataPage(back)
                    SettingsPage.Display -> DisplayPage(open, back)
                    SettingsPage.AppIcon -> AppIconPage(back)
                    SettingsPage.Rotation -> RotationPage(back)
                    SettingsPage.Language -> LanguagePage(back)
                    SettingsPage.App -> AppPage(navController, back)
                }
            }
        }
    }
}

@Composable
private fun SettingsRoot(navController: NavController, open: (SettingsPage) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        com.music.spotui.data.api.ProfileCache.ensure(ctx)
        com.music.spotui.data.preferences.AppIconManager.init(ctx)
        com.music.spotui.data.preferences.LookPref.init(ctx)
    }
    val name = com.music.spotui.data.api.ProfileCache.name.orEmpty()
    val avatar = com.music.spotui.data.api.ProfileCache.imageUrl
    val myId = com.music.spotui.data.api.ProfileCache.id

    val registry by com.music.spotui.extensions.Extensions.registry.collectAsStateWithLifecycle()
    val rootEnergy by app.wavebits.power.EnergySettings.state.collectAsStateWithLifecycle()
    val backendMode by app.wavebits.host.android.WaveBitsRuntime.backendMode.collectAsStateWithLifecycle()
    val isSpotify = backendMode == app.wavebits.host.android.BackendMode.SPOTIFY
    val isYouTube = backendMode == app.wavebits.host.android.BackendMode.YOUTUBE
    val isJioSaavn = backendMode == app.wavebits.host.android.BackendMode.JIOSAAVN
    val scope = rememberCoroutineScope()
    val liveYtGuest = if (app.wavebits.host.android.WaveBitsRuntime.isStarted) {
        app.wavebits.host.android.WaveBitsRuntime.youtubeAccount.isGuest.collectAsStateWithLifecycle().value
    } else true
    val isYtGuest = remember(backendMode, name, myId, liveYtGuest) {
        com.music.spotui.ui.platform.PlatformManager.isYouTubeGuest(ctx)
    }
    val withSettings = registry.extensions.mapNotNull { e ->
        com.music.spotui.extensions.Extensions.manifest(e.id)?.settings?.takeIf { it.isNotEmpty() }?.let { e to it }
    }
    var showAbout by remember { mutableStateOf(false) }
    var showSupportUpi by remember { mutableStateOf(false) }
    var showPlatformSwitch by remember { mutableStateOf(false) }
    var showJioProfile by remember { mutableStateOf(false) }
    var syncingYouTubeLibrary by remember { mutableStateOf(false) }
    var showYouTubeChannelDialog by remember { mutableStateOf(false) }
    var channelChoices by remember { mutableStateOf<List<app.wavebits.innertube.models.YouTubeChannel>>(emptyList()) }
    if (showAbout) {
        com.music.spotui.ui.components.AboutSheet(onDismiss = { showAbout = false })
    }
    if (showSupportUpi) {
        com.music.spotui.ui.components.SupportUpiSheet(onDismiss = { showSupportUpi = false })
    }
    if (showPlatformSwitch) {
        com.music.spotui.ui.components.PlatformSwitchDialog(onDismiss = { showPlatformSwitch = false })
    }
    if (showJioProfile) {
        com.music.spotui.ui.components.JioSaavnProfileDialog(
            onDismiss = { showJioProfile = false },
            onSaved = {
                showJioProfile = false
                com.music.spotui.data.api.ProfileCache.ensure(ctx)
            },
        )
    }
    if (showYouTubeChannelDialog) {
        AlertDialog(
            onDismissRequest = { showYouTubeChannelDialog = false },
            title = { Text("Choose YouTube Channel") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    channelChoices.forEach { channel ->
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(Immersive.Card)
                                .background(if (channel.selected) Immersive.Accent.copy(alpha = 0.16f) else Color.Transparent)
                                .clickable(role = Role.Button) {
                                    showYouTubeChannelDialog = false
                                    scope.launch {
                                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            app.wavebits.host.android.WaveBitsRuntime.youtubeAccount.useChannel(channel)
                                        }
                                        if (ok) {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                app.wavebits.host.android.WaveBitsRuntime.youtube.clearSessionCaches(clearUnlikedFilter = true)
                                                com.music.spotui.data.preferences.clearYouTubeLibraryCache(ctx)
                                                com.music.spotui.data.api.ScreenCache.clearYouTubeBlocking()
                                                com.music.spotui.data.api.Api.HomeCache.clear()
                                                com.music.spotui.ui.screens.ContextViewModel.clearCache()
                                                com.music.spotui.data.api.ProfileCache.clearSession()
                                            }
                                            com.music.spotui.ui.platform.PlatformManager.saveYouTubeSession(
                                                ctx,
                                                channel.name,
                                                channel.thumbnailUrl,
                                            )
                                            com.music.spotui.data.api.LibraryEdits.reloadNow()
                                            app.wavebits.host.android.WaveBitsRuntime.youtube.notifyLibraryChanged()
                                            com.music.spotui.data.api.ProfileCache.ensure(ctx)
                                            android.widget.Toast.makeText(
                                                ctx,
                                                "Switched to ${channel.name}",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = channel.name,
                                style = Immersive.Body,
                                modifier = Modifier.weight(1f),
                            )
                            if (channel.selected) {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selected",
                                    tint = Immersive.Accent,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            showYouTubeChannelDialog = false
                            runCatching {
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                android.webkit.CookieManager.getInstance().flush()
                            }
                            com.music.spotui.ui.platform.PlatformManager.requestPlatformLogin(
                                app.wavebits.host.android.BackendMode.YOUTUBE,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Sign in with another Gmail account")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showYouTubeChannelDialog = false }) {
                    Text("Close")
                }
            },
        )
    }

    val validSpotifyUserId = myId?.takeIf {
        isSpotify && it.isNotBlank() && it != "youtube" && it != "youtube_guest" && it != "jiosaavn"
    }

    ImmersivePage(title = stringResource(R.string.settings_title), onBack = back) {
        immersiveGroup("profile") {
            row {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            when {
                                validSpotifyUserId != null ->
                                    navController.navigate(com.music.spotui.ui.navigation.profileRoute(validSpotifyUserId, name))
                                isJioSaavn -> showJioProfile = true
                                isYouTube -> showPlatformSwitch = true
                                else -> showPlatformSwitch = true
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    com.music.spotui.ui.immersive.screens.AvatarDisc(
                        url = avatar,
                        initial = name.trim().firstOrNull()?.uppercase() ?: "•",
                        contentDescription = null,
                        onClick = null,
                        size = 56.dp,
                    )
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(start = 14.dp).weight(1f),
                    ) {
                        Text(
                            text = name.ifBlank {
                                when {
                                    isYouTube && isYtGuest -> "Google Guest"
                                    isYouTube -> "YouTube Music"
                                    isJioSaavn -> "JioSaavn"
                                    else -> stringResource(R.string.immersive_screens_account)
                                }
                            },
                            style = Immersive.Headline,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            text = when {
                                validSpotifyUserId != null -> stringResource(R.string.view_profile)
                                isYouTube && isYtGuest -> "YouTube Music • Google Guest Mode (Tap to switch)"
                                isYouTube -> "YouTube Music • Tap to switch Google Account / Guest Mode"
                                isJioSaavn -> "JioSaavn • Tap to edit profile"
                                else -> "Tap to switch music platform"
                            },
                            style = Immersive.Caption,
                        )
                    }
                    if (com.music.spotui.data.api.SupportReminder.level != com.music.spotui.data.api.SupportReminder.Level.NONE) {
                        com.music.spotui.ui.components.SupporterBadge(Modifier.padding(start = 10.dp))
                    }
                }
            }
            row {
                val platformLabel = when (backendMode) {
                    app.wavebits.host.android.BackendMode.SPOTIFY -> "Spotify"
                    app.wavebits.host.android.BackendMode.YOUTUBE -> if (isYtGuest) "YouTube Music (Guest)" else "YouTube Music"
                    app.wavebits.host.android.BackendMode.JIOSAAVN -> "JioSaavn"
                }
                ImmersiveGroupRow(
                    title = "Switch Music Platform",
                    value = platformLabel,
                    chevron = true,
                    onClick = { showPlatformSwitch = true },
                )
            }
        }

        if (isYouTube) {
            immersiveGroup(
                key = "youtube_gmail_sync",
                header = "YouTube Music Account",
                footer = if (isYtGuest) {
                    "You are currently browsing as Google Guest. Connect your Gmail account to sync Liked Songs, playlists, albums, artists, and recommendations."
                } else {
                    "Your YouTube Music library is synced with your Google account."
                },
            ) {
                if (isYtGuest) {
                    row {
                        ImmersiveGroupRow(
                            title = "Connect Gmail Account",
                            detail = "Sync your library, liked songs & playlists for a better experience",
                            icon = Icons.Rounded.AccountCircle,
                            chevron = true,
                            modifier = Modifier
                                .testTag("settings_connect_youtube_gmail")
                                .semantics {
                                    contentDescription = "Connect Gmail account to sync YouTube Music library"
                                },
                            onClick = {
                                com.music.spotui.ui.platform.PlatformManager.requestPlatformLogin(
                                    app.wavebits.host.android.BackendMode.YOUTUBE,
                                )
                            },
                        )
                    }
                } else {
                    row {
                        ImmersiveGroupRow(
                            title = "Google Account Connected",
                            detail = "Library, liked songs & playlists synced",
                            value = name.ifBlank { "Synced" },
                            icon = Icons.Rounded.AccountCircle,
                            modifier = Modifier
                                .testTag("settings_youtube_account_status")
                                .semantics { contentDescription = "Google account connected" },
                        )
                    }
                    row {
                        ImmersiveGroupRow(
                            title = if (syncingYouTubeLibrary) "Syncing Library..." else "Sync Library Now",
                            detail = "Refresh liked songs, playlists, albums & artists from Gmail",
                            icon = Icons.Rounded.Sync,
                            enabled = !syncingYouTubeLibrary,
                            modifier = Modifier
                                .testTag("settings_sync_youtube_library")
                                .semantics { contentDescription = "Sync YouTube Music library now" },
                            onClick = {
                                syncingYouTubeLibrary = true
                                scope.launch {
                                    runCatching {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            if (app.wavebits.host.android.WaveBitsRuntime.isStarted) {
                                                app.wavebits.host.android.WaveBitsRuntime.youtubeAccount.revalidate()
                                                app.wavebits.host.android.WaveBitsRuntime.youtube.clearSessionCaches(clearUnlikedFilter = true)
                                            }
                                            com.music.spotui.data.preferences.clearYouTubeLibraryCache(ctx)
                                            com.music.spotui.data.api.ScreenCache.clearYouTubeBlocking()
                                            com.music.spotui.data.api.Api.HomeCache.clear()
                                            com.music.spotui.ui.screens.ContextViewModel.clearCache()
                                            com.music.spotui.data.api.ProfileCache.clearSession()
                                        }
                                        com.music.spotui.data.api.LibraryEdits.reloadNow()
                                        if (app.wavebits.host.android.WaveBitsRuntime.isStarted) {
                                            app.wavebits.host.android.WaveBitsRuntime.youtube.notifyLibraryChanged()
                                        }
                                        com.music.spotui.data.api.ProfileCache.ensure(ctx)
                                        android.widget.Toast.makeText(
                                            ctx,
                                            "YouTube Music library synced",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    syncingYouTubeLibrary = false
                                }
                            },
                        )
                    }
                    row {
                        ImmersiveGroupRow(
                            title = "Switch Gmail / Channel",
                            detail = "Choose another YouTube channel or sign in with a different Gmail",
                            icon = Icons.Rounded.SwapHoriz,
                            chevron = true,
                            modifier = Modifier
                                .testTag("settings_switch_youtube_gmail")
                                .semantics { contentDescription = "Switch Gmail account or YouTube channel" },
                            onClick = {
                                scope.launch {
                                    val list = if (app.wavebits.host.android.WaveBitsRuntime.isStarted) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            app.wavebits.host.android.WaveBitsRuntime.youtubeAccount.channels()
                                        }
                                    } else emptyList()
                                    if (list.size > 1) {
                                        channelChoices = list
                                        showYouTubeChannelDialog = true
                                    } else {
                                        runCatching {
                                            android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                            android.webkit.CookieManager.getInstance().flush()
                                        }
                                        com.music.spotui.ui.platform.PlatformManager.requestPlatformLogin(
                                            app.wavebits.host.android.BackendMode.YOUTUBE,
                                        )
                                    }
                                }
                            },
                        )
                    }
                    row {
                        ImmersiveGroupRow(
                            title = "Switch to Guest Mode",
                            detail = "Browse YouTube Music without a synced Gmail account",
                            icon = Icons.Rounded.PersonOutline,
                            modifier = Modifier
                                .testTag("settings_youtube_switch_guest")
                                .semantics { contentDescription = "Switch YouTube Music to Guest mode" },
                            onClick = {
                                scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        if (app.wavebits.host.android.WaveBitsRuntime.isStarted) {
                                            app.wavebits.host.android.WaveBitsRuntime.youtubeAccount.signOut()
                                            app.wavebits.host.android.WaveBitsRuntime.youtube.clearSessionCaches(clearUnlikedFilter = true)
                                        }
                                        com.music.spotui.data.preferences.clearYouTubeLibraryCache(ctx)
                                        com.music.spotui.data.api.ScreenCache.clearYouTubeBlocking()
                                        com.music.spotui.data.api.Api.HomeCache.clear()
                                        com.music.spotui.ui.screens.ContextViewModel.clearCache()
                                        com.music.spotui.data.api.ProfileCache.clearSession()
                                    }
                                    com.music.spotui.ui.platform.PlatformManager.saveYouTubeGuestSession(ctx, "Google Guest")
                                    com.music.spotui.ui.platform.PlatformManager.activatePlatform(
                                        ctx,
                                        app.wavebits.host.android.BackendMode.YOUTUBE,
                                    )
                                    com.music.spotui.data.api.LibraryEdits.reloadNow()
                                    android.widget.Toast.makeText(
                                        ctx,
                                        "Switched to YouTube Music Guest Mode",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                    }
                }
            }
        }

        immersiveGroup("support") {
            row {
                ImmersiveGroupRow(
                    "Support Wavebits",
                    detail = "Buy me a coffee with UPI to keep WaveBits 100% free and ad-free",
                    icon = androidx.compose.material.icons.Icons.Outlined.Coffee,
                    chevron = true,
                    onClick = { showSupportUpi = true },
                )
            }
        }

        immersiveGroup("root") {
            row { ImmersiveGroupRow(stringResource(R.string.settings_section_playback), icon = Icons.Rounded.PlayArrow, chevron = true, onClick = { open(SettingsPage.Playback) }) }
            row { ImmersiveGroupRow(stringResource(R.string.settings_section_downloads), icon = Icons.Rounded.Download, chevron = true, onClick = { open(SettingsPage.Downloads) }) }
            row {
                val dataStatus = when {
                    rootEnergy.policy.saverEnabled -> "Battery saver on"
                    rootEnergy.manualSaver && rootEnergy.isCharging -> "Paused (charging)"
                    com.music.spotui.data.DataSaver.enabled -> "Data saver on"
                    else -> null
                }
                ImmersiveGroupRow(
                    stringResource(R.string.settings_section_data),
                    value = dataStatus,
                    icon = Icons.Rounded.SignalCellularAlt,
                    chevron = true,
                    onClick = { open(SettingsPage.Data) },
                )
            }
            row { ImmersiveGroupRow(stringResource(R.string.settings_section_display), icon = Icons.Rounded.Palette, chevron = true, onClick = { open(SettingsPage.Display) }) }
            row {
                val activeIconTitle = com.music.spotui.data.preferences.AppIconManager.currentOption().title
                ImmersiveGroupRow(
                    "App Icon",
                    value = activeIconTitle,
                    icon = Icons.Rounded.Apps,
                    chevron = true,
                    onClick = { open(SettingsPage.AppIcon) },
                )
            }
            row { ImmersiveGroupRow(stringResource(R.string.settings_section_app), icon = Icons.Rounded.Info, chevron = true, onClick = { open(SettingsPage.App) }) }
        }



        immersiveGroup("discord") {
            row {
                val discordUser by com.music.spotui.data.discord.DiscordRpcManager.user.collectAsStateWithLifecycle()
                val discordStatus by com.music.spotui.data.discord.DiscordRpcManager.status.collectAsStateWithLifecycle()
                val discordConfig by com.music.spotui.data.discord.DiscordRpcManager.config.collectAsStateWithLifecycle()
                val discordDetail = when {
                    discordStatus == com.music.spotui.data.discord.DiscordRpcManager.ConnectionStatus.CONNECTED && discordUser != null ->
                        "Connected as @${discordUser!!.username}"
                    discordConfig.enabled -> "Enabled • Rich Presence active"
                    else -> "Show current music status on your Discord profile"
                }
                ImmersiveGroupRow(
                    "Discord Music Status",
                    detail = discordDetail,
                    icon = Icons.Rounded.Extension,
                    chevron = true,
                    onClick = { navController.navigate(Routes.DiscordStatus.route) },
                )
            }
        }
        immersiveGroup("account") {
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.immersive_screens_account_page),
                    detail = stringResource(R.string.immersive_screens_account_page_detail),
                    chevron = true,
                    onClick = {
                        com.music.spotui.ui.screens.AccountBrowser.open(
                            ctx,
                            com.music.spotui.ui.screens.AccountBrowser.accountUrlForPlatform(backendMode),
                        )
                    },
                )
            }
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.creator_biography_section_title),
                    detail = stringResource(R.string.about_detail),
                    chevron = true,
                    onClick = { showAbout = true },
                )
            }
            val platformLogoutText = when (backendMode) {
                app.wavebits.host.android.BackendMode.SPOTIFY -> "Log out of Spotify"
                app.wavebits.host.android.BackendMode.YOUTUBE -> if (isYtGuest) "Exit YouTube Music Guest Mode" else "Log out of YouTube Music"
                app.wavebits.host.android.BackendMode.JIOSAAVN -> "Log out of JioSaavn"
            }
            row {
                ImmersiveGroupRow(
                    platformLogoutText,
                    destructive = true,
                    onClick = {
                        com.music.spotui.di.SessionActions.logout(ctx)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Playback

@Composable
private fun PlaybackPage(navController: NavController, open: (SettingsPage) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val wifi = getStreamingQuality(ctx, false)
    val cell = getStreamingQuality(ctx, true)
    val label = { q: StreamingQuality -> StreamingChoices.first { it.first == q }.second }
    var canvasWifiOnly by remember { mutableStateOf(com.music.spotui.data.preferences.isCanvasWifiOnly(ctx)) }
    var keepPlaying by remember { mutableStateOf(app.wavebits.host.android.PlaybackService.keepsPlayingOnClose(ctx)) }
    var wordSync by remember { mutableStateOf(com.music.spotui.data.preferences.isLyricsWordSync(ctx)) }
    var extraLyrics by remember { mutableStateOf(com.music.spotui.data.preferences.isLyricsExtraSources(ctx)) }
    val hasLyricsExt = com.music.spotui.extensions.Extensions.hasLyrics()
    val isSpotifyPlayback = app.wavebits.host.android.WaveBitsRuntime.backendMode.collectAsStateWithLifecycle().value == app.wavebits.host.android.BackendMode.SPOTIFY
    val crossfade = com.music.spotui.data.preferences.CrossfadePref.seconds
    ImmersivePage(title = stringResource(R.string.settings_section_playback), onBack = back) {
        immersiveGroup("quality", footerRes = R.string.quality_streaming_detail) {
            row { ImmersiveGroupRow(stringResource(R.string.quality_streaming_wifi_title), value = stringResource(label(wifi)), chevron = true, onClick = { open(SettingsPage.StreamingWifi) }) }
            row { ImmersiveGroupRow(stringResource(R.string.quality_streaming_cell_title), value = stringResource(label(cell)), chevron = true, onClick = { open(SettingsPage.StreamingCell) }) }
        }
        immersiveGroup("playback") {
            row {
                ImmersiveSwitchRow(
                    stringResource(R.string.settings_autoplay_title),
                    checked = com.music.spotui.data.preferences.AutoplayPref.on,
                    onChecked = { com.music.spotui.data.preferences.AutoplayPref.set(ctx, it) },
                    detail = stringResource(R.string.settings_autoplay_detail),
                )
            }
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.settings_crossfade_title), detail = stringResource(R.string.settings_crossfade_detail),
                    value = if (crossfade == 0) stringResource(R.string.settings_crossfade_off) else stringResource(R.string.settings_crossfade_seconds, crossfade),
                    chevron = true, onClick = { open(SettingsPage.Crossfade) },
                )
            }
            row {
                ImmersiveSwitchRow(
                    stringResource(R.string.settings_keep_playing_title), checked = keepPlaying,
                    onChecked = { keepPlaying = it; app.wavebits.host.android.PlaybackService.setKeepsPlayingOnClose(ctx, it) },
                    detail = stringResource(R.string.settings_keep_playing_detail),
                )
            }
        }
        immersiveGroup("visuals") {
            row {
                ImmersiveSwitchRow(
                    stringResource(R.string.canvas_wifi_only), checked = canvasWifiOnly,
                    onChecked = { canvasWifiOnly = it; com.music.spotui.data.preferences.setCanvasWifiOnly(ctx, it) },
                    detail = stringResource(R.string.canvas_wifi_only_detail),
                )
            }
            // Lyrics: word by word (karaoke) first, or line by line with Spotify's first.
            row {
                ImmersiveSwitchRow(
                    stringResource(R.string.lyrics_word_sync_title), checked = wordSync,
                    onChecked = {
                        wordSync = it
                        com.music.spotui.data.preferences.setLyricsWordSync(ctx, it)
                        com.music.spotui.data.api.LyricsApi.wordSync = it
                        com.music.spotui.data.api.LyricsApi.forgetAll()
                    },
                    detail = stringResource(if (wordSync) R.string.lyrics_word_sync_on else R.string.lyrics_word_sync_off),
                )
            }
            row {
                ImmersiveSwitchRow(
                    "Extra Lyrics Sources (NetEase & Genius)", checked = extraLyrics,
                    onChecked = {
                        extraLyrics = it
                        com.music.spotui.data.preferences.setLyricsExtraSources(ctx, it)
                        com.music.spotui.data.api.LyricsApi.extraSources = it
                        com.music.spotui.data.api.LyricsApi.forgetAll()
                    },
                    detail = "Query NetEase Cloud Music and Genius when primary sources miss",
                )
            }
            row {
                ImmersiveGroupRow(
                    title = "WaveBits Lyrics+",
                    detail = if (hasLyricsExt) "Active across all platforms" else stringResource(R.string.extensions_row_detail),
                    chevron = true,
                    onClick = { navController.navigate(installedRoute(com.music.spotui.extensions.BundledLyricsExtension.ID)) },
                )
            }
        }
    }
}

@Composable
private fun StreamingPage(cellular: Boolean, back: () -> Unit) {
    val ctx = LocalContext.current
    var current by remember(cellular) { mutableStateOf(getStreamingQuality(ctx, cellular)) }
    ImmersivePage(
        title = stringResource(if (cellular) R.string.quality_streaming_cell_title else R.string.quality_streaming_wifi_title),
        onBack = back,
    ) {
        immersiveGroup("choices", footerRes = R.string.quality_streaming_detail) {
            StreamingChoices.forEach { (value, label, detail) ->
                row {
                    ImmersiveChoiceRow(stringResource(label), selected = current == value, detail = stringResource(detail),
                        onClick = { current = value; setStreamingQuality(ctx, value, cellular) })
                }
            }
        }
    }
}

@Composable
private fun CrossfadePage(back: () -> Unit) {
    val ctx = LocalContext.current
    val pref = com.music.spotui.data.preferences.CrossfadePref
    ImmersivePage(title = stringResource(R.string.settings_crossfade_title), onBack = back) {
        immersiveGroup("choices", footerRes = R.string.settings_crossfade_detail) {
            for (v in listOf(0, 3, 6, 9, 12)) {
                row {
                    ImmersiveChoiceRow(
                        if (v == 0) stringResource(R.string.settings_crossfade_off) else stringResource(R.string.settings_crossfade_seconds, v),
                        selected = pref.seconds == v, onClick = { pref.set(ctx, v) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Downloads

@Composable
private fun DownloadsPage(navController: NavController, open: (SettingsPage) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val quality = getDownloadQuality(ctx)
    var ask by remember { mutableStateOf(false) }
    val size = android.text.format.Formatter.formatShortFileSize(ctx, com.music.spotui.data.offline.OfflineStore.totalBytes())
    val downloadBackendMode = app.wavebits.host.android.WaveBitsRuntime.backendMode.collectAsStateWithLifecycle().value
    val isSpotifyDownloads = downloadBackendMode == app.wavebits.host.android.BackendMode.SPOTIFY
    val registry by com.music.spotui.extensions.Extensions.registry.collectAsStateWithLifecycle()
    val waveBitsDownloadEnabled = registry.find(com.music.spotui.extensions.BundledWaveBitsDownloadExtension.ID)?.enabled != false
    ImmersivePage(title = stringResource(R.string.settings_section_downloads), onBack = back) {
        immersiveGroup("quality", footerRes = R.string.quality_download_detail) {
            row {
                ImmersiveGroupRow(stringResource(R.string.quality_download_title),
                    value = stringResource(DownloadChoices.first { it.first == quality }.second), chevron = true,
                    onClick = { open(SettingsPage.DownloadQuality) })
            }
        }
        immersiveGroup("network") {
            row {
                var dlWifiOnly by remember { mutableStateOf(com.music.spotui.data.preferences.isDownloadWifiOnly(ctx)) }
                ImmersiveSwitchRow(
                    title = "Download Over Wi-Fi Only",
                    checked = dlWifiOnly,
                    onChecked = {
                        dlWifiOnly = it
                        com.music.spotui.data.preferences.setDownloadWifiOnly(ctx, it)
                    },
                    detail = "Only download music and podcasts when connected to Wi-Fi",
                )
            }
        }
        // Where downloads come from: WaveBits Download Manager on Spotify, or direct server on YT Music & JioSaavn.
        immersiveGroup("source") {
            row {
                val sourceLabel = when (downloadBackendMode) {
                    app.wavebits.host.android.BackendMode.SPOTIFY -> "Built-in WaveBits Multi-Source Engine (Background Service)"
                    app.wavebits.host.android.BackendMode.YOUTUBE -> "Built-in YouTube Music Server & WaveBits Engine"
                    app.wavebits.host.android.BackendMode.JIOSAAVN -> "Built-in JioSaavn 320kbps Server & WaveBits Engine"
                }
                ImmersiveGroupRow(
                    stringResource(R.string.downloads_source_row_title),
                    detail = sourceLabel,
                    chevron = false,
                )
            }
        }
        if (com.music.spotui.data.offline.OfflineStore.tracks.isNotEmpty()) immersiveGroup("remove") {
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.settings_item_remove_downloads_title), detail = stringResource(R.string.downloads_size, size),
                    destructive = true, onClick = { ask = true },
                )
            }
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        containerColor = Immersive.Raised,
        title = { Text(stringResource(R.string.settings_storage_remove_downloads_dialog_title), style = Immersive.Headline) },
        text = { Text(stringResource(R.string.playlist_entity_remove_download_dialog_description), style = Immersive.Subhead) },
        confirmButton = {
            TextButton(onClick = { ask = false; com.music.spotui.data.offline.OfflineDownloader.removeAll() }) {
                Text(stringResource(R.string.playlist_entity_remove_download_dialog_positive_button), color = ImmersiveDestructive)
            }
        },
        dismissButton = {
            TextButton(onClick = { ask = false }) { Text(stringResource(R.string.add_to_playlist_button_cancel), color = Immersive.Accent) }
        },
    )
}

@Composable
private fun DownloadQualityPage(back: () -> Unit) {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf(getDownloadQuality(ctx)) }
    ImmersivePage(title = stringResource(R.string.quality_download_title), onBack = back) {
        immersiveGroup("choices", footerRes = R.string.quality_download_detail) {
            DownloadChoices.forEach { (value, label, detail) ->
                row {
                    ImmersiveChoiceRow(stringResource(label), selected = current == value, detail = stringResource(detail),
                        onClick = { current = value; setDownloadQuality(ctx, value) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Data and battery

@Composable
private fun DataPage(back: () -> Unit) {
    val ctx = LocalContext.current
    val energy by app.wavebits.power.EnergySettings.state.collectAsStateWithLifecycle()
    // Battery optimization off = music and Connect keep going with the screen off.
    var exempt by remember { mutableStateOf(app.wavebits.power.BatteryExemption.isExempt(ctx)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        exempt = app.wavebits.power.BatteryExemption.isExempt(ctx)
    }
    val saverDetail = when {
        energy.policy.saverEnabled -> stringResource(R.string.energy_saver_status_active)
        energy.manualSaver && energy.isCharging -> stringResource(R.string.energy_saver_status_charging)
        else -> stringResource(R.string.energy_saver_summary)
    }
    ImmersivePage(title = stringResource(R.string.settings_section_data), onBack = back) {
        immersiveGroup("data") {
            row {
                Box(Modifier.testTag("settings_battery_saver_switch")) {
                    ImmersiveSwitchRow(
                        stringResource(R.string.energy_saver_title),
                        checked = energy.manualSaver,
                        onChecked = app.wavebits.power.EnergySettings::setSaver,
                        detail = saverDetail,
                    )
                }
            }
            row {
                ImmersiveSwitchRow(stringResource(R.string.data_saver_title), checked = com.music.spotui.data.DataSaver.enabled,
                    onChecked = com.music.spotui.data.DataSaver::set, detail = stringResource(R.string.data_saver_summary))
            }
            row {
                ImmersiveSwitchRow(
                    stringResource(R.string.background_playback_title), checked = exempt,
                    onChecked = {
                        if (exempt) ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        else app.wavebits.power.BatteryExemption.request(ctx)
                    },
                    detail = stringResource(if (exempt) R.string.background_playback_on else R.string.background_playback_off),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Display

@Composable
private fun DisplayPage(open: (SettingsPage) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val energy by app.wavebits.power.EnergySettings.state.collectAsStateWithLifecycle()
    val rotation = com.music.spotui.data.preferences.OrientationPref.mode
    val language = remember { com.music.spotui.data.AppLanguage.current(ctx) }
    val auto = stringResource(R.string.language_auto)
    ImmersivePage(title = stringResource(R.string.settings_section_display), onBack = back) {
        immersiveGroup("look") {
            row {
                val activeIconTitle = com.music.spotui.data.preferences.AppIconManager.currentOption().title
                ImmersiveGroupRow(
                    "App Icon",
                    value = activeIconTitle,
                    chevron = true,
                    onClick = { open(SettingsPage.AppIcon) },
                )
            }
            row { AccentRow() }
        }
        immersiveGroup("display") {
            row {
                val glass = com.music.spotui.ui.immersive.shell.liquidGlassAvailability()
                val unsupported = glass == com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.UNSUPPORTED
                ImmersiveSwitchRow(
                    stringResource(R.string.settings_liquid_glass_title),
                    checked = com.music.spotui.data.preferences.LiquidGlassPref.on && !unsupported,
                    enabled = !unsupported,
                    onChecked = { com.music.spotui.data.preferences.LiquidGlassPref.set(ctx, it) },
                    detail = stringResource(
                        when (glass) {
                            com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.UNSUPPORTED -> R.string.settings_liquid_glass_unsupported
                            com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.REDUCED -> R.string.settings_liquid_glass_reduced
                            com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.AVAILABLE -> R.string.settings_liquid_glass_detail
                        },
                    ),
                )
            }
            row {
                ImmersiveSwitchRow(stringResource(R.string.settings_amoled_theme), checked = energy.blackPreference,
                    onChecked = app.wavebits.power.EnergySettings::setBlackBackground, detail = stringResource(R.string.settings_amoled_theme_subtitle))
            }
            row {
                ImmersiveSwitchRow(
                    stringResource(R.string.settings_item_canvas_title),
                    checked = energy.canvasEnabled && !energy.policy.saverEnabled,
                    enabled = !energy.policy.saverEnabled,
                    onChecked = app.wavebits.power.EnergySettings::setCanvasEnabled,
                    detail = stringResource(if (energy.policy.saverEnabled) R.string.energy_saver_summary else R.string.video_settings_canvas_subtitle),
                )
            }
            row {
                ImmersiveSwitchRow(stringResource(R.string.settings_music_video_title), checked = com.music.spotui.data.preferences.MusicVideoPref.on,
                    onChecked = { com.music.spotui.data.preferences.MusicVideoPref.set(ctx, it) }, detail = stringResource(R.string.settings_music_video_detail))
            }
            row {
                ImmersiveSwitchRow(stringResource(R.string.settings_solid_nav_title), checked = com.music.spotui.data.preferences.SolidNavBar.on,
                    onChecked = { com.music.spotui.data.preferences.SolidNavBar.set(ctx, it) }, detail = stringResource(R.string.settings_solid_nav_detail))
            }
        }
        immersiveGroup("layout") {
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.settings_rotation_title),
                    value = stringResource(rotationLabel(rotation)), chevron = true, onClick = { open(SettingsPage.Rotation) },
                )
            }
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.settings_item_app_language_title),
                    value = if (language == com.music.spotui.data.AppLanguage.SYSTEM) auto else com.music.spotui.data.AppLanguage.nativeName(language),
                    chevron = true, onClick = { open(SettingsPage.Language) },
                )
            }
        }
    }
}

private fun rotationLabel(mode: String): Int = when (mode) {
    com.music.spotui.data.preferences.OrientationPref.AUTO -> R.string.settings_rotation_auto
    com.music.spotui.data.preferences.OrientationPref.LANDSCAPE -> R.string.settings_rotation_landscape
    else -> R.string.settings_rotation_portrait
}

/** The app's accent colour, for everyone (the same palette the supporter sheet uses). */
@Composable
private fun AccentRow() {
    val ctx = LocalContext.current
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.supporter_accent), style = Immersive.Body)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 10.dp)) {
            for (a in com.music.spotui.data.api.SupportReminder.Accent.entries) {
                val selected = com.music.spotui.data.api.SupportReminder.accent == a
                val name = stringResource(when (a) {
                    com.music.spotui.data.api.SupportReminder.Accent.WHITE -> R.string.color_white
                    com.music.spotui.data.api.SupportReminder.Accent.GREEN -> R.string.color_green
                    com.music.spotui.data.api.SupportReminder.Accent.BLUE -> R.string.color_blue
                    com.music.spotui.data.api.SupportReminder.Accent.PURPLE -> R.string.color_purple
                    com.music.spotui.data.api.SupportReminder.Accent.PINK -> R.string.color_pink
                    com.music.spotui.data.api.SupportReminder.Accent.ORANGE -> R.string.color_orange
                })
                Box(
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                    modifier = Modifier.size(38.dp)
                        .semantics { contentDescription = name; role = Role.RadioButton }
                        .clip(CircleShape).background(a.color)
                        .then(if (selected) Modifier.border(2.dp, if (a == com.music.spotui.data.api.SupportReminder.Accent.WHITE) Color.Black else Immersive.Text, CircleShape) else Modifier)
                        .clickable { com.music.spotui.data.api.SupportReminder.setAccent(ctx, a) },
                ) {
                    if (selected) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Rounded.Check,
                            contentDescription = null,
                            tint = if (a.color.red * 0.299f + a.color.green * 0.587f + a.color.blue * 0.114f > 0.55f) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIconPage(back: () -> Unit) {
    ImmersivePage(title = "App Icon", onBack = back) {
        immersiveGroup("app_icon") {
            row {
                com.music.spotui.ui.components.AppIconSelectorGrid()
            }
        }
    }
}

@Composable
private fun RotationPage(back: () -> Unit) {
    val ctx = LocalContext.current
    val pref = com.music.spotui.data.preferences.OrientationPref
    ImmersivePage(title = stringResource(R.string.settings_rotation_title), onBack = back) {
        immersiveGroup("choices") {
            for ((value, label) in listOf(
                pref.PORTRAIT to R.string.settings_rotation_portrait,
                pref.AUTO to R.string.settings_rotation_auto,
                pref.LANDSCAPE to R.string.settings_rotation_landscape,
            )) {
                row { ImmersiveChoiceRow(stringResource(label), selected = pref.mode == value, onClick = { pref.set(ctx, value) }) }
            }
        }
    }
}

/** "App language": Automatic (French or English) or any language with Spotify's labels. */
@Composable
private fun LanguagePage(back: () -> Unit) {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf(com.music.spotui.data.AppLanguage.current(ctx)) }
    val auto = stringResource(R.string.language_auto)
    fun label(tag: String) = if (tag == com.music.spotui.data.AppLanguage.SYSTEM) auto else com.music.spotui.data.AppLanguage.nativeName(tag)
    ImmersivePage(title = stringResource(R.string.settings_item_app_language_title), onBack = back) {
        immersiveGroup("choices", footerRes = R.string.language_partial_note) {
            com.music.spotui.data.AppLanguage.AVAILABLE.forEach { tag ->
                row {
                    ImmersiveChoiceRow(
                        label(tag), selected = tag == current,
                        detail = if (tag == com.music.spotui.data.AppLanguage.SYSTEM) stringResource(R.string.language_auto_detail) else null,
                        onClick = {
                            if (tag != current) {
                                current = tag
                                com.music.spotui.data.AppLanguage.apply(ctx, tag)
                                // Screens take the new language when the activity is rebuilt.
                                generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
                                    .filterIsInstance<android.app.Activity>().firstOrNull()?.recreate()
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- App

@Composable
private fun AppPage(navController: NavController, back: () -> Unit) {
    val ctx = LocalContext.current
    var showSupportUpi by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    if (showSupportUpi) com.music.spotui.ui.components.SupportUpiSheet(onDismiss = { showSupportUpi = false })
    if (showAbout) com.music.spotui.ui.components.AboutSheet(onDismiss = { showAbout = false })
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    val knownUpdate = com.music.spotui.updateRelease.value
        ?: remember { com.music.spotui.data.api.UpdateChecker.known(ctx) }
    ImmersivePage(title = stringResource(R.string.settings_section_app), onBack = back) {
        immersiveGroup("support") {
            row {
                ImmersiveGroupRow(
                    "Support Wavebits",
                    detail = "Buy me a coffee with UPI to keep WaveBits 100% free and ad-free",
                    icon = Icons.Outlined.Coffee,
                    chevron = true,
                    onClick = { showSupportUpi = true },
                )
            }
            row {
                ImmersiveGroupRow(
                    title = stringResource(R.string.creator_biography_section_title),
                    value = if (version.isNotBlank()) "v$version" else null,
                    detail = stringResource(R.string.about_detail),
                    chevron = true,
                    onClick = { showAbout = true },
                )
            }
        }
        immersiveGroup("updates") {
            row {
                ImmersiveSwitchRow(
                    title = "Automatic update checks",
                    checked = com.music.spotui.data.preferences.AutoUpdateCheckPref.on,
                    onChecked = { com.music.spotui.data.preferences.AutoUpdateCheckPref.set(ctx, it) },
                    detail = "Check for new WaveBits updates at startup",
                )
            }
            row {
                ImmersiveGroupRow(
                    title = stringResource(if (checkingUpdate) R.string.settings_update_checking else R.string.settings_check_update_title),
                    detail = stringResource(R.string.settings_check_update_detail),
                    chevron = false,
                    onClick = {
                        if (checkingUpdate) return@ImmersiveGroupRow
                        checkingUpdate = true
                        scope.launch {
                            val found = runCatching { com.music.spotui.data.api.UpdateChecker.check(ctx, manual = true) }.getOrNull()
                            checkingUpdate = false
                            if (found != null) com.music.spotui.updateRelease.value = found
                            else android.widget.Toast.makeText(ctx, ctx.getString(R.string.settings_update_check_latest), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            row {
                ImmersiveGroupRow(
                    title = stringResource(R.string.settings_about_version, app.wavebits.engine.BuildConfig.VERSION_NAME),
                    value = knownUpdate?.let { stringResource(R.string.settings_update_available, it.version) },
                    detail = if (knownUpdate != null) stringResource(R.string.settings_update_available_detail) else null,
                    chevron = knownUpdate != null,
                    onClick = knownUpdate?.let { rel -> { com.music.spotui.updateRelease.value = rel } },
                )
            }
        }
        immersiveGroup("repair") {
            // Rebuild / reset the active player and clear transient caches without leaving the app.
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.restart_player_title), detail = stringResource(R.string.restart_player_detail),
                    onClick = {
                        com.music.spotui.data.preferences.restartActivePlayer(ctx)
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.restart_player_done), android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }
            row {
                ImmersiveGroupRow(
                    stringResource(R.string.diagnostic_export_title), detail = stringResource(R.string.diagnostic_export_detail),
                    onClick = {
                        val file = runCatching { app.wavebits.host.android.Logbook.export(ctx) }.getOrNull()
                        if (file != null) shareDiagnosticReport(ctx, file)
                        else android.widget.Toast.makeText(ctx, ctx.getString(R.string.diagnostic_export_failed), android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}
